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
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.RetryPolicy;

/** Sharded claims: pool-level wake, skipping empty shards, multi-shard fill (S1c follow-up to #173). */
class ShardClaimTest {

    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    Topics topics;

    TaskService open(int shards, long sliceNanos) {
        this.topics = new Topics(this.dir, new RetryPolicy(5, 0, 0), this.now::get);
        return new TaskService(this.topics, shards, sliceNanos);
    }

    @AfterEach
    void close() {
        if (this.topics != null) this.topics.close();
    }

    /** A seq that {@code shardOf(1, seq)} maps to {@code shard}, above {@code after}. */
    static long seqFor(TaskService t, int shard, long after) {
        long s = after + 1;
        while (t.shardOf(1, s) != shard) s++;
        return s;
    }

    @Test
    void anEmptyPollDoesNotClobberTheHintOfASubmitThatRacedIn() throws Exception {
        // Forces the lost-wakeup interleaving: shard s carries a stale hint (1), the hinted poll of s comes back
        // empty, and a submit to s lands between that poll and the hint reset (the afterEmptyPoll seam). A blind
        // ready.set(s, 0) wiped the submit's +1 too; the CAS reset (expects the 1 it read) must leave it.
        var t = open(4, TimeUnit.SECONDS.toNanos(30));
        String worker = "w";
        int s = Math.floorMod(worker.hashCode(), 4);
        var ps = t.pool("p");
        ps.added(s, 1); // stale: nothing in the partition
        t.afterEmptyPoll = () -> {
            t.afterEmptyPoll = TaskService.NO_HOOK;
            t.submit("p", 1, seqFor(t, s, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        };
        var got = t.claim("p", worker, 1, 10_000, 0);
        // the claim's follow-up full sweep takes the task either way; the hint tells the two resets apart:
        // fixed: 1 (stale) + 1 (submit) kept by the failed CAS, minus 1 taken = 1; blind set: 0 - 1 -> 0
        assertEquals(1, got.size());
        assertEquals(s, got.get(0).shard());
        assertEquals(1, t.readyHints("p")[s], "the raced-in submit's hint survived the empty-poll reset");
    }

    @Test
    void aStaleHintWithNoRaceIsResetByTheEmptyPoll() {
        // the CAS succeeds when nothing raced in: a regression to "never reset" would leave the 1
        var t = open(4, TimeUnit.SECONDS.toNanos(30));
        String worker = "w";
        int s = Math.floorMod(worker.hashCode(), 4);
        t.pool("p").added(s, 1); // stale: nothing in the partition
        assertTrue(t.claim("p", worker, 1, 10_000, 0).isEmpty());
        assertEquals(0, t.readyHints("p")[s], "the stale hint was reset");
    }

    @Test
    void aSubmitOnAnotherShardWakesAWaitingClaimAtOnce() throws Exception {
        // a 30 s backstop slice: without the pool-level signal the claim would sleep through the whole wait
        var t = open(4, TimeUnit.SECONDS.toNanos(30));
        String worker = "w";
        int home = Math.floorMod(worker.hashCode(), 4);
        int other = (home + 1) % 4;
        var claim = CompletableFuture.supplyAsync(() -> t.claim("p", worker, 1, 10_000, TimeUnit.SECONDS.toNanos(20)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.pool("p").waiting.get() == 0 && System.nanoTime() < until) Thread.onSpinWait();
        assertEquals(1, t.pool("p").waiting.get(), "the claim is parked");
        long t0 = System.nanoTime();
        t.submit("p", 1, seqFor(t, other, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var got = claim.get(5, TimeUnit.SECONDS);
        long wakeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(1, got.size());
        assertEquals(other, got.get(0).shard());
        assertTrue(wakeMs < 1_000, "woken by the cross-shard submit in " + wakeMs + " ms, not by the 30 s slice");
    }

    @Test
    void emptyShardsAreSkipped() {
        var t = open(8, TaskService.SHARD_WAIT_SLICE_NANOS);
        t.submit("p", 1, seqFor(t, 5, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        long before = t.shardPolls();
        var got = t.claim("p", "w", 1, 10_000);
        assertEquals(1, got.size());
        assertEquals(5, got.get(0).shard());
        assertEquals(1, t.shardPolls() - before, "only the shard with a ready task is polled");
        // nothing ready: one full sweep, then return (no wait)
        before = t.shardPolls();
        assertTrue(t.claim("p", "w", 1, 10_000).isEmpty());
        assertEquals(8, t.shardPolls() - before);
    }

    @Test
    void redeliveriesAreFoundByTheFullSweep() {
        var t = open(4, TaskService.SHARD_WAIT_SLICE_NANOS);
        t.submit("p", 1, seqFor(t, 2, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var first = t.claim("p", "w", 1, 1_000).get(0);
        this.now.addAndGet(1_001); // lease expired: no submit, no ready hint
        var again = t.claim("p", "w2", 1, 1_000);
        assertEquals(1, again.size());
        assertEquals(first.offset(), again.get(0).offset());
        assertEquals(first.attempt() + 1, again.get(0).attempt());
    }

    @Test
    void aClaimWithMaxAboveOneFillsAcrossShardsAndAckAllSettlesEachShard() {
        var t = open(4, TaskService.SHARD_WAIT_SLICE_NANOS);
        long seq = 0;
        for (int s = 0; s < 4; s++) {
            for (int i = 0; i < 2; i++) {
                seq = seqFor(t, s, seq);
                t.submit("p", 1, seq, TaskServiceTest.env("t" + s + i, "", TaskEnvelope.NO_DEADLINE));
            }
        }
        var got = t.claim("p", "w", 8, 10_000);
        assertEquals(8, got.size(), "one claim fills from every shard");
        var shardsSeen = new HashSet<Integer>();
        got.forEach(x -> shardsSeen.add(x.shard()));
        assertEquals(4, shardsSeen.size());
        var results = t.ackAll(got);
        assertEquals(8, results.size());
        assertTrue(results.stream().allMatch(r -> r == LeaseResult.OK), results.toString());
        this.now.addAndGet(20_000);
        assertTrue(t.claim("p", "w", 8, 10_000).isEmpty(), "acked on their own shards: nothing redelivered");
        // results keep input order across shards
        var mixed = new ArrayList<Task>();
        for (int s = 0; s < 2; s++) {
            seq = seqFor(t, s, seq);
            t.submit("p", 1, seq, TaskServiceTest.env("m", "", TaskEnvelope.NO_DEADLINE));
        }
        mixed.addAll(t.claim("p", "w", 2, 10_000));
        assertEquals(LeaseResult.OK, t.ack(mixed.get(1)));
        assertEquals(List.of(LeaseResult.OK, LeaseResult.NOT_IN_FLIGHT), t.ackAll(mixed));
    }

    @Test
    void replyAllAcksTasksFromSeveralShards() throws Exception {
        var t = open(4, TaskService.SHARD_WAIT_SLICE_NANOS);
        long seq = 0;
        for (int s = 0; s < 4; s++) {
            seq = seqFor(t, s, seq);
            t.submit("p", 1, seq, TaskServiceTest.env("r" + s, "replies", TaskEnvelope.NO_DEADLINE));
        }
        var got = t.claim("p", "w", 4, 10_000);
        assertEquals(4, got.size());
        var bodies = new ArrayList<byte[]>();
        got.forEach(x -> bodies.add(new byte[] {1}));
        var r = t.replyAll(got, 9, 1, "", bodies).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(LeaseResult.OK, LeaseResult.OK, LeaseResult.OK, LeaseResult.OK), r);
    }

    @Test
    void readyHintsArePerShardSnapshots() {
        var t = open(4, TaskService.SHARD_WAIT_SLICE_NANOS);
        assertEquals(4, t.readyHints("p").length);
        long seq = seqFor(t, 3, 0);
        t.submit("p", 1, seq, TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        t.submit("p", 1, seqFor(t, 3, seq), TaskServiceTest.env("y", "", TaskEnvelope.NO_DEADLINE));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new long[] {0, 0, 0, 2}, t.readyHints("p"));
        t.claim("p", "w", 1, 10_000);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new long[] {0, 0, 0, 1}, t.readyHints("p"));
    }

    @Test
    void oneShardKeepsThePlainPath() {
        var t = open(1, TaskService.SHARD_WAIT_SLICE_NANOS);
        t.submit("p", 1, 1, TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        assertEquals(1, t.claim("p", "w", 4, 10_000).size());
        assertEquals(0, t.shardPolls(), "no sharding state on a 1-shard pool");
    }

    @Test
    void anInterruptedWaitReturnsEmpty() throws Exception {
        var t = open(2, TimeUnit.SECONDS.toNanos(30));
        var th = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            assertTrue(t.claim("p", "w", 1, 1_000, TimeUnit.SECONDS.toNanos(10)).isEmpty());
        });
        th.join(5_000);
        assertTrue(!th.isAlive());
    }

    @Test
    void aSubmitHandsItsTaskToAParkedClaimDirectly() throws Exception {
        var t = open(4, TimeUnit.SECONDS.toNanos(30));
        var claim = CompletableFuture.supplyAsync(() -> t.claim("p", "w", 4, 10_000, TimeUnit.SECONDS.toNanos(20)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.pool("p").waiting.get() == 0 && System.nanoTime() < until) Thread.onSpinWait();
        assertEquals(1, t.pool("p").waiting.get(), "the claim is parked");
        long polls = t.shardPolls();
        t.submit("p", 1, seqFor(t, 2, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var got = claim.get(5, TimeUnit.SECONDS);
        assertEquals(1, got.size());
        assertEquals(2, got.get(0).shard());
        assertEquals(1, t.handoffs(), "claimed by the submitter for the parked worker");
        assertEquals(1, t.shardPolls() - polls, "the woken worker did not sweep again");
        assertEquals(0, t.readyHints("p")[2]);
        assertTrue(t.pool("p").waiters.isEmpty());
        assertEquals(LeaseResult.OK, t.ack(got.get(0)));
    }

    @Test
    void handoffUnderRaceClaimsEveryTaskExactlyOnce() throws Exception {
        // W workers park/claim with a tiny backstop slice (so cancel races the submitters' handoff constantly),
        // S submitters push N tasks. Every task must be claimed once: none lost to a cancelled waiter, none twice.
        var t = open(4, 50_000);
        int workers = 6, submitters = 3, perSubmitter = 3_000, n = submitters * perSubmitter;
        var seen = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var dup = new AtomicLong();
        var count = new AtomicLong();
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var pool = new ArrayList<Thread>();
        for (int w = 0; w < workers; w++) {
            String name = "w" + w;
            pool.add(Thread.ofPlatform().start(() -> {
                while (!stop.get()) {
                    var got = t.claim("p", name, 1 + (name.hashCode() & 3), 600_000, 5_000_000);
                    for (var x : got) {
                        if (!seen.add(x.shard() + ":" + x.offset())) dup.incrementAndGet();
                        count.incrementAndGet();
                    }
                    if (!got.isEmpty()) t.ackAll(got);
                }
            }));
        }
        var subs = new ArrayList<Thread>();
        for (int s = 0; s < submitters; s++) {
            long producer = 10 + s;
            subs.add(Thread.ofPlatform().start(() -> {
                for (int i = 1; i <= perSubmitter; i++) {
                    t.submit("p", producer, i, TaskServiceTest.env("t", "", TaskEnvelope.NO_DEADLINE));
                    if ((i & 63) == 0) Thread.yield();
                }
            }));
        }
        for (var s : subs) s.join(60_000);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (count.get() < n && System.nanoTime() < until) Thread.sleep(1);
        stop.set(true);
        for (var w : pool) w.join(10_000);
        assertEquals(0, dup.get(), "no task claimed twice");
        assertEquals(n, count.get(), "every task claimed");
        assertEquals(n, seen.size());
        assertTrue(t.handoffs() > 0, "the handoff path ran");
        assertTrue(t.pool("p").waiters.isEmpty(), "no waiter left behind");
        assertEquals(0, t.pool("p").waiting.get());
    }

    @Test
    void aTaskHandedToAWaiterThatIsBeingInterruptedIsStillReturned() throws Exception {
        // interrupt and submit race on a parked claim; whichever wins, the task is either returned or still
        // claimable, never leased to nobody
        for (int round = 0; round < 200; round++) {
            this.topics = new Topics(this.dir.resolve("r" + round), new RetryPolicy(5, 0, 0), this.now::get);
            var t = new TaskService(this.topics, 2, TimeUnit.SECONDS.toNanos(30));
            t.claim("p", "warm", 1, 1_000); // opens both shards on this thread, not on the one we interrupt
            var out = new java.util.concurrent.atomic.AtomicReference<List<Task>>();
            var th = Thread.ofPlatform().start(() -> out.set(t.claim("p", "w", 1, 600_000, TimeUnit.SECONDS.toNanos(10))));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (t.pool("p").waiting.get() == 0 && System.nanoTime() < until) Thread.onSpinWait();
            var sub = Thread.ofPlatform().start(() -> t.submit("p", 1, 1, TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE)));
            th.interrupt();
            sub.join(5_000);
            th.join(5_000);
            assertTrue(!th.isAlive());
            int returned = out.get().size();
            int left = t.claim("p", "other", 1, 600_000).size();
            assertEquals(1, returned + left, "round " + round + ": returned=" + returned + " left=" + left);
            this.topics.close();
            this.topics = null;
        }
    }

    @Test
    void aHandedLeaseStartsWhenTheWokenWorkerRunsNotAtTheSubmittersPoll() throws Exception {
        // Review of #223: the submitter polls (lease starts) before the waiter is runnable. Simulate a schedule gap
        // longer than a short visibilityMs between that poll and the waiter's return: the waiter must refresh the
        // lease on receipt, so the task is not redelivered to another worker while the first one still holds it.
        var t = open(4, TimeUnit.SECONDS.toNanos(30));
        long vis = 100;
        long gap = 150; // > vis: without the refresh the handed lease would already be expired on receipt
        t.afterHandoffPoll = () -> this.now.addAndGet(gap);
        var claim = CompletableFuture.supplyAsync(() -> t.claim("p", "w", 1, vis, TimeUnit.SECONDS.toNanos(20)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.pool("p").waiting.get() == 0 && System.nanoTime() < until) Thread.onSpinWait();
        long polledAt = this.now.get();
        t.submit("p", 1, seqFor(t, 2, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var got = claim.get(5, TimeUnit.SECONDS);
        t.afterHandoffPoll = TaskService.NO_HOOK;
        assertEquals(1, got.size());
        assertEquals(1, t.handoffs(), "taken by the handoff path");
        assertEquals(1, t.handoffRefreshes(), "the woken worker refreshed the handed lease");
        long received = polledAt + gap;
        assertTrue(got.get(0).leaseDeadlineMs() >= received + vis,
                "lease runs from receipt: " + got.get(0).leaseDeadlineMs() + " < " + (received + vis));
        // past the poll-time deadline, inside the refreshed one: nobody else may get it
        this.now.addAndGet(vis / 2);
        assertTrue(t.claim("p", "other", 4, vis).isEmpty(), "not redelivered while the first worker holds it");
        assertEquals(LeaseResult.OK, t.ack(got.get(0)));
    }

    @Test
    void aSubMillisecondHandoffDoesNotRewriteTheLease() throws Exception {
        var t = open(4, TimeUnit.SECONDS.toNanos(30));
        var claim = CompletableFuture.supplyAsync(() -> t.claim("p", "w", 1, 10_000, TimeUnit.SECONDS.toNanos(20)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.pool("p").waiting.get() == 0 && System.nanoTime() < until) Thread.onSpinWait();
        t.submit("p", 1, seqFor(t, 1, 0), TaskServiceTest.env("x", "", TaskEnvelope.NO_DEADLINE));
        var got = claim.get(5, TimeUnit.SECONDS);
        assertEquals(1, got.size());
        assertEquals(0, t.handoffRefreshes(), "clock did not move: no extra ledger write on the fast path");
        assertEquals(LeaseResult.OK, t.ack(got.get(0)));
    }
}
