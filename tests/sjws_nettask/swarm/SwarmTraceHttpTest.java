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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.swarm.trace.SwarmTrace;
import sjws_nettask.swarm.wire.Frame;

/** S2b: W3C traceparent ingress, a child span per claim, propagation on reply (swarm §9). */
class SwarmTraceHttpTest {

    static final String TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @TempDir
    Path dir;
    Swarm swarm;

    @AfterEach
    void close() {
        if (this.swarm != null) {
            this.swarm.close();
        }
    }

    SwarmHttp open(int shards, boolean trace) {
        var old = System.getProperty("sjws.swarm.pool.shards");
        System.setProperty("sjws.swarm.pool.shards", Integer.toString(shards));
        try {
            this.swarm = Swarm.open(this.dir, "trace-test", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                    System::currentTimeMillis, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        } finally {
            if (old == null) {
                System.clearProperty("sjws.swarm.pool.shards");
            } else {
                System.setProperty("sjws.swarm.pool.shards", old);
            }
        }
        assertEquals(shards, this.swarm.tasks().shards());
        return new SwarmHttp(this.swarm, 4, SwarmHttpTest.ALLOW_ALL, trace);
    }

    static Response post(SwarmHttp http, String target, String traceparent, byte[] data) {
        var h = traceparent == null ? RequestHeaders.NONE : RequestHeaders.of(Map.of("traceparent", List.of(traceparent)));
        return http.handle(new Request("POST", target, h, null, false, null, SwarmHttpTest.body(data)));
    }

    static byte[] task(String s) {
        return new Frame(Frame.Type.TASK, 0, s.getBytes(StandardCharsets.UTF_8)).encode();
    }

    static byte[] result(String s) {
        return new Frame(Frame.Type.RESULT, Frame.LAST, s.getBytes(StandardCharsets.UTF_8)).encode();
    }

    /** Claims one task, waiting; returns {lease id, child traceparent}. */
    static String[] claim(SwarmHttp http, String pool) {
        var r = post(http, "/swarm/v1/tasks/" + pool + ":claim?waitMs=5000", null, new byte[0]);
        assertEquals(200, r.status());
        var leases = SwarmHttpTest.leases(r);
        assertEquals(1, leases.size());
        return new String[] {leases.get(0).id(), r.headers().get(SwarmTrace.LEASE_HEADER)};
    }

    @Test
    void submitCarriesTraceparentAcrossShardsAndEachClaimGetsAChildSpan() {
        var http = open(4, true);
        for (int i = 0; i < 8; i++) {
            assertEquals(202, post(http, "/swarm/v1/tasks/p", TP, task("t" + i)).status());
        }
        assertEquals(202, post(http, "/swarm/v1/tasks/p", "bogus", task("untraced")).status());
        // claim until all 9 are leased (a claim may fill from one shard only)
        var spans = new java.util.ArrayList<String>();
        for (int tries = 0; spans.size() < 9 && tries < 50; tries++) {
            var r = post(http, "/swarm/v1/tasks/p:claim?max=16&waitMs=1000", null, new byte[0]);
            assertEquals(200, r.status());
            var got = r.headers().get(SwarmTrace.LEASE_HEADER).split(",");
            assertEquals(SwarmHttpTest.leases(r).size(), got.length, "one span entry per LEASE frame");
            spans.addAll(List.of(got));
        }
        assertEquals(9, spans.size());
        int untraced = 0;
        var seen = new java.util.HashSet<String>();
        for (var s : spans) {
            if ("-".equals(s)) {
                untraced++;
                continue;
            }
            assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(s));
            assertNotEquals(SwarmTrace.spanId(TP), SwarmTrace.spanId(s));
            assertTrue(seen.add(s));
        }
        assertEquals(1, untraced, "an invalid header is dropped, not forwarded");
        // the envelope in the shard log holds the caller's traceparent, unchanged
        var t = this.swarm.tasks().claim("p", "x", 1, 1000, 0);
        assertTrue(t.isEmpty());
    }

