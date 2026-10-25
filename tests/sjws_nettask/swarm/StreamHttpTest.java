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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

/** S2c PR 4: {@code :stream}, {@code :chunk} and {@code GET replies/{session}} over the handler. */
class StreamHttpTest {

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;
    final AtomicLong now = new AtomicLong(1_000_000);

    /** The agent is the {@code x-agent} header: lets tests act as two agents. */
    static final SwarmGate BY_HEADER = new SwarmGate() {
        @Override
        public boolean isAdmin(Agent agent) {
            return false;
        }

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
        public long maxRequestBytes() {
            return SwarmHttp.MAX_REQUEST;
        }
    };

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "stream-http", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
    }

    void streamsOn() {
        streamsOn(TokenStreams.Config.DEFAULT);
    }

    void streamsOn(TokenStreams.Config config) {
        this.swarm.enableStreams(config);
        this.http = new SwarmHttp(this.swarm, 4, BY_HEADER);
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

    Response post(String target, byte[] body) {
        return req("POST", "a", target, body);
    }

    /** Submits a streaming call; returns the session path. */
    String startStream(String query) {
        var r = post("/swarm/v1/tasks/gen:stream" + query, SwarmHttpTest.task("prompt").encode());
        assertEquals(201, r.status(), SwarmHttpTest.text(r));
        var f = Frame.decode(ByteBuffer.wrap(SwarmHttpTest.bytes(r)));
        assertEquals(16, f.payload().length);
        var loc = r.headers().get("location");
        assertEquals("/swarm/v1/replies/" + java.util.HexFormat.of().formatHex(f.payload()), loc);
        return loc;
    }

    record Claimed(String lease, long hi, long lo) { }

    Claimed claim() {
        var r = post("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]);
        var ls = SwarmHttpTest.leases(r);
        assertEquals(1, ls.size());
        // the stream id is the correlation id, read from sjws-lease-stream (#261 Q1) as an HTTP worker does
        var entry = r.headers().get(StreamHttp.LEASE_STREAM_HEADER);
        assertTrue(entry != null && entry.length() > 33 && entry.charAt(32) == '/', String.valueOf(entry));
        var id = ByteBuffer.wrap(java.util.HexFormat.of().parseHex(entry, 0, 32));
        return new Claimed(ls.get(0).id(), id.getLong(), id.getLong());
    }

    Response chunk(Claimed c, long seq, int flags, String text) {
        var sc = new StreamChunk(c.hi(), c.lo(), seq, flags, text.getBytes(StandardCharsets.UTF_8));
        return post("/swarm/v1/leases/" + c.lease() + ":chunk", sc.toFrame().encode());
    }

    static String drain(Response r) throws Exception {
        return new String(drainBytes(r), StandardCharsets.UTF_8);
    }

    /** Reads the first {@code frames} binary frames, then closes the reader (the stream need not have ended). */
    static byte[] drainBytesUntil(Response r, int frames) throws Exception {
        var src = ((Body.Stream) r.body()).source();
        var out = new ByteArrayOutputStream();
        try (src) {
            int n = 0;
            while (n < frames) {
                var b = src.next();
                if (b == null) {
                    break;
                }
                var a = new byte[b.remaining()];
                b.get(a);
                out.write(a);
                n++;
            }
        }
        return out.toByteArray();
    }

    static byte[] drainBytes(Response r) throws Exception {
        var src = ((Body.Stream) r.body()).source();
        var out = new ByteArrayOutputStream();
        try (src) {
            ByteBuffer b;
            while ((b = src.next()) != null) {
                var a = new byte[b.remaining()];
                b.get(a);
                out.write(a);
            }
        }
        return out.toByteArray();
    }

    @Test
    void streamsOverSseToTheBoundAgentOnly() throws Exception {
        streamsOn();
        var session = startStream("");
        var c = claim();
        assertEquals(204, chunk(c, 0, StreamChunk.FIRST, "hel").status());
        assertEquals(204, chunk(c, 1, 0, "lo\nwor\r\nld").status());
        assertEquals(204, chunk(c, 2, Frame.LAST, "!").status());
        assertEquals(404, req("GET", "b", session, new byte[0]).status(), "another agent: 404, not 403");
        assertEquals(404, req("GET", "a", "/swarm/v1/replies/00112233445566778899aabbccddeeff", new byte[0]).status());
        var r = req("GET", "a", session, new byte[0]);
        assertEquals(200, r.status());
        assertEquals("text/event-stream", r.headers().get("content-type"));
        assertEquals("""
                retry: 1000

                id: 1.0
                event: chunk
                data: hel

                id: 1.1
                event: chunk
                data: lo
                data: wor
                data: ld

                id: 1.2
                event: chunk
                data: !

                id: 1.2
                event: end
                data: ok

                """, drain(r));
        // resume: Last-Event-ID n -> from n + 1; ?from=n -> from n
        var resumed = drain(req("GET", "a", session, new byte[0], "last-event-id", "1.1"));
        assertTrue(resumed.startsWith("retry: 1000\n\nid: 1.2\n"), resumed);
        assertTrue(drain(req("GET", "a", session + "?from=1", new byte[0])).startsWith("retry: 1000\n\nid: 1.1\n"));
        assertEquals(409, chunk(c, 3, 0, "late").status(), "the lease is settled after LAST");
        // #232: the stream's final reply on reply.<node> is consumed by the router without counting late
        long until = System.nanoTime() + 5_000_000_000L;
        while (this.swarm.replies().streams() > 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertEquals(0, this.swarm.replies().streams());
        assertEquals(0, this.swarm.replies().lateReplies());
    }

    @Test
    void binaryFramesOnAccept() throws Exception {
        streamsOn();
        var session = startStream("");
        var c = claim();
        chunk(c, 0, StreamChunk.FIRST, "a\u0000b");
        chunk(c, 1, Frame.LAST, "\uD83D\uDE00");
        var r = req("GET", "a", session, new byte[0], "accept", SwarmHttp.CONTENT_TYPE);
        assertEquals(SwarmHttp.CONTENT_TYPE, r.headers().get("content-type"));
        var in = ByteBuffer.wrap(drainBytes(r));
        var got = new ArrayList<StreamChunk>();
        Frame f;
        while ((f = Frame.decode(in)) != null) {
            assertEquals(Frame.Type.TOKEN, f.type());
            got.add(StreamChunk.decode(f.payload()));
        }
        assertEquals(2, got.size());
        assertArrayEquals("a\u0000b".getBytes(StandardCharsets.UTF_8), got.get(0).bytes());
        assertTrue(got.get(1).last());
    }

    @Test
    void deadlineGivesCancelledWithReasonAndEndsTheStream() throws Exception {
        streamsOn();
        var session = startStream("?deadlineMs=1000");
        var c = claim();
        assertEquals(204, chunk(c, 0, StreamChunk.FIRST, "x").status());
        this.now.addAndGet(2_000);
        var r = chunk(c, 1, 0, "y");
        assertEquals(409, r.status());
        var body = SwarmHttpTest.text(r);
        assertTrue(body.contains("\"type\":\"" + SwarmHttp.CANCELLED_TYPE + "\""), body);
        assertTrue(body.contains("\"reason\":\"deadline\""), body);
        var text = drain(req("GET", "a", session, new byte[0]));
        assertTrue(text.endsWith("event: end\ndata: deadline\n\n"), text);
    }

    @Test
    void extendCancelledCarriesReason() {
        var body = new String(SwarmHttpTest.bytes(SwarmHttp.leaseProblem(
                sjws_nettask.messaging.delivery.LeaseResult.CANCELLED).response()), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"reason\":\"cancelled\""), body);
        var stale = new String(SwarmHttpTest.bytes(SwarmHttp.leaseProblem(
                sjws_nettask.messaging.delivery.LeaseResult.STALE_ATTEMPT).response()), StandardCharsets.UTF_8);
        assertTrue(!stale.contains("reason"), stale);
    }

    @Test
    void oneReaderAtATimeAndSessionCap() throws Exception {
        streamsOn();
        var session = startStream("");
        var first = req("GET", "a", session, new byte[0]);
        assertEquals(200, first.status());
        assertEquals(409, req("GET", "a", session, new byte[0]).status());
        ((Body.Stream) first.body()).source().close();
        var again = req("GET", "a", session, new byte[0]);
        assertEquals(200, again.status());
        ((Body.Stream) again.body()).source().close();
        for (int i = 1; i < StreamHttp.MAX_SESSIONS_PER_AGENT; i++) {
            startStream("");
        }
        assertEquals(503, post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("p").encode()).status());
        assertEquals(StreamHttp.MAX_SESSIONS_PER_AGENT, this.swarm.replies().streams(), "a refused submit registers nothing");
        assertEquals(201, req("POST", "b", "/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("p").encode()).status(),
                "the cap is per agent");
        assertEquals(405, req("DELETE", "a", session, new byte[0]).status());
    }

    @Test
    void sessionExpiresAfterLastPlusGrace() throws Exception {
        streamsOn();
        var session = startStream("");
        var c = claim();
        chunk(c, 0, StreamChunk.FIRST | Frame.LAST, "done");
        drain(req("GET", "a", session, new byte[0]));
        this.now.addAndGet(StreamHttp.GRACE_MS + 1);
        assertEquals(404, req("GET", "a", session, new byte[0]).status());
        this.http.streams().sweep();
        assertEquals(0, this.http.streams().sessionCount());
    }

    @Test
    void badChunks() {
        streamsOn();
        var session = startStream("");
        var c = claim();
        var wrongStream = new StreamChunk(1, 2, 0, 0, new byte[0]);
        assertEquals(400, post("/swarm/v1/leases/" + c.lease() + ":chunk", wrongStream.toFrame().encode()).status());
        assertEquals(400, post("/swarm/v1/leases/" + c.lease() + ":chunk", SwarmHttpTest.task("x").encode()).status());
        assertEquals(400, post("/swarm/v1/leases/" + c.lease() + ":chunk",
                new Frame(Frame.Type.TOKEN, 0, new byte[3]).encode()).status());
        assertEquals(409, post("/swarm/v1/leases/gen~99~1:chunk",
                new StreamChunk(c.hi(), c.lo(), 0, 0, new byte[0]).toFrame().encode()).status());
        assertEquals(400, req("GET", "a", session + "?from=nope", new byte[0]).status());
        assertEquals(400, req("GET", "a", session, new byte[0], "last-event-id", "x").status());
        assertEquals(404, req("GET", "a", "/swarm/v1/replies/x?from=nope", new byte[0]).status(), "unknown first");
    }

    @Test
    void offRoutesAre404AndNoLegIsBuilt() {
        this.http = new SwarmHttp(this.swarm, 4, BY_HEADER);
        assertNull(this.http.streams());
        assertEquals(404, req("GET", "a", "/swarm/v1/replies/00112233445566778899aabbccddeeff", new byte[0]).status());
        assertEquals(404, post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("p").encode()).status());
        assertEquals(404, post("/swarm/v1/leases/gen~0~1:chunk", new byte[0]).status());
        assertEquals(405, req("GET", "a", "/swarm/v1/tasks/gen", new byte[0]).status(), "unchanged");
        assertNotEquals(null, this.http);
    }

    @Test
    void sseEncodingEdges() {
        var empty = new String(StreamHttp.sse(new StreamChunk(1, 1, 4, Frame.LAST | Frame.CANCELLED, new byte[0])),
                StandardCharsets.UTF_8);
        assertEquals("id: 0.4\nevent: end\ndata: cancelled\n\n", empty);
        var err = new String(StreamHttp.sse(new StreamChunk(1, 1, 5, Frame.LAST | Frame.ERROR, new byte[0])),
                StandardCharsets.UTF_8);
        assertTrue(err.endsWith("data: error\n\n"));
        var cr = new String(StreamHttp.sse(new StreamChunk(1, 1, 0, 0, "a\rb\n".getBytes(StandardCharsets.UTF_8))),
                StandardCharsets.UTF_8);
        assertEquals("id: 0.0\nevent: chunk\ndata: a\ndata: b\ndata: \n\n", cr);
    }

    /** inceptor (1): chunks before the first GET are retained up to the bound; beyond it the reader gets a gap. */
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void overflowBeforeFirstGetIsAnExplicitGapAndLastIsKept() throws Exception {
        streamsOn(new TokenStreams.Config(1, 64, 4));
        var session = startStream("");
        var c = claim();
        assertEquals(204, chunk(c, 0, StreamChunk.FIRST, "c0").status());
        for (int i = 1; i < 10; i++) {
            assertEquals(204, chunk(c, i, 0, "c" + i).status());
        }
        assertEquals(204, chunk(c, 10, Frame.LAST, "end").status());
        var text = drain(req("GET", "a", session, new byte[0]));
        assertEquals("retry: 1000\n\nevent: gap\ndata: 0 6 overflow\n\nevent: end\ndata: error\n\n", text);
        var tail = drain(req("GET", "a", session + "?from=7", new byte[0]));
        assertTrue(tail.contains("id: 1.9\nevent: chunk\ndata: c9\n") && tail.endsWith("id: 1.10\nevent: end\ndata: ok\n\n"),
                tail);
        var bin = ByteBuffer.wrap(drainBytes(req("GET", "a", session, new byte[0], "accept", SwarmHttp.CONTENT_TYPE)));
        var f = Frame.decode(bin);
        assertEquals(Frame.Type.ERROR, f.type());
        assertEquals("gap 0 6 overflow", new String(f.payload(), StandardCharsets.UTF_8));
    }

    /** inceptor (1): a hole in the stream (a dropped chunk) is reported, never skipped. */
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void droppedChunkIsAGapNotASkip() throws Exception {
        streamsOn(new TokenStreams.Config(1, 4, 256));
        var session = startStream("");
        var c = claim();
        assertEquals(204, chunk(c, 0, StreamChunk.FIRST, "a").status());
        assertEquals(204, chunk(c, 50, 0, "far").status()); // beyond the reorder window: dropped
        var text = drain(req("GET", "a", session, new byte[0]));
        assertEquals("retry: 1000\n\nid: 1.0\nevent: chunk\ndata: a\n\nevent: gap\ndata: 1 ? overflow\n\nevent: end\ndata: error\n\n",
                text);
    }

    /** inceptor (2): reconnecting after the end returns the terminal state at once (no wait for the deadline). */
    @Test
    @org.junit.jupiter.api.Timeout(10)
    void reconnectAfterEndReturnsTerminalImmediately() throws Exception {
        streamsOn();
        var session = startStream("?deadlineMs=3600000");
        var c = claim();
        chunk(c, 0, StreamChunk.FIRST, "x");
        chunk(c, 1, Frame.LAST | Frame.ERROR, "");
        drain(req("GET", "a", session, new byte[0]));
        long t0 = System.nanoTime();
        assertEquals("retry: 1000\n\nid: 1.1\nevent: end\ndata: error\n\n",
                drain(req("GET", "a", session, new byte[0], "last-event-id", "1.1")));
        var bin = ByteBuffer.wrap(drainBytes(req("GET", "a", session + "?from=2", new byte[0], "accept", SwarmHttp.CONTENT_TYPE)));
        var last = StreamChunk.decode(Frame.decode(bin).payload());
        assertTrue(last.last() && last.chunkSeq() == 1 && last.size() == 0, last.toString());
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L);
    }

    /** A live reader is woken by the producer (no 250 ms poll step per chunk). */
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void liveReaderSeesChunksAsTheyArrive() throws Exception {
        streamsOn();
        var session = startStream("");
        var c = claim();
        var r = req("GET", "a", session, new byte[0]);
        var src = ((Body.Stream) r.body()).source();
        src.next(); // retry
        var producer = Thread.ofVirtual().start(() -> {
            chunk(c, 0, StreamChunk.FIRST, "hi");
            chunk(c, 1, Frame.LAST, "");
        });
        var first = src.next();
        assertTrue(StandardCharsets.UTF_8.decode(first).toString().contains("data: hi"));
        assertTrue(StandardCharsets.UTF_8.decode(src.next()).toString().contains("data: ok"));
        assertNull(src.next());
        src.close();
        producer.join();
    }

    /**
     * A reader that caught up parks on the session (one or more 250 ms poll steps, no heartbeat before 15 s) and
     * returns the next chunk once it arrives. Unlike {@link #liveReaderSeesChunksAsTheyArrive}, the producer only
     * writes after the reader is seen parked, so the wait path runs on every run (it was timing-dependent before).
     */
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void aParkedSseReaderReturnsTheNextChunk() throws Exception {
        streamsOn();
        var session = startStream("");
        var c = claim();
        var src = ((Body.Stream) req("GET", "a", session, new byte[0]).body()).source();
        assertEquals("retry: 1000\n\n", StandardCharsets.UTF_8.decode(src.next()).toString());
        var got = parkedNext(src, () -> assertEquals(204, chunk(c, 0, StreamChunk.FIRST, "late").status()));
        assertEquals("id: 1.0\nevent: chunk\ndata: late\n\n", StandardCharsets.UTF_8.decode(got).toString(),
                "the chunk, not a heartbeat comment");
        chunk(c, 1, Frame.LAST, "");
        assertTrue(StandardCharsets.UTF_8.decode(src.next()).toString().contains("data: ok"));
        assertNull(src.next());
        src.close();
    }

    /** The same wait on a binary reader (no SSE heartbeat to consider): the parked reader gets the TOKEN frame. */
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void aParkedBinaryReaderReturnsTheNextFrame() throws Exception {
        streamsOn();
        var session = startStream("");
        var c = claim();
        var src = ((Body.Stream) req("GET", "a", session, new byte[0], "accept", SwarmHttp.CONTENT_TYPE).body()).source();
        var got = parkedNext(src, () -> assertEquals(204, chunk(c, 0, StreamChunk.FIRST | Frame.LAST, "bin").status()));
        var f = Frame.decode(got);
        assertEquals(Frame.Type.TOKEN, f.type());
        var sc = StreamChunk.decode(f.payload());
        assertArrayEquals("bin".getBytes(StandardCharsets.UTF_8), sc.bytes());
        assertTrue(sc.last());
        assertNull(src.next());
        src.close();
    }

    /** A binary reader past the deadline gets one ERROR|LAST "deadline" frame and the stream ends. */
    @Test
    void deadlineEndsABinaryReaderWithAnErrorFrame() throws Exception {
        streamsOn();
        var session = startStream("?deadlineMs=1000");
        var c = claim();
        assertEquals(204, chunk(c, 0, StreamChunk.FIRST, "x").status());
        this.now.addAndGet(2_000);
        var in = ByteBuffer.wrap(drainBytes(req("GET", "a", session, new byte[0], "accept", SwarmHttp.CONTENT_TYPE)));
        assertEquals(Frame.Type.TOKEN, Frame.decode(in).type());
        var end = Frame.decode(in);
        assertEquals(Frame.Type.ERROR, end.type());
        assertTrue((end.flags() & Frame.LAST) != 0);
        assertEquals("deadline", new String(end.payload(), StandardCharsets.UTF_8));
        assertNull(Frame.decode(in));
    }

    /**
     * Calls {@code src.next()} on another thread, waits until that thread is parked in the session wait and has
     * been through at least one 250 ms poll step, then runs {@code produce} and returns what the reader got.
     */
    static ByteBuffer parkedNext(Body.Stream.Source src, Runnable produce) throws Exception {
        var out = new java.util.concurrent.atomic.AtomicReference<ByteBuffer>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var reader = new Thread(() -> {
            try {
                out.set(src.next());
            } catch (Throwable e) {
                err.set(e);
            }
        }, "parked-reader");
        reader.start();
        long until = System.nanoTime() + 10_000_000_000L;
        while (reader.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(reader.isAlive() && System.nanoTime() < until, "reader never parked: " + err.get());
            Thread.sleep(5);
        }
        Thread.sleep(400); // past one await(250) timeout: the reader loops back to Wait instead of returning
        assertTrue(reader.isAlive(), "the reader returned before any chunk: " + out.get());
        produce.run();
        reader.join(10_000);
        assertNull(err.get());
        assertTrue(out.get() != null, "the parked reader returned the chunk");
        return out.get();
    }

    @Test
    void sweepDropsExpiredUnreadSessions() {
        streamsOn();
        startStream("?deadlineMs=1000");
        assertEquals(1, this.http.streams().sessionCount());
        assertEquals(1, this.swarm.replies().streams(), "registered with the reply router (#232)");
        this.now.addAndGet(1000 + StreamHttp.GRACE_MS + 1);
        this.http.streams().sweep();
        assertEquals(0, this.http.streams().sessionCount());
        assertEquals(0, this.swarm.replies().streams(), "cleared on expiry without LAST");
        for (int i = 0; i < StreamHttp.MAX_SESSIONS_PER_AGENT; i++) {
            startStream(""); // the per-agent count was released
        }
    }

    /** #232: a refused router registration fails the submit explicitly and leaves no session or count behind. */
    @Test
    void routerRefusalIs503() {
        System.setProperty(ReplyRouter.MAX_STREAMS_PROPERTY, "1");
        try {
            this.swarm.close();
            open();
            streamsOn();
        } finally {
            System.clearProperty(ReplyRouter.MAX_STREAMS_PROPERTY);
        }
        startStream("");
        var r = post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("p").encode());
        assertEquals(503, r.status());
        assertTrue(SwarmHttpTest.text(r).contains("reply router stream limit"), SwarmHttpTest.text(r));
        assertEquals(1, this.http.streams().sessionCount());
        assertEquals(1, this.swarm.replies().streams());
    }
}
