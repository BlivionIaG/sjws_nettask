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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.RetryPolicy;

class TaskServiceTest {

    @TempDir
    Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    Topics topics;
    TaskService tasks;

    @BeforeEach
    void open() {
        this.topics = new Topics(this.dir, new RetryPolicy(3, 0, 0), this.now::get);
        this.tasks = new TaskService(this.topics);
    }

    @AfterEach
    void close() {
        this.topics.close();
    }

    static TaskEnvelope env(String body, String replyTo, long deadline) {
        return new TaskEnvelope(ReplyRouter.newId(), ReplyRouter.newId(), replyTo, deadline, "00-tp", "", "b1",
                "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void submitClaimAckIsFencedAndDedupedByProducerSeq() {
        var e = env("hello", "", TaskEnvelope.NO_DEADLINE);
        var first = this.tasks.submit("sum", 7, 1, e);
        assertFalse(first.duplicate());
        var again = this.tasks.submit("sum", 7, 1, e);
        assertTrue(again.duplicate());
        assertEquals(first.offset(), again.offset());
        var got = this.tasks.claim("sum", "w1", 10, 5_000);
        assertEquals(1, got.size());
        var t = got.get(0);
        assertEquals(e, t.envelope());
        assertEquals(1, t.attempt());
        assertEquals(this.now.get() + 5_000, t.leaseDeadlineMs());
        assertTrue(this.tasks.claim("sum", "w2", 10, 5_000).isEmpty(), "leased to one worker of the group");
        assertEquals(LeaseResult.OK, this.tasks.ack(t));
        assertEquals(LeaseResult.NOT_IN_FLIGHT, this.tasks.ack(t));
    }

    @Test
    void aStaleWorkerCannotSettleTheNewAttempt() {
        this.tasks.submit("p", 1, 1, env("x", "", TaskEnvelope.NO_DEADLINE));
        var old = this.tasks.claim("p", "slow", 1, 100).get(0);
        this.now.addAndGet(101); // lease expired
        var fresh = this.tasks.claim("p", "fast", 1, 100).get(0);
        assertEquals(2, fresh.attempt());
        assertEquals(LeaseResult.STALE_ATTEMPT, this.tasks.ack(old));
        assertEquals(LeaseResult.STALE_ATTEMPT, this.tasks.nack(old, 0));
        assertEquals(LeaseResult.STALE_ATTEMPT, this.tasks.reject(old, "x"));
        assertEquals(LeaseResult.STALE_ATTEMPT,
                this.tasks.extend("p", new long[] {old.offset()}, new int[] {old.attempt()}, 100).get(0));
        assertEquals(LeaseResult.OK, this.tasks.ack(fresh));
    }

    @Test
    void nackRetriesThenDeadLettersAndRejectGoesStraightToTheDlq() {
        this.tasks.submit("p", 1, 1, env("flaky", "", TaskEnvelope.NO_DEADLINE));
        for (int attempt = 1; attempt <= 3; attempt++) {
            var t = this.tasks.claim("p", "w", 1, 1_000).get(0);
            assertEquals(attempt, t.attempt());
            assertEquals(LeaseResult.OK, this.tasks.nack(t, 0));
        }
        assertTrue(this.tasks.claim("p", "w", 1, 1_000).isEmpty());
        this.tasks.submit("p", 1, 2, env("poison", "", TaskEnvelope.NO_DEADLINE));
        var poison = this.tasks.claim("p", "w", 1, 1_000).get(0);
        assertEquals(LeaseResult.OK, this.tasks.reject(poison, "bad-input"));
        var dlq = this.tasks.deadLetters("p");
        dlq.subscribe("audit", true);
        var dead = dlq.poll("audit", "a", 10, 1_000);
        assertEquals(2, dead.size());
        assertEquals("nacked:max-attempts", dead.get(0).originReason());
        assertEquals("rejected:bad-input", dead.get(1).originReason());
        assertEquals("poison", new String(TaskEnvelope.decode(dead.get(1).payload()).body(), StandardCharsets.UTF_8));
    }

    @Test
    void expiredAndMalformedTasksAreRejectedAtClaim() {
        this.tasks.submit("p", 1, 1, env("late", "", this.now.get() - 1));
        this.tasks.partition("p").produce(1, 2, new byte[] {9, 9});
        this.tasks.submit("p", 1, 3, env("ok", "", TaskEnvelope.NO_DEADLINE));
        var got = this.tasks.claim("p", "w", 10, 1_000);
        assertEquals(1, got.size());
        assertEquals("ok", new String(got.get(0).envelope().body(), StandardCharsets.UTF_8));
        var dlq = this.tasks.deadLetters("p");
        dlq.subscribe("audit", true);
        var reasons = dlq.poll("audit", "a", 10, 1_000).stream().map(d -> d.originReason()).toList();
        assertEquals(List.of("rejected:deadline", "rejected:bad-envelope"), reasons);
    }

    @Test
    void leaseIsCappedAtTheTaskDeadline() {
        long deadline = this.now.get() + 400;
        this.tasks.submit("p", 1, 1, env("a", "", deadline));
        this.tasks.submit("p", 1, 2, env("b", "", this.now.get() + 900));
        this.tasks.submit("p", 1, 3, env("c", "", TaskEnvelope.NO_DEADLINE));
        var got = this.tasks.claim("p", "w", 10, 5_000);
        assertEquals(3, got.size());
        assertEquals(deadline, got.get(0).leaseDeadlineMs());
        assertTrue(got.get(1).leaseDeadlineMs() <= this.now.get() + 900);
        assertEquals(this.now.get() + 5_000, got.get(2).leaseDeadlineMs());
        this.now.addAndGet(401); // a and b's leases ran out; c's did not
        var again = this.tasks.claim("p", "w2", 10, 5_000);
        assertEquals(1, again.size(), "b is still before its deadline, so it is redelivered; a is past it and rejected");
        assertEquals("b", new String(again.get(0).envelope().body(), StandardCharsets.UTF_8));
    }

    @Test
    void replyIsDurableBeforeTheTaskIsAcked() throws Exception {
        var e = env("q", "reply.n1", TaskEnvelope.NO_DEADLINE);
        this.tasks.submit("p", 1, 1, e);
        var t = this.tasks.claim("p", "w", 1, 10_000).get(0);
        var acked = this.tasks.reply(t, 42, 1, "text/plain", "a".getBytes(StandardCharsets.UTF_8));
        assertEquals(LeaseResult.OK, acked.get(5, TimeUnit.SECONDS));
        var reply = this.topics.partition("reply.n1");
        reply.subscribe("r", true);
        var got = reply.poll("r", "r", 10, 1_000);
        assertEquals(1, got.size());
        var r = TaskEnvelope.decode(got.get(0).payload());
        assertArrayEquals(e.correlationId(), r.correlationId());
        assertEquals("", r.replyTo());
        assertEquals(42, got.get(0).producerId());
        // A reply retried after a crash (same producer, seq) is dropped, and the ack is NOT_IN_FLIGHT.
        assertEquals(LeaseResult.NOT_IN_FLIGHT,
                this.tasks.reply(t, 42, 1, "text/plain", new byte[0]).get(5, TimeUnit.SECONDS));
        assertTrue(reply.poll("r", "r", 10, 1_000).isEmpty());
        var noReplyTo = new Task("p", 0, 1, 0, env("x", "", TaskEnvelope.NO_DEADLINE));
        assertThrows(IllegalArgumentException.class, () -> this.tasks.reply(noReplyTo, 1, 1, "", new byte[0]));
    }

    @Test
    void leaseKeeperUsesOneBatchedExtendPerPoolAndDropsLostLeases() {
        var lost = new ArrayList<String>();
        var keeper = new LeaseKeeper(this.tasks, 1_000, (t, r) -> lost.add(t.pool() + ":" + t.offset() + ":" + r));
        for (int i = 0; i < 5; i++) {
            this.tasks.submit("a", 1, i + 1, env("a" + i, "", TaskEnvelope.NO_DEADLINE));
        }
        this.tasks.submit("b", 1, 1, env("b", "", TaskEnvelope.NO_DEADLINE));
        this.tasks.submit("b", 1, 2, env("soon", "", this.now.get() + 1_500));
        var a = this.tasks.claim("a", "w", 5, 1_000);
        var b = this.tasks.claim("b", "w", 2, 1_000);
        a.forEach(keeper::hold);
        b.forEach(keeper::hold);
        assertEquals(7, keeper.size());
        long recordsBefore = this.tasks.partition("a").logBytes();
        this.now.addAndGet(600);
        keeper.tick();
        assertEquals(2, keeper.batches(), "one extend call per pool");
        long until = System.nanoTime() + 5_000_000_000L; // the extend record reaches the log via the async writer
        while (this.tasks.partition("a").logBytes() <= recordsBefore && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertTrue(this.tasks.partition("a").logBytes() > recordsBefore);
        this.now.addAndGet(900); // 1.5 s after claim: without the keeper every lease would have expired
        assertTrue(this.tasks.claim("a", "w2", 5, 1_000).isEmpty(), "kept leases are not redelivered");
        // "soon" left the batch (its deadline is within one visibility) and is reported with a null result.
        assertEquals(List.of("b:" + b.get(1).offset() + ":null"), lost);
        // Settle one lease behind the keeper's back and let another worker take one after expiry: both are reported.
        assertEquals(LeaseResult.OK, this.tasks.ack(a.get(0)));
        keeper.tick();
        assertTrue(lost.contains("a:" + a.get(0).offset() + ":NOT_IN_FLIGHT"), lost.toString());
        keeper.release(a.get(1));
        assertEquals(4, keeper.size());
        keeper.close(); // never started: a no-op
    }

    @Test
    void slicedTicksRotateThroughEveryLease() {
        for (int i = 0; i < 5; i++) {
            this.tasks.submit("s", 1, i + 1, env("s" + i, "", TaskEnvelope.NO_DEADLINE));
        }
        var held = this.tasks.claim("s", "w", 5, 1_000);
        var keeper = new LeaseKeeper(this.tasks, 1_000, (t, r) -> { throw new AssertionError(r); });
        held.forEach(keeper::hold);
        // 3 slices of 2 cover all 5 leases (the third wraps), each within the 1 s lease.
        for (int i = 0; i < 3; i++) {
            this.now.addAndGet(300);
            keeper.tick(2);
        }
        assertEquals(3, keeper.batches());
        this.now.addAndGet(300); // 1.2 s after claim: every lease got at least one extend
        assertTrue(this.tasks.claim("s", "w2", 5, 1_000).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> keeper.tick(0));
        new LeaseKeeper(this.tasks, 1_000, (t, r) -> { }).tick(1); // empty keeper
    }

    @Test
    void leaseKeeperThreadHeartbeatsUntilClosed() throws Exception {
        var real = new Topics(this.dir.resolve("real"), RetryPolicy.DEFAULT, System::currentTimeMillis);
        try (real) {
            var svc = new TaskService(real);
            svc.submit("p", 1, 1, env("x", "", TaskEnvelope.NO_DEADLINE));
            var t = svc.claim("p", "w", 1, 300).get(0);
            try (var keeper = new LeaseKeeper(svc, 300, (x, r) -> { }).start(50)) {
                keeper.hold(t);
                Thread.sleep(700);
                assertTrue(svc.claim("p", "w2", 1, 300).isEmpty(), "kept alive past its 300 ms lease");
                assertTrue(keeper.batches() >= 5, "batches=" + keeper.batches());
            }
            assertEquals(LeaseResult.OK, svc.ack(t));
        }
        assertThrows(IllegalArgumentException.class, () -> new LeaseKeeper(this.tasks, 0, (x, r) -> { }));
        var k = new LeaseKeeper(this.tasks, 100, (x, r) -> { });
        assertThrows(IllegalArgumentException.class, () -> k.start(0));
        assertThrows(IllegalArgumentException.class, () -> k.start(101));
    }

    @Test
    void envelopeRoundTripsAndRejectsBadInput() {
        var e = env("body", "reply.x", 123);
        var d = TaskEnvelope.decode(e.encode());
        assertEquals(e, d);
        assertEquals(e.hashCode(), d.hashCode());
        assertTrue(d.toString().contains("reply.x"));
        assertNotEquals(e, env("body", "reply.x", 123));
        assertNotEquals(e, "x");
        assertTrue(e.expiredAt(123));
        assertFalse(e.expiredAt(122));
        assertFalse(env("b", "", TaskEnvelope.NO_DEADLINE).expiredAt(Long.MAX_VALUE));
        var bytes = e.encode();
        bytes[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> TaskEnvelope.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> TaskEnvelope.decode(java.util.Arrays.copyOf(e.encode(), 20)));
        var longLen = e.encode();
        longLen[longLen.length - 5] = 0x7f; // body length beyond the payload
        assertThrows(IllegalArgumentException.class, () -> TaskEnvelope.decode(longLen));
        assertThrows(IllegalArgumentException.class, () -> new TaskEnvelope(new byte[15], new byte[16], "", 0, "", "", "", "", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new TaskEnvelope(new byte[16], new byte[17], "", 0, "", "", "", "", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new TaskEnvelope(new byte[16], new byte[16], "", 0, "", "", "", "",
                new byte[TaskEnvelope.MAX_INLINE_BODY + 1]));
        var nulls = new TaskEnvelope(new byte[16], new byte[16], null, 0, null, null, null, null, new byte[0]);
        assertEquals("", nulls.replyTo() + nulls.traceparent() + nulls.tracestate() + nulls.budgetRef() + nulls.contentType());
        var copy = e.taskId();
        copy[0] ^= 1;
        assertNotEquals(copy[0], e.taskId()[0], "defensive copies");
    }

    @Test
    void replyAllWritesEveryReplyThenOneBatchedAck() throws Exception {
        for (int i = 0; i < 3; i++) {
            this.tasks.submit("p", 1, i + 1, env("q" + i, "reply.all", TaskEnvelope.NO_DEADLINE));
        }
        var got = this.tasks.claim("p", "w", 3, 10_000);
        var bodies = new ArrayList<byte[]>();
        for (var t : got) {
            bodies.add(("a" + t.offset()).getBytes(StandardCharsets.UTF_8));
        }
        var acks = this.tasks.replyAll(got, 9, 100, "text/plain", bodies).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(LeaseResult.OK, LeaseResult.OK, LeaseResult.OK), acks);
        var reply = this.topics.partition("reply.all");
        reply.subscribe("r", true);
        var rs = reply.poll("r", "r", 10, 1_000);
        assertEquals(3, rs.size());
        assertEquals(102, rs.get(2).seq());
        assertEquals("a" + got.get(1).offset(), new String(TaskEnvelope.decode(rs.get(1).payload()).body(), StandardCharsets.UTF_8));
        assertEquals(List.of(LeaseResult.NOT_IN_FLIGHT), this.tasks.replyAll(List.of(got.get(0)), 9, 200, "",
                List.of(new byte[0])).get(5, TimeUnit.SECONDS));
        assertEquals(List.of(), this.tasks.replyAll(List.of(), 9, 1, "", List.of()).get());
        assertThrows(IllegalArgumentException.class, () -> this.tasks.replyAll(got, 9, 1, "", List.of()));
        var other = new Task("q", 0, 1, 0, got.get(0).envelope());
        assertThrows(IllegalArgumentException.class,
                () -> this.tasks.replyAll(List.of(got.get(0), other), 9, 1, "", List.of(new byte[0], new byte[0])));
        var noReply = new Task("p", 0, 1, 0, env("x", "", TaskEnvelope.NO_DEADLINE));
        assertThrows(IllegalArgumentException.class, () -> this.tasks.replyAll(List.of(noReply), 9, 1, "", List.of(new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> env("x", "r".repeat(70_000), 0).encode());
    }

    @Test
    void envelopeEqualityCoversEveryField() {
        byte[] a = new byte[16], b = new byte[16];
        b[0] = 1;
        var base = new TaskEnvelope(a, a, "r", 1, "tp", "ts", "bg", "ct", new byte[] {1});
        assertEquals(base, new TaskEnvelope(a, a, "r", 1, "tp", "ts", "bg", "ct", new byte[] {1}));
        var others = List.of(
                new TaskEnvelope(b, a, "r", 1, "tp", "ts", "bg", "ct", new byte[] {1}),
                new TaskEnvelope(a, b, "r", 1, "tp", "ts", "bg", "ct", new byte[] {1}),
                new TaskEnvelope(a, a, "x", 1, "tp", "ts", "bg", "ct", new byte[] {1}),
                new TaskEnvelope(a, a, "r", 2, "tp", "ts", "bg", "ct", new byte[] {1}),
                new TaskEnvelope(a, a, "r", 1, "xx", "ts", "bg", "ct", new byte[] {1}),
                new TaskEnvelope(a, a, "r", 1, "tp", "xx", "bg", "ct", new byte[] {1}),
                new TaskEnvelope(a, a, "r", 1, "tp", "ts", "xx", "ct", new byte[] {1}),
                new TaskEnvelope(a, a, "r", 1, "tp", "ts", "bg", "xx", new byte[] {1}),
                new TaskEnvelope(a, a, "r", 1, "tp", "ts", "bg", "ct", new byte[] {2}));
        for (var o : others) {
            assertNotEquals(base, o, o.toString());
        }
    }

    @Test
    void topicsReopenAfterCloseAndRefuseAfterShutdown() {
        var p = this.topics.partition("x");
        assertTrue(this.topics.isOpen("x"));
        this.topics.close("x");
        this.topics.close("x");
        assertFalse(this.topics.isOpen("x"));
        assertNotEquals(p, this.topics.partition("x"));
        this.topics.close();
        assertThrows(IllegalStateException.class, () -> this.topics.partition("y"));
        var bad = new Topics(this.dir, RetryPolicy.DEFAULT, this.now::get);
        assertThrows(IllegalArgumentException.class, () -> bad.partition("a/b"));
        var file = this.dir.resolve("file");
        assertThrows(java.io.UncheckedIOException.class, () -> {
            java.nio.file.Files.writeString(file, "x");
            new Topics(file, RetryPolicy.DEFAULT, this.now::get).partition("t");
        });
    }
}
