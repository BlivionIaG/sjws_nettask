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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.RetryPolicy;

/** Multi-partition pools (S1c step 2): a pool spread over N partitions, claimed across all of them. */
class ShardedPoolTest {

    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    Topics topics;
    TaskService tasks;

    @BeforeEach
    void open() {
        this.topics = new Topics(this.dir, new RetryPolicy(3, 0, 0), this.now::get);
        this.tasks = new TaskService(this.topics, 4);
    }

    @AfterEach
    void close() {
        this.topics.close();
    }

    static TaskEnvelope env(String body) {
        return TaskServiceTest.env(body, "", TaskEnvelope.NO_DEADLINE);
    }

    @Test
    void oneShardKeepsTheOldTopicLayout() {
        assertEquals("tasks.p", TaskService.topic("p", 0));
        assertEquals("tasks.p#3", TaskService.topic("p", 3));
        var one = new TaskService(this.topics, 1);
        for (long s = 1; s < 50; s++) assertEquals(0, one.shardOf(7, s));
        assertThrows(IllegalArgumentException.class, () -> new TaskService(this.topics, 0));
        assertThrows(IllegalArgumentException.class, () -> new TaskService(this.topics, 257));
    }

    @Test
    void tasksSpreadOverShardsAndEachIsClaimedAndAckedExactlyOnce() {
        int n = 400;
        for (int i = 1; i <= n; i++) assertFalse(this.tasks.submit("p", 9, i, env("t" + i)).duplicate());
        var shardsUsed = new HashSet<Integer>();
        var seen = new HashSet<String>();
        while (true) {
            var got = this.tasks.claim("p", "w" + seen.size() % 5, 7, 60_000);
            if (got.isEmpty()) break;
            for (var t : got) {
                shardsUsed.add(t.shard());
                assertTrue(seen.add(t.shard() + ":" + t.offset()), "claimed twice");
                assertEquals(LeaseResult.OK, this.tasks.ack(t));
                assertEquals(LeaseResult.NOT_IN_FLIGHT, this.tasks.ack(t));
            }
        }
        assertEquals(n, seen.size());
        assertEquals(4, shardsUsed.size(), "all shards used: " + shardsUsed);
    }

    @Test
    void aRetriedSubmitIsDedupedOnItsShard() {
        for (int i = 1; i <= 50; i++) {
            var first = this.tasks.submit("p", 3, i, env("x"));
            var again = this.tasks.submit("p", 3, i, env("x"));
            assertFalse(first.duplicate());
            assertTrue(again.duplicate());
            assertEquals(first.offset(), again.offset());
        }
        int claimed = 0;
        for (var got = this.tasks.claim("p", "w", 100, 60_000); !got.isEmpty(); got = this.tasks.claim("p", "w", 100, 60_000)) claimed += got.size();
        assertEquals(50, claimed);
    }

    @Test
    void settlesAndExtendsAreRoutedToTheTasksShard() {
        for (int i = 1; i <= 40; i++) this.tasks.submit("p", 0, 0, env("r" + i)); // anonymous: round-robin
        var got = this.tasks.claim("p", "w", 1, 60_000);
        var other = got.get(0);
        // walk until a task on a non-zero shard
        while (other.shard() == 0) { this.tasks.ack(other); other = this.tasks.claim("p", "w", 1, 60_000).get(0); }
        var r = this.tasks.extend(other.pool(), other.shard(), new long[] {other.offset()}, new int[] {other.attempt()}, 120_000);
        assertEquals(LeaseResult.OK, r.get(0));
        // the same offset on shard 0 is a different (or no) lease: shard 0 does not know this attempt
        assertEquals(LeaseResult.OK, this.tasks.nack(other, 0));
        Task again = null;
        for (int i = 0; i < 40 && again == null; i++) {
            for (var t : this.tasks.claim("p", "w", 1, 60_000)) {
                if (t.shard() == other.shard() && t.offset() == other.offset()) again = t;
            }
        }
        assertTrue(again != null, "redelivered on its shard");
        assertEquals(other.attempt() + 1, again.attempt());
        assertEquals(LeaseResult.OK, this.tasks.reject(again, "bad"));
        var dlq = this.tasks.deadLetters("p", again.shard());
        dlq.subscribe("audit", true);
        var dead = dlq.poll("audit", "a", 10, 1_000);
        assertEquals(1, dead.size());
        assertEquals("rejected:bad", dead.get(0).originReason());
    }

