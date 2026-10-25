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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.OptionalModule;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.swarm.wire.Credit;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * S2c PR 6b: the node stream-memory budget (#267, inceptor's ruling issuecomment-6402985417). Every HTTP test ends with
 * the accounting identity checked ({@code charged} = the sessions' resident bytes plus outstanding tickets, each
 * session's resident = retained + held) and, after every session is swept, {@code charged == 0} and
 * {@code budgetUnderflows == 0} ({@link #close}).
 */
class StreamBudgetTest {

    static final long MIB = 1L << 20;
    static final long KIB = 1L << 10;

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;
    final AtomicLong now = new AtomicLong(1_000_000);
    final Map<String, String> resume = new HashMap<>(); // session path -> last-event-id

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "budget", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
    }

    static TokenStreams.Config cfg(long bytes, double soft, double admit) {
        return new TokenStreams.Config(1, 64, 256, true, bytes, TokenStreams.Config.HEAP_FRACTION, soft, admit);
    }

    void on(TokenStreams.Config config) {
        this.swarm.enableStreams(config);
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER);
    }

    @AfterEach
    void close() {
        try {
            if (this.http != null && this.http.streams() != null && this.http.streams().credit() != null) {
                assertAccounting();
                this.now.addAndGet(10 * 3_600_000L); // past every deadline + grace
                this.http.streams().periodicSweep();
                assertEquals(0, this.http.streams().sessionCount(), "every session swept");
                assertConserved();
            }
        } finally {
            this.swarm.close();
        }
    }

    // --- helpers

    record Stream(String pool, String session, String lease, long hi, long lo, long attempt) { }

    StreamBudget budget() {
        return this.http.streams().credit().budget;
    }

    Response req(String method, String target, byte[] body, String... headers) {
        var fields = new HashMap<String, List<String>>();
        fields.put("x-agent", List.of("a"));
        for (int i = 0; i < headers.length; i += 2) {
            fields.put(headers[i], List.of(headers[i + 1]));
        }
        return this.http.handle(new Request(method, target, RequestHeaders.of(fields), null, false, null,
                SwarmHttpTest.body(body)));
    }

    Response post(String target, byte[] body) {
        return req("POST", target, body);
    }

    Response submit(String pool) {
        return post("/swarm/v1/tasks/" + pool + ":stream", SwarmHttpTest.task("prompt").encode());
    }

    Stream stream(String pool) {
        var r = submit(pool);
        assertEquals(201, r.status(), SwarmHttpTest.text(r));
        var s = claimFor(pool, r.headers().get("location"));
        assertNotNull(s);
        return s;
    }

    /** Claims the pool's task; null when the claim hands out nothing (e.g. it applied a cancel record). */
    Stream claimFor(String pool, String session) {
        for (int i = 0; i < 4; i++) {
            var ls = SwarmHttpTest.leases(post("/swarm/v1/tasks/" + pool + ":claim?visibilityMs=600000", new byte[0]));
            if (!ls.isEmpty()) {
                var t = held(ls.get(0).id());
                var b = ByteBuffer.wrap(t.envelope().correlationId());
                return new Stream(pool, session, ls.get(0).id(), b.getLong(), b.getLong(), t.attempt() & StreamChunk.MAX_ATTEMPT);
            }
        }
        return null;
    }

    Stream takeover(Stream s) {
        post("/swarm/v1/leases/" + s.lease() + ":nack", new byte[0]);
        return claimFor(s.pool(), s.session());
    }

    @SuppressWarnings("unchecked")
    Task held(String lease) {
        try {
            var f = SwarmHttp.class.getDeclaredField("claimed");
            f.setAccessible(true);
            return ((Map<String, Task>) f.get(this.http)).get(lease);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, StreamHttp.Session> sessions() {
        try {
            var f = StreamHttp.class.getDeclaredField("sessions");
            f.setAccessible(true);
            return (Map<String, StreamHttp.Session>) f.get(this.http.streams());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    StreamHttp.Session session(Stream s) {
        var session = this.http.streams().credit().session(s.hi(), s.lo());
        assertNotNull(session);
        return session;
    }

    StreamCredit.State st(Stream s) {
        return session(s).credit;
    }

    static byte[] data(long size) {
        var b = new byte[(int) size];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    Response chunk(Stream s, long seq, long size) {
        return chunk(s, seq, 0, data(size));
    }

    Response chunk(Stream s, long seq, int flags, byte[] bytes) {
        return post("/swarm/v1/leases/" + s.lease() + ":chunk", new StreamChunk(s.hi(), s.lo(), seq, flags, bytes).toFrame()
                .encode());
    }

    static Credit credit(Response r) {
        assertEquals(200, r.status(), SwarmHttpTest.text(r));
        var f = Frame.decode(ByteBuffer.wrap(SwarmHttpTest.bytes(r)));
        assertEquals(Frame.Type.CREDIT, f.type());
        return Credit.decode(f.payload());
    }

    static void assertNode429(Response r) {
        assertEquals(429, r.status(), SwarmHttpTest.text(r));
        assertEquals("1", r.headers().get("retry-after"));
        assertEquals("application/problem+json", r.headers().get("content-type"));
        var t = SwarmHttpTest.text(r);
        assertTrue(t.contains("\"type\":\"https://sjws.dev/problems/credit\""), t);
        assertTrue(t.contains("\"reason\":\"credit\""), t);
        assertTrue(t.contains("\"scope\":\"node\""), t);
        assertTrue(t.contains("\"status\":429"), t);
    }

    static void assertSession429(Response r) {
        StreamCreditTest.assert429(r);
        assertFalse(SwarmHttpTest.text(r).contains("\"scope\""), "session-scope refusals keep their shape");
    }

    /** {@code charged} = Σ resident + outstanding tickets; resident = retained + held per open session. */
    void assertAccounting() {
        var b = budget();
        long sum = 0;
        for (var s : sessions().values()) {
            var st = s.credit;
            s.lock.lock();
            try {
                assertFalse(st.closed, "a session in the table is open");
                assertEquals(s.retainedBytes + st.heldBytesLocked, st.resident.get(), "resident = retained + held");
                assertEquals(st.heldBytesLocked, st.heldBytes.get(), "held bytes, quiescent");
            } finally {
                s.lock.unlock();
            }
            sum += st.resident.get();
        }
        sum += b.gapTicketsOutstanding() * StreamBudget.GAP_TICKET + b.lastTicketsOutstanding() * StreamBudget.LAST_TICKET;
        assertEquals(sum, b.charged(), "charged = resident + tickets (no request in flight)");
        assertEquals(sessions().size(), b.openSessions());
        assertEquals(sessions().size(), b.lastTicketsOutstanding() + lastConverted());
        assertEquals(0, b.budgetUnderflows(), "budgetUnderflows");
    }

    long lastConverted() {
        long n = 0;
        for (var s : sessions().values()) {
            if (!s.credit.lastTicket.get()) {
                n++;
            }
        }
        return n;
    }

    void assertConserved() {
        var b = budget();
        assertEquals(0, b.charged(), "charged == 0 with the session table empty and no request in flight");
        assertEquals(0, b.budgetUnderflows(), "budgetUnderflows == 0");
        assertEquals(0, b.gapTicketsOutstanding());
        assertEquals(0, b.lastTicketsOutstanding());
        assertEquals(0, b.openSessions());
        assertEquals(0, this.http.streams().credit().reserveUnderflows());
    }

    /** Reads whatever the session has now (binary), resuming where the last read stopped; returns the frames. */
    List<Frame> readAvailable(Stream s) throws Exception {
        var session = this.http.streams().credit().session(s.hi(), s.lo());
        var token = this.resume.get(s.session());
        var r = token == null ? req("GET", s.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE)
                : req("GET", s.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE, "last-event-id", token);
        var out = new ArrayList<Frame>();
        if (r.status() != 200 || session == null) {
            return out;
        }
        long attempt = -1;
        long next = 0;
        if (token != null) {
            int dot = token.indexOf('.');
            attempt = Long.parseLong(token.substring(0, dot));
            next = Long.parseLong(token.substring(dot + 1)) + 1;
        }
        try (var src = ((Body.Stream) r.body()).source()) {
            while (session.at(next, attempt) != StreamHttp.Wait.INSTANCE) {
                var b = src.next();
                if (b == null) {
                    break;
                }
                var f = Frame.decode(b);
                out.add(f);
                if (f.type() != Frame.Type.TOKEN) {
                    break; // gap or deadline: the reader ended
                }
                var c = StreamChunk.decode(f.payload());
                attempt = c.attempt();
                if (c.reset()) {
                    next = 0;
                    this.resume.remove(s.session());
                    continue;
                }
                next = c.chunkSeq() + 1;
                this.resume.put(s.session(), c.attempt() + "." + c.chunkSeq());
                if (c.last()) {
                    break;
                }
            }
        }
        return out;
    }

    void expireAndSweep() {
        this.now.addAndGet(10 * 3_600_000L);
        this.http.streams().periodicSweep();
    }

    // --- config and size (Q1)

    @Test
    void theBudgetSettingsAreReadOnlyWithCreditAndAMalformedOneStopsTheProcess() {
        var off = TokenStreams.Config.from(Map.of("sjws.swarm.stream.budget.bytes", "nonsense")::get);
        assertFalse(off.credit(), "credit off: the budget settings are not read");
        var on = TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", "true",
                "sjws.swarm.stream.budget.bytes", " 33554432 ", "sjws.swarm.stream.budget.heapFraction", "0.5",
                "sjws.swarm.stream.budget.softWatermark", "0.6", "sjws.swarm.stream.budget.admitWatermark", "0.8")::get);
        assertEquals(32 * MIB, on.budgetBytes());
        assertEquals(0.5, on.heapFraction());
        assertEquals(0.6, on.softWatermark());
        assertEquals(0.8, on.admitWatermark());
        var dflt = TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", "true")::get);
        assertEquals(0, dflt.budgetBytes(), "bytes unset by default");
        assertEquals(0.25, dflt.heapFraction());
        assertEquals(0.75, dflt.softWatermark());
        assertEquals(0.90, dflt.admitWatermark());
        for (var bad : List.of(Map.of("sjws.swarm.stream.budget.bytes", "1e9"), Map.of("sjws.swarm.stream.budget.bytes", "0"),
                Map.of("sjws.swarm.stream.budget.bytes", "-5"), Map.of("sjws.swarm.stream.budget.heapFraction", "quarter"),
                Map.of("sjws.swarm.stream.budget.heapFraction", "0"), Map.of("sjws.swarm.stream.budget.heapFraction", "1.5"),
                Map.of("sjws.swarm.stream.budget.heapFraction", "NaN"), Map.of("sjws.swarm.stream.budget.softWatermark", "x"),
                Map.of("sjws.swarm.stream.budget.softWatermark", "0.95"), // above the admit watermark
                Map.of("sjws.swarm.stream.budget.admitWatermark", "1.01"),
                Map.of("sjws.swarm.stream.budget.softWatermark", "-0.1"))) {
            var all = new HashMap<>(bad);
            all.put("sjws.swarm.stream.credit.enabled", "true");
            assertThrows(OptionalModule.Refused.class, () -> TokenStreams.Config.from(all::get), bad.toString());
        }
        assertThrows(OptionalModule.Refused.class, () -> new TokenStreams.Config(1, 64, 256, true, -1, 0.25, 0.75, 0.9));
        assertEquals(-1, new TokenStreams.Config(1, 64, 256, false, -1, 9, 9, 9).budgetBytes(), "credit off: not checked");
    }

    @Test
    void theBudgetIsTheMinimumOfBytesAndTheHeapFractionAndStartupRefusesWhatCannotRunOneSession() {
        long heap = 1024 * MIB;
        var heapOnly = StreamBudget.resolve(cfg(0, 0.75, 0.9), heap);
        assertEquals(256 * MIB, heapOnly.budgetBytes());
        assertEquals("swarm: stream budget 256 MiB (0.25 of heap 1024 MiB), soft 0.75, admit 0.9", heapOnly.describe());
        var bytesBelow = StreamBudget.resolve(cfg(64 * MIB, 0.75, 0.9), heap);
        assertEquals(64 * MIB, bytesBelow.budgetBytes());
        assertTrue(bytesBelow.describe().startsWith("swarm: stream budget 64 MiB (sjws.swarm.stream.budget.bytes"),
                bytesBelow.describe());
        assertEquals(256 * MIB, StreamBudget.resolve(cfg(512 * MIB, 0.75, 0.9), heap).budgetBytes(), "min(bytes, fraction)");
        // an unbounded heap: bytes is required
        var e = assertThrows(OptionalModule.Refused.class, () -> StreamBudget.resolve(cfg(0, 0.75, 0.9), Long.MAX_VALUE));
        assertTrue(e.getMessage().contains("sjws.swarm.stream.budget.bytes"), e.getMessage());
        assertEquals(64 * MIB, StreamBudget.resolve(cfg(64 * MIB, 0.75, 0.9), Long.MAX_VALUE).budgetBytes());
        // below one session's floor plus tickets
        var floor = assertThrows(OptionalModule.Refused.class, () -> StreamBudget.resolve(cfg(StreamBudget.FLOOR - 1, 0.75,
                0.9), heap));
        assertTrue(floor.getMessage().contains(Long.toString(StreamBudget.FLOOR)), floor.getMessage());
        assertEquals(StreamBudget.FLOOR, StreamBudget.resolve(cfg(StreamBudget.FLOOR, 0.75, 0.9), heap).budgetBytes());
        assertThrows(OptionalModule.Refused.class, () -> StreamBudget.resolve(cfg(0, 0.75, 0.9), 16 * MIB), "a tiny heap");
        // the ceiling: MAX_SESSIONS × 13 636 638 B
        var big = StreamBudget.resolve(cfg(0, 0.75, 0.9), 1L << 50);
        assertEquals(StreamHttp.MAX_SESSIONS * 13_636_638L, big.budgetBytes());
        assertTrue(big.describe().contains("capped"), big.describe());
        // the floor's terms (#267 §3.4/§4.3)
        assertEquals(StreamHttp.RETAIN_BYTES + 2L * SwarmHttp.MAX_REQUEST + StreamBudget.LAST_TICKET
                + StreamBudget.TRANSPORT_WINDOW + SwarmHttp.MAX_REQUEST + StreamBudget.GAP_TICKET + StreamBudget.LAST_TICKET,
                StreamBudget.FLOOR);
        assertEquals(64 * 1024 + StreamChunk.HEADER, StreamBudget.LAST_TICKET);
        assertEquals(4096 + StreamChunk.HEADER, StreamBudget.MIN_SHARE);
    }

    @Test
    void theSwarmModuleRefusesToStartTheProcessWithAMalformedBudget() {
        System.setProperty("sjws.swarm.stream.credit.enabled", "true");
        System.setProperty("sjws.swarm.stream.budget.heapFraction", "lots");
        try {
            var other = Swarm.open(this.dir.resolve("module"), "m", 1, sjws_nettask.messaging.delivery.RetryPolicy.DEFAULT,
                    System::currentTimeMillis, 600_000);
            try {
                var e = assertThrows(OptionalModule.Refused.class, () -> new SwarmModule(other, SwarmGate.DENY_ALL, false,
                        true, null));
                assertTrue(e.getMessage().contains("sjws.swarm.stream.budget.heapFraction"), e.getMessage());
                System.setProperty("sjws.swarm.stream.budget.heapFraction", "0.25");
                System.setProperty("sjws.swarm.stream.budget.bytes", "1048576"); // below the floor
                var floor = assertThrows(OptionalModule.Refused.class, () -> new SwarmModule(other, SwarmGate.DENY_ALL,
                        false, true, null));
                assertTrue(floor.getMessage().contains("floor"), floor.getMessage());
            } finally {
                other.close();
            }
        } finally {
            System.clearProperty("sjws.swarm.stream.credit.enabled");
            System.clearProperty("sjws.swarm.stream.budget.heapFraction");
            System.clearProperty("sjws.swarm.stream.budget.bytes");
        }
    }

    @Test
    void withCreditOffThereIsNoBudget() throws Exception {
        on(TokenStreams.Config.DEFAULT);
        assertNull(this.swarm.tasks().streams().budget());
        assertNull(this.http.streams().credit());
        var r = submit("gen");
        assertEquals(201, r.status());
        var ls = SwarmHttpTest.leases(post("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]));
        var t = held(ls.get(0).id());
        var b = ByteBuffer.wrap(t.envelope().correlationId());
        var big = new StreamChunk(b.getLong(), b.getLong(), 0, Frame.LAST, data(StreamBudget.LAST_MAX + 1));
        var last = post("/swarm/v1/leases/" + ls.get(0).id() + ":chunk", big.toFrame().encode());
        assertEquals(204, last.status(), "credit off: LAST is unchanged, no cap");
    }

    // --- L3: LAST ≤ 64 KiB under credit (Q3), e2e

    @Test
    void underCreditALastOver64KiBIs413AndTheDataGoesAsChunksThenASmallLast() throws Exception {
        on(cfg(0, 0.75, 0.9));
        var s = stream("gen");
        var payload = new byte[200_000];
        new Random(7).nextBytes(payload);
        var big = chunk(s, 0, Frame.LAST, payload);
        assertEquals(413, big.status(), SwarmHttpTest.text(big));
        var t = SwarmHttpTest.text(big);
        assertTrue(t.contains("\"type\":\"https://sjws.dev/problems/credit\""), t);
        assertTrue(t.contains("\"reason\":\"last-too-large\""), t);
        assertNotNull(held(s.lease()), "the lease stays held: the worker splits and resends");
        assertEquals(0, st(s).maxSeq + 1, "nothing was written");
        var exact = chunk(s, 0, Frame.LAST, data(StreamBudget.LAST_MAX + 1));
        assertEquals(413, exact.status(), "LAST_MAX + 1 is refused");
        // the SDK's split (PR 7): ordinary chunks, then a small LAST
        int seq = 0;
        for (int off = 0; off < payload.length; off += 64 * 1024) {
            credit(chunk(s, seq++, 0, Arrays.copyOfRange(payload, off, Math.min(payload.length, off + 64 * 1024))));
        }
        assertEquals(204, chunk(s, seq, Frame.LAST, data(StreamBudget.LAST_MAX)).status(), "exactly LAST_MAX is accepted");
        var r = req("GET", s.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE);
        var in = ByteBuffer.wrap(StreamHttpTest.drainBytes(r));
        var got = new ByteArrayOutputStream();
        Frame f;
        StreamChunk lastChunk = null;
        while ((f = Frame.decode(in)) != null) {
            assertEquals(Frame.Type.TOKEN, f.type());
            var c = StreamChunk.decode(f.payload());
            if (c.last()) {
                lastChunk = c;
            } else {
                got.write(c.bytes());
            }
        }
        assertTrue(Arrays.equals(payload, got.toByteArray()), "the data arrives whole, in order");
        assertNotNull(lastChunk);
        assertEquals(StreamBudget.LAST_MAX, lastChunk.size());
    }

    // --- node-scope refusal (Q4)

    @Test
    void theNodeBudgetRefusesWith429ScopeNodeAndSessionRefusalsKeepTheirShape() throws Exception {
        on(cfg(16 * MIB, 1.0, 1.0)); // watermarks at 1: only the hard limit applies
        var streams = new ArrayList<Stream>();
        for (int i = 0; i < 4; i++) {
            streams.add(stream("p" + i));
        }
        long tickets = 4 * StreamBudget.LAST_TICKET;
        assertEquals(tickets, budget().charged(), "four LAST tickets");
        int admitted = 0;
        Response refused = null;
        outer:
        for (var s : streams) {
            for (int seq = 0; seq < 4; seq++) {
                var r = chunk(s, seq, MIB);
                if (r.status() != 200) {
                    refused = r;
                    break outer;
                }
                admitted++;
            }
        }
        assertEquals(15, admitted, "15 MiB + tickets fit, the 16th MiB does not");
        assertNode429(refused);
        assertEquals(1, budget().nodeRefusals());
        assertEquals(0, this.http.streams().credit().refusals(), "kept apart from the session refusals");
        assertTrue(budget().peakCharged() <= budget().budgetBytes() + SwarmHttp.MAX_REQUEST, "transient overlap only");
        // a session refusal (its own window is full) keeps its shape
        assertSession429(chunk(streams.get(0), 4, 10));
        // a retried, already-accepted chunk is never node-refused: the snapshot answers
        var dup = chunk(streams.get(3), 0, MIB);
        assertEquals(200, dup.status(), SwarmHttpTest.text(dup));
        assertEquals(1, budget().nodeRefusals());
        assertAccounting();
    }

    // --- fairness (Q6) and session admission

    @Test
    void aReaderlessSessionAtItsFairShareCannotBlockAnotherSessionsChunks() throws Exception {
        on(cfg(16 * MIB, 0.75, 0.9)); // soft 12 MiB
        var a = new ArrayList<Stream>();
        for (int i = 0; i < 3; i++) {
            a.add(stream("a" + i));
        }
        var b = stream("b");
        for (int i = 0; i < 2; i++) {
            for (int seq = 0; seq < 4; seq++) {
                credit(chunk(a.get(i), seq, MIB)); // 8 MiB, below the soft watermark
            }
        }
        for (int seq = 0; seq < 3; seq++) {
            credit(chunk(a.get(2), seq, MIB)); // 11 MiB + 4 tickets
        }
        assertEquals(3 * MIB, budget().fairShare(), "0.75 × 16 MiB / 4 sessions");
        assertNode429(chunk(a.get(2), 3, MIB)); // above soft: 3 MiB resident + 1 MiB > fair share
        credit(chunk(b, 0, MIB)); // b holds nothing: its chunk goes through
        credit(chunk(b, 1, MIB));
        assertTrue(budget().charged() > (long) (0.75 * 16 * MIB));
        assertAccounting();
    }

    @Test
    void sessionAdmissionAnswers503AtTheAdmitWatermarkAndLeavesNoState() throws Exception {
        on(cfg(16 * MIB, 0.5, 0.5)); // admit at 8 MiB
        var a = new ArrayList<Stream>();
        for (int i = 0; i < 2; i++) {
            a.add(stream("a" + i));
            for (int seq = 0; seq < 4; seq++) {
                var r = chunk(a.get(i), seq, MIB);
                if (r.status() != 200) {
                    break;
                }
            }
        }
        assertTrue(budget().charged() + StreamBudget.LAST_TICKET > 8 * MIB, "at the watermark: "
                + budget().charged());
        int cancels = this.swarm.tasks().streams().cancels().size();
        long charged = budget().charged();
        var r = submit("z");
        assertEquals(503, r.status(), SwarmHttpTest.text(r));
        assertEquals("1", r.headers().get("retry-after"));
        assertTrue(SwarmHttpTest.text(r).contains("stream memory budget"));
        assertEquals(1, budget().sessionAdmitRefusals());
        assertEquals(charged, budget().charged(), "the ticket was given back");
        assertEquals(cancels, this.swarm.tasks().streams().cancels().size(), "no CancelSet slot taken");
        assertEquals(2, this.http.streams().sessionCount());
        assertEquals(2, budget().openSessions());
        assertAccounting();
    }

    @Test
    void aSubmitThatFailsAfterAdmissionGivesTheTicketBack() throws Exception {
        on(cfg(0, 0.75, 0.9));
        this.http.streams().beforePlace = () -> {
            throw new IllegalStateException("boom");
        };
        assertThrows(IllegalStateException.class, () -> submit("gen"));
        this.http.streams().beforePlace = null;
        assertEquals(0, budget().charged(), "forget released the session's LAST ticket");
        assertEquals(0, budget().openSessions());
        assertConserved();
    }

    // --- CREDIT frame (Q7)

    @Test
    void theCreditFrameNeverAdvertisesMoreThanTheCheckAdmits() throws Exception {
        on(cfg(16 * MIB, 1.0, 1.0));
        var streams = new ArrayList<Stream>();
        for (int i = 0; i < 4; i++) {
            streams.add(stream("p" + i));
        }
        for (int i = 0; i < 3; i++) {
            for (int seq = 0; seq < 4; seq++) {
                credit(chunk(streams.get(i), seq, MIB));
            }
        }
        var s = streams.get(3);
        var c = credit(chunk(s, 0, MIB));
        c = credit(chunk(s, 1, MIB));
        c = credit(chunk(s, 2, MIB));
        long node = 16 * MIB - budget().charged();
        assertTrue(node < 4 * MIB - 3 * MIB, "the node is the binding dimension");
        assertEquals(node, c.bytes(), "min(session, node)");
        assertEquals(200, chunk(s, 3, c.bytes()).status(), "exactly the advertised bytes are admitted");
        var after = this.http.streams().credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt());
        assertEquals(0, after.bytes());
        assertNode429(chunk(s, 4, 1));
        assertAccounting();
    }

    @Test
    void advertisedNodeBytesAreAlwaysAdmittedAndOneMoreIsNot() {
        // property, single-threaded: random budget states; advertise() never exceeds what admit() takes
        var rnd = new Random(267);
        for (int round = 0; round < 2_000; round++) {
            double soft = 0.1 + 0.9 * rnd.nextDouble();
            var b = new StreamBudget(16 * MIB + rnd.nextInt(64) * MIB, "test", soft, Math.min(1.0, soft + 0.05));
            var sts = new ArrayList<StreamCredit.State>();
            var owner = new StreamCredit(256, 4 * MIB, b);
            int n = 1 + rnd.nextInt(8);
            for (int i = 0; i < n; i++) {
                assertTrue(b.admitSession());
                var st = new StreamCredit.State(owner);
                st.lastTicket.set(true);
                sts.add(st);
            }
            for (var st : sts) {
                b.resident(st, (long) (rnd.nextDouble() * b.budgetBytes() / n));
            }
            for (var st : sts) {
                long adv = b.advertise(st);
                if (adv > 0) {
                    long got = b.admit(st, adv, false, false);
                    assertTrue(got >= 0, "advertised " + adv + " must be admitted (round " + round + ")");
                    b.release(got);
                }
                long more = b.admit(st, adv + 1, false, false);
                assertTrue(more < 0, "the advertisement is tight: one byte more is refused (round " + round + ")");
            }
            assertEquals(0, b.budgetUnderflows());
        }
    }

    // --- pressure trim (Q5)

    @Test
    void underPressureReadChunksAreTrimmedToTheResumeTailAndUnreadOnesNever() throws Exception {
        on(cfg(16 * MIB, 0.6, 0.9)); // soft 9.6 MiB
        var a = stream("a");
        var u = stream("u");
        var x = stream("x");
        for (int seq = 0; seq < 4; seq++) {
            credit(chunk(a, seq, MIB));
            credit(chunk(u, seq, MIB));
        }
        for (int seq = 0; seq < 4; seq++) {
            var r = chunk(x, seq, 900 * KIB);
            if (r.status() != 200) {
                break;
            }
        }
        assertTrue(budget().pressured(), "charged " + budget().charged() + " is above the soft watermark");
        long before = budget().charged();
        assertEquals(4, readAvailable(a).size()); // the reader trims its read chunks on its next at()
        this.http.streams().pressureSweep(); // the reader has left: the sweep trims to its published position
        var sa = session(a);
        assertEquals(1, sa.retained.size(), "a resume tail of one 1 MiB chunk (≥ 64 KiB)");
        assertEquals(3, budget().pressureTrimChunks());
        assertEquals(3 * MIB, budget().pressureTrimBytes());
        assertEquals(before - 3 * MIB, budget().charged(), "the trim released its charge");
        assertEquals(4, session(u).retained.size(), "unread chunks are never trimmed");
        // resume inside the tail works; before it, the reader gets a gap (overflow)
        var inside = StreamHttpTest.drainBytesUntil(req("GET", a.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE,
                "last-event-id", a.attempt() + ".2"), 1);
        assertEquals(Frame.Type.TOKEN, Frame.decode(ByteBuffer.wrap(inside)).type());
        var past = StreamHttpTest.drainBytesUntil(req("GET", a.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE,
                "last-event-id", a.attempt() + ".0"), 1);
        var gap = Frame.decode(ByteBuffer.wrap(past));
        assertEquals(Frame.Type.ERROR, gap.type());
        assertTrue(new String(gap.payload(), java.nio.charset.StandardCharsets.UTF_8).endsWith("overflow"));
        assertAccounting();
    }

    @Test
    void theResumeTailKeepsAtLeastTheTransportWindowOfSmallChunks() throws Exception {
        on(cfg(16 * MIB, 0.75, 0.9));
        var fill = new ArrayList<Stream>();
        for (int i = 0; i < 3; i++) {
            var f = stream("f" + i);
            fill.add(f);
            for (int seq = 0; seq < 4; seq++) {
                chunk(f, seq, MIB);
            }
        }
        var s = stream("s");
        for (int seq = 0; seq < 100; seq++) {
            credit(chunk(s, seq, 2 * KIB));
        }
        assertTrue(budget().pressured());
        assertEquals(100, readAvailable(s).size());
        this.http.streams().pressureSweep();
        var se = session(s);
        long kept = se.retainedBytes;
        assertTrue(kept >= StreamBudget.TRANSPORT_WINDOW && kept < StreamBudget.TRANSPORT_WINDOW + 2 * KIB,
                "the tail covers the transport window: " + kept);
        assertAccounting();
    }

    // --- liveness at a full budget

    @Test
    void atAFullBudgetTheFillerLastAndANewerAttemptAreStillAdmitted() throws Exception {
        on(cfg(16 * MIB, 1.0, 1.0));
        var s = stream("s");
        credit(chunk(s, 0, 10));
        assertEquals(1, readAvailable(s).size()); // unreadIn == 0
        credit(chunk(s, 2, 100 * KIB)); // held 1: the gap ticket is booked
        assertEquals(1, budget().gapTicketsOutstanding());
        var x = stream("x");
        var y = stream("y");
        var z = stream("z");
        for (var f : List.of(x, y, z)) {
            for (int seq = 0; seq < 4; seq++) {
                if (chunk(f, seq, MIB).status() != 200) {
                    break;
                }
            }
        }
        var w = stream("w");
        for (int seq = 0; seq < 4; seq++) {
            if (chunk(w, seq, MIB).status() != 200) {
                break;
            }
        }
        assertNode429(chunk(w, 4, MIB));
        // the hole filler is exempt: admitted although the budget is full, and its ticket covered it
        long nodeRefusals = budget().nodeRefusals();
        credit(chunk(s, 1, MIB));
        assertEquals(2, st(s).maxSeq, "held flushed through the filler");
        assertEquals(0, budget().gapTicketsOutstanding());
        assertEquals(nodeRefusals, budget().nodeRefusals());
        assertTrue(budget().charged() <= budget().budgetBytes() + StreamBudget.GAP_TICKET, "the filler fit its ticket");
        // LAST is admitted (and converts the ticket)
        assertEquals(204, chunk(s, 3, Frame.LAST, data(StreamBudget.LAST_MAX)).status());
        assertFalse(st(s).lastTicket.get());
        // a newer attempt is admitted, and the old window is released in the same request
        long charged = budget().charged();
        long old = session(x).retainedBytes;
        assertTrue(old >= 3 * MIB);
        var n = takeover(x);
        assertNotNull(n);
        credit(chunk(n, 0, MIB));
        assertEquals(n.attempt(), session(n).attempt);
        assertEquals(charged - old + MIB, budget().charged(), "the RESET released the old window in the request");
        assertAccounting();
    }

    // --- release on every exit

    @Test
    void resetReleasesRetainedAndHeldAndTheTicketsFollow() throws Exception {
        on(cfg(0, 0.75, 0.9));
        var s = stream("s");
        credit(chunk(s, 0, 1000));
        credit(chunk(s, 2, 2000)); // held, gap ticket
        assertEquals(StreamBudget.LAST_TICKET + StreamBudget.GAP_TICKET + 3000, budget().charged());
        // an out-of-order LAST (delivered in process, so the task is not acked): held, its ticket converted
        var sink = session(s).sink;
        long lower = sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt(), 5, Frame.LAST, data(10)), st(s));
        sink.listener.run();
        st(s).lowerHeld(lower);
        assertFalse(st(s).lastTicket.get());
        assertEquals(StreamBudget.GAP_TICKET + 3010, budget().charged());
        var n = takeover(s);
        assertNotNull(n);
        credit(chunk(n, 0, 500)); // RESET: retained and held released, gap ticket released, LAST ticket rebooked
        assertTrue(st(n).lastTicket.get());
        assertEquals(StreamBudget.LAST_TICKET + 500, budget().charged());
        assertAccounting();
    }

    @Test
    void cancelThenSweepReleasesEverything() throws Exception {
        on(cfg(0, 0.75, 0.9));
        var s = stream("s");
        credit(chunk(s, 0, 1000));
        credit(chunk(s, 2, 2000));
        var key = s.session().substring("/swarm/v1/replies/".length());
        assertEquals(200, post("/swarm/v1/tasks/" + key + ":cancel", new byte[0]).status());
        assertAccounting(); // the cancel marker has 0 bytes: no charge
        expireAndSweep();
        assertConserved();
    }

    @Test
    void duplicatesConflictsOverflowsDropsAndStaleOffersChargeNothing() throws Exception {
        on(new TokenStreams.Config(1, 4, 8, true));
        var s = stream("s");
        credit(chunk(s, 0, 100));
        var sink = session(s).sink;
        var st = st(s);
        long base = budget().charged();
        // duplicate (in-process offer of an accepted chunk)
        assertEquals(0, sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt(), 0, 0, data(100)), st));
        assertEquals(base, budget().charged(), "duplicate");
        // stale attempt
        if (s.attempt() > 0) {
            sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt() - 1, 7, 0, data(100)), st);
            assertEquals(base, budget().charged(), "stale");
        }
        // reorder overflow (beyond the window of 4)
        long r = sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt(), 40, 0, data(100)), st);
        assertTrue(r < 0, "dropped");
        assertEquals(base, budget().charged(), "overflow");
        // conflict: a LAST at or below a chunk already seen
        credit(chunk(s, 1, 100));
        long b2 = budget().charged();
        sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt(), 0, Frame.LAST, data(5)), st);
        assertEquals(b2, budget().charged(), "conflict");
        session(s).drain();
        assertAccounting();
    }

    @Test
    void aLateOfferAfterForgetChargesNothing() throws Exception {
        on(cfg(0, 0.75, 0.9));
        var s = stream("s");
        credit(chunk(s, 0, 100));
        var session = session(s);
        var st = session.credit;
        var sink = session.sink;
        var listener = sink.listener;
        expireAndSweep(); // forget: closed, everything released
        assertTrue(st.closed);
        assertConserved();
        // an offer that already held the Sink reference lands now (in order and out of order), plus its drain
        for (long seq : new long[] {2, 1, 3}) {
            long lower = sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt(), seq, 0, data(1000)), st);
            listener.run();
            st.lowerHeld(lower & ~TokenStreams.Sink.DROPPED);
        }
        session.trimRead(st, 100);
        assertConserved();
        assertEquals(0, st.resident.get(), "nothing charged to the closed session");
    }

    @Test
    void retentionTrimReleasesItsCharge() throws Exception {
        on(new TokenStreams.Config(1, 2, 2, true, 0, 0.25, 0.75, 0.9));
        var s = stream("s");
        credit(chunk(s, 0, 100));
        credit(chunk(s, 1, 200));
        readAvailable(s);
        credit(chunk(s, 2, 300));
        credit(chunk(s, 3, 400)); // retention (sinkChunks + 3 = 5) is not reached yet
        readAvailable(s);
        for (int seq = 4; seq < 12; seq++) {
            credit(chunk(s, seq, 10));
            readAvailable(s);
        }
        assertEquals(5, session(s).retained.size());
        assertAccounting();
    }

    // --- conservation (property, seeded random)

    @Test
    void conservationHoldsOverRandomInterleavings() throws Exception {
        for (long seed = 1; seed <= 12; seed++) {
            conservationRun(seed);
        }
    }

    private void conservationRun(long seed) throws Exception {
        if (this.http != null) {
            this.swarm.close();
        }
        this.swarm = Swarm.open(this.dir.resolve("seed" + seed), "budget", 3,
                new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0), this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        on(new TokenStreams.Config(1, 8, 16, true, 16 * MIB, 0.25, 0.6, 0.9));
        this.resume.clear();
        var rnd = new Random(seed);
        var live = new ArrayList<Stream>();
        int pools = 0;
        for (int op = 0; op < 160; op++) {
            int k = rnd.nextInt(12);
            Stream s = live.isEmpty() ? null : live.get(rnd.nextInt(live.size()));
            if (s == null || k == 0) {
                if (live.size() < 6) {
                    var pool = "q" + (pools++);
                    var r = post("/swarm/v1/tasks/" + pool + ":stream?deadlineMs=" + (5_000 + rnd.nextInt(60_000)),
                            SwarmHttpTest.task("p").encode());
                    if (r.status() == 201) {
                        var c = claimFor(pool, r.headers().get("location"));
                        if (c != null) {
                            live.add(c);
                        }
                    } else {
                        assertEquals(503, r.status());
                    }
                }
            } else {
                var session = this.http.streams().credit().session(s.hi(), s.lo());
                long next = session == null ? 0 : session.credit.maxSeq + 1;
                long size = 1 + rnd.nextInt(rnd.nextBoolean() ? 4096 : 600 * 1024);
                switch (k) {
                    case 1, 2, 3 -> chunk(s, next, size); // in order
                    case 4 -> chunk(s, next + 1 + rnd.nextInt(5), size); // out of order
                    case 5 -> chunk(s, Math.max(0, next - 1 - rnd.nextInt(3)), size); // duplicate or earlier
                    case 6 -> {
                        var n = takeover(s);
                        live.remove(s);
                        if (n != null) {
                            live.add(n);
                            chunk(n, rnd.nextInt(2), size); // a newer attempt (RESET), maybe opening a gap
                        }
                    }
                    case 7 -> chunk(s, next + rnd.nextInt(2), Frame.LAST, data(rnd.nextInt(3) == 0
                            ? StreamBudget.LAST_MAX + 1 : rnd.nextInt(StreamBudget.LAST_MAX + 1)));
                    case 8 -> {
                        var key = s.session().substring("/swarm/v1/replies/".length());
                        post("/swarm/v1/tasks/" + key + ":cancel", new byte[0]);
                    }
                    case 9, 10 -> readAvailable(s);
                    default -> {
                        this.now.addAndGet(rnd.nextInt(40_000));
                        this.http.streams().periodicSweep(); // expiry and, under pressure, reader-less trims
                        this.http.streams().pressureSweep();
                        live.removeIf(x -> this.http.streams().credit().session(x.hi(), x.lo()) == null);
                    }
                }
            }
            assertAccounting();
        }
        expireAndSweep();
        assertEquals(0, this.http.streams().sessionCount());
        assertConserved();
    }

    // --- concurrency

    @Test
    void racingWorkersReadersAndTheSweepStayWithinTheBoundAndConserve() throws Exception {
        on(cfg(24 * MIB, 0.75, 0.9));
        int m = 6;
        int perSession = 2;
        long size = 64 * KIB;
        var streams = new ArrayList<Stream>();
        for (int i = 0; i < m; i++) {
            streams.add(stream("c" + i));
        }
        var stop = new AtomicBoolean();
        var go = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        var failures = new AtomicInteger();
        int chunks = 120;
        for (int i = 0; i < m; i++) {
            var s = streams.get(i);
            for (int w = 0; w < perSession; w++) {
                int lane = w;
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        for (int seq = lane; seq < chunks && !stop.get(); seq += perSession) {
                            while (!stop.get()) {
                                var r = chunk(s, seq, size);
                                if (r.status() == 200) {
                                    break;
                                }
                                if (r.status() != 429) {
                                    failures.incrementAndGet();
                                    return;
                                }
                                Thread.onSpinWait();
                            }
                        }
                    } catch (Throwable e) {
                        failures.incrementAndGet();
                    }
                }));
            }
        }
        var readers = new ArrayList<Thread>();
        for (int i = 0; i < m; i += 2) { // half the sessions have a reader; the rest pin memory until the sweep
            var s = streams.get(i);
            readers.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    var r = req("GET", s.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE);
                    StreamHttpTest.drainBytesUntil(r, chunks);
                } catch (Throwable e) {
                    failures.incrementAndGet();
                }
            }));
        }
        var sweeper = Thread.ofPlatform().start(() -> {
            try {
                go.await();
                while (!stop.get()) {
                    this.http.streams().pressureSweep();
                    Thread.sleep(1);
                }
            } catch (Throwable e) {
                failures.incrementAndGet();
            }
        });
        go.countDown();
        Thread.sleep(3_000);
        stop.set(true);
        for (var t : threads) {
            t.join(10_000);
        }
        this.now.addAndGet(10 * 3_600_000L); // readers still waiting end at the deadline
        for (var t : readers) {
            t.join(10_000);
            assertFalse(t.isAlive());
        }
        sweeper.join(10_000);
        assertEquals(0, failures.get());
        long concurrent = (long) m * perSession;
        long bound = budget().budgetBytes() + concurrent * (size + StreamBudget.GAP_TICKET) + m * StreamBudget.GAP_TICKET;
        assertTrue(budget().peakCharged() <= bound, "peak " + budget().peakCharged() + " ≤ " + bound);
        assertAccounting();
    }

    @Test
    void aGapTicketRaceBetweenBufferedAndFlushLeavesNoTicketBehind() throws Exception {
        on(cfg(0, 0.75, 0.9));
        var s = stream("s");
        for (int round = 0; round < 150; round++) {
            long base = 2L * round;
            var go = new CountDownLatch(1);
            var t1 = Thread.ofPlatform().start(() -> {
                await(go);
                chunk(s, base + 1, 100); // BUFFERED: held 0 → 1 (books)
            });
            var t2 = Thread.ofPlatform().start(() -> {
                await(go);
                chunk(s, base, 100); // the filler: flush → 0 (releases), or in order before t1
            });
            go.countDown();
            t1.join(10_000);
            t2.join(10_000);
            assertEquals(base + 1, st(s).maxSeq, "both landed (round " + round + ")");
            assertEquals(0, budget().gapTicketsOutstanding(), "no ticket left behind (round " + round + ")");
            readAvailable(s);
            assertAccounting();
        }
    }

    @Test
    void forgetRacingALateOfferNeverLeaksACharge() throws Exception {
        on(cfg(0, 0.75, 0.9));
        for (int round = 0; round < 60; round++) {
            var s = stream("r" + round);
            credit(chunk(s, 0, 100));
            var session = session(s);
            var st = session.credit;
            var sink = session.sink;
            var listener = sink.listener;
            var go = new CountDownLatch(1);
            var offer = Thread.ofPlatform().start(() -> {
                await(go);
                for (long seq : new long[] {2, 1, 4}) {
                    long lower = sink.offer(new StreamChunk(s.hi(), s.lo(), s.attempt(), seq, 0, data(500)), st);
                    listener.run();
                    st.lowerHeld(lower & ~TokenStreams.Sink.DROPPED);
                }
            });
            go.countDown();
            expireAndSweep();
            offer.join(10_000);
            assertEquals(0, this.http.streams().sessionCount());
            assertConserved();
        }
    }

    @Test
    void theBudgetChargeHookSeesEveryCheckedChunk() throws Exception {
        on(cfg(0, 0.75, 0.9));
        var s = stream("s");
        var calls = new AtomicInteger();
        budget().beforeBudgetCharge = calls::incrementAndGet;
        credit(chunk(s, 0, 10));
        credit(chunk(s, 1, 10));
        assertEquals(204, chunk(s, 2, Frame.LAST, new byte[0]).status());
        budget().beforeBudgetCharge = null;
        assertEquals(2, calls.get(), "non-LAST checked chunks only");
    }

    private static void await(CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
