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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.swarm.wire.Frame;

class SwarmHttpTest {

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "http-test", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                System::currentTimeMillis, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        this.http = new SwarmHttp(this.swarm, 4, ALLOW_ALL);
    }

    /** Lets every request through: these tests cover the endpoints; SwarmGateTest covers admission. */
    static final SwarmGate ALLOW_ALL = new SwarmGate() {
        @Override
        public Agent authenticate(Request request) {
            return new Agent("test");
        }

        @Override
        public boolean allows(Agent agent, String pool) {
            return true;
        }

        @Override
        public long maxRequestBytes() {
            return SwarmHttp.MAX_REQUEST;
        }
    };

    @AfterEach
    void close() {
        this.swarm.close();
    }

    static RequestBody body(byte[] data) {
        return new RequestBody() {
            int pos;

            @Override
            public long length() {
                return data.length;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                if (this.pos >= data.length) {
                    return -1;
                }
                int n = Math.min(len, data.length - this.pos);
                System.arraycopy(data, this.pos, into, off, n);
                this.pos += n;
                return n;
            }
        };
    }

    Response post(String target, byte[] data) {
        return this.http.handle(new Request("POST", target, RequestHeaders.NONE, null, false, null, body(data)));
    }

    Response post(String target, Frame f) {
        return post(target, f.encode());
    }

    static byte[] bytes(Response r) {
        if (r.body() instanceof Body.Bytes b) {
            var d = b.data().duplicate();
            var out = new byte[d.remaining()];
            d.get(out);
            return out;
        }
        return new byte[0];
    }

    static String text(Response r) {
        return new String(bytes(r), StandardCharsets.UTF_8);
    }

    static Frame task(String s) {
        return new Frame(Frame.Type.TASK, 0, s.getBytes(StandardCharsets.UTF_8));
    }

    record Lease(String id, long deadline, byte[] body) { }

    static List<Lease> leases(Response r) {
        var in = ByteBuffer.wrap(bytes(r));
        var out = new ArrayList<Lease>();
        Frame f;
        while ((f = Frame.decode(in)) != null) {
            assertEquals(Frame.Type.LEASE, f.type());
            var p = ByteBuffer.wrap(f.payload());
            var id = new byte[p.getShort()];
            p.get(id);
            long deadline = p.getLong();
            var b = new byte[p.remaining()];
            p.get(b);
            out.add(new Lease(new String(id, StandardCharsets.UTF_8), deadline, b));
        }
        return out;
    }

    @Test
    void submitClaimAndFencedSettles() {
        var r = post("/swarm/v1/tasks/sum", task("1+1"));
        assertEquals(202, r.status());
        assertEquals(SwarmHttp.CONTENT_TYPE, r.headers().get("content-type"));
        var ack = Frame.decode(ByteBuffer.wrap(bytes(r)));
        assertEquals(Frame.Type.TASK, ack.type());
        assertEquals(16, ack.payload().length);
        post("/swarm/v1/tasks/sum?deadlineMs=60000", task("2+2"));
        var claimed = post("/swarm/v1/tasks/sum:claim?max=5&visibilityMs=10000", new byte[0]);
        assertEquals(200, claimed.status());
        var ls = leases(claimed);
        assertEquals(2, ls.size());
        assertEquals("1+1", new String(ls.get(0).body(), StandardCharsets.UTF_8));
        assertTrue(ls.get(0).id().startsWith("sum~"), ls.get(0).id());
        assertEquals(204, post("/swarm/v1/leases/" + ls.get(0).id() + ":extend?visibilityMs=20000", new byte[0]).status());
        assertEquals(204, post("/swarm/v1/leases/" + ls.get(0).id() + ":ack", new byte[0]).status());
        var again = post("/swarm/v1/leases/" + ls.get(0).id() + ":ack", new byte[0]);
        assertEquals(409, again.status());
        assertEquals("application/problem+json", again.headers().get("content-type"));
        assertTrue(text(again).contains("NOT_IN_FLIGHT"), text(again));
        assertEquals(204, post("/swarm/v1/leases/" + ls.get(1).id() + ":nack?delayMs=0", new byte[0]).status());
        var redelivered = leases(post("/swarm/v1/tasks/sum:claim", new byte[0]));
        assertEquals(1, redelivered.size());
        assertTrue(redelivered.get(0).id().endsWith("~2"), "attempt 2: " + redelivered.get(0).id());
        // The old attempt is fenced off.
        var stale = post("/swarm/v1/leases/" + ls.get(1).id() + ":reject?reason=x", new byte[0]);
        assertEquals(409, stale.status());
        assertTrue(text(stale).contains("STALE_ATTEMPT"), text(stale));
        assertEquals(204, post("/swarm/v1/leases/" + redelivered.get(0).id() + ":reject?reason=bad%20input", new byte[0]).status());
        assertEquals(204, post("/swarm/v1/tasks/sum:claim", new byte[0]).status());
        var dlq = this.swarm.tasks().deadLetters("sum");
        dlq.subscribe("a", true);
        assertEquals("rejected:bad input", dlq.poll("a", "a", 1, 1000).get(0).originReason());
        assertEquals(0, this.http.claimedCount());
    }

    @Test
    void callWaitsForTheReplyFromAnHttpWorker() throws Exception {
        var call = CompletableFuture.supplyAsync(() -> post("/swarm/v1/tasks/echo:call?deadlineMs=10000", task("ping")));
        var ls = leases(post("/swarm/v1/tasks/echo:claim?waitMs=5000", new byte[0]));
        assertEquals(1, ls.size());
        var reply = post("/swarm/v1/leases/" + ls.get(0).id() + ":reply",
                new Frame(Frame.Type.RESULT, Frame.LAST, "pong".getBytes(StandardCharsets.UTF_8)));
        assertEquals(204, reply.status(), text(reply));
        var r = call.get(10, TimeUnit.SECONDS);
        assertEquals(200, r.status(), text(r));
        var f = Frame.decode(ByteBuffer.wrap(bytes(r)));
        assertEquals(Frame.Type.RESULT, f.type());
        assertArrayEquals("pong".getBytes(StandardCharsets.UTF_8), f.payload());
        // Replying again: the lease is gone.
        assertEquals(409, post("/swarm/v1/leases/" + ls.get(0).id() + ":reply",
                new Frame(Frame.Type.RESULT, 0, new byte[0])).status());
    }

    @Test
    void callTimesOutWith504AndSubmitHasNoReplyTopic() {
        var r = post("/swarm/v1/tasks/none:call?deadlineMs=50", task("x"));
        assertEquals(504, r.status());
        assertTrue(text(r).contains("\"status\":504"), text(r));
        post("/swarm/v1/tasks/fire", task("y"));
        var ls = leases(post("/swarm/v1/tasks/fire:claim", new byte[0]));
        var reply = post("/swarm/v1/leases/" + ls.get(0).id() + ":reply", new Frame(Frame.Type.RESULT, 0, new byte[0]));
        assertEquals(409, reply.status());
        // An unknown lease id (never claimed here) cannot be replied to.
        assertEquals(409, post("/swarm/v1/leases/x~1~1:reply", new Frame(Frame.Type.RESULT, 0, new byte[0])).status());
    }

    @Test
    void badRequests() {
        assertEquals(405, this.http.handle(new Request("GET", "/swarm/v1/tasks/a")).status());
        assertEquals("POST", this.http.handle(new Request("GET", "/swarm/v1/tasks/a")).headers().get("allow"));
        assertEquals(404, post("/swarm/v1/nope", new byte[0]).status());
        assertEquals(404, post("/other", new byte[0]).status());
        assertEquals(404, post("/swarm/v1/tasks/a:frob", task("x")).status());
        assertEquals(404, post("/swarm/v1/leases/a~1~1:frob", new byte[0]).status());
        assertEquals(400, post("/swarm/v1/tasks/a", new byte[] {1, 1}).status(), "partial frame");
        assertEquals(400, post("/swarm/v1/tasks/a", new Frame(Frame.Type.CLAIM, 0, new byte[0])).status());
        var two = new byte[12];
        System.arraycopy(task("").encode(), 0, two, 0, 6);
        System.arraycopy(task("").encode(), 0, two, 6, 6);
        assertEquals(400, post("/swarm/v1/tasks/a", two).status(), "two frames");
        assertEquals(400, post("/swarm/v1/tasks/a", new byte[] {9, 1, 0, 0, 0, 0}).status(), "bad version");
        assertEquals(400, post("/swarm/v1/tasks/a:claim?max=0", new byte[0]).status());
        assertEquals(400, post("/swarm/v1/tasks/a:claim?max=x", new byte[0]).status());
        assertEquals(400, post("/swarm/v1/tasks/a?deadlineMs=0", task("x")).status());
        assertEquals(400, post("/swarm/v1/leases/nope:ack", new byte[0]).status());
        assertEquals(400, post("/swarm/v1/leases/a~x~1:ack", new byte[0]).status());
        assertEquals(400, post("/swarm/v1/leases/~1:ack", new byte[0]).status());
        assertEquals(400, post("/swarm/v1/leases/a~1~1:reply", task("x")).status(), "reply needs RESULT");
        assertEquals(400, post("/swarm/v1/tasks/bad%2Fpool", task("x")).status(), "invalid topic name");
        var big = new byte[SwarmHttp.MAX_REQUEST + 1];
        assertEquals(413, post("/swarm/v1/tasks/a", big).status());
        var unknownLength = new RequestBody() {
            int left = SwarmHttp.MAX_REQUEST + 10;

            @Override
            public long length() {
                return -1;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                if (this.left <= 0) {
                    return -1;
                }
                int n = Math.min(len, this.left);
                this.left -= n;
                return n;
            }
        };
        assertEquals(413, this.http.handle(new Request("POST", "/swarm/v1/tasks/a", RequestHeaders.NONE, null, false,
                null, unknownLength)).status());
        var failing = new RequestBody() {
            @Override
            public long length() {
                return 10;
            }

            @Override
            public int read(byte[] into, int off, int len) throws IOException {
                throw new IOException("reset");
            }
        };
        var r = this.http.handle(new Request("POST", "/swarm/v1/tasks/a", RequestHeaders.NONE, null, false, null, failing));
        assertEquals(400, r.status());
        assertTrue(text(r).contains("reset"));
    }

    @Test
    void helpers() throws Exception {
        assertEquals("\"a\\\"b\\\\c\\u0001\"", SwarmHttp.quote("a\"b\\c\u0001"));
        var q = SwarmHttp.query("a=1&&b&c=%20x");
        assertEquals("1", q.get("a"));
        assertEquals("", q.get("b"));
        assertEquals(" x", q.get("c"));
        assertThrows(SwarmHttp.BadRequest.class, () -> SwarmHttp.number(java.util.Map.of("n", "5"), "n", 0, 0, 4));
        var t = SwarmHttp.parseLease("my~pool~7~2");
        assertEquals("my~pool", t.pool());
        assertEquals(7, t.offset());
        assertEquals(2, t.attempt());
        assertEquals("p~7~2", SwarmHttp.leaseId(new Task("p", 7, 2, 0, null)));
    }

    @Test
    void anExpiredHttpClaimIsSwept() throws Exception {
        post("/swarm/v1/tasks/s", task("x"));
        leases(post("/swarm/v1/tasks/s:claim?visibilityMs=1", new byte[0]));
        assertEquals(1, this.http.claimedCount());
        Thread.sleep(5);
        post("/swarm/v1/tasks/s:claim", new byte[0]);
        assertTrue(this.http.claimedCount() <= 1);
    }

    /** An extend answered CANCELLED did not move the lease, so the cached claim keeps its deadline. */
    @Test
    void extendCancelledKeepsTheCachedClaimDeadline() {
        var held = new Task("p", 7, 2, 1_000, null, 1);
        assertSame(held, SwarmHttp.afterExtend(held, sjws_nettask.messaging.delivery.LeaseResult.CANCELLED, 99_000));
        assertEquals(1_000, SwarmHttp.afterExtend(held, sjws_nettask.messaging.delivery.LeaseResult.CANCELLED, 99_000).leaseDeadlineMs());
        assertSame(held, SwarmHttp.afterExtend(held, sjws_nettask.messaging.delivery.LeaseResult.STALE_ATTEMPT, 99_000));
        var moved = SwarmHttp.afterExtend(held, sjws_nettask.messaging.delivery.LeaseResult.OK, 99_000);
        assertEquals(99_000, moved.leaseDeadlineMs());
        assertEquals(1, moved.shard());
    }

    /** #224: CANCELLED (extend only) is a 409 problem+json with its own type; the other refusals keep theirs. */
    @Test
    void cancelledLeaseMapsTo409WithACancelledProblemType() {
        var r = SwarmHttp.leaseProblem(sjws_nettask.messaging.delivery.LeaseResult.CANCELLED).response();
        assertEquals(409, r.status());
        assertEquals("application/problem+json", r.headers().get("content-type"));
        var json = new String(bytes(r), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"type\":\"" + SwarmHttp.CANCELLED_TYPE + "\""), json);
        assertTrue(SwarmHttp.CANCELLED_TYPE.endsWith("/cancelled"));
        for (var other : java.util.List.of(sjws_nettask.messaging.delivery.LeaseResult.NOT_IN_FLIGHT,
                sjws_nettask.messaging.delivery.LeaseResult.STALE_ATTEMPT)) {
            var o = SwarmHttp.leaseProblem(other).response();
            assertEquals(409, o.status());
            var j = new String(bytes(o), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(j.contains("\"type\":\"about:blank\"") && j.contains(other.name()), j);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SwarmHttp.leaseProblem(sjws_nettask.messaging.delivery.LeaseResult.OK));
    }
}
