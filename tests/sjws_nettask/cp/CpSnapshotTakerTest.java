/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;

/** #341: CpStore SnapshotTaker, truncateAndCompact, restart and lag catch-up. */
@Timeout(60)
class CpSnapshotTakerTest {

    private static final String YAML = "schema: 1\nserver:\n  port: 0\n";

    @TempDir
    Path dir;

    @Test
    void restartRestoresFromDurableSnapshotWithSameIdempotencyVerdicts() throws Exception {
        var clock = new FakeClock(0);
        var keys = keys();
        Path state = dir.resolve("solo");
        long through;
        byte[] idemImage;
        long version;
        try (var store = CpStore.open(state, keys, clock)) {
            clock.advance(Duration.ofMillis(150));
            store.drive();
            assertTrue(store.leadership().leaderReady());
            assertEquals(200, store.propose(YAML, "idem-propose").status());
            store.drive();
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L, "idem-act").status());
            store.drive();
            assertNotNull(store.activated());
            version = store.activated().version();
            // Same-key retry is a hit.
            assertEquals(200, store.propose(YAML, "idem-propose").status());
            store.drive();
            through = store.groupCommitIndex();
            store.compactThrough(through).get(10, TimeUnit.SECONDS);
            store.drive();
            // Prefix gone on disk.
            var probe = new FileLogStorage(state.resolve("cp-config"));
            try {
                assertNull(probe.read(CpGroup.GROUP, 1), "log prefix deleted after compact");
            } finally {
                probe.close();
            }
            idemImage = store.snapshotIdempotency();
            assertTrue(store.idempotencySize() >= 1);
        }
        // Restart: open loads durable snapshot via onSnapshotRestored.
        try (var store = CpStore.open(state, keys, clock)) {
            clock.advance(Duration.ofMillis(150));
            store.drive();
            assertNotNull(store.activated());
            assertEquals(version, store.activated().version());
            assertArrayEquals(idemImage, store.snapshotIdempotency(), "idempotency table matches pre-restart");
            // Duplicate propose with same key still returns the first outcome (table restored).
            assertEquals(200, store.propose(YAML, "idem-propose").status());
        }
    }

    @Test
    void laggingVoterCatchesUpViaSnapshotInstallWithSameVerdicts() throws Exception {
        var clock = new FakeClock(0);
        var net = new SimTransport();
        var stores = open(3, clock, net);
        try {
            clock.advance(Duration.ofMillis(150));
            drive(stores);
            var leader = leader(stores);
            int lagIdx = -1;
            for (int i = 0; i < stores.size(); i++) {
                if (stores.get(i) != leader) {
                    lagIdx = i;
                    break;
                }
            }
            var lagging = stores.get(lagIdx);
            // Isolate the lagging voter.
            for (var s : stores) {
                if (s != lagging) {
                    net.cut(id(lagging, stores), id(s, stores));
                }
            }
            assertEquals(200, run(stores, () -> leader.propose(YAML, "k-lag")).status());
            assertEquals(200, run(stores, () -> leader.activate(System.currentTimeMillis(), 60_000L, "k-act")).status());
            // Extra mutations so the snapshot is non-trivial.
            for (int i = 0; i < 5; i++) {
                final int n = i;
                run(stores, () -> leader.propose("schema: 1\nserver:\n  port: " + (n + 1) + "\n", "p-" + n));
                run(stores, () -> leader.activate(System.currentTimeMillis(), 60_000L, "a-" + n));
            }
            long through = leader.groupCommitIndex();
            leader.compactThrough(through).get(15, TimeUnit.SECONDS);
            drive(List.of(leader)); // keep leader ticking while compact settles
            byte[] leaderIdem = leader.snapshotIdempotency();
            long leaderVersion = leader.activated().version();
            // Heal and catch up via InstallSnapshot.
            for (var s : stores) {
                if (s != lagging) {
                    net.heal(id(lagging, stores), id(s, stores));
                }
            }
            for (int i = 0; i < 40; i++) {
                clock.advance(Duration.ofMillis(40));
                drive(stores);
                if (lagging.activated() != null && lagging.activated().version() == leaderVersion
                        && java.util.Arrays.equals(leaderIdem, lagging.snapshotIdempotency())) {
                    break;
                }
            }
            assertNotNull(lagging.activated());
            assertEquals(leaderVersion, lagging.activated().version());
            assertArrayEquals(leaderIdem, lagging.snapshotIdempotency(), "lagging voter matches after snapshot install");
            // Same-key retry agrees on every replica.
            for (var s : stores) {
                assertEquals(200, s.propose(YAML, "k-lag").status());
            }
        } finally {
            for (var s : stores) {
                s.close();
            }
        }
    }

    @Test
    void snapshotRoundTripPreservesPolicyJoinsAndRollout() throws Exception {
        var clock = new FakeClock(0);
        var keys = keys();
        try (var store = CpStore.open(dir.resolve("round"), keys, clock)) {
            clock.advance(Duration.ofMillis(150));
            store.drive();
            assertEquals(200, store.propose(YAML).status());
            store.drive();
            assertEquals(200, store.activate(System.currentTimeMillis(), 60_000L).status());
            store.drive();
            assertEquals(200, store.rollout(List.of("stage-a", "stage-b"), "roll-1").status());
            store.drive();
            assertEquals(200, store.advance("adv-1").status());
            store.drive();
            var secret = new byte[16];
            java.util.Arrays.fill(secret, (byte) 7);
            assertEquals(200, store.issueJoinToken("tok-1", secret, System.currentTimeMillis(), 60_000L).status());
            store.drive();
            byte[] image = store.snapshotBytes();
            var decoded = CpStateSnapshot.read(image);
            assertEquals(1, decoded.history.size());
            assertEquals(2, decoded.selectors.size());
            assertEquals(1, decoded.stage);
            assertTrue(decoded.policy.containsValue(sjws_nettask.controlplane.Role.ADMIN));
        }
    }

    // --- seams used by tests (package-visible helpers on CpStore would be better; use reflection-free API) ---

    private List<CpStore> open(int count, FakeClock clock, SimTransport net) throws Exception {
        var keys = keys();
        var members = members(count);
        var set = VoterSet.majority(members, ids(members));
        var stores = new ArrayList<CpStore>();
        for (var member : members) {
            stores.add(CpStore.open(dir.resolve(member.id().name()), keys, clock, "urn:sjws:admin:root", member.id(),
                    set, net));
        }
        return stores;
    }

    private static KeyRing keys() {
        return KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
    }

    private static List<Member> members(int count) {
        var members = new ArrayList<Member>();
        for (int i = 1; i <= count; i++) {
            members.add(new Member(new NodeId(i, "cp-" + i), new SiteId("local"),
                    new InetSocketAddress("127.0.0.1", 22000 + i), 1L, MemberState.ALIVE, false));
        }
        return members;
    }

    private static java.util.Set<NodeId> ids(List<Member> members) {
        var ids = new HashSet<NodeId>();
        for (var member : members) {
            ids.add(member.id());
        }
        return ids;
    }

    private static NodeId id(CpStore store, List<CpStore> stores) {
        int i = stores.indexOf(store) + 1;
        return new NodeId(i, "cp-" + i);
    }

    private static CpStore leader(List<CpStore> stores) {
        CpStore found = null;
        for (var store : stores) {
            if (store.leadership().leaderReady()) {
                found = store;
            }
        }
        assertNotNull(found);
        return found;
    }

    private static void drive(List<CpStore> stores) {
        for (int round = 0; round < 8; round++) {
            for (var store : stores) {
                store.drive();
            }
        }
    }

    private static CpStore.Outcome run(List<CpStore> stores, java.util.concurrent.Callable<CpStore.Outcome> write)
            throws Exception {
        var out = new java.util.concurrent.atomic.AtomicReference<CpStore.Outcome>();
        var err = new java.util.concurrent.atomic.AtomicReference<Exception>();
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var driver = Thread.ofPlatform().start(() -> {
            while (!done.get()) {
                drive(stores);
                Thread.onSpinWait();
            }
        });
        try {
            out.set(write.call());
        } catch (Exception e) {
            err.set(e);
        } finally {
            done.set(true);
            driver.join(5_000);
        }
        if (err.get() != null) {
            throw err.get();
        }
        assertNotNull(out.get());
        return out.get();
    }
}
