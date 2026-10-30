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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;

/** {@code cp-config} as 3 or 5 voters on one {@link SimTransport}. */
class CpStoreVotersTest {

    private static final String YAML = "schema: 1\nserver:\n  port: 0\n";

    @TempDir
    Path dir;

    @Test
    void threeVotersReplicateActivationAndFailOver() throws Exception {
        var clock = new FakeClock(0);
        var net = new SimTransport();
        var stores = open(3, clock, net);
        try {
            clock.advance(Duration.ofMillis(100));
            drive(stores);
            var leader = leader(stores);
            assertEquals("cp-1", leader.leadership().leaderId().orElseThrow());
            for (var store : stores) {
                if (store != leader) {
                    assertEquals(503, store.propose(YAML).status(), "a follower refuses writes");
                }
            }
            var proposed = new java.util.concurrent.atomic.AtomicReference<CpStore.Outcome>();
            run(stores, () -> proposed.set(leader.propose(YAML)));
            assertEquals(200, proposed.get().status(), proposed.get().body());
            var activated = new java.util.concurrent.atomic.AtomicReference<CpStore.Outcome>();
            run(stores, () -> activated.set(leader.activate(System.currentTimeMillis(), 60_000L)));
            assertEquals(200, activated.get().status(), activated.get().body());
            clock.advance(Duration.ofMillis(40));
            drive(stores);
            for (var store : stores) {
                assertNotNull(store.activated(), "every voter applies the committed envelope");
                assertEquals(1L, store.activated().version());
                assertNotNull(store.awaitActivated(0L, 0L));
            }

            leader.close();
            for (var store : stores) {
                if (store != leader) {
                    net.cut(new NodeId(1, "cp-1"), id(store, stores));
                }
            }
            var survivors = new ArrayList<>(stores);
            survivors.remove(leader);
            clock.advance(Duration.ofMillis(1500));
            drive(survivors);
            var next = leader(survivors);
            assertNotEquals(leader, next);
            assertTrue(next.leadership().term() > leader.leadership().term());
            var again = new java.util.concurrent.atomic.AtomicReference<CpStore.Outcome>();
            run(survivors, () -> {
                next.propose("schema: 1\nserver:\n  port: 0\n  max_connections: 2048\n");
                again.set(next.activate(System.currentTimeMillis(), 60_000L));
            });
            assertEquals(200, again.get().status(), again.get().body());
            clock.advance(Duration.ofMillis(40));
            drive(survivors);
            for (var store : survivors) {
                assertEquals(2L, store.activated().version());
            }
        } finally {
            for (var store : stores) {
                store.close();
            }
        }
    }

    /**
     * #254 (Security): Idempotency-Key is checked at admission against applied keys only, so a same-key retry that
     * arrives while the original is uncommitted is appended too. Both entries commit; apply skips the second, on
     * every replica, so a rollout ADVANCE moves one stage, not two. A same-key entry with a different request
     * (fingerprint) is skipped the same way: the first applied entry wins and the late caller gets 409.
     */
    @Test
    void sameKeyEntriesThatBothCommitApplyOnceOnEveryReplica() throws Exception {
        var clock = new FakeClock(0);
        var net = new SimTransport();
        var stores = open(3, clock, net);
        try {
            clock.advance(Duration.ofMillis(100));
            drive(stores);
            var leader = leader(stores);
            var rolled = new java.util.concurrent.atomic.AtomicReference<CpStore.Outcome>();
            run(stores, () -> rolled.set(leader.rollout(List.of("edge", "core", "rest"))));
            assertEquals(200, rolled.get().status(), rolled.get().body());
            clock.advance(Duration.ofMillis(40));
            drive(stores);

            // Nothing commits until the voters are driven, so both requests pass admission and both are appended.
            var first = blocked(() -> leader.advance("k-1"));
            var retry = blocked(() -> leader.advance("k-1"));
            settle(stores, first, retry);
            assertEquals(200, first.result().status(), first.result().body());
            assertEquals(200, retry.result().status(), retry.result().body());
            assertEquals(first.result().body(), retry.result().body(), "the retry answers with the original outcome");
            clock.advance(Duration.ofMillis(40));
            drive(stores);
            for (var store : stores) {
                assertEquals(1, store.stage(), "two same-key ADVANCE entries moved " + id(store, stores) + " twice");
            }

            // Same key, different request: the first applied entry wins; the rollout is skipped and answered 409.
            var advance = blocked(() -> leader.advance("k-2"));
            var reused = blocked(() -> leader.rollout(List.of("only"), "k-2"));
            settle(stores, advance, reused);
            assertEquals(200, advance.result().status(), advance.result().body());
            assertEquals(409, reused.result().status(), reused.result().body());
            clock.advance(Duration.ofMillis(40));
            drive(stores);
            for (var store : stores) {
                assertEquals(2, store.stage(), "the skipped rollout must not reset " + id(store, stores));
            }
            assertEquals(200, leader.advance("k-1").status(), "an applied key still replays");
            assertEquals(2, leader.stage());
        } finally {
            for (var store : stores) {
                store.close();
            }
        }
    }

