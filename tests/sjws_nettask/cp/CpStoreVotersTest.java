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
