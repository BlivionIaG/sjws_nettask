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

    /** Produces with read-back deduplication; remembers what was acked, at which offset, and what was ever sent. */
    static final class Producer {
        static final java.util.regex.Pattern BASE = java.util.regex.Pattern.compile("\"baseOffset\":(\\d+)");
        final MsgReplCluster c;
        final String name;
        final Set<String> acked = new LinkedHashSet<>();
        /** The offset each 200 answer gave; ids resolved by a read-back have none. */
        final Map<String, Long> ackOffset = new HashMap<>();
        final Set<String> sent = new LinkedHashSet<>();
        /** Ids whose answer was not a definite yes or no (outcome-unknown, a dropped connection). */
        final Set<String> uncertainIds = new LinkedHashSet<>();
        /** Ids resent after an uncertain answer whose read-back did not find them yet. */
        final Set<String> resentAfterUncertain = new LinkedHashSet<>();
        int seq;
        int uncertain;
        int resent;

        Producer(MsgReplCluster c, String name) {
            this.c = c;
            this.name = name;
        }

        synchronized String next() {
            return this.name + "-" + String.format("%05d", this.seq++);
        }

        void produce(int n) throws Exception {
            for (int i = 0; i < n; i++) {
                produceOne(next());
            }
        }

        synchronized void produceOne(String id) throws Exception {
            produceOne(id, false);
        }

        /**
         * Returns once {@code id} is in the log exactly as acked; never sends it while it may already be there.
         * {@code maybeStored}: an earlier attempt's outcome is unknown, so read back first.
         */
        synchronized void produceOne(String id, boolean maybeStored) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
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
                    this.uncertainIds.add(id);
                    maybeStored = true; // the request may have reached the leader before it died
                    continue;
                }
                if (r.statusCode() == 200) {
                    var m = BASE.matcher(r.body());
                    assertTrue(m.find(), "200 without baseOffset: " + r.body());
                    this.ackOffset.put(id, Long.parseLong(m.group(1)));
                    this.acked.add(id);
                    return;
                }
                if (r.body().contains("outcome-unknown")) {
                    // The contract: 503, never a redirect (a resent body could be stored twice).
                    assertEquals(503, r.statusCode(), r.body());
                    assertTrue(r.headers().firstValue("location").isEmpty(), "outcome-unknown with a Location");
                    this.uncertain++;
                    this.uncertainIds.add(id);
                    maybeStored = true;
                    continue;
                }
                // Refused before the log, safe to resend: not leader (a second 307, or 503 without a leader),
                // retryable not-appended, or unavailable, each a 503 with Retry-After. Anything else is a bug here.
                boolean retryable = r.statusCode() == 307 || r.statusCode() == 503
                        && r.headers().firstValue("retry-after").isPresent();
                if (!retryable) {
                    fail("unexpected answer for " + id + ": " + r.statusCode() + " " + r.body());
                }
                Thread.sleep(50);
            }
            fail("could not produce " + id);
        }

        /**
         * Sends {@code id} to {@code node} as a request known to be open (all but its last byte), runs
         * {@code whileOpen} (the kill), and then resolves it the way any unknown outcome is resolved.
         */
        synchronized void produceHeldOpen(String node, Runnable whileOpen) throws Exception {
            var id = next();
            this.sent.add(id);
            try (var s = this.c.holdOpen(node, MsgReplCluster.frame(id))) {
                whileOpen.run();
                int got;
                try {
                    got = s.getInputStream().read();
                } catch (IOException e) {
                    got = -1;
                }
                assertEquals(-1, got, "the killed node answered a request it never fully received");
            }
            this.uncertain++;
            this.uncertainIds.add(id);
            produceOne(id, true);
        }
    }

    /**
     * Every acked id once, nothing that was never sent, no id twice, offsets without gaps from 0, each id acked with
     * an offset stored at exactly that offset, acked ids in ack order (the producer is sequential), every uncertain id
     * stored exactly once, and one log on every listed node.
     */
    static List<String> assertLog(MsgReplCluster c, Producer p, String... nodes) throws Exception {
        List<String> first = null;
        for (var node : nodes) {
            awaitRecords(c, node, p.acked);
            var entries = c.entries(node, false);
            var recs = entries.stream().map(MsgReplCluster.Entry::id).toList();
            for (int i = 0; i < entries.size(); i++) {
                assertEquals(i, entries.get(i).offset(), node + " offsets have a gap or do not start at 0 at "
                        + entries.get(i));
            }
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
            for (var id : p.uncertainIds) {
                assertEquals(1, counts.getOrDefault(id, 0), node + " uncertain write " + id + " is not stored once");
            }
            for (var r : recs) {
                assertTrue(p.sent.contains(r), node + " has a record nobody sent: " + r);
            }
            for (var e : p.ackOffset.entrySet()) {
                int at = recs.indexOf(e.getKey());
                assertEquals(e.getValue().longValue(), entries.get(at).offset(), node + " stores " + e.getKey()
                        + " at another offset than its ack gave");
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
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            var writer = Thread.ofPlatform().start(() -> {
                try {
                    int afterKill = 0;
                    while (afterKill < 150 && !stop.get()) {
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
            try {
                // Deterministic unknown outcome: the leader dies while one produce is known to be open on it.
                p.produceHeldOpen(leader, () -> {
                    killedAt.set(System.nanoTime());
                    try {
                        c.kill(leader);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });
                writer.join(Duration.ofSeconds(120));
                assertFalse(writer.isAlive(), "producer stuck after failover");
            } finally {
                stop.set(true);
                writer.interrupt();
                writer.join(Duration.ofSeconds(10));
            }
            assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(p.acked.size() > ackedBeforeKill + 150, "the producer was writing when the leader died");
            assertTrue(p.uncertain >= 1, "the leader kill produced no unknown outcome");
            assertFalse(p.resentAfterUncertain.isEmpty(), "the held-open write was resolved by read-back and resent");
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

            // Turning replication back on after that local write must refuse (exit 64), not fork the offsets.
            c.stop(leader);
            int code = c.startExpectingExit(leader, "on");
            var refused = c.logText(leader);
            assertEquals(64, code, refused);
            assertTrue(refused.contains("replicated topic " + MsgReplCluster.TOPIC + " ledger end is "
                    + (before.size() + 1) + " but replication stopped cleanly at " + before.size()), refused);
        }
    }
}
