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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.RetryPolicy;

class ReplyRouterTest {

    @TempDir
    Path dir;

    @Test
    void requestReplyRoundTripThroughTheLedger() throws Exception {
        try (var swarm = Swarm.open(this.dir, "node-1", 99)) {
            assertEquals("reply.node-1", swarm.replies().topic());
            long deadline = System.currentTimeMillis() + 10_000;
            var f = swarm.replies().call("echo", "text/plain", "ping".getBytes(StandardCharsets.UTF_8), deadline, "00-t");
            assertTrue(swarm.replies().polling());
            var t = swarm.tasks().claim("echo", "w", 1, 5_000).get(0);
            assertEquals("reply.node-1", t.envelope().replyTo());
            assertEquals("00-t", t.envelope().traceparent());
            assertEquals(LeaseResult.OK, swarm.tasks().reply(t, 5, 1, "text/plain", "pong".getBytes(StandardCharsets.UTF_8))
                    .get(5, TimeUnit.SECONDS));
            var r = f.get(5, TimeUnit.SECONDS);
            assertEquals("pong", new String(r.body(), StandardCharsets.UTF_8));
            assertEquals(0, swarm.replies().pending());
            try (var keeper = swarm.leaseKeeper(1_000, (x, y) -> { })) {
                assertEquals(0, keeper.size());
            }
            assertTrue(swarm.topics().isOpen("tasks.echo"));
        }
    }

    @Test
    void aCallTimesOutAtItsDeadlineAndALateReplyIsAckedAndDiscarded() throws Exception {
        try (var swarm = Swarm.open(this.dir, "n", 1)) {
            var f = swarm.replies().call("slow", "", new byte[0], System.currentTimeMillis() + 100, "");
            var t = swarm.tasks().claim("slow", "w", 1, 5_000).get(0);
            var e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, e.getCause());
            swarm.tasks().reply(t, 5, 1, "", new byte[0]).get(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().lateReplies() == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(1, swarm.replies().lateReplies());
            // A payload that is not an envelope is also acked and dropped.
            swarm.topics().partition("reply.n").produce(0, 0, new byte[] {1});
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().lateReplies() < 2 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(2, swarm.replies().lateReplies());
        }
    }