    /**
     * #259: every replica's bounded Idempotency-Key table agrees across window eviction, cap eviction, a different
     * fingerprint, and a snapshot install at an eviction boundary. Uses a test-sized window/cap (state-machine
     * defaults are 100_000); both voters get the same table before any apply.
     */
    @Test
    void idempotencyBoundAgreesAcrossReplicasAndSnapshot() throws Exception {
        var clock = new FakeClock(0);
        var net = new SimTransport();
        var stores = open(3, clock, net);
        try {
            long window = 4;
            int cap = 6;
            for (var store : stores) {
                store.useIdempotencyTableForTest(new IdempotencyTable(window, cap));
            }
            clock.advance(Duration.ofMillis(100));
            drive(stores);
            var leader = leader(stores);
            run(stores, () -> assertEquals(200, leader.rollout(java.util.List.of("a", "b", "c", "d", "e", "f", "g", "h",
                    "i", "j", "k", "l")).status()));

            // Fill keys k0..k5 (cap=6). Then k6 evicts eldest by index (cap).
            for (int i = 0; i < 7; i++) {
                String key = "cap-" + i;
                int n = i;
                run(stores, () -> assertEquals(200, leader.advance(key).status(), key));
                clock.advance(Duration.ofMillis(40));
                drive(stores);
            }
            for (var store : stores) {
                assertTrue(store.idempotencyEvictions() >= 1, "cap eviction on " + id(store, stores));
                assertTrue(store.idempotencySize() <= cap);
            }
            // Same key, different fingerprint → 409 while still in the table.
            run(stores, () -> assertEquals(409, leader.rollout(java.util.List.of("z"), "cap-6").status()));

            // Snapshot at current applied state (after cap traffic) and install on a fresh table of the same bounds.
            byte[] image = leader.snapshotIdempotency();
            for (var store : stores) {
                assertEquals(0, java.util.Arrays.compare(image, store.snapshotIdempotency()),
                        "replicas agree before install " + id(store, stores));
            }
            var restored = new IdempotencyTable(window, cap);
            restored.replaceWith(IdempotencyTable.read(
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(image)), window, cap));
            // Push past the window: keys applied at i-W must be gone; i-W+1 still dedups.
            for (int i = 0; i < 8; i++) {
                String key = "win-" + i;
                run(stores, () -> assertEquals(200, leader.advance(key).status(), key));
                clock.advance(Duration.ofMillis(40));
                drive(stores);
            }
            // After several advances, an early win-* key is outside the window → applies again (no KeyExpired).
            run(stores, () -> {
                var again = leader.advance("win-0");
                assertEquals(200, again.status(), "evicted key applies again: " + again.body());
            });
            clock.advance(Duration.ofMillis(40));
            drive(stores);
            for (var store : stores) {
                assertEquals(0, java.util.Arrays.compare(leader.snapshotIdempotency(), store.snapshotIdempotency()),
                        "replicas still agree " + id(store, stores));
            }
            // Restored table at the pre-window-push boundary, then replay the same win-* remembers, must match leader.
            // (Table-level: the CpStore path already agreed; this checks install-then-continue.)
            long start = 1000;
            var live = new IdempotencyTable(window, cap);
            for (long i = 0; i < window; i++) {
                live.remember("b-" + i, "fp", 200, "x", start + i);
            }
            // Exactly at eviction boundary: next remember drops b-0 (appliedIndex start == (start+window) - window).
            byte[] atBoundary = null;
            {
                var out = new java.io.ByteArrayOutputStream();
                try (var d = new java.io.DataOutputStream(out)) {
                    live.write(d);
                }
                atBoundary = out.toByteArray();
            }
            var installed = IdempotencyTable.read(
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(atBoundary)), window, (int) cap);
            live.remember("b-edge", "fp", 200, "x", start + window); // drops b-0
            installed.remember("b-edge", "fp", 200, "x", start + window);
            assertNull(live.get("b-0"));
            assertNull(installed.get("b-0"));
            assertEquals(200, live.get("b-1").status());
            assertEquals(200, installed.get("b-1").status());
        } finally {
            for (var store : stores) {
                store.close();
            }
        }
    }

    /** A write started on its own thread, parked in its commit wait (nothing drives the voters yet). */
    private record Pending(Thread thread, java.util.concurrent.atomic.AtomicReference<CpStore.Outcome> out) {
        CpStore.Outcome result() {
            return out.get();
        }
    }

    private static Pending blocked(java.util.function.Supplier<CpStore.Outcome> write) throws Exception {
        var out = new java.util.concurrent.atomic.AtomicReference<CpStore.Outcome>();
        var t = Thread.ofPlatform().start(() -> out.set(write.get()));
        for (int i = 0; i < 5_000 && t.getState() != Thread.State.TIMED_WAITING; i++) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.TIMED_WAITING, t.getState(), "the write never reached its commit wait");
        return new Pending(t, out);
    }

    private static void settle(List<CpStore> stores, Pending... writes) throws Exception {
        run(stores, () -> {
            for (var w : writes) {
                try {
                    w.thread().join(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        for (var w : writes) {
            assertNotNull(w.result(), "a write did not finish");
        }
    }

    @Test
    void onlyThreeOrFiveFullVotersAreAccepted() throws Exception {
        var keys = keys();
        var members = members(4);
        var four = VoterSet.majority(members, ids(members));
        assertThrows(IllegalArgumentException.class, () -> CpStore.open(dir.resolve("x"), keys, new FakeClock(0),
                "urn:sjws:admin:root", members.get(0).id(), four, new SimTransport()));
        var w = new Member(new NodeId(3, "w"), new SiteId("local"), new InetSocketAddress("127.0.0.1", 21003), 1L,
                MemberState.ALIVE, true);
        var withWitness = VoterSet.dataAndWitness(members.get(0), members.get(1), w);
        assertThrows(IllegalArgumentException.class, () -> CpStore.open(dir.resolve("y"), keys, new FakeClock(0),
                "urn:sjws:admin:root", w.id(), withWitness, new SimTransport()), "a witness is CpWitness, not CpStore");
        CpStore.open(dir.resolve("z"), keys, new FakeClock(0), "urn:sjws:admin:root", members.get(0).id(), withWitness,
                new SimTransport()).close();
        var five = members(5);
        var clock = new FakeClock(0);
        var net = new SimTransport();
        var stores = new ArrayList<CpStore>();
        try {
            for (var member : five) {
                stores.add(CpStore.open(dir.resolve("five-" + member.id().name()), keys, clock, "urn:sjws:admin:root",
                        member.id(), VoterSet.majority(five, ids(five)), net));
            }
            clock.advance(Duration.ofMillis(100));
            drive(stores);
            assertNotNull(leader(stores));
        } finally {
            for (var store : stores) {
                store.close();
            }
        }
    }

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
                    new InetSocketAddress("127.0.0.1", 21000 + i), 1L, MemberState.ALIVE, false));
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
                assertEquals(null, found, "one ready leader");
                found = store;
            }
        }
        assertNotNull(found, "a ready leader");
        return found;
    }

    private static void drive(List<CpStore> stores) {
        for (int round = 0; round < 8; round++) {
            for (var store : stores) {
                store.drive();
            }
        }
    }

    /** Runs a blocking write while another thread drives the voters until it returns. */
    private static void run(List<CpStore> stores, Runnable write) throws Exception {
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        var driver = Thread.ofPlatform().start(() -> {
            while (!done.get()) {
                synchronized (stores) {
                    drive(stores);
                }
                Thread.onSpinWait();
            }
        });
        try {
            write.run();
        } finally {
            done.set(true);
            driver.join(5_000);
        }
    }
}
