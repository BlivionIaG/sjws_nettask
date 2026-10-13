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
    void aSubmitFailureFailsTheCall() throws Exception {
        try (var swarm = Swarm.open(this.dir, "f", 1)) {
            var f = swarm.replies().call("bad/pool", "", new byte[0], System.currentTimeMillis() + 60_000, "");
            var e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
    }
}
