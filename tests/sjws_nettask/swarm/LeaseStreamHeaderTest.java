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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.HashMap;
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
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * #261 Q1: {@code :claim} answers carry {@code sjws-lease-stream} with the stream flag on, one entry per {@code LEASE} in
 * frame order: {@code <32-hex correlationId>/<attempt>} for a stream task, {@code -} for a unary one; never with it off.
 */
class LeaseStreamHeaderTest {

    static final Path GOLDEN = Path.of("tests/sjws_nettask/swarm/wire/golden-lease-stream-v1.txt");
    static final HexFormat HEX = HexFormat.of();

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;
    final AtomicLong now = new AtomicLong(1_000_000);

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "lease-stream", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
    }

    @AfterEach
    void close() {
        this.swarm.close();
    }

    void streamsOn() {
        this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER);
    }

    Response post(String target, byte[] body) {
        var fields = new HashMap<String, List<String>>();
        fields.put("x-agent", List.of("a"));
        return this.http.handle(new Request("POST", target, RequestHeaders.of(fields), null, false, null,
                SwarmHttpTest.body(body)));
    }

    void submitStream() {
        assertEquals(201, post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("prompt").encode()).status());
    }

    void submitUnary() {
        assertEquals(202, post("/swarm/v1/tasks/gen", SwarmHttpTest.task("unary").encode()).status());
    }

    Response claim(int max) {
        var r = post("/swarm/v1/tasks/gen:claim?visibilityMs=60000&max=" + max, new byte[0]);
        assertEquals(200, r.status(), SwarmHttpTest.text(r));
        return r;
    }

    @SuppressWarnings("unchecked")
    Task held(String lease) throws ReflectiveOperationException {
        var f = SwarmHttp.class.getDeclaredField("claimed");
        f.setAccessible(true);
        return ((Map<String, Task>) f.get(this.http)).get(lease);
    }

    /** What the header must say for the held task: from the server's own state, not from the header code. */
    String expected(String lease) throws ReflectiveOperationException {
        var t = held(lease);
        return t.envelope().tracestate().isEmpty() ? "-"
                : HEX.formatHex(t.envelope().correlationId()) + "/" + Integer.toUnsignedString(t.attempt());
    }

    static Task task(String correlationHex, long attempt) {
        boolean stream = !correlationHex.equals("-");
        var corr = stream ? HEX.parseHex(correlationHex) : new byte[16];
        var env = new TaskEnvelope(new byte[16], corr, "reply.n", TaskEnvelope.NO_DEADLINE, "",
                stream ? "sjws-stream=shards:1" : "", "", "", new byte[0]);
        return new Task("gen", 0, (int) attempt, 0, env);
    }

    @Test
    void goldenEntriesAndHeaders() throws Exception {
        var entries = new HashMap<String, String>();
        int singles = 0;
        int headers = 0;
        for (var line : Files.readAllLines(GOLDEN)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var f = line.split(" ");
            if (f[1].equals("!")) {
                StringBuilder b = null; // joined as SwarmHttp.claim does: lazily, at the first stream task
                int unary = 0;
                for (var name : f[2].split(",")) {
                    var parts = entries.get(name).split(" ");
                    var t = task(parts[0], parts[1].equals("-") ? 0 : Long.parseLong(parts[1]));
                    if (b != null) {
                        StreamHttp.appendLeaseStream(b.append(','), t);
                    } else if (TokenStreams.isStreamTask(t.envelope())) {
                        b = StreamHttp.appendLeaseStream(StreamHttp.leaseStreamPrefix(unary), t);
                    } else {
                        unary++;
                    }
                }
                assertTrue(b != null, f[0] + ": a header vector needs a stream entry");
                assertEquals(f[3], b.toString(), f[0]);
                headers++;
                continue;
            }
            entries.put(f[0], f[1] + " " + f[2]);
            var t = task(f[1], f[2].equals("-") ? 0 : Long.parseLong(f[2]));
            assertEquals(f[3], StreamHttp.appendLeaseStream(new StringBuilder(), t).toString(), f[0]);
            singles++;
        }
        assertEquals(5, singles);
        assertEquals(3, headers);
    }

    @Test
    void streamTaskMarkerIsTheShardsMemberOnly() {
        var id = new byte[16];
        java.util.function.Function<String, TaskEnvelope> env = ts -> new TaskEnvelope(id, id, "", 0, "", ts, "", "",
                new byte[0]);
        assertFalse(TokenStreams.isStreamTask(env.apply("")));
        assertFalse(TokenStreams.isStreamTask(env.apply("vendor=1")));
        assertFalse(TokenStreams.isStreamTask(env.apply("sjws-streamx=shards:1")));
        assertFalse(TokenStreams.isStreamTask(env.apply("sjws-strea=shards:1")));
        assertFalse(TokenStreams.isStreamTask(env.apply("sjws-stream")));
        assertTrue(TokenStreams.isStreamTask(env.apply("sjws-stream=shards:4")));
        assertTrue(TokenStreams.isStreamTask(env.apply("vendor=1, sjws-stream=shards:2")));
        assertTrue(TokenStreams.isStreamTask(env.apply("sjws-stream=bogus")), "a malformed stamp is still a stream task");
    }

    @Test
    void streamTaskEntryIsItsCorrelationIdAndAttempt() throws Exception {
        streamsOn();
        submitStream();
        var r = claim(1);
        var ls = SwarmHttpTest.leases(r);
        var got = single(r, 1)[0];
        assertEquals(expected(ls.get(0).id()), got);
        assertTrue(got.matches("[0-9a-f]{32}/[0-9]+"), got);
        assertEquals("1", got.substring(33), "first delivery");
    }

    @Test
    void unaryOnlyBatchSendsNeitherHeader() {
        streamsOn();
        submitUnary();
        submitUnary();
        var r = claim(2);
        assertEquals(2, SwarmHttpTest.leases(r).size());
        assertNull(r.headers().get(StreamHttp.LEASE_STREAM_HEADER));
        assertNull(r.headers().get(StreamHttp.STREAM_WINDOW_HEADER));
        assertEquals(List.of(Map.entry("content-type", SwarmHttp.CONTENT_TYPE)), r.headers().entries(),
                "R2: a unary claim answer is unchanged with the flag on");
    }

    /** Exactly one field of each header (set, never add), with one entry per LEASE frame. */
    static String[] single(Response r, int leases) {
        long n = r.headers().entries().stream().filter(e -> e.getKey().equals(StreamHttp.LEASE_STREAM_HEADER)).count();
        long w = r.headers().entries().stream().filter(e -> e.getKey().equals(StreamHttp.STREAM_WINDOW_HEADER)).count();
        assertEquals(1, n, "one sjws-lease-stream field");
        assertEquals(1, w, "one sjws-stream-window field");
        var got = r.headers().get(StreamHttp.LEASE_STREAM_HEADER).split(",", -1);
        assertEquals(leases, got.length, "one entry per LEASE");
        return got;
    }

    @Test
    void mixedBatchKeepsLeaseOrder() throws Exception {
        streamsOn();
        submitUnary();
        submitUnary();
        submitStream();
        submitUnary();
        submitStream();
        var r = claim(8);
        var ls = SwarmHttpTest.leases(r);
        assertEquals(5, ls.size());
        var got = single(r, ls.size());
        for (int i = 0; i < ls.size(); i++) {
            assertEquals(expected(ls.get(i).id()), got[i], "entry " + i);
        }
        assertEquals(List.of("-", "-", "-"), List.of(got[0], got[1], got[3]), "unary entries before and after streams");
        assertTrue(got[2].matches("[0-9a-f]{32}/1") && got[4].matches("[0-9a-f]{32}/1"), String.join(",", got));
        assertFalse(got[2].equals(got[4]), "distinct streams");
        assertEquals("64", r.headers().get(StreamHttp.STREAM_WINDOW_HEADER), "the default reorder window");
    }

    @Test
    void streamFirstBatchAndAdvertisedWindowFollowTheConfig() throws Exception {
        this.swarm.enableStreams(new TokenStreams.Config(1, 4, 4, true));
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER);
        submitStream();
        submitUnary();
        var r = claim(2);
        var ls = SwarmHttpTest.leases(r);
        var got = single(r, 2);
        assertEquals(expected(ls.get(0).id()), got[0]);
        assertEquals("-", got[1]);
        assertEquals("4", r.headers().get(StreamHttp.STREAM_WINDOW_HEADER), "R1: sjws.swarm.stream.window");
    }

    @Test
    void shardedLeaseEntriesCarryNoShardSuffix() throws Exception {
        var old = System.getProperty("sjws.swarm.pool.shards");
        System.setProperty("sjws.swarm.pool.shards", "4");
        try {
            this.swarm.close();
            this.swarm = Swarm.open(this.dir.resolve("sharded"), "lease-stream", 3,
                    new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0), this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        } finally {
            if (old == null) {
                System.clearProperty("sjws.swarm.pool.shards");
            } else {
                System.setProperty("sjws.swarm.pool.shards", old);
            }
        }
        assertEquals(4, this.swarm.tasks().shards());
        streamsOn();
        for (int i = 0; i < 8; i++) {
            submitStream();
        }
        var ls = new java.util.ArrayList<SwarmHttpTest.Lease>();
        var entries = new java.util.ArrayList<String>();
        for (int round = 0; round < 8 && ls.size() < 8; round++) {
            var r = post("/swarm/v1/tasks/gen:claim?visibilityMs=60000&max=8", new byte[0]);
            if (r.status() == 204) {
                continue;
            }
            var got = SwarmHttpTest.leases(r);
            ls.addAll(got);
            entries.addAll(List.of(single(r, got.size())));
        }
        assertEquals(8, ls.size());
        assertTrue(ls.stream().anyMatch(l -> l.id().substring(l.id().lastIndexOf('~')).contains(".")),
                "some lease ids carry the .shard suffix");
        for (int i = 0; i < ls.size(); i++) {
            assertEquals(expected(ls.get(i).id()), entries.get(i), "entry " + i);
            assertTrue(entries.get(i).matches("[0-9a-f]{32}/1"), entries.get(i));
        }
    }

    @Test
    void traceAndStreamHeadersAlignWithTheLeases() throws Exception {
        this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER, true);
        submitUnary();
        submitStream();
        var r = claim(2);
        var got = single(r, 2);
        assertEquals(2, r.headers().get(sjws_nettask.swarm.trace.SwarmTrace.LEASE_HEADER).split(",", -1).length);
        assertEquals("-", got[0]);
        assertEquals(expected(SwarmHttpTest.leases(r).get(1).id()), got[1]);
    }

    @Test
    void attemptFollowsAReLeaseAndChunksWithItAreAccepted() throws Exception {
        streamsOn();
        submitStream();
        var r1 = claim(1);
        var lease1 = SwarmHttpTest.leases(r1).get(0).id();
        var e1 = r1.headers().get(StreamHttp.LEASE_STREAM_HEADER);
        assertEquals(204, post("/swarm/v1/leases/" + lease1 + ":nack", new byte[0]).status());
        var r2 = claim(1);
        var lease2 = SwarmHttpTest.leases(r2).get(0).id();
        var e2 = r2.headers().get(StreamHttp.LEASE_STREAM_HEADER);
        assertEquals(expected(lease2), e2);
        assertEquals(e1.substring(0, 32), e2.substring(0, 32), "same stream");
        assertEquals(Long.parseLong(e1.substring(33)) + 1, Long.parseLong(e2.substring(33)), "re-lease: attempt + 1");
        assertEquals(held(lease2).attempt(), Integer.parseInt(e2.substring(33)));
        // an HTTP worker that only has the header can stream: its chunk is accepted with that id and attempt
        var id = ByteBuffer.wrap(HEX.parseHex(e2, 0, 32));
        var c = new StreamChunk(id.getLong(), id.getLong(), Long.parseLong(e2.substring(33)), 0, StreamChunk.FIRST,
                "tok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(204, post("/swarm/v1/leases/" + lease2 + ":chunk", c.toFrame().encode()).status());
    }

    @Test
    void absentWithTheStreamFlagOff() {
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER);
        submitUnary();
        var r = claim(1);
        assertNull(r.headers().get(StreamHttp.LEASE_STREAM_HEADER));
        assertNull(r.headers().get(StreamHttp.STREAM_WINDOW_HEADER));
        assertEquals(List.of(Map.entry("content-type", SwarmHttp.CONTENT_TYPE)), r.headers().entries(),
                "flag off: the claim answer's headers are unchanged");
        assertNull(this.http.streams(), "no stream leg was built");
    }

    @Test
    void noHeaderOnAnEmptyClaim() {
        streamsOn();
        var r = post("/swarm/v1/tasks/gen:claim", new byte[0]);
        assertEquals(204, r.status());
        assertNull(r.headers().get(StreamHttp.LEASE_STREAM_HEADER));
    }
}
