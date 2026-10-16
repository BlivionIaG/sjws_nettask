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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

/** S2c PR 5: cancel, the CancelProbe on :extend, attempts in chunks, RESET, and the periodic session sweep. */
class StreamCancelTest {

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;
    final AtomicLong now = new AtomicLong(1_000_000);

    /** Agent from {@code x-agent}; agent {@code root} is an admin. */
    static final SwarmGate GATE = new SwarmGate() {
        @Override
        public Agent authenticate(Request request) {
            var v = request.headers().values("x-agent");
            return new Agent(v.isEmpty() ? "a" : v.get(0));
        }

        @Override
        public boolean allows(Agent agent, String pool) {
            return true;
        }

        @Override
        public boolean isAdmin(Agent agent) {
            return "root".equals(agent.id());
        }

        @Override
        public long maxRequestBytes() {
            return SwarmHttp.MAX_REQUEST;
        }
    };

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "cancel", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        this.http = new SwarmHttp(this.swarm, 4, GATE);
    }

    @AfterEach
    void close() {
        this.swarm.close();
    }

    Response req(String method, String agent, String target, byte[] body, String... headers) {
        var fields = new java.util.HashMap<String, List<String>>();
        fields.put("x-agent", List.of(agent));
        for (int i = 0; i < headers.length; i += 2) {
            fields.put(headers[i], List.of(headers[i + 1]));
        }
        return this.http.handle(new Request(method, target, RequestHeaders.of(fields), null, false, null,
                SwarmHttpTest.body(body)));
    }

    Response post(String agent, String target) {
        return req("POST", agent, target, new byte[0]);
    }

    String start(String query) {
        var r = req("POST", "a", "/swarm/v1/tasks/gen:stream" + query, SwarmHttpTest.task("prompt").encode());
        assertEquals(201, r.status(), SwarmHttpTest.text(r));
        return r.headers().get("location");
    }

    static String key(String session) {
        return session.substring("/swarm/v1/replies/".length());
    }

    record Claimed(String lease, long hi, long lo) { }

    Claimed claim() {
        var ls = SwarmHttpTest.leases(post("w", "/swarm/v1/tasks/gen:claim?visibilityMs=60000"));
        assertEquals(1, ls.size());
        var corr = held(ls.get(0).id()).envelope().correlationId();
        var b = ByteBuffer.wrap(corr);
        return new Claimed(ls.get(0).id(), b.getLong(), b.getLong());
    }

    Task held(String lease) {
        try {
            var f = SwarmHttp.class.getDeclaredField("claimed");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var m = (Map<String, Task>) f.get(this.http);
            return m.get(lease);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    Response chunk(Claimed c, long attempt, long seq, int flags, String text) {
        var sc = new StreamChunk(c.hi(), c.lo(), attempt, seq, flags, text.getBytes(StandardCharsets.UTF_8));
        return post0(c, sc);
    }

    Response post0(Claimed c, StreamChunk sc) {
        return req("POST", "w", "/swarm/v1/leases/" + c.lease() + ":chunk", sc.toFrame().encode());
    }

    @Test
    void submitterCancelsIdempotentlyOthersGet403AndTheWorkerLearnsOnItsNextChunk() throws Exception {
        var session = start("");
        var c = claim();
        assertEquals(204, chunk(c, 0, 0, StreamChunk.FIRST, "a").status());
        assertEquals(1, this.swarm.replies().streams());
        assertEquals(403, post("b", "/swarm/v1/tasks/" + key(session) + ":cancel").status(), "not the submitter");
        assertEquals(404, post("a", "/swarm/v1/tasks/00112233445566778899aabbccddeeff:cancel").status());
        var r = post("a", "/swarm/v1/tasks/" + key(session) + ":cancel");
        assertEquals(200, r.status());
        assertEquals("{\"state\":\"cancelled\"}", SwarmHttpTest.text(r));
        assertEquals(0, this.swarm.replies().streams(), "cancel left through exit(): clearStream ran");
        var again = post("a", "/swarm/v1/tasks/" + key(session) + ":cancel");
        assertEquals(200, again.status(), "idempotent");
        assertEquals("{\"state\":\"cancelled\"}", SwarmHttpTest.text(again));
        assertEquals(1, this.http.streams().cancels(), "the second cancel did nothing");
        // the worker: next chunk is 409 cancelled, the claim stays held, LAST|CANCELLED still ends and acks
        var refused = chunk(c, 0, 1, 0, "b");
        assertEquals(409, refused.status());
        var body = SwarmHttpTest.text(refused);
        assertTrue(body.contains("\"reason\":\"cancelled\""), body);
        assertTrue(body.contains(SwarmHttp.CANCELLED_TYPE), body);
        // the reader: what was produced, then the cancelled terminal
        var text = StreamHttpTest.drain(req("GET", "a", session, new byte[0]));
        assertTrue(text.contains("data: a\n") && text.endsWith("event: end\ndata: cancelled\n\n"), text);
        assertFalse(text.contains("data: b"), text);
        assertEquals(204, chunk(c, 0, 1, Frame.LAST | Frame.CANCELLED, "").status());
    }

    @Test
    void adminMayCancelAnotherAgentsStream() {
        var session = start("");
        claim();
        assertEquals(200, post("root", "/swarm/v1/tasks/" + key(session) + ":cancel").status());
    }

    @Test
    void cancelAfterTheEndReturnsTheTerminalState() throws Exception {
        var session = start("");
        var c = claim();
        assertEquals(204, chunk(c, 0, 0, Frame.LAST, "done").status());
        var r = post("a", "/swarm/v1/tasks/" + key(session) + ":cancel");
        assertEquals(200, r.status());
        assertEquals("{\"state\":\"ok\"}", SwarmHttpTest.text(r));
        assertEquals(0, this.http.streams().cancels());
    }

    @Test
    void extendReturnsCancelledThroughTheProbe() {
        var session = start("");
        var c = claim();
        var ok = post("w", "/swarm/v1/leases/" + c.lease() + ":extend?visibilityMs=1000");
        assertEquals(204, ok.status());
        post("a", "/swarm/v1/tasks/" + key(session) + ":cancel");
        // messaging itself answers CANCELLED: the probe is installed on the task partition
        var t = held(c.lease());
        assertEquals(LeaseResult.CANCELLED, this.swarm.tasks().extend(t.pool(), t.shard(), new long[] {t.offset()},
                new int[] {t.attempt()}, 1000).get(0));
        var r = post("w", "/swarm/v1/leases/" + c.lease() + ":extend?visibilityMs=1000");
        assertEquals(409, r.status());
        assertTrue(SwarmHttpTest.text(r).contains("\"reason\":\"cancelled\""));
        assertTrue(held(c.lease()) != null, "CANCELLED keeps the claim: the worker acks it");
        assertEquals(204, post("w", "/swarm/v1/leases/" + c.lease() + ":ack").status());
    }

    @Test
    void extendIsClampedToTheDeadlineAndPastItIsCancelledWithReasonDeadline() {
        start("?deadlineMs=5000");
        var c = claim();
        assertEquals(204, post("w", "/swarm/v1/leases/" + c.lease() + ":extend?visibilityMs=60000").status());
        assertTrue(held(c.lease()).leaseDeadlineMs() <= this.now.get() + 5000, "clamped to the task deadline");
        this.now.addAndGet(6_000);
        var t = held(c.lease());
        // the probe treats a past-deadline stream task as cancelled (no EXPIRED value)
        assertEquals("deadline", this.http.streams().reason(t));
        var r = post("w", "/swarm/v1/leases/" + c.lease() + ":extend?visibilityMs=1000");
        assertEquals(409, r.status());
        assertTrue(SwarmHttpTest.text(r).contains("\"reason\":\"deadline\""), SwarmHttpTest.text(r));
    }

    @Test
    void cancelRecordIsAnOrdinaryProduceOnTheTaskTopicAndIsNeverHandedOut() {
        var session = start("");
        var c = claim();
        var t = held(c.lease());
        var topic = TaskService.topic(t.pool(), t.shard());
        long before = this.swarm.topics().partition(topic).counts().groups().get(TaskService.WORKERS).counts().enqueued();
        post("a", "/swarm/v1/tasks/" + key(session) + ":cancel");
        long after = this.swarm.topics().partition(topic).counts().groups().get(TaskService.WORKERS).counts().enqueued();
        assertEquals(before + 1, after, "one opaque record on the task's own topic");
        // a claim applies and acks the record; nothing is handed out
        assertEquals(204, post("w", "/swarm/v1/tasks/gen:claim").status());
        // a remote cancel (record only, never tracked on this node: no track() call) is applied by the claim
        var cs = this.swarm.tasks().streams().cancels();
        assertNull(cs.reason(topic, 999, this.now.get()));
        int size = cs.size();
        this.swarm.topics().partition(topic).produce(0, 0, CancelSet.record(999, this.now.get() + 60_000, new byte[16]));
        assertEquals(204, post("w", "/swarm/v1/tasks/gen:claim").status());
        assertEquals("cancelled", cs.reason(topic, 999, this.now.get()), "created by apply");
        assertEquals(size + 1, cs.size());
        // it lives until the record's deadline + grace
        this.now.addAndGet(60_000 + CancelSet.GRACE_MS + 1);
        cs.sweep(this.now.get());
        assertNull(cs.reason(topic, 999, this.now.get()));
    }

    @Test
    void cancelTakesEffectOnAnotherNodeAfterATakeover() throws Exception {
        // node B shares the ledger; enabled last, so the task partition opens with B's probe (B serves the lease)
        var b = Swarm.sharing(this.swarm, "nodeb", 7, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        try {
            var bStreams = b.enableStreams(TokenStreams.Config.DEFAULT);
            var bHttp = new SwarmHttp(b, 8, GATE);
            java.util.function.BiFunction<String, byte[], Response> onB = (target, body) -> bHttp.handle(new Request("POST",
                    target, RequestHeaders.of(Map.of("x-agent", List.of("w"))), null, false, null, SwarmHttpTest.body(body)));
            var session = start("");
            // attempt 1 on node A, lease lost; node B takes it over with a higher attempt (ledger delivery count)
            var c1 = claim();
            long a1 = held(c1.lease()).attempt();
            assertEquals(204, chunk(c1, 0, 0, StreamChunk.FIRST, "a").status());
            assertEquals(204, post("w", "/swarm/v1/leases/" + c1.lease() + ":nack").status());
            var ls = SwarmHttpTest.leases(onB.apply("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]));
            assertEquals(1, ls.size());
            var lease = ls.get(0).id();
            var t = heldOn(bHttp, lease);
            assertTrue(t.attempt() > a1, "takeover on another node has a higher attempt");
            var topic = TaskService.topic(t.pool(), t.shard());
            assertNull(bStreams.cancels().reason(topic, t.offset(), this.now.get()), "B never tracked the task");
            var corr = ByteBuffer.wrap(t.envelope().correlationId());
            long hi = corr.getLong(), lo = corr.getLong();
            java.util.function.Function<StreamChunk, Response> chunkB = sc -> onB.apply(
                    "/swarm/v1/leases/" + lease + ":chunk", sc.toFrame().encode());
            assertEquals(204, chunkB.apply(new StreamChunk(hi, lo, 0, 0, StreamChunk.FIRST, "b0".getBytes())).status());
            // the submitter cancels on node A; B learns only through the ledger record
            assertEquals("{\"state\":\"cancelled\"}",
                    SwarmHttpTest.text(post("a", "/swarm/v1/tasks/" + key(session) + ":cancel")));
            assertEquals(204, onB.apply("/swarm/v1/tasks/gen:claim", new byte[0]).status(), "B applies and acks the record");
            assertEquals("cancelled", bStreams.cancels().reason(topic, t.offset(), this.now.get()));
            var r = chunkB.apply(new StreamChunk(hi, lo, 0, 1, 0, "b1".getBytes()));
            assertEquals(409, r.status(), "B's worker learns on its next chunk");
            assertTrue(SwarmHttpTest.text(r).contains("\"reason\":\"cancelled\""));
            var ext = onB.apply("/swarm/v1/leases/" + lease + ":extend?visibilityMs=1000", new byte[0]);
            assertEquals(409, ext.status(), "and on :extend (B's probe)");
            assertTrue(SwarmHttpTest.text(ext).contains("\"reason\":\"cancelled\""));
            // the worker ends the stream and acks
            assertEquals(204, chunkB.apply(new StreamChunk(hi, lo, 0, 2, Frame.LAST | Frame.CANCELLED, new byte[0])).status());
            assertEquals(0, this.swarm.tasks().counts("gen").workers().inFlight());
        } finally {
            b.replies().close();
        }
    }

    @Test
    void aRemoteNodeHoldingTheTaskAppliesTheRecordOnItsNextClaim() {
        var b = Swarm.sharing(this.swarm, "nodeb", 7, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        try {
            b.enableStreams(TokenStreams.Config.DEFAULT);
            var bHttp = new SwarmHttp(b, 8, GATE);
            var session = start("");
            assertEquals(200, post("a", "/swarm/v1/tasks/" + key(session) + ":cancel").status());
            // A's own claim never runs: B claims both the task and the record
            var claim = bHttp.handle(new Request("POST", "/swarm/v1/tasks/gen:claim", RequestHeaders.of(Map.of("x-agent",
                    List.of("w"))), null, false, null, SwarmHttpTest.body(new byte[0])));
            // the task precedes the record: handed out first, then the next claim applies the record
            assertEquals(200, claim.status());
            {
                var lease = SwarmHttpTest.leases(claim).get(0).id();
                assertEquals(204, bHttp.handle(new Request("POST", "/swarm/v1/tasks/gen:claim", RequestHeaders.of(Map.of(
                        "x-agent", List.of("w"))), null, false, null, SwarmHttpTest.body(new byte[0]))).status());
                var ext = bHttp.handle(new Request("POST", "/swarm/v1/leases/" + lease + ":extend?visibilityMs=1000",
                        RequestHeaders.of(Map.of("x-agent", List.of("w"))), null, false, null,
                        SwarmHttpTest.body(new byte[0])));
                assertEquals(409, ext.status());
            }
        } finally {
            b.replies().close();
        }
    }

    @Test
    void cancelBeforeTheTaskIsPlacedStillCancels() throws Exception {
        var sh = streamHttp();
        var answer = new java.util.concurrent.atomic.AtomicReference<Response>();
        sh.beforePlace = () -> {
            sh.beforePlace = null;
            // the session is visible, nothing placed yet
            var key = sessionKeys(sh).iterator().next();
            answer.set(post("a", "/swarm/v1/tasks/" + key + ":cancel"));
        };
        var session = start("");
        assertEquals(200, answer.get().status());
        assertEquals("{\"state\":\"cancelled\"}", SwarmHttpTest.text(answer.get()), "never 200 running");
        assertEquals(1, sh.cancels());
        // the task side ran once placed: the claim applies the record and rejects the cancelled task
        assertEquals(204, post("w", "/swarm/v1/tasks/gen:claim").status());
        assertEquals(204, post("w", "/swarm/v1/tasks/gen:claim").status());
        assertEquals(1, this.swarm.tasks().counts("gen").workers().deadLettered(), "rejected: cancelled");
        var text = StreamHttpTest.drain(req("GET", "a", session, new byte[0]));
        assertTrue(text.endsWith("event: end\ndata: cancelled\n\n"), text);
        assertEquals("{\"state\":\"cancelled\"}",
                SwarmHttpTest.text(post("a", "/swarm/v1/tasks/" + key(session) + ":cancel")), "idempotent");
    }

    @Test
    void aFullCancelSetRefusesTheStreamWith503() {
        var sh = streamHttp();
        var cs = this.swarm.tasks().streams().cancels();
        for (int i = cs.size(); i < CancelSet.MAX_ENTRIES; i++) {
            assertTrue(cs.track("filler", i, this.now.get() + 600_000));
        }
        long enq = this.swarm.tasks().counts("gen").workers().enqueued();
        var r = req("POST", "a", "/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("prompt").encode());
        assertEquals(503, r.status(), SwarmHttpTest.text(r));
        assertEquals(1, sh.cancelRejections());
        assertEquals(1, cs.refused());
        assertEquals(enq, this.swarm.tasks().counts("gen").workers().enqueued(), "nothing submitted");
        assertEquals(0, this.swarm.replies().streams(), "router slot given back");
        // room again: streams are accepted, with cancel state
        this.now.addAndGet(600_000 + CancelSet.GRACE_MS + 1);
        start("");
    }

    StreamHttp streamHttp() {
        try {
            var f = SwarmHttp.class.getDeclaredField("streams");
            f.setAccessible(true);
            return (StreamHttp) f.get(this.http);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static java.util.Set<String> sessionKeys(StreamHttp sh) {
        try {
            var f = StreamHttp.class.getDeclaredField("sessions");
            f.setAccessible(true);
            return ((Map<String, ?>) f.get(sh)).keySet();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static Task heldOn(SwarmHttp h, String lease) {
        try {
            var f = SwarmHttp.class.getDeclaredField("claimed");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var m = (Map<String, Task>) f.get(h);
            return m.get(lease);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void redeliveredCancelledTaskIsRejected() {
        var session = start("");
        var c = claim();
        post("a", "/swarm/v1/tasks/" + key(session) + ":cancel");
        assertEquals(204, post("w", "/swarm/v1/leases/" + c.lease() + ":nack").status());
        assertEquals(204, post("w", "/swarm/v1/tasks/gen:claim").status(), "rejected with reason cancelled");
        var counts = this.swarm.tasks().counts("gen");
        assertEquals(1, counts.workers().deadLettered());
    }

    @Test
    void chunksCarryTheLeaseAttemptAndANewerAttemptResetsTheReader() throws Exception {
        var session = start("");
        var c1 = claim();
        long a1 = held(c1.lease()).attempt();
        assertEquals(204, chunk(c1, 99, 0, StreamChunk.FIRST, "old0").status(), "the client's attempt is ignored");
        assertEquals(204, chunk(c1, 99, 1, 0, "old1").status());
        var bin = StreamHttpTest.drainBytesUntil(req("GET", "a", session, new byte[0], "accept", SwarmHttp.CONTENT_TYPE), 2);
        assertEquals(a1, StreamChunk.decode(Frame.decode(ByteBuffer.wrap(bin)).payload()).attempt(), "stamped by the server");
        // takeover: the lease is lost (nack) and the task redelivered with a higher attempt
        assertEquals(204, post("w", "/swarm/v1/leases/" + c1.lease() + ":nack").status());
        var c2 = claim();
        long a2 = held(c2.lease()).attempt();
        assertTrue(a2 > a1);
        assertEquals(204, chunk(c2, 0, 0, StreamChunk.FIRST, "new0").status());
        var sink = sink();
        assertEquals(1, sink.resets());
        assertEquals(409, chunk(c1, 0, 2, 0, "zombie").status(), "older attempt: fenced");
        assertEquals(204, chunk(c2, 0, 1, Frame.LAST, "new1").status());
        // a reader resuming on the old attempt gets RESET(current), then the new attempt from 0
        var text = StreamHttpTest.drain(req("GET", "a", session, new byte[0], "last-event-id", a1 + ".1"));
        assertTrue(text.startsWith("retry: 1000\n\nevent: reset\ndata: " + a2 + "\n\nid: " + a2 + ".0\nevent: chunk\ndata: new0\n"),
                text);
        assertTrue(text.endsWith("id: " + a2 + ".1\nevent: end\ndata: ok\n\n"), text);
        assertFalse(text.contains("old"), text);
        // a fresh reader (no token) sees only the current attempt, no reset
        var fresh = StreamHttpTest.drain(req("GET", "a", session, new byte[0]));
        assertFalse(fresh.contains("reset"), fresh);
        // resume token on the current attempt
        var resumed = StreamHttpTest.drain(req("GET", "a", session, new byte[0], "last-event-id", a2 + ".0"));
        assertTrue(resumed.startsWith("retry: 1000\n\nid: " + a2 + ".1\n"), resumed);
        assertEquals(400, req("GET", "a", session, new byte[0], "last-event-id", "x.1").status());
    }

    TokenStreams.Sink sink() {
        try {
            var f = TokenStreams.class.getDeclaredField("sinks");
            f.setAccessible(true);
            var m = (Map<?, ?>) f.get(this.swarm.tasks().streams());
            return (TokenStreams.Sink) m.values().iterator().next();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void sinkDropsAndCountsOlderAttemptChunksAndQueuesResetForNewer() {
        var s = this.swarm.tasks().streams().open(new byte[16]);
        assertTrue(s.offer(new StreamChunk(0, 0, 1, 0, StreamChunk.FIRST, new byte[] {1})));
        assertTrue(s.offer(new StreamChunk(0, 0, 1, 2, 0, new byte[] {3}))); // buffered (gap at 1)
        assertTrue(s.offer(new StreamChunk(0, 0, 2, 0, StreamChunk.FIRST, new byte[] {7})));
        assertTrue(s.offer(new StreamChunk(0, 0, 1, 1, 0, new byte[] {2})), "stale is not a failure");
        assertEquals(1, s.staleChunks());
        assertEquals(1, s.resets());
        assertEquals(2, s.attempt());
        var got = s.drain();
        assertEquals(2, got.size(), String.valueOf(got));
        assertTrue(got.get(0).reset());
        assertEquals(2, got.get(0).attempt());
        assertEquals(7, got.get(1).bytes()[0]);
        s.close();
    }

    @Test
    void periodicSweepDropsExpiredSessionsAndCancelEntries() throws Exception {
        start("?deadlineMs=1000");
        var h = this.http.streams();
        assertEquals(1, h.sessionCount());
        assertEquals(1, this.swarm.tasks().streams().cancels().size());
        this.now.addAndGet(1000 + StreamHttp.GRACE_MS + CancelSet.GRACE_MS + 1);
        h.periodicSweep(); // what the timer runs: no stream request needed
        assertEquals(0, h.sessionCount());
        assertEquals(0, this.swarm.tasks().streams().cancels().size());
        assertEquals(0, this.swarm.replies().streams());
        assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("sjws-stream-sweep")));
    }

    @Test
    void cancelSetBasics() {
        var cs = new CancelSet(this.now::get);
        assertFalse(cs.cancel("t", 1), "untracked");
        assertTrue(cs.track("t", 1, this.now.get() + 10));
        assertNull(cs.reason("t", 1, this.now.get()));
        assertFalse(cs.probe("t").isCancelled("g", 1));
        assertTrue(cs.cancel("t", 1));
        assertFalse(cs.cancel("t", 1), "once");
        assertTrue(cs.probe("t").isCancelled("g", 1));
        assertTrue(cs.track("t", 2, this.now.get() - 1));
        assertEquals("deadline", cs.reason("t", 2, this.now.get()));
        assertFalse(cs.probe("t").isCancelled("g", 3));
        assertTrue(CancelSet.isRecord(CancelSet.record(5, 77, new byte[16])));
        assertEquals(5, CancelSet.recordTarget(CancelSet.record(5, 77, new byte[16])));
        assertEquals(77, CancelSet.recordDeadline(CancelSet.record(5, 77, new byte[16])));
        assertFalse(CancelSet.isRecord(new byte[CancelSet.RECORD_BYTES]));
        assertEquals(2, cs.sweep(this.now.get() + CancelSet.GRACE_MS + 11));
        assertEquals(0, cs.size());
    }

    @Test
    void adminIsAnAgentGrantedEveryPool() {
        var g = new TokenGate(List.of("alice " + SwarmGateTest.ALICE + " a,b", "bob " + SwarmGateTest.BOB + " *"), 4096);
        assertTrue(g.isAdmin(new SwarmGate.Agent("bob")));
        assertFalse(g.isAdmin(new SwarmGate.Agent("alice")));
        assertFalse(g.isAdmin(new SwarmGate.Agent("carol")));
        assertFalse(g.isAdmin(null));
        assertFalse(SwarmGate.DENY_ALL.isAdmin(new SwarmGate.Agent("bob")), "default: nobody");
    }

    @Test
    void cancelSetRefusesWhenFullAfterASweep() {
        var cs = new CancelSet(this.now::get);
        for (int i = 0; i < CancelSet.MAX_ENTRIES; i++) {
            assertTrue(cs.track("t", i, this.now.get() + 1_000));
        }
        assertFalse(cs.track("t", -1, this.now.get() + 1_000), "full, nothing expired");
        this.now.addAndGet(1_000 + CancelSet.GRACE_MS + 1);
        assertTrue(cs.track("t", -1, this.now.get() + 1_000), "the sweep on insert made room");
        assertEquals(1, cs.size());
    }

    @Test
    void reasonFallsBackToTheEnvelopeDeadline() {
        var h = this.http.streams();
        assertEquals("cancelled", h.reason(new Task("gen", 77, 1, 0, null, 0)));
        var noDeadline = new TaskEnvelope(new byte[16], new byte[16], "", TaskEnvelope.NO_DEADLINE, "", "", "", "", new byte[0]);
        assertEquals("cancelled", h.reason(new Task("gen", 77, 1, 0, noDeadline, 0)));
        var past = new TaskEnvelope(new byte[16], new byte[16], "", this.now.get() - 1, "", "", "", "", new byte[0]);
        assertEquals("deadline", h.reason(new Task("gen", 77, 1, 0, past, 0)));
        var future = new TaskEnvelope(new byte[16], new byte[16], "", this.now.get() + 1000, "", "", "", "", new byte[0]);
        assertEquals("cancelled", h.reason(new Task("gen", 77, 1, 0, future, 0)));
    }

    @Test
    void cancelAfterAnErrorEndReportsError() {
        var session = start("");
        var c = claim();
        assertEquals(204, chunk(c, 0, 0, Frame.LAST | Frame.ERROR, "").status());
        assertEquals("{\"state\":\"error\"}", SwarmHttpTest.text(post("a", "/swarm/v1/tasks/" + key(session) + ":cancel")));
        var cancelledEnd = start("");
        var c2 = claim();
        assertEquals(204, chunk(c2, 0, 0, Frame.LAST | Frame.CANCELLED, "").status());
        assertEquals("{\"state\":\"cancelled\"}",
                SwarmHttpTest.text(post("a", "/swarm/v1/tasks/" + key(cancelledEnd) + ":cancel")));
    }

    @Test
    void cancelBeforeAnyClaimAndExpiredSession() throws Exception {
        var session = start("?deadlineMs=1000");
        assertEquals(200, post("a", "/swarm/v1/tasks/" + key(session) + ":cancel").status());
        // the task is redelivered nowhere: the claim applies the record and rejects the cancelled task
        assertEquals(204, post("w", "/swarm/v1/tasks/gen:claim").status());
        this.now.addAndGet(1000 + StreamHttp.GRACE_MS + 1);
        assertEquals(404, post("a", "/swarm/v1/tasks/" + key(session) + ":cancel").status(), "expired");
    }

    @Test
    void producersMayNotSendResetAndBadResumeTokensAre400() throws Exception {
        var session = start("");
        var c = claim();
        var reset = StreamChunk.reset(c.hi(), c.lo(), 1);
        assertEquals(400, post0(c, reset).status());
        assertEquals(400, req("GET", "a", session, new byte[0], "last-event-id", "-5.1").status());
        assertEquals(400, req("GET", "a", session, new byte[0], "last-event-id", "1.-3").status());
    }

    @Test
    void binaryReaderGetsAResetFrame() throws Exception {
        var session = start("");
        var c1 = claim();
        long a1 = held(c1.lease()).attempt();
        assertEquals(204, chunk(c1, 0, 0, StreamChunk.FIRST, "old").status());
        assertEquals(204, post("w", "/swarm/v1/leases/" + c1.lease() + ":nack").status());
        var c2 = claim();
        long a2 = held(c2.lease()).attempt();
        assertEquals(204, chunk(c2, 0, 0, Frame.LAST, "new").status());
        var in = ByteBuffer.wrap(StreamHttpTest.drainBytes(req("GET", "a", session + "?attempt=" + a1 + "&from=1", new byte[0],
                "accept", SwarmHttp.CONTENT_TYPE)));
        var first = StreamChunk.decode(Frame.decode(in).payload());
        assertTrue(first.reset(), first.toString());
        assertEquals(a2, first.attempt());
        var next = StreamChunk.decode(Frame.decode(in).payload());
        assertEquals("new", new String(next.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void probesAreNotInstalledOnDlqTopicsAndCancelIsAPoolVerbWhenStreamingIsOff() throws Exception {
        assertTrue(this.swarm.topics().partition("tasks.gen.dlq") != null);
        try (var off = Swarm.open(this.dir.resolve("off"), "off", 5)) {
            var h = new SwarmHttp(off, 6, GATE);
            var r = h.handle(new Request("POST", "/swarm/v1/tasks/gen:cancel", RequestHeaders.of(Map.of()), null, false, null,
                    SwarmHttpTest.body(new byte[0])));
            assertEquals(404, r.status(), "unknown task verb, as before");
        }
    }

    @Test
    void extendOfATaskWithoutDeadlineIsNotClamped() {
        assertEquals(202, req("POST", "a", "/swarm/v1/tasks/gen", SwarmHttpTest.task("x").encode()).status());
        var ls = SwarmHttpTest.leases(post("w", "/swarm/v1/tasks/gen:claim?visibilityMs=1000"));
        var lease = ls.get(0).id();
        assertEquals(204, post("w", "/swarm/v1/leases/" + lease + ":extend?visibilityMs=60000").status());
        assertEquals(this.now.get() + 60000, held(lease).leaseDeadlineMs());
    }
}