    @Test
    void theReplyTopicClosesWhenIdleAndReopensOnDemand() throws Exception {
        try (var swarm = Swarm.open(this.dir, "idle", 1, RetryPolicy.DEFAULT, System::currentTimeMillis, 50)) {
            var f = swarm.replies().call("p", "", new byte[0], System.currentTimeMillis() + 10_000, "");
            var t = swarm.tasks().claim("p", "w", 1, 5_000).get(0);
            swarm.tasks().reply(t, 5, 1, "", new byte[0]).get(5, TimeUnit.SECONDS);
            f.get(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().polling() && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertFalse(swarm.replies().polling());
            assertFalse(swarm.topics().isOpen("reply.idle"), "closed after the idle period; files kept");
            var g = swarm.replies().call("p", "", new byte[0], System.currentTimeMillis() + 10_000, "");
            assertTrue(swarm.replies().polling());
            var t2 = swarm.tasks().claim("p", "w", 1, 5_000).get(0);
            swarm.tasks().reply(t2, 5, 2, "", "again".getBytes(StandardCharsets.UTF_8)).get(5, TimeUnit.SECONDS);
            assertEquals("again", new String(g.get(5, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void closeFailsPendingCallsAndRefusesNewOnes() throws Exception {
        var swarm = Swarm.open(this.dir, "c", 1);
        var f = swarm.replies().call("p", "", new byte[0], System.currentTimeMillis() + 60_000, "");
        swarm.close();
        var e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertThrows(IllegalStateException.class, () -> swarm.replies().call("p", "", new byte[0], 0, ""));
        swarm.replies().close(); // idempotent
        assertThrows(IllegalArgumentException.class,
                () -> new ReplyRouter(swarm.topics(), swarm.tasks(), "x", 1, 0));
    }

    @Test
    void aSameProcessReplyCompletesTheCallerBeforeThePollerAndIsNotLate() throws Exception {
        try (var swarm = Swarm.open(this.dir, "direct", 7)) {
            long deadline = System.currentTimeMillis() + 10_000;
            var f = swarm.replies().call("echo", "text/plain", "ping".getBytes(StandardCharsets.UTF_8), deadline, "");
            var t = swarm.tasks().claim("echo", "w", 1, 5_000).get(0);
            assertEquals(LeaseResult.OK, swarm.tasks().reply(t, 5, 1, "text/plain", "pong".getBytes(StandardCharsets.UTF_8))
                    .get(5, TimeUnit.SECONDS));
            // Caller is already done (completeLocal); waiting here must not take the poller's 50 ms slice.
            var r = f.get(50, TimeUnit.MILLISECONDS);
            assertEquals("pong", new String(r.body(), StandardCharsets.UTF_8));
            assertEquals(0, swarm.replies().pending());
            // Give the poller time to ack the ledger copy; it must not count the direct delivery as late.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (swarm.replies().lateReplies() == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(0, swarm.replies().lateReplies());
        }
    }

    @Test
    void aSubmitFailureFailsTheCall() throws Exception {
        try (var swarm = Swarm.open(this.dir, "f", 1)) {
            var f = swarm.replies().call("bad/pool", "", new byte[0], System.currentTimeMillis() + 60_000, "");
            var e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
    }

    /** #219 review: the poller drains each direct delivery's key, so the dedup map does not grow with traffic. */
    @Test
    void directDeliveryKeysAreDrainedByThePoller() throws Exception {
        try (var swarm = Swarm.open(this.dir, "drain", 7)) {
            int n = 300;
            for (int i = 0; i < n; i++) {
                long deadline = System.currentTimeMillis() + 10_000;
                var f = swarm.replies().call("echo", "text/plain", new byte[] {1}, deadline, "");
                var t = swarm.tasks().claim("echo", "w", 1, 5_000).get(0);
                swarm.tasks().reply(t, 5, i + 1, "text/plain", new byte[] {2}).get(5, TimeUnit.SECONDS);
                f.get(5, TimeUnit.SECONDS);
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().deliveredLocalSize() > 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(0, swarm.replies().deliveredLocalSize());
            assertEquals(0, swarm.replies().lateReplies());
        }
    }

    /**
     * #219 review: completeLocal runs before produce. When produce throws, the caller is completed exactly once, the
     * task is not acked, it is redelivered after its lease, and the retried reply does not complete the caller again.
     */
    @Test
    void produceFailureAfterLocalCompletionRedeliversWithoutASecondCompletion() throws Exception {
        try (var swarm = Swarm.open(this.dir, "throws", 7)) {
            long deadline = System.currentTimeMillis() + 30_000;
            var f = swarm.replies().call("echo", "text/plain", new byte[] {1}, deadline, "");
            var t = swarm.tasks().claim("echo", "w", 1, 300).get(0);
            // Close the reply partition in place (still registered): produce runs after completeLocal and fails.
            var replyTopic = swarm.replies().topic();
            swarm.topics().partition(replyTopic).close();
            boolean failed;
            try {
                swarm.tasks().reply(t, 5, 1, "text/plain", new byte[] {2}).get(5, TimeUnit.SECONDS);
                failed = false;
            } catch (RuntimeException | ExecutionException e) {
                failed = true;
            }
            assertTrue(failed, "reply must fail when its produce fails");
            var r = f.get(1, TimeUnit.SECONDS);
            assertEquals(2, r.body()[0]);
            // Reopen the reply topic for the retry (the poller re-resolves the partition each slice).
            swarm.topics().close(replyTopic);
            assertEquals(0, swarm.replies().pending());
            // Not acked: the lease expires and the task comes back.
            var again = swarm.tasks().claim("echo", "w", 1, 5_000);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (again.isEmpty() && System.nanoTime() < until) {
                Thread.sleep(20);
                again = swarm.tasks().claim("echo", "w", 1, 5_000);
            }
            assertFalse(again.isEmpty(), "task redelivered after a failed reply");
            assertEquals(t.offset(), again.get(0).offset());
            assertEquals(LeaseResult.OK, swarm.tasks().reply(again.get(0), 5, 2, "text/plain", new byte[] {3})
                    .get(5, TimeUnit.SECONDS));
            // The retry finds no pending caller, so the first completion stands. Its ledger copy is the first one the
            // poller sees for that key: it consumes the remembered direct delivery (so the key does not leak) and is
            // acked without counting late.
            assertEquals(2, f.get().body()[0]);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().deliveredLocalSize() > 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(0, swarm.replies().deliveredLocalSize());
            assertEquals(0, swarm.replies().lateReplies());
            assertEquals(0, swarm.replies().pending());
        }
    }

    /** A call submitted outside {@link ReplyRouter#call}, as S2c's :stream does, with this router as replyTo. */
    private static Task submitStreamLike(Swarm swarm, byte[] id, long deadline) throws Exception {
        var env = new TaskEnvelope(id, id, swarm.replies().topic(), deadline, "00-tp", "", "b1", "text/plain",
                "go".getBytes(StandardCharsets.UTF_8));
        swarm.tasks().submit("gen", 3, 1, env);
        return swarm.tasks().claim("gen", "w", 1, 5_000).get(0);
    }

    private static void awaitDrained(Swarm swarm) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((swarm.replies().lateReplies() == 0 || swarm.replies().deliveredLocalSize() > 0)
                && System.nanoTime() < until) {
            Thread.sleep(5);
        }
    }

    /** Reproduces the S2c bug: the final LAST reply of an unregistered streaming call is counted late. */
    @Test
    void anUnregisteredStreamResultIsCountedLate() throws Exception {
        try (var swarm = Swarm.open(this.dir, "unreg", 7)) {
            // a unary call starts the reply poller (as on a node serving calls and streams)
            var c = swarm.replies().call("gen", "text/plain", new byte[] {1}, System.currentTimeMillis() + 10_000, "");
            var u = swarm.tasks().claim("gen", "w", 1, 5_000).get(0);
            swarm.tasks().reply(u, 5, 100, "text/plain", new byte[] {2}).get(5, TimeUnit.SECONDS);
            c.get(5, TimeUnit.SECONDS);
            var t = submitStreamLike(swarm, ReplyRouter.newId(), System.currentTimeMillis() + 10_000);
            assertEquals(LeaseResult.OK, swarm.tasks().reply(t, 5, 1, "text/plain", new byte[] {9}).get(5, TimeUnit.SECONDS));
            awaitDrained(swarm);
            assertEquals(1, swarm.replies().lateReplies());
        }
    }

    /** Fix: a registered stream's final reply is acked without counting late, with no pending entry held. */
    @Test
    void aRegisteredStreamResultIsNotLate() throws Exception {
        try (var swarm = Swarm.open(this.dir, "registered", 7)) {
            var c = swarm.replies().call("gen", "text/plain", new byte[] {1}, System.currentTimeMillis() + 10_000, "");
            var u = swarm.tasks().claim("gen", "w", 1, 5_000).get(0);
            swarm.tasks().reply(u, 5, 100, "text/plain", new byte[] {2}).get(5, TimeUnit.SECONDS);
            c.get(5, TimeUnit.SECONDS);
            var id = ReplyRouter.newId();
            swarm.replies().registerStream(id, System.currentTimeMillis() + 60_000);
            assertEquals(0, swarm.replies().pending(), "no pending future for a stream");
            assertEquals(1, swarm.replies().streams());
            assertThrows(IllegalArgumentException.class, () -> swarm.replies().registerStream(new byte[8], 0));
            var t = submitStreamLike(swarm, id, System.currentTimeMillis() + 10_000);
            assertEquals(LeaseResult.OK, swarm.tasks().reply(t, 5, 1, "text/plain", new byte[] {9}).get(5, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().streams() > 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(0, swarm.replies().streams(), "cleared when the final reply is read");
            Thread.sleep(100);
            assertEquals(0, swarm.replies().lateReplies());
        }
    }

    /** registerStream starts the poller: the final reply is read even when no unary call ever ran. */
    @Test
    void registeringAStreamStartsThePoller() throws Exception {
        try (var swarm = Swarm.open(this.dir, "nocall", 7)) {
            var id = ReplyRouter.newId();
            swarm.replies().registerStream(id, System.currentTimeMillis() + 60_000);
            var env = new TaskEnvelope(id, id, swarm.replies().topic(), 0, "00-tp", "", "b1", "text/plain", new byte[] {1});
            // straight to the reply topic, as from a worker on another node
            swarm.topics().partition(swarm.replies().topic()).produce(99, 1, env.reply("text/plain", new byte[] {7}).encode(),
                    sjws_nettask.messaging.ledger.Durability.ASYNC);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().streams() > 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(0, swarm.replies().streams());
            assertEquals(0, swarm.replies().lateReplies());
        }
    }

    /** A cleared stream (session expired) no longer suppresses the late count. */
    @Test
    void aClearedStreamCountsLateAgainAndCloseRefusesRegistration() throws Exception {
        try (var swarm = Swarm.open(this.dir, "cleared", 7)) {
            var id = ReplyRouter.newId();
            swarm.replies().registerStream(id, System.currentTimeMillis() + 60_000);
            swarm.replies().clearStream(id);
            assertEquals(0, swarm.replies().streams());
            var env = new TaskEnvelope(id, id, swarm.replies().topic(), 0, "00-tp", "", "b1", "text/plain", new byte[] {1});
            swarm.topics().partition(swarm.replies().topic()).produce(99, 1, env.reply("text/plain", new byte[] {7}).encode(),
                    sjws_nettask.messaging.ledger.Durability.ASYNC);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().lateReplies() == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(1, swarm.replies().lateReplies());
            swarm.replies().close();
            assertThrows(IllegalStateException.class, () -> swarm.replies().registerStream(ReplyRouter.newId(), 0));
        }
    }

    private Swarm openIdle(String id, long idleCloseMs) {
        return Swarm.open(this.dir, id, 7, new RetryPolicy(5, 0, 0), System::currentTimeMillis, idleCloseMs);
    }

    private static boolean awaitPolling(Swarm swarm, boolean want, long ms) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (swarm.replies().polling() != want && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        return swarm.replies().polling() == want;
    }

    /** #232 review: a registered stream keeps the poller past idleCloseMs; after clearStream it idle-closes. */
    @Test
    void aRegisteredStreamKeepsThePollerAliveUntilCleared() throws Exception {
        try (var swarm = openIdle("idle-clear", 150)) {
            var id = ReplyRouter.newId();
            assertTrue(swarm.replies().registerStream(id, System.currentTimeMillis() + 60_000));
            assertTrue(swarm.replies().polling());
            Thread.sleep(600); // four idle periods
            assertTrue(swarm.replies().polling(), "a registered stream blocks idle close");
            swarm.replies().clearStream(id);
            assertTrue(awaitPolling(swarm, false, 3_000), "idle-closes once the stream is cleared");
        }
    }

    /** #232 review: a key nobody clears expires at its deadline (self-healing) and the poller then idle-closes. */
    @Test
    void anUnclearedStreamExpiresAndThePollerIdleCloses() throws Exception {
        try (var swarm = openIdle("idle-expire", 150)) {
            var id = ReplyRouter.newId();
            swarm.replies().registerStream(id, System.currentTimeMillis() + 300);
            Thread.sleep(200);
            assertTrue(swarm.replies().polling());
            assertEquals(1, swarm.replies().streams());
            assertTrue(awaitPolling(swarm, false, 3_000), "idle-closes after the key expires");
            assertEquals(0, swarm.replies().streams());
            assertEquals(1, swarm.replies().streamsExpired());
            // a reply for the expired stream is late again
            swarm.replies().registerStream(ReplyRouter.newId(), System.currentTimeMillis() + 60_000); // restart poller
            var env = new TaskEnvelope(id, id, swarm.replies().topic(), 0, "00-tp", "", "b1", "text/plain", new byte[] {1});
            swarm.topics().partition(swarm.replies().topic()).produce(99, 1, env.reply("text/plain", new byte[] {7}).encode(),
                    sjws_nettask.messaging.ledger.Durability.ASYNC);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (swarm.replies().lateReplies() == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(1, swarm.replies().lateReplies());
        }
    }

    @Test
    void sweepDropsOnlyExpiredKeys() throws Exception {
        try (var swarm = Swarm.open(this.dir, "sweep", 7)) {
            var r = swarm.replies();
            var a = ReplyRouter.newId();
            var b = ReplyRouter.newId();
            r.registerStream(a, 1_000);
            r.registerStream(b, 5_000);
            r.sweepStreams(999);
            assertEquals(2, r.streams());
            r.sweepStreams(1_000);
            assertEquals(1, r.streams());
            assertEquals(1, r.streamsExpired());
            r.registerStream(b, 9_000); // re-register refreshes the deadline
            r.sweepStreams(6_000);
            assertEquals(1, r.streams());
        }
    }

    /** #232 review: registrations past the cap are refused and counted; close() clears the set. */
    @Test
    void streamRegistrationIsCappedAndClearedOnClose() throws Exception {
        String old = System.getProperty(ReplyRouter.MAX_STREAMS_PROPERTY);
        System.setProperty(ReplyRouter.MAX_STREAMS_PROPERTY, "2");
        try (var swarm = Swarm.open(this.dir, "cap", 7)) {
            var r = swarm.replies();
            long dl = System.currentTimeMillis() + 60_000;
            var a = ReplyRouter.newId();
            assertTrue(r.registerStream(a, dl));
            assertTrue(r.registerStream(ReplyRouter.newId(), dl));
            assertFalse(r.registerStream(ReplyRouter.newId(), dl), "third key refused");
            assertEquals(1, r.streamRejections());
            assertTrue(r.registerStream(a, dl), "re-registering a held key is not a new key");
            assertEquals(2, r.streams());
            r.close();
            assertEquals(0, r.streams(), "close clears the stream keys");
            assertFalse(r.polling());
        } finally {
            if (old == null) System.clearProperty(ReplyRouter.MAX_STREAMS_PROPERTY);
            else System.setProperty(ReplyRouter.MAX_STREAMS_PROPERTY, old);
        }
        System.setProperty(ReplyRouter.MAX_STREAMS_PROPERTY, "-1");
        try {
            assertThrows(IllegalArgumentException.class, () -> Swarm.open(this.dir, "bad", 7));
        } finally {
            if (old == null) System.clearProperty(ReplyRouter.MAX_STREAMS_PROPERTY);
            else System.setProperty(ReplyRouter.MAX_STREAMS_PROPERTY, old);
        }
    }
}