    @Test
    void leaseIdsCarryTheShardAndUnshardedIdsAreUnchanged() throws Exception {
        assertEquals("p~7~2", SwarmHttp.leaseId(new Task("p", 7, 2, 0, null)));
        var t = new Task("a~b", 7, 2, 0, null, 3);
        assertEquals("a~b~7~2.3", SwarmHttp.leaseId(t));
        var back = SwarmHttp.parseLease(SwarmHttp.leaseId(t));
        assertEquals("a~b", back.pool());
        assertEquals(7, back.offset());
        assertEquals(2, back.attempt());
        assertEquals(3, back.shard());
        assertEquals(0, SwarmHttp.parseLease("p~7~2").shard());
        assertThrows(Exception.class, () -> SwarmHttp.parseLease("p~7~2.-1"));
        assertThrows(Exception.class, () -> SwarmHttp.parseLease("p~7~2.x"));
    }

    @Test
    void aWaitingClaimSeesATaskOnAnotherShard() throws Exception {
        int home = Math.floorMod("w".hashCode(), 4);
        var waiter = java.util.concurrent.CompletableFuture.supplyAsync(() -> this.tasks.claim("p", "w", 1, 60_000, TimeUnit.SECONDS.toNanos(20)));
        // find a (producer, seq) that routes away from the waiter's home shard
        long seq = 1;
        while (this.tasks.shardOf(5, seq) == home) seq++;
        this.tasks.submit("p", 5, seq, env("late"));
        var got = waiter.get(20, TimeUnit.SECONDS);
        assertEquals(1, got.size());
        assertTrue(got.get(0).shard() != home);
    }

    @Test
    void poolCountsAndDeadLettersAggregateAcrossShards() {
        int n = 40;
        for (int i = 1; i <= n; i++) this.tasks.submit("p", 0, 0, env("d" + i)); // round-robin: 10 per shard
        var shardsRejected = new HashSet<Integer>();
        int acked = 0, rejected = 0;
        for (var got = this.tasks.claim("p", "w", 5, 60_000); !got.isEmpty(); got = this.tasks.claim("p", "w", 5, 60_000)) {
            for (var t : got) {
                if (t.offset() % 3 == 0) { assertEquals(LeaseResult.OK, this.tasks.reject(t, "bad")); rejected++; shardsRejected.add(t.shard()); }
                else if (t.offset() % 3 == 1) { assertEquals(LeaseResult.OK, this.tasks.ack(t)); acked++; }
                // the rest stay in flight
            }
        }
        assertTrue(shardsRejected.size() > 1, "dead letters on several shards: " + shardsRejected);
        var c = this.tasks.counts("p");
        assertEquals(4, c.shards());
        assertEquals(acked, c.workers().acked());
        assertEquals(rejected, c.workers().deadLettered());
        assertEquals(n - acked - rejected, c.workers().inFlight());
        assertEquals(rejected, c.deadLetterQueueSize());
        assertTrue(c.oldestUnresolvedAtMs() > 0);
        assertEquals(4, this.tasks.deadLetterQueues("p").size());
        var dead = this.tasks.pollDeadLetters("p", "audit", "a", 1000, 60_000);
        assertEquals(rejected, dead.size());
        var seen = new HashSet<Integer>();
        for (var d : dead) {
            seen.add(d.shard());
            assertEquals("rejected:bad", d.delivery().originReason());
            assertTrue(this.tasks.deadLetters("p", d.shard()).ack("audit", d.delivery().offset()));
        }
        assertEquals(shardsRejected, seen);
        assertTrue(this.tasks.pollDeadLetters("p", "audit", "a", 1000, 60_000).isEmpty());
    }

    @Test
    void unshardedPoolCountsEqualThePartitionsOwn() {
        var one = new TaskService(this.topics, 1);
        for (int i = 1; i <= 5; i++) one.submit("u", 1, i, env("u" + i));
        var t = one.claim("u", "w", 2, 60_000);
        one.ack(t.get(0));
        one.reject(t.get(1), "x");
        var c = one.counts("u");
        var own = one.partition("u").counts().groups().get(TaskService.WORKERS).counts();
        assertEquals(own, c.workers());
        assertEquals(1, c.deadLetterQueueSize());
        assertEquals(List.of(one.deadLetters("u")), one.deadLetterQueues("u"));
    }
}
