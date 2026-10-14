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
}
