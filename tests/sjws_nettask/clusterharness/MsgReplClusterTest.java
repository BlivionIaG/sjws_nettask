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
package sjws_nettask.clusterharness;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gate G3: HA messaging across real processes ({@link MsgReplCluster}: 2 data nodes plus a witness, real TCP, file
 * logs). Every acked record must survive, exactly once, through a leader {@code kill -9}, a restart and catch-up,
 * witness loss, a partition between the data nodes, and the {@code on} to {@code off} rollback.
 *
 * <p>The topic produce API has no server-side idempotency key (#254), so the producer here is idempotent the way
 * clients are told to be: every record carries a unique id, and after an answer that is not a definite yes or no
 * (outcome unknown, a dropped connection) it reads back from the confirmed leader before it resends. A duplicate
 * in the log therefore means the cluster stored one write twice.
 */
@Tag("cluster")
class MsgReplClusterTest {

    static final Duration FAILOVER = Duration.ofSeconds(20);

    @TempDir
    Path dir;

    /** Produces with read-back deduplication; remembers what was acked and what was ever sent. */
    static final class Producer {
        final MsgReplCluster c;
        final String name;
        final Set<String> acked = new LinkedHashSet<>();
        final Set<String> sent = new LinkedHashSet<>();
        /** Ids resent after an uncertain answer whose read-back did not find them yet. */
        final Set<String> resentAfterUncertain = new LinkedHashSet<>();
        int seq;
        int uncertain;
        int resent;

        Producer(MsgReplCluster c, String name) {
            this.c = c;
            this.name = name;
        }

        String next() {
            return this.name + "-" + String.format("%05d", this.seq++);
        }

        void produce(int n) throws Exception {
            for (int i = 0; i < n; i++) {
                produceOne(next());
            }
        }

        /** Returns once {@code id} is in the log exactly as acked; never sends it while it may already be there. */
        void produceOne(String id) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            boolean maybeStored = false;
            while (System.nanoTime() < deadline) {
                if (maybeStored) {
                    var leader = this.c.awaitLeader(FAILOVER);
                    List<String> recs;
                    try {
                        recs = this.c.records(leader, true);
                    } catch (IOException e) {
                        continue; // leader moved; ask again
                    }
                    if (recs.contains(id)) {
                        this.acked.add(id);
                        return;
                    }
                    this.resent++;
                    this.resentAfterUncertain.add(id);
                    maybeStored = false;
                }
                var target = this.c.leader().orElse(null);
                if (target == null) {
                    Thread.sleep(50);
                    continue;
                }
                HttpResponse<String> r;
                try {
                    this.sent.add(id);
                    r = this.c.post(this.c.url(target, ""), MsgReplCluster.frame(id));
                    if (r.statusCode() == 307) {
                        var location = r.headers().firstValue("location").orElseThrow();
                        r = this.c.post(location, MsgReplCluster.frame(id));
                    }
                } catch (IOException e) {
                    this.uncertain++;
                    maybeStored = true; // the request may have reached the leader before it died
                    continue;
                }
                if (r.statusCode() == 200) {
                    this.acked.add(id);
                    return;
                }
                if (r.body().contains("outcome-unknown")) {
                    this.uncertain++;
                    maybeStored = true;
                    continue;
                }
                // not leader / no leader / not appended: refused before the log, safe to resend
                Thread.sleep(50);
            }
            fail("could not produce " + id);
        }
    }

    /**
     * Every acked id once, nothing that was never sent, no id twice, acked ids in ack order (the producer is
     * sequential), in one order on every listed node.
     */
    static List<String> assertLog(MsgReplCluster c, Producer p, String... nodes) throws Exception {
        List<String> first = null;
        for (var node : nodes) {
            List<String> recs = awaitRecords(c, node, p.acked);
            var counts = new HashMap<String, Integer>();
            for (var r : recs) {
                counts.merge(r, 1, Integer::sum);
            }
            var dups = counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
            if (!dups.isEmpty()) {
                var late = dups.stream().filter(p.resentAfterUncertain::contains).toList();
                fail(node + " stored duplicates: " + dups + (late.isEmpty() ? ""
                        : "; of these " + late + " had an uncertain answer, were absent on the read-back and were"
                                + " resent, so the first copy committed late (after the read-back)"));
            }
            for (var id : p.acked) {
                assertTrue(counts.containsKey(id), node + " lost acked record " + id);
            }
            for (var r : recs) {
                assertTrue(p.sent.contains(r), node + " has a record nobody sent: " + r);
            }
            var inLog = recs.stream().filter(p.acked::contains).toList();
            var ackOrder = List.copyOf(p.acked);
            if (!inLog.equals(ackOrder)) {
                int i = 0;
                while (i < inLog.size() && inLog.get(i).equals(ackOrder.get(i))) {
                    i++;
                }
                fail(node + " stores acked records out of ack order at position " + i + ": log has " + inLog.get(i)
                        + ", acked order has " + ackOrder.get(i));
            }
            if (first == null) {
                first = recs;
            } else {
                assertEquals(first, recs, node + " diverges from " + nodes[0]);
            }
        }
        return first;
    }

    /** {@code node}'s local log once it holds every acked id (a follower applies asynchronously). */
    static List<String> awaitRecords(MsgReplCluster c, String node, Set<String> acked) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<String> recs = List.of();
        while (System.nanoTime() < deadline) {
            try {
                recs = c.records(node, false);
                if (recs.containsAll(acked)) {
                    return recs;
                }
            } catch (IOException e) {
                // still starting
            }
            Thread.sleep(100);
        }
        return recs;
    }

    @Test
    @Timeout(240)
    void leaderKillFailsOverWithoutLossOrDuplicatesAndTheNodeCatchesUp() throws Exception {
        try (var c = new MsgReplCluster(dir)) {
            c.startAll("on");
            assertTrue(c.logText("a").contains("ha messaging: replication on, node=a"), c.logText("a"));
            assertTrue(c.logText("w").contains("node=w (witness)"), c.logText("w"));
            var p = new Producer(c, "p");
            var leader = c.awaitLeader(FAILOVER);
            p.produce(150);
            // kill -9 the leader while a producer keeps writing from another thread
            var errors = new ArrayList<Throwable>();
            var killedAt = new java.util.concurrent.atomic.AtomicLong();
            var firstAckAfter = new java.util.concurrent.atomic.AtomicLong();
            int ackedBeforeKill = p.acked.size();
            var writer = Thread.ofPlatform().start(() -> {
                try {
                    int afterKill = 0;
                    while (afterKill < 150) {
                        p.produce(1);
                        if (killedAt.get() != 0) {
                            firstAckAfter.compareAndSet(0, System.nanoTime());
                            afterKill++;
                        }
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
            Thread.sleep(500); // the producer is mid-stream
            killedAt.set(System.nanoTime());
            c.kill(leader);
            writer.join(Duration.ofSeconds(120));
            assertFalse(writer.isAlive(), "producer stuck after failover");
            assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(p.acked.size() > ackedBeforeKill + 150, "the producer was writing when the leader died");
            System.out.printf("G3 leader-kill: killed=%s acked=%d uncertain=%d resent=%d failover=%dms%n", leader,
                    p.acked.size(), p.uncertain, p.resent, (firstAckAfter.get() - killedAt.get()) / 1_000_000);
            var survivor = "a".equals(leader) ? "b" : "a";
            assertEquals(survivor, c.awaitLeader(FAILOVER), "the surviving data node leads with the witness");
            p.produce(50);
            assertLog(c, p, survivor);

            c.start(leader, "on"); // restart from its own Raft log and ledger
            p.produce(50);
            var log = assertLog(c, p, survivor, leader);
            assertEquals(p.acked.size(), log.size(), "exactly the acked records (uncertain=" + p.uncertain
                    + ", resent=" + p.resent + ")");
            assertTrue(c.logText(leader).contains("ha messaging: replication on"), "restarted with replication");
        }
    }

    @Test
    @Timeout(240)
    void witnessLossKeepsCommittingAndAFollowerRestartCatchesUp() throws Exception {
        try (var c = new MsgReplCluster(dir)) {
            c.startAll("on");
            var p = new Producer(c, "w");
            var leader = c.awaitLeader(FAILOVER);
            p.produce(50);
            c.kill("w");
            p.produce(100); // two data nodes are a majority of three
            assertEquals(leader, c.awaitLeader(FAILOVER), "losing the witness does not move the leader");
            var follower = "a".equals(leader) ? "b" : "a";
            assertLog(c, p, leader, follower);

            c.start("w", "on");
            c.kill(follower);
            p.produce(100); // leader plus witness
            c.start(follower, "on");
            p.produce(20);
            assertLog(c, p, leader, follower);
        }
    }

    @Test
    @Timeout(240)
    void partitionBetweenDataNodesKeepsTheLeaderAndHeals() throws Exception {
        try (var c = new MsgReplCluster(dir)) {
            c.startAll("on");
            var p = new Producer(c, "x");
            var leader = c.awaitLeader(FAILOVER);
            var follower = "a".equals(leader) ? "b" : "a";
            p.produce(50);
            c.partition("a", "b");
            p.produce(100); // the leader still reaches the witness
            Thread.sleep(3_000); // several election timeouts: the cut-off follower must not take over
            assertEquals(leader, c.awaitLeader(FAILOVER), "the witness keeps the sitting leader (pre-vote)");
            c.heal();
            p.produce(20);
            assertLog(c, p, leader, follower);
        }
    }

    @Test
    @Timeout(240)
    void rollbackToOffServesTheLocalCopyOnOneNode() throws Exception {
        try (var c = new MsgReplCluster(dir)) {
            c.startAll("on");
            var p = new Producer(c, "r");
            var leader = c.awaitLeader(FAILOVER);
            p.produce(80);
            var before = assertLog(c, p, leader);
            for (var n : MsgReplCluster.NODES) {
                c.stop(n);
            }
            // Roll back: the former leader alone, ha.messaging: off (member list kept), serving its applied ledger.
            c.start(leader, "off");
            var text = c.logText(leader);
            assertFalse(text.contains("replication on"), text);
            assertEquals(before, c.records(leader, false), "off serves the local applied copy unchanged");
            var r = c.post(c.url(leader, ""), MsgReplCluster.frame("after-off"));
            assertEquals(200, r.statusCode(), "the lone node takes writes locally: " + r.body());
            var after = c.records(leader, false);
            assertEquals(before.size() + 1, after.size());
            assertEquals("after-off", after.get(after.size() - 1));
        }
    }
}