    @Test
    void threeHopTraceContinuity() throws Exception {
        var http = open(4, true);
        // hop 1: client -> agent A
        var a = CompletableFuture.supplyAsync(() -> post(http, "/swarm/v1/tasks/a:call?deadlineMs=20000", TP, task("q")));
        var la = claim(http, "a");
        var s1 = la[1];
        assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(s1));
        // hop 2: agent A -> agent B, propagating its claim span
        var b = CompletableFuture.supplyAsync(() -> post(http, "/swarm/v1/tasks/b:call?deadlineMs=20000", s1, task("q2")));
        var lb = claim(http, "b");
        var s2 = lb[1];
        // hop 3: agent B replies to A, then A replies to the client
        assertEquals(204, post(http, "/swarm/v1/leases/" + lb[0] + ":reply", null, result("rb")).status());
        var rb = b.get(10, TimeUnit.SECONDS);
        assertEquals(200, rb.status());
        assertEquals(s2, rb.headers().get("traceparent"), "the reply carries B's claim span");
        assertEquals(204, post(http, "/swarm/v1/leases/" + la[0] + ":reply", null, result("ra")).status());
        var ra = a.get(10, TimeUnit.SECONDS);
        assertEquals(200, ra.status());
        assertEquals(s1, ra.headers().get("traceparent"), "the reply carries A's claim span");
        var spans = new java.util.HashSet<>(List.of(SwarmTrace.spanId(TP), SwarmTrace.spanId(s1), SwarmTrace.spanId(s2)));
        assertEquals(3, spans.size(), "one distinct span per hop");
        for (var s : List.of(s1, s2)) {
            assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(s), "one trace id across all hops");
        }
    }

    @Test
    void untracedCallGetsNoTraceHeaders() throws Exception {
        var http = open(1, true);
        var a = CompletableFuture.supplyAsync(() -> post(http, "/swarm/v1/tasks/a:call?deadlineMs=20000", null, task("q")));
        var la = claim(http, "a");
        assertEquals("-", la[1]);
        assertEquals(204, post(http, "/swarm/v1/leases/" + la[0] + ":reply", null, result("r")).status());
        var ra = a.get(10, TimeUnit.SECONDS);
        assertEquals(200, ra.status());
        assertNull(ra.headers().get("traceparent"));
    }

    @Test
    void offIgnoresTheHeaderAndAddsNothing() throws Exception {
        var http = open(1, false);
        var a = CompletableFuture.supplyAsync(() -> post(http, "/swarm/v1/tasks/a:call?deadlineMs=20000", TP, task("q")));
        var r = post(http, "/swarm/v1/tasks/a:claim?waitMs=5000", null, new byte[0]);
        assertEquals(200, r.status());
        assertNull(r.headers().get(SwarmTrace.LEASE_HEADER));
        var id = SwarmHttpTest.leases(r).get(0).id();
        assertEquals(204, post(http, "/swarm/v1/leases/" + id + ":reply", null, result("r")).status());
        var ra = a.get(10, TimeUnit.SECONDS);
        assertNull(ra.headers().get("traceparent"));
        assertEquals(202, post(http, "/swarm/v1/tasks/p", TP, task("x")).status());
        var t = this.swarm.tasks().claim("p", "x", 1, 1000, TimeUnit.SECONDS.toNanos(5));
        assertEquals("", t.get(0).envelope().traceparent(), "tracing off: the envelope carries nothing");
    }

    static final String BAD = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7\r\n1"; // 55 chars, invalid

    static TaskEnvelope env(String traceparent, String body) {
        var id = ReplyRouter.newId();
        return new TaskEnvelope(id, id, "", TaskEnvelope.NO_DEADLINE, traceparent, "", "", "",
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidStoredTraceparentNeverBreaksAClaim() {
        var http = open(1, true);
        assertEquals(55, BAD.length());
        // in-process submit: bypasses HTTP ingress validation
        this.swarm.tasks().submit("p", 0, 0, env(BAD, "bad"));
        this.swarm.tasks().submit("p", 0, 0, env(TP.toUpperCase(), "upper"));
        var r = post(http, "/swarm/v1/tasks/p:claim?max=2&waitMs=2000", null, new byte[0]);
        assertEquals(200, r.status());
        assertEquals(2, SwarmHttpTest.leases(r).size(), "every lease is delivered");
        assertEquals("-,-", r.headers().get(SwarmTrace.LEASE_HEADER));
    }

    @Test
    void invalidStoredReplyTraceparentIsNotEchoed() throws Exception {
        var http = open(1, true);
        var a = CompletableFuture.supplyAsync(() -> post(http, "/swarm/v1/tasks/a:call?deadlineMs=20000", null, task("q")));
        var t = this.swarm.tasks().claim("a", "w", 1, 30_000, TimeUnit.SECONDS.toNanos(5)).get(0);
        // an in-process worker replies with an envelope carrying an invalid traceparent
        var forged = new Task(t.pool(), t.offset(), t.attempt(), t.leaseDeadlineMs(), t.envelope().withTraceparent(BAD),
                t.shard());
        assertTrue(this.swarm.tasks().reply(forged, 9, 1, "", "r".getBytes(StandardCharsets.UTF_8))
                .get(10, TimeUnit.SECONDS).ok());
        var ra = a.get(10, TimeUnit.SECONDS);
        assertEquals(200, ra.status());
        assertNull(ra.headers().get("traceparent"));
    }

    @Test
    void reclaimAfterExpiryGetsASiblingSpan() throws Exception {
        var http = open(1, true);
        assertEquals(202, post(http, "/swarm/v1/tasks/p", TP, task("x")).status());
        var first = post(http, "/swarm/v1/tasks/p:claim?visibilityMs=50&waitMs=2000", null, new byte[0]);
        var c1 = first.headers().get(SwarmTrace.LEASE_HEADER);
        Thread.sleep(200);
        var second = post(http, "/swarm/v1/tasks/p:claim?visibilityMs=30000&waitMs=5000", null, new byte[0]);
        assertEquals(200, second.status());
        var c2 = second.headers().get(SwarmTrace.LEASE_HEADER);
        assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(c1));
        assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(c2));
        assertNotEquals(SwarmTrace.spanId(c1), SwarmTrace.spanId(c2), "a fresh child per claim");
        assertNotEquals(SwarmTrace.spanId(TP), SwarmTrace.spanId(c2));
        // the ledger keeps the caller's parent, so the re-claim is a sibling, not a grandchild
        var stored = this.swarm.tasks().claim("p", "w", 1, 1, 0);
        assertTrue(stored.isEmpty());
        assertEquals(TP.substring(52), c2.substring(52), "flags kept");
    }

    @Test
    void multipleTraceparentHeadersAreDropped() {
        var http = open(1, true);
        var h = RequestHeaders.of(Map.of("traceparent", List.of(TP, "00-11111111111111111111111111111111-2222222222222222-01")));
        assertEquals(202, http.handle(new Request("POST", "/swarm/v1/tasks/p", h, null, false, null,
                SwarmHttpTest.body(task("x")))).status());
        var r = post(http, "/swarm/v1/tasks/p:claim?waitMs=2000", null, new byte[0]);
        assertEquals("-", r.headers().get(SwarmTrace.LEASE_HEADER), "several traceparent fields restart the trace");
    }

    @Test
    void mixedBatchKeepsLeaseOrder() {
        var http = open(1, true);
        assertEquals(202, post(http, "/swarm/v1/tasks/p", TP, task("t0")).status());
        assertEquals(202, post(http, "/swarm/v1/tasks/p", null, task("u1")).status());
        assertEquals(202, post(http, "/swarm/v1/tasks/p", TP, task("t2")).status());
        assertEquals(202, post(http, "/swarm/v1/tasks/p", null, task("u3")).status());
        var r = post(http, "/swarm/v1/tasks/p:claim?max=4&waitMs=2000", null, new byte[0]);
        var leases = SwarmHttpTest.leases(r);
        var spans = r.headers().get(SwarmTrace.LEASE_HEADER).split(",");
        assertEquals(4, leases.size());
        assertEquals(4, spans.length);
        for (int i = 0; i < 4; i++) {
            var body = new String(leases.get(i).body(), StandardCharsets.UTF_8);
            if (body.startsWith("t")) {
                assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(spans[i]), body);
            } else {
                assertEquals("-", spans[i], body);
            }
        }
    }

    @Test
    void moduleTraceFlag() {
        open(1, false);
        var on = new SwarmModule(this.swarm, SwarmHttpTest.ALLOW_ALL, true);
        assertTrue(on.trace());
        var off = new SwarmModule(this.swarm, SwarmHttpTest.ALLOW_ALL, false);
        assertEquals(false, off.trace());
    }
}
