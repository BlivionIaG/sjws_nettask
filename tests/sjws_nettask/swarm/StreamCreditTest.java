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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.swarm.wire.Credit;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * S2c PR 6 CREDIT (agent-swarm.md §5.2 <i>Backpressure</i>; #247 ruling, #248 r3–r5): the deterministic test list of the
 * spec. Every test ends with {@code reserved} back at its baseline (both fields 0, extracted unsigned) and
 * {@code creditReserveUnderflow == 0} ({@link #close}). Tests match the status, the problem {@code type}, the machine field
 * {@code "reason"} and {@code Retry-After}, never the human title or detail.
 */
class StreamCreditTest {

    static final long MIB = 1L << 20;

    @TempDir
    Path dir;
    Swarm swarm;
    SwarmHttp http;
    final AtomicLong now = new AtomicLong(1_000_000);
    final List<Stream> streams = new ArrayList<>();

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "credit", 3, new sjws_nettask.messaging.delivery.RetryPolicy(5, 0, 0),
                this.now::get, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
    }

    void on(int sinkChunks) {
        on(new TokenStreams.Config(1, Math.min(64, sinkChunks), sinkChunks, true)); // window ≤ sinkChunks (#257 R2)
    }

    void on(TokenStreams.Config config) {
        this.swarm.enableStreams(config);
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER);
    }

    @AfterEach
    void close() {
        try {
            if (this.http != null && this.http.streams() != null && credit() != null) {
                for (var s : this.streams) {
                    var session = credit().session(s.hi(), s.lo());
                    if (session != null) {
                        assertBaseline(session.credit);
                    }
                }
                assertEquals(0, credit().reserveUnderflows(), "creditReserveUnderflow");
            }
        } finally {
            this.swarm.close();
        }
    }

    // --- helpers

    record Stream(String session, String lease, long hi, long lo, long attempt) { }

    Response req(String method, String target, byte[] body, String... headers) {
        var fields = new java.util.HashMap<String, List<String>>();
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

    Stream stream() {
        var r = post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("prompt").encode());
        assertEquals(201, r.status(), SwarmHttpTest.text(r));
        var session = r.headers().get("location");
        return claimFor(session);
    }

    Stream claimFor(String session) {
        var ls = SwarmHttpTest.leases(post("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]));
        for (int i = 0; i < 4 && ls.isEmpty(); i++) { // a claim that applied (and acked) a cancel record hands out nothing
            ls = SwarmHttpTest.leases(post("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]));
        }
        assertEquals(1, ls.size());
        var t = held(ls.get(0).id());
        var b = ByteBuffer.wrap(t.envelope().correlationId());
        var s = new Stream(session, ls.get(0).id(), b.getLong(), b.getLong(), t.attempt() & StreamChunk.MAX_ATTEMPT);
        this.streams.add(s);
        return s;
    }

    @SuppressWarnings("unchecked")
    Map<String, Task> claimed() {
        try {
            var f = SwarmHttp.class.getDeclaredField("claimed");
            f.setAccessible(true);
            return (Map<String, Task>) f.get(this.http);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    Task held(String lease) {
        return claimed().get(lease);
    }

    long producerSeq() {
        try {
            var f = SwarmHttp.class.getDeclaredField("replySeq");
            f.setAccessible(true);
            return ((AtomicLong) f.get(this.http)).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    StreamCredit credit() {
        return this.http.streams().credit();
    }

    StreamHttp.Session session(Stream s) {
        var session = credit().session(s.hi(), s.lo());
        assertNotNull(session, "the session is on this node");
        return session;
    }

    StreamCredit.State st(Stream s) {
        return session(s).credit;
    }

    TokenStreams tokens() {
        return this.swarm.tasks().streams();
    }

    TokenStreams.Sink sink(Stream s) {
        return session(s).sink;
    }

    static byte[] data(int size) {
        var b = new byte[size];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    Response chunk(Stream s, long seq, int size) {
        return chunk(s, seq, 0, data(size));
    }

    Response chunk(Stream s, long seq, int flags, byte[] bytes) {
        return chunkOn(s.lease(), new StreamChunk(s.hi(), s.lo(), seq, flags, bytes));
    }

    Response chunkOn(String lease, StreamChunk c) {
        return post("/swarm/v1/leases/" + lease + ":chunk", c.toFrame().encode());
    }

    /** Asserts a 200 with one CREDIT frame and returns it. */
    static Credit credit(Response r) {
        assertEquals(200, r.status(), SwarmHttpTest.text(r));
        assertEquals(SwarmHttp.CONTENT_TYPE, r.headers().get("content-type"));
        var f = Frame.decode(ByteBuffer.wrap(SwarmHttpTest.bytes(r)));
        assertEquals(Frame.Type.CREDIT, f.type());
        assertEquals(0, f.flags());
        return Credit.decode(f.payload());
    }

    static void assert429(Response r) {
        assertEquals(429, r.status(), SwarmHttpTest.text(r));
        assertEquals("1", r.headers().get("retry-after"));
        assertEquals("application/problem+json", r.headers().get("content-type"));
        var t = SwarmHttpTest.text(r);
        assertTrue(t.contains("\"type\":\"https://sjws.dev/problems/credit\""), t);
        assertTrue(t.contains("\"reason\":\"credit\""), t);
        assertTrue(t.contains("\"status\":429"), t);
    }

    void assertBaseline(StreamCredit.State st) {
        long r = st.reserved.get();
        assertEquals(0, StreamCredit.chunksOf(r), "reservedChunks back to 0");
        assertEquals(0, StreamCredit.bytesOf(r), "reservedBytes back to 0");
        assertTrue(st.heldChunks.get() >= 0 && st.heldBytes.get() >= 0, "held never negative");
        assertEquals(0, credit().reserveUnderflows(), "creditReserveUnderflow");
    }

    /** Opens a binary reader, takes exactly {@code frames} frames and closes it (they must be available). */
    byte[] read(Stream s, int frames, String... headers) throws Exception {
        var h = new ArrayList<>(List.of("accept", SwarmHttp.CONTENT_TYPE));
        h.addAll(List.of(headers));
        var r = req("GET", s.session(), new byte[0], h.toArray(String[]::new));
        assertEquals(200, r.status());
        return StreamHttpTest.drainBytesUntil(r, frames);
    }

    /** unreadChunks as the check computes it, without reservations. */
    long unread(Stream s) {
        var c = credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt());
        return credit().sinkChunks - c.chunks();
    }

    long creditChunks(Stream s) {
        return credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt()).chunks();
    }

    /** Fills a fresh stream with {@code n} in-order chunks of {@code size} (seq 0..n-1), no reader. */
    Stream filled(int n, int size) {
        var s = stream();
        for (int i = 0; i < n; i++) {
            credit(chunk(s, i, size));
        }
        return s;
    }

    // --- refusal rule and liveness

    @Test
    void unreadZeroIsNeverRefusedAndCreditComesBackAfterTheReaderDrains() throws Exception {
        on(1);
        var s = stream();
        var c0 = credit(chunk(s, 0, (int) MIB - 64)); // unread 0: admitted whatever its size
        assertEquals(new Credit(s.hi(), s.lo(), s.attempt(), 0, 4 * MIB - (MIB - 64)), c0, "snapshot after the admit");
        assert429(chunk(s, 1, 1));
        assertEquals(1, credit().refusals());
        read(s, 1); // the reader takes chunk 0: unread is 0 again
        assertEquals(0, unread(s));
        var c1 = credit(chunk(s, 1, (int) MIB - 64));
        assertEquals(0, c1.chunks());
        assertBaseline(st(s));
    }

    @Test
    void aSlowReaderGets429AndNeverAnOverflowGap() throws Exception {
        on(4);
        var s = stream();
        int sent = 0;
        int refused = 0;
        int read = 0;
        var got = new java.io.ByteArrayOutputStream();
        while (sent < 20) { // in flight = 1: deterministic
            var r = chunk(s, sent, 0, ("c" + sent + ";").getBytes(StandardCharsets.UTF_8));
            if (r.status() == 429) {
                assert429(r);
                refused++;
                got.write(read(s, 2, "last-event-id", s.attempt() + "." + (read - 1))); // the reader takes two
                read += 2;
                continue;
            }
            credit(r);
            sent++;
        }
        assertTrue(refused >= 4, "the reader was slower than the worker: " + refused);
        got.write(read(s, sent - read, "last-event-id", s.attempt() + "." + (read - 1)));
        read = sent;
        // LAST is never refused (so it can still overflow a full window: drop-and-gap stays the backstop there)
        assertEquals(204, chunk(s, 20, Frame.LAST, new byte[0]).status());
        got.write(StreamHttpTest.drainBytes(req("GET", s.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE,
                "last-event-id", s.attempt() + "." + (read - 1))));
        var in = ByteBuffer.wrap(got.toByteArray());
        var text = new StringBuilder();
        Frame f;
        while ((f = Frame.decode(in)) != null) {
            var fr = f;
            assertEquals(Frame.Type.TOKEN, fr.type(), () -> "no gap (ERROR) frame: " + text + " | "
                    + new String(fr.payload(), StandardCharsets.UTF_8));
            text.append(new String(StreamChunk.decode(f.payload()).bytes(), StandardCharsets.UTF_8));
        }
        var want = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            want.append("c").append(i).append(';');
        }
        assertEquals(want.toString(), text.toString());
        assertFalse(sink(s).dropped());
        assertEquals(0, tokens().sinkDrops());
    }

    @Test
    void theHoleFillerIsAdmittedAtZeroCreditWithTheReaderDrainedAndOvershootsByAtMostOne() throws Exception {
        // credit reaches zero through held chunks in the bytes dimension: held chunks are bounded by window − 1 < sinkChunks
        on(256);
        var s = stream();
        credit(chunk(s, 0, 10));
        read(s, 1); // unreadIn == 0
        for (int seq = 2; seq <= 5; seq++) {
            credit(chunk(s, seq, (int) MIB)); // held 1..4, 4 MiB
        }
        assertEquals(4, st(s).heldChunks.get());
        assertEquals(4 * MIB, st(s).heldBytes.get());
        var zero = credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt());
        assertEquals(0, zero.bytes(), "credit is at zero through held chunks");
        assert429(chunk(s, 6, 1));
        var c = credit(chunk(s, 1, (int) MIB)); // maxSeq + 1, held > 0, unreadIn == 0: exempt
        assertEquals(0, c.bytes());
        assertEquals(0, st(s).heldChunks.get(), "flushed");
        assertEquals(0, st(s).heldBytes.get());
        assertEquals(5, st(s).maxSeq);
        long unreadBytes = st(s).appendedBytes - st(s).readBytes;
        assertTrue(unreadBytes <= 4 * MIB + MIB, "overshoot at most one chunk: " + unreadBytes);
        assertBaseline(st(s));
    }
    @Test
    void severalHolesTheFirstFillerIsExemptTheSecondIsRefusedUntilTheReaderCatchesUp() throws Exception {
        // #248 r2 (i), setup per r3 T1: credit at zero through held chunks, the reader caught up, then paused
        on(256);
        var s = stream();
        credit(chunk(s, 0, 10));
        read(s, 1);
        for (long seq : new long[] {2, 4, 5, 6}) {
            credit(chunk(s, seq, (int) MIB));
        }
        assertEquals(4, st(s).heldChunks.get());
        assert429(chunk(s, 7, 1));
        credit(chunk(s, 1, 10)); // first filler: exempt, flushes 1, 2
        assertEquals(2, st(s).maxSeq);
        assertEquals(3, st(s).heldChunks.get());
        long before = credit().refusals();
        assert429(chunk(s, 3, 10)); // second filler: unreadIn = 2 > 0 after the flush, so not exempt
        assertEquals(before + 1, credit().refusals());
        long unreadBytes = st(s).appendedBytes - st(s).readBytes + st(s).heldBytes.get();
        assertTrue(unreadBytes <= 4 * MIB + MIB, "overshoot at most one chunk: " + unreadBytes);
        // the reader catches up (1, 2): the second filler becomes exempt
        read(s, 2, "last-event-id", s.attempt() + ".0");
        credit(chunk(s, 3, 10));
        assertEquals(6, st(s).maxSeq);
        assertEquals(0, st(s).heldChunks.get());
        assertBaseline(st(s));
    }
    @Test
    void withNoReaderEverAttachedEvenTheFirstFillerIsRefusedUntilTheReaderDrains() throws Exception {
        // #248 r2 (ii)
        on(256);
        var s = stream();
        credit(chunk(s, 0, (int) MIB)); // maxSeq 0, never read
        for (long seq = 2; seq <= 4; seq++) {
            credit(chunk(s, seq, (int) MIB));
        }
        assertEquals(0, credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt()).bytes());
        assert429(chunk(s, 1, 10)); // unreadIn = maxSeq + 1 = 1 > 0: not exempt
        read(s, 1);
        credit(chunk(s, 1, 10)); // the reader drained: admitted at zero credit
        assertEquals(4, st(s).maxSeq);
        assertBaseline(st(s));
    }
    @Test
    void aReaderThatResumedAheadIsNotDeadlocked() throws Exception {
        // #248 r2 (iii): ?from= beyond maxSeq + 1, held chunks fill the credit; readNext == maxSeq + 1 would deadlock
        on(256);
        var s = stream();
        credit(chunk(s, 0, 10));
        var r = req("GET", s.session() + "?from=3", new byte[0], "accept", SwarmHttp.CONTENT_TYPE);
        assertEquals(200, r.status());
        try (var src = ((sjws_nettask.core.Body.Stream) r.body()).source()) {
            assertEquals(3, st(s).readNext, "published on attach");
            for (long seq : new long[] {2, 4, 5, 6}) {
                credit(chunk(s, seq, (int) MIB));
            }
            assertEquals(0, credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt()).bytes());
            assert429(chunk(s, 7, 1));
            credit(chunk(s, 1, 10)); // exempt: unreadIn = max(0, 0 − 3 + 1) = 0
            assertEquals(2, st(s).maxSeq);
            credit(chunk(s, 3, 10)); // unreadIn = 0 still (the reader waits for 3): exempt again
            assertEquals(6, st(s).maxSeq);
            var b = src.next(); // the reader gets chunk 3, its resume point
            assertEquals(3, StreamChunk.decode(Frame.decode(b).payload()).chunkSeq());
        }
        assertBaseline(st(s));
    }
    @Test
    void aRewindingReaderGetsCreditFromTheStoredOffsetNotMore() throws Exception {
        on(8);
        var s = stream();
        int[] sizes = {100, 200, 300, 400};
        for (int i = 0; i < sizes.length; i++) {
            credit(chunk(s, i, sizes[i]));
        }
        read(s, 4);
        assertEquals(1000, st(s).readBytes);
        assertEquals(8, creditChunks(s));
        // rewind to 1: 1..3 are unread again, with exactly their bytes
        var r = req("GET", s.session() + "?from=1", new byte[0], "accept", SwarmHttp.CONTENT_TYPE);
        try (var src = ((sjws_nettask.core.Body.Stream) r.body()).source()) {
            assertEquals(1, st(s).readNext);
            assertEquals(100, st(s).readBytes, "the stored offset of chunk 1");
            var c = credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt());
            assertEquals(8 - 3, c.chunks());
            assertEquals(4 * MIB - 900, c.bytes());
            src.next(); // chunk 1 sent: readBytes = offset(1) + size(1)
            assertEquals(300, st(s).readBytes);
            assertEquals(2, st(s).readNext);
        }
        // SSE readers publish too
        var sse = StreamHttpTest.drainBytesUntil(req("GET", s.session() + "?from=2", new byte[0]), 3);
        assertTrue(new String(sse, StandardCharsets.UTF_8).contains("id: " + s.attempt() + ".3"));
        assertEquals(4, st(s).readNext);
        assertEquals(1000, st(s).readBytes);
        assertBaseline(st(s));
    }

    /** A takeover: the first lease is lost and the task redelivered (higher attempt); returns the new stream view. */
    Stream takeover(Stream s) {
        assertEquals(204, post("/swarm/v1/leases/" + s.lease() + ":nack", new byte[0]).status());
        var n = claimFor(s.session());
        assertTrue(n.attempt() > s.attempt());
        return n;
    }

    @Test
    void aReaderStillPublishingAnOldAttemptAfterResetCountsAsNothingRead() throws Exception {
        on(4);
        var s = stream();
        credit(chunk(s, 0, 50));
        credit(chunk(s, 1, 50));
        read(s, 2);
        assertEquals(2, st(s).readNext);
        var n = takeover(s);
        var c = credit(chunk(n, 0, 70)); // a newer attempt: RESET
        assertEquals(n.attempt(), c.attempt(), "the lease's attempt");
        assertEquals(s.attempt(), st(n).readAttempt, "the reader has not seen the RESET yet");
        // with the old readNext (2) the new chunk 0 would count as read; the rule treats the position as 0
        assertEquals(4 - 1, c.chunks());
        assertEquals(4 * MIB - 70, c.bytes());
        assertBaseline(st(n));
    }

    @Test
    void resetRestoresTheWindowAndTheFirstChunkOfANewAttemptIsNotRefused() throws Exception {
        on(2);
        var s = filled(2, 10);
        assert429(chunk(s, 2, 10));
        var n = takeover(s);
        var writes = new AtomicInteger();
        var reservedInAdmit = new AtomicLong(-1);
        tokens().beforeWrite = () -> {
            writes.incrementAndGet();
            reservedInAdmit.set(st(n).reserved.get());
        };
        var c = credit(chunk(n, 0, 10)); // a newer attempt is never refused, and it reserves too
        assertEquals(1, StreamCredit.chunksOf(reservedInAdmit.get()), "the RESET chunk held a reservation in its admit");
        assertEquals(10, StreamCredit.bytesOf(reservedInAdmit.get()));
        assertEquals(1, c.chunks(), "the window is back: sinkChunks − the new chunk");
        assertEquals(n.attempt(), session(n).attempt);
        credit(chunk(n, 1, 10));
        assert429(chunk(n, 2, 10));
        assertBaseline(st(n));
    }

    @Test
    void a429LeavesNoTrace() throws Exception {
        on(2);
        var s = filled(2, 10);
        var writes = new AtomicInteger();
        tokens().beforeWrite = writes::incrementAndGet;
        long seq = producerSeq();
        int fences = tokens().fenceEntries();
        long maxSeq = st(s).maxSeq;
        var r = chunk(s, 2, 10);
        assert429(r);
        assertEquals(seq, producerSeq(), "no producer seq consumed");
        assertEquals(0, writes.get(), "no fence move, no ledger record");
        assertEquals(fences, tokens().fenceEntries());
        assertEquals(maxSeq, st(s).maxSeq);
        assertEquals(1, credit().refusals());
        assertBaseline(st(s));
        // the worker retries the same (attempt, chunkSeq) once the reader has read
        read(s, 1);
        credit(chunk(s, 2, 10));
        assertEquals(seq + 1, producerSeq());
        assertEquals(1, writes.get());
    }

    // --- the per-session reservation (#248 r3/r4)

    @Test
    void nConcurrentChunksAtCreditOneAdmitExactlyOne() throws Exception {
        on(64); // window 64 (≤ sinkChunks, #257 R2): every concurrent seq lies inside the reorder window
        for (int round = 0; round < 10; round++) {
            var s = filled(63, 10); // credit 1, no reader
            assertEquals(1, creditChunks(s));
            var writes = new AtomicInteger();
            tokens().beforeWrite = writes::incrementAndGet;
            long seq = producerSeq();
            long refusals = credit().refusals();
            int n = 8 + round;
            var go = new CountDownLatch(1);
            var statuses = new AtomicInteger[3]; // 200, 429, other
            for (int i = 0; i < 3; i++) {
                statuses[i] = new AtomicInteger();
            }
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < n; i++) {
                long chunkSeq = 63 + i; // distinct seqs of the current attempt
                threads.add(Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    var r = chunk(s, chunkSeq, 10);
                    statuses[r.status() == 200 ? 0 : r.status() == 429 ? 1 : 2].incrementAndGet();
                }));
            }
            go.countDown();
            for (var t : threads) {
                t.join(10_000);
            }
            assertEquals(1, statuses[0].get(), "exactly one admitted (round " + round + ")");
            assertEquals(n - 1, statuses[1].get(), "the others get 429");
            assertEquals(0, statuses[2].get());
            assertEquals(seq + 1, producerSeq(), "the producer seq advanced by exactly one");
            assertEquals(1, writes.get(), "one ledger record");
            assertEquals(refusals + n - 1, credit().refusals());
            assertEquals(0, creditChunks(s));
            assertBaseline(st(s));
            tokens().beforeWrite = null;
        }
    }

    @Test
    void anAbaInterleavingNeverAdmitsBoth() throws Exception {
        // #248 r4 B1: A reserves, publishes and releases between B's read of reserved and B's CAS
        on(8);
        var s = filled(7, 10); // credit 1
        var fired = new AtomicBoolean();
        var a = new AtomicReference<Response>();
        var bRead = new AtomicLong(-1);
        credit().beforeReserve = () -> {
            if (!fired.compareAndSet(false, true)) {
                return; // A's own pass through the hook
            }
            bRead.set(st(s).reserved.get());
            var t = Thread.ofPlatform().start(() -> a.set(chunk(s, 8, 10)));
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            assertEquals(bRead.get(), st(s).reserved.get(), "reserved is back at the value B read: B's CAS will succeed");
        };
        var b = chunk(s, 7, 10);
        credit(a.get());
        assert429(b); // B's validation after the CAS sees A's published chunk
        assertEquals(1, credit().refusals());
        assertEquals(8, st(s).maxSeq + 1 + st(s).heldChunks.get(), "exactly one of the two was published");
        assertBaseline(st(s));
    }

    @Test
    void reservedReturnsToBaselineAfterEveryOutcome() throws Exception {
        on(new TokenStreams.Config(1, 4, 8, true));
        var s = stream();
        var st = st(s);
        // a published admit
        credit(chunk(s, 0, 10));
        assertBaseline(st);
        // a held chunk
        credit(chunk(s, 2, 10));
        assertEquals(1, st.heldChunks.get());
        assertBaseline(st);
        // a dedup-dropped duplicate (credit left, so it is admitted and dropped by the reorder dedup)
        credit(chunk(s, 2, 10));
        credit(chunk(s, 0, 10));
        assertEquals(1, st.heldChunks.get());
        assertBaseline(st);
        // a sink drop (reorder overflow: beyond the window of 4)
        credit(chunk(s, 9, 10));
        assertTrue(sink(s).dropped());
        assertBaseline(st);
        // WRONG_STREAM never reaches the check: the admit refuses it (400) as before
        assertEquals(400, chunkOn(s.lease(), new StreamChunk(s.hi() + 1, s.lo(), 1, 0, data(1))).status());
        assertBaseline(st);
        // an exception before the publish (in the fence lock, before deliverLocal)
        tokens().beforeWrite = () -> {
            throw new IllegalStateException("boom before");
        };
        assertThrows(IllegalStateException.class, () -> chunk(s, 1, 10));
        assertBaseline(st);
        tokens().beforeWrite = null;
        // an exception thrown after the publish (the listener drained, then failed)
        var session = session(s);
        sink(s).onOffer(() -> {
            session.drain();
            throw new IllegalStateException("boom after");
        });
        assertThrows(IllegalStateException.class, () -> chunk(s, 1, 10));
        sink(s).onOffer(session::drain);
        assertEquals(0, st.heldChunks.get(), "flushed and lowered even though the listener threw");
        assertBaseline(st);
        // a stale attempt at the sink: a newer attempt reached the sink (e.g. through the ledger) before the session
        var s2 = stream();
        var st2 = st(s2);
        credit(chunk(s2, 0, 10));
        sink(s2).offer(new StreamChunk(s2.hi(), s2.lo(), s2.attempt() + 5, 0, 0, data(1)), st2);
        long stale = sink(s2).staleChunks();
        credit(chunk(s2, 1, 10));
        assertEquals(stale + 1, sink(s2).staleChunks());
        assertBaseline(st2);
        // AFTER_LAST: the sink already has LAST at 2
        var s3 = stream();
        var st3 = st(s3);
        credit(chunk(s3, 0, 10));
        sink(s3).offer(new StreamChunk(s3.hi(), s3.lo(), s3.attempt(), 2, Frame.LAST, new byte[0]), st3);
        credit(chunk(s3, 3, 10));
        assertBaseline(st3);
        // CONFLICT is a LAST-only outcome, and LAST never reserves
        assertEquals(204, chunk(s3, 1, Frame.LAST, new byte[0]).status());
        assertTrue(sink(s3).conflicts() >= 1);
        assertBaseline(st3);
        // no sink (closed under a live session)
        var s4 = stream();
        var st4 = st(s4);
        credit(chunk(s4, 0, 10));
        sink(s4).close();
        credit(chunk(s4, 1, 10));
        assertBaseline(st4);
        // a cancelled session's drain discards the chunk
        var s5 = stream();
        var st5 = st(s5);
        credit(chunk(s5, 0, 10));
        assertTrue(StreamHttp.markCancelled(session(s5)));
        credit(chunk(s5, 1, 10));
        assertEquals(0, st5.maxSeq);
        assertBaseline(st5);
        // admit 409 cancelled (credit left, so the admit's own cancel check answers)
        var s6 = stream();
        var st6 = st(s6);
        credit(chunk(s6, 0, 10));
        assertEquals(200, post("/swarm/v1/tasks/" + s6.session().substring("/swarm/v1/replies/".length()) + ":cancel",
                new byte[0]).status());
        var r6 = chunk(s6, 1, 10);
        assertEquals(409, r6.status());
        assertTrue(SwarmHttpTest.text(r6).contains("\"reason\":\"cancelled\""));
        assertBaseline(st6);
        // admit 409 STALE_ATTEMPT for the session's own attempt (the fence was settled)
        var s7 = stream();
        var st7 = st(s7);
        credit(chunk(s7, 0, 10));
        tokens().settle(held(s7.lease()));
        var r7 = chunk(s7, 1, 10);
        assertEquals(409, r7.status());
        assertTrue(SwarmHttpTest.text(r7).contains("STALE_ATTEMPT"), SwarmHttpTest.text(r7));
        assertBaseline(st7);
        assertEquals(0, credit().reserveUnderflows());
    }

    @Test
    void aFlushLowersHeldOnlyAfterTheDrainHasAdvancedMaxSeq() throws Exception {
        // #248 r4 B3; today's Reorder lowers its own count inside offer, the published one moves after the drain
        on(8);
        var s = stream();
        var st = st(s);
        credit(chunk(s, 0, 10));
        credit(chunk(s, 2, 20));
        credit(chunk(s, 3, 30));
        assertEquals(2, st.heldChunks.get());
        assertEquals(50, st.heldBytes.get());
        var seen = new ArrayList<long[]>();
        credit().beforeHeldLowered = () -> seen.add(new long[] {st.maxSeq, st.heldChunks.get(), st.heldBytes.get(),
            st.appendedBytes});
        credit(chunk(s, 1, 10));
        credit().beforeHeldLowered = null;
        assertEquals(1, seen.size());
        assertEquals(3, seen.get(0)[0], "maxSeq already advanced past the flushed chunks");
        assertEquals(2, seen.get(0)[1], "held not yet lowered: counted twice for an instant, never zero times");
        assertEquals(50, seen.get(0)[2]);
        assertEquals(70, seen.get(0)[3]);
        assertEquals(0, st.heldChunks.get());
        assertEquals(0, st.heldBytes.get());
        assertBaseline(st);
    }

    @Test
    void anExemptFillerReservesAndReleasesToo() throws Exception {
        on(256);
        var s = stream();
        credit(chunk(s, 0, 10));
        read(s, 1);
        for (long seq = 2; seq <= 5; seq++) {
            credit(chunk(s, seq, (int) MIB));
        }
        assert429(chunk(s, 6, 1));
        var inAdmit = new AtomicLong(-1);
        tokens().beforeWrite = () -> inAdmit.set(st(s).reserved.get());
        credit(chunk(s, 1, 10));
        assertEquals(1, StreamCredit.chunksOf(inAdmit.get()));
        assertEquals(10, StreamCredit.bytesOf(inAdmit.get()));
        assertBaseline(st(s));
    }
    @Test
    void heldCountersSurviveAResetRacingAnOutOfOrderOffer() throws Exception {
        // #248 r5 C1: the RESET subtracts the discarded held amount captured under the sink lock, never stores 0
        on(8);
        var s = stream();
        credit(chunk(s, 0, 10));
        credit(chunk(s, 2, 10));
        credit(chunk(s, 3, 10));
        var st = st(s);
        assertEquals(2, st.heldChunks.get());
        var n = takeover(s);
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var armed = new AtomicBoolean(true);
        credit().beforeHeldLowered = () -> {
            if (armed.compareAndSet(true, false)) {
                paused.countDown();
                try {
                    assertTrue(resume.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        var x = new AtomicReference<Response>();
        var tx = Thread.ofPlatform().start(() -> x.set(chunk(n, 0, 10))); // the RESET offer
        assertTrue(paused.await(10, TimeUnit.SECONDS));
        // meanwhile the shard reader offers an out-of-order chunk of the new attempt (sink lock only)
        long d = sink(n).offer(new StreamChunk(n.hi(), n.lo(), n.attempt(), 5, 0, data(7)), st);
        assertEquals(0, d, "BUFFERED: nothing to lower");
        assertEquals(3, st.heldChunks.get(), "2 discarded (not yet subtracted) + 1 new");
        resume.countDown();
        tx.join(10_000);
        credit(x.get());
        assertEquals(1, st.heldChunks.get(), "a store of 0 would have wiped the new chunk out");
        assertEquals(7, st.heldBytes.get());
        for (long seq = 1; seq <= 4; seq++) {
            credit(chunk(n, seq, 10));
            assertTrue(st.heldChunks.get() >= 0 && st.heldBytes.get() >= 0);
        }
        assertEquals(0, st.heldChunks.get(), "back to 0 once the hole is filled");
        assertEquals(0, st.heldBytes.get());
        assertEquals(5, st.maxSeq);
        assertBaseline(st);
    }

    @Test
    void theReleaseNeverGoesBelowZeroAndCountsTheUnderflow() {
        var cr = new StreamCredit(8, 4 * MIB);
        var st = new StreamCredit.State(cr);
        cr.release(st, 10); // a double release: clamped, counted
        assertEquals(0, st.reserved.get());
        assertEquals(1, cr.reserveUnderflows());
        st.reserved.set(StreamCredit.pack(1, 5));
        cr.release(st, 10); // the bytes field is short: clamped at 0, chunks released
        assertEquals(0, StreamCredit.chunksOf(st.reserved.get()));
        assertEquals(0, StreamCredit.bytesOf(st.reserved.get()));
        assertEquals(2, cr.reserveUnderflows());
        // fields are extracted unsigned; the packed word is negative from 2^23 chunks on
        long big = StreamCredit.pack(1L << 23, StreamCredit.BYTES_MASK);
        assertTrue(big < 0);
        assertEquals(1L << 23, StreamCredit.chunksOf(big));
        assertEquals(StreamCredit.BYTES_MASK, StreamCredit.bytesOf(big));
        st.reserved.set(StreamCredit.pack(StreamCredit.CHUNKS_MAX, 0));
        var session = new StreamHttp.Session("a", new byte[16], null, Long.MAX_VALUE, 8, 4 * MIB);
        assertFalse(cr.reserve(session, st, 0, 0, 1), "a full chunks field fails closed");
        st.reserved.set(StreamCredit.pack(0, StreamCredit.BYTES_MASK));
        assertFalse(cr.reserve(session, st, 0, 0, 1), "a full bytes field fails closed");
    }

    // --- caps, answers and precedence

    @Test
    void theBytesCapRefusesWithChunksOver16KiB() throws Exception {
        on(256);
        var s = stream();
        for (int i = 0; i < 4; i++) {
            var c = credit(chunk(s, i, 1_000_000));
            assertEquals(256 - (i + 1), c.chunks());
            assertEquals(4 * MIB - 1_000_000L * (i + 1), c.bytes());
        }
        assert429(chunk(s, 4, 1_000_000)); // 252 chunks of credit, but 194 304 bytes
        credit(chunk(s, 4, 17 * 1024)); // a smaller chunk still fits
        assertBaseline(st(s));
    }

    @Test
    void lastChunksAreNeverRefused() throws Exception {
        on(1);
        int[] ends = {Frame.LAST, Frame.LAST | Frame.CANCELLED, Frame.LAST | Frame.ERROR};
        for (int flags : ends) {
            var s = filled(1, 10);
            assert429(chunk(s, 1, 10));
            var r = chunk(s, 1, flags, new byte[0]);
            assertEquals(204, r.status(), "LAST keeps today's answer, no CREDIT frame");
            assertEquals(0, SwarmHttpTest.bytes(r).length);
        }
    }

    @Test
    void cancelledIsAnsweredBeforeCredit() throws Exception {
        on(1);
        var s = filled(1, 10);
        assert429(chunk(s, 1, 10));
        long refusals = credit().refusals();
        assertEquals(200, post("/swarm/v1/tasks/" + s.session().substring("/swarm/v1/replies/".length()) + ":cancel",
                new byte[0]).status());
        var r = chunk(s, 1, 10);
        assertEquals(409, r.status(), "409 cancelled before 429");
        assertTrue(SwarmHttpTest.text(r).contains("\"reason\":\"cancelled\""));
        assertEquals(refusals, credit().refusals());
    }

    @Test
    void aStaleAttemptGets409Not429() throws Exception {
        on(2);
        var s = stream();
        var zombie = held(s.lease());
        credit(chunk(s, 0, 10));
        var n = takeover(s);
        credit(chunk(n, 0, 10)); // RESET
        credit(chunk(n, 1, 10)); // the new attempt's credit is now 0
        assert429(chunk(n, 2, 10));
        claimed().put(s.lease(), zombie); // the old worker still believes it holds its lease
        var writes = new AtomicInteger();
        tokens().beforeWrite = writes::incrementAndGet;
        var r = chunk(s, 1, 10);
        assertEquals(409, r.status(), SwarmHttpTest.text(r));
        assertTrue(SwarmHttpTest.text(r).contains("STALE_ATTEMPT"), SwarmHttpTest.text(r));
        assertEquals(0, writes.get());
        assertBaseline(st(n));
    }

    @Test
    void anAlreadyAcceptedRetryAtZeroCreditGets200WithoutAnAdmit() throws Exception {
        // #248 r5 C2 (optional, taken): chunkSeq <= maxSeq of the current attempt
        on(2);
        var s = filled(2, 10);
        var writes = new AtomicInteger();
        tokens().beforeWrite = writes::incrementAndGet;
        long seq = producerSeq();
        var c = credit(chunk(s, 1, 10));
        assertEquals(0, c.chunks());
        assertEquals(0, writes.get(), "no admit");
        assertEquals(seq, producerSeq());
        assertEquals(0, credit().refusals());
        assert429(chunk(s, 3, 10)); // a chunk ahead of maxSeq + 1 is not a retry: 429 as specified
    }

    @Test
    void aCrossNodePairGets204WithNoCreditAndNoRefusal() throws Exception {
        on(2);
        var b = Swarm.sharing(this.swarm, "nodeb", 7, ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
        try {
            b.enableStreams(new TokenStreams.Config(1, 2, 2, true)); // window ≤ sinkChunks (#257 R2)
            var bHttp = new SwarmHttp(b, 8, StreamHttpTest.BY_HEADER);
            java.util.function.BiFunction<String, byte[], Response> onB = (target, body) -> bHttp.handle(new Request("POST",
                    target, RequestHeaders.of(Map.of("x-agent", List.of("w"))), null, false, null, SwarmHttpTest.body(body)));
            var r = post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("prompt").encode());
            assertEquals(201, r.status());
            var ls = SwarmHttpTest.leases(onB.apply("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]));
            assertEquals(1, ls.size());
            var lease = ls.get(0).id();
            var f = SwarmHttp.class.getDeclaredField("claimed");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var t = ((Map<String, Task>) f.get(bHttp)).get(lease);
            var corr = ByteBuffer.wrap(t.envelope().correlationId());
            long hi = corr.getLong();
            long lo = corr.getLong();
            for (int i = 0; i < 6; i++) { // more than A's window of 2: never refused on B
                var rb = onB.apply("/swarm/v1/leases/" + lease + ":chunk", new StreamChunk(hi, lo, i, 0, data(10)).toFrame()
                        .encode());
                assertEquals(204, rb.status());
                assertEquals(0, SwarmHttpTest.bytes(rb).length, "no CREDIT frame");
            }
            assertEquals(6, bHttp.streams().credit().unknown());
            assertEquals(0, bHttp.streams().credit().refusals());
            assertNull(bHttp.streams().credit().session(hi, lo));
        } finally {
            b.replies().close();
        }
    }

    // --- flag off

    @Test
    void withTheSubFlagOffNothingChanges() throws Exception {
        this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        this.http = new SwarmHttp(this.swarm, 4, StreamHttpTest.BY_HEADER);
        assertFalse(TokenStreams.Config.DEFAULT.credit());
        assertNull(this.http.streams().credit(), "no credit state, no hooks");
        var r = post("/swarm/v1/tasks/gen:stream", SwarmHttpTest.task("prompt").encode());
        var ls = SwarmHttpTest.leases(post("/swarm/v1/tasks/gen:claim?visibilityMs=60000", new byte[0]));
        var t = held(ls.get(0).id());
        var corr = ByteBuffer.wrap(t.envelope().correlationId());
        long hi = corr.getLong();
        long lo = corr.getLong();
        for (int i = 0; i < 300; i++) { // past sinkChunks: today's answers, no 429
            var c = chunkOn(ls.get(0).id(), new StreamChunk(hi, lo, i, 0, data(10)));
            assertEquals(204, c.status());
            assertEquals(0, SwarmHttpTest.bytes(c).length);
            assertNull(c.headers().get("content-type"));
        }
        var key = r.headers().get("location").substring("/swarm/v1/replies/".length());
        var f = StreamHttp.class.getDeclaredField("sessions");
        f.setAccessible(true);
        var s = (StreamHttp.Session) ((Map<?, ?>) f.get(this.http.streams())).get(key);
        assertNull(s.credit, "no reserved state is allocated");
        assertNull(s.sink.credit);
    }

    @Test
    void theSubFlagIsReadFromSettings() {
        assertTrue(TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", "true")::get).credit());
        assertTrue(TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", " TRUE ")::get).credit());
        assertFalse(TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", "false")::get).credit());
        assertFalse(TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", "")::get).credit());
        assertFalse(TokenStreams.Config.from(k -> null).credit());
        for (var bad : List.of("yes", "1", "on", "tru", "true false")) { // #257 N-c: stops the process, names the value
            var e = assertThrows(sjws_nettask.OptionalModule.Refused.class,
                    () -> TokenStreams.Config.from(Map.of("sjws.swarm.stream.credit.enabled", bad)::get), bad);
            assertTrue(e.getMessage().contains("sjws.swarm.stream.credit.enabled must be true or false"), e.getMessage());
            assertTrue(e.getMessage().contains("\"" + bad + "\""), e.getMessage());
        }
        assertFalse(new TokenStreams.Config(1, 64, 256).credit(), "the three-setting constructor keeps credit off");
    }

    // --- #257 review (Architecture R1–R3)

    /** Every frame in {@code got} is a TOKEN (no gap ERROR) and their seqs are {@code first, first + 1, …}; returns the count. */
    static int assertConsecutiveTokens(byte[] got, long first) {
        var in = ByteBuffer.wrap(got);
        int n = 0;
        Frame f;
        while ((f = Frame.decode(in)) != null) {
            var fr = f;
            assertEquals(Frame.Type.TOKEN, fr.type(), () -> "no gap: " + new String(fr.payload(), StandardCharsets.UTF_8));
            assertEquals(first + n, StreamChunk.decode(f.payload()).chunkSeq());
            n++;
        }
        return n;
    }

    long trimmedThrough(Stream s) {
        var se = session(s);
        se.lock.lock();
        try {
            return se.trimmedThrough;
        } finally {
            se.lock.unlock();
        }
    }

    @Test
    void r1AtDefaultsTheExemptFillerInTheBytesDimensionTrimsNoUnreadChunk() throws Exception {
        // #257 R1: the default config (window 64, sinkChunks 256, 4 MiB); only the credit sub-flag is set
        on(TokenStreams.Config.from(n -> n.equals("sjws.swarm.stream.credit.enabled") ? "true" : null));
        assertEquals(256, tokens().config().sinkChunks());
        var s = stream();
        credit(chunk(s, 0, 10));
        read(s, 1); // unreadIn == 0
        for (int seq = 2; seq <= 5; seq++) {
            credit(chunk(s, seq, (int) MIB)); // held: credit at zero in bytes
        }
        assertEquals(0, credit().snapshot(session(s), st(s), s.hi(), s.lo(), s.attempt()).bytes());
        credit(chunk(s, 1, (int) MIB)); // exempt filler: unread is now 4 MiB + one chunk
        assertEquals(5, st(s).maxSeq);
        assertTrue(trimmedThrough(s) < st(s).readNext, "no unread chunk trimmed: trimmedThrough=" + trimmedThrough(s)
                + " readNext=" + st(s).readNext);
        var got = read(s, 5, "last-event-id", s.attempt() + ".0");
        assertEquals(5, assertConsecutiveTokens(got, 1), "the reader gets chunks 1..5 with no gap");
        assertEquals(6, st(s).readNext);
        assertEquals(0, tokens().sinkDrops());
        assertBaseline(st(s));
    }

    @Test
    void r1InTheChunksDimensionHeldCanReachTheWindowAndNothingUnreadIsTrimmed() throws Exception {
        // window == sinkChunks (the largest window credit allows, R2): held reaches window − 1, then the filler and LAST
        on(new TokenStreams.Config(1, 8, 8, true));
        var s = stream();
        credit(chunk(s, 0, 10));
        read(s, 1);
        for (int seq = 2; seq <= 8; seq++) {
            credit(chunk(s, seq, 10)); // held 1..7 = window − 1
        }
        assertEquals(7, st(s).heldChunks.get());
        credit(chunk(s, 1, 10)); // the filler fits the sink: delivered, not dropped
        assertEquals(8, st(s).maxSeq);
        assertEquals(0, st(s).heldChunks.get());
        assertFalse(sink(s).dropped());
        assertEquals(0, tokens().sinkDrops());
        assert429(chunk(s, 9, 10)); // 8 unread: the window is full
        assertEquals(204, chunk(s, 9, Frame.LAST, new byte[0]).status()); // LAST is never refused
        assertTrue(trimmedThrough(s) < st(s).readNext, "trimmedThrough=" + trimmedThrough(s));
        var got = read(s, 9, "last-event-id", s.attempt() + ".0");
        assertEquals(9, assertConsecutiveTokens(got, 1), "chunks 1..8 and LAST with no gap");
        assertBaseline(st(s));
    }

    @Test
    void r2WithCreditOnAWindowLargerThanSinkChunksFailsAtStartupNamingBothValues() {
        // OptionalModule.Refused: the process does not start (a plain exception would only skip the swarm module)
        var e = assertThrows(sjws_nettask.OptionalModule.Refused.class, () -> new TokenStreams.Config(1, 16, 8, true));
        assertTrue(e.getMessage().contains("sjws.swarm.stream.window (16)"), e.getMessage());
        assertTrue(e.getMessage().contains("sjws.swarm.stream.sinkChunks (8)"), e.getMessage());
        assertTrue(e.getMessage().contains("sjws.swarm.stream.credit.enabled"), e.getMessage());
        var from = assertThrows(sjws_nettask.OptionalModule.Refused.class, () -> TokenStreams.Config.from(n -> switch (n) {
            case "sjws.swarm.stream.credit.enabled" -> "true";
            case "sjws.swarm.stream.sinkChunks" -> "32"; // below the default window (64)
            default -> null;
        }));
        assertTrue(from.getMessage().contains("(64)") && from.getMessage().contains("(32)"), from.getMessage());
        // allowed: window == sinkChunks, and anything with credit off (unchanged)
        assertEquals(8, new TokenStreams.Config(1, 8, 8, true).window());
        assertFalse(new TokenStreams.Config(1, 16, 8, false).credit());
        assertFalse(new TokenStreams.Config(1, 64, 2).credit());
    }

    @Test
    void r2TheSwarmModuleRefusesToStartTheProcessWithAWindowLargerThanSinkChunks() {
        System.setProperty("sjws.swarm.stream.credit.enabled", "true");
        System.setProperty("sjws.swarm.stream.sinkChunks", "32"); // the default window is 64
        try {
            var other = Swarm.open(this.dir.resolve("module"), "m", 1, sjws_nettask.messaging.delivery.RetryPolicy.DEFAULT,
                    System::currentTimeMillis, 600_000);
            try {
                var e = assertThrows(sjws_nettask.OptionalModule.Refused.class,
                        () -> new SwarmModule(other, SwarmGate.DENY_ALL, false, true, null));
                assertTrue(e.getMessage().contains("sjws.swarm.stream.window (64)")
                        && e.getMessage().contains("sjws.swarm.stream.sinkChunks (32)"), e.getMessage());
                assertThrows(IllegalStateException.class, () -> other.replies().registerStream(new byte[16], Long.MAX_VALUE));
            } finally {
                other.close();
            }
        } finally {
            System.clearProperty("sjws.swarm.stream.credit.enabled");
            System.clearProperty("sjws.swarm.stream.sinkChunks");
        }
    }

    @Test
    void aMalformedCreditFlagRefusesToStartTheProcess() {
        // #257 N-c: as R2, end to end through the module the process loads, not a warning and a node without swarm
        System.setProperty("sjws.swarm.stream.credit.enabled", "yes");
        try {
            var other = Swarm.open(this.dir.resolve("malformed"), "m", 1,
                    sjws_nettask.messaging.delivery.RetryPolicy.DEFAULT, System::currentTimeMillis, 600_000);
            try {
                var e = assertThrows(sjws_nettask.OptionalModule.Refused.class,
                        () -> new SwarmModule(other, SwarmGate.DENY_ALL, false, true, null));
                assertTrue(e.getMessage().contains("sjws.swarm.stream.credit.enabled must be true or false")
                        && e.getMessage().contains("\"yes\""), e.getMessage());
                // the module closed the swarm before refusing (#262 review nit)
                assertThrows(IllegalStateException.class, () -> other.replies().registerStream(new byte[16], Long.MAX_VALUE));
            } finally {
                other.close();
            }
        } finally {
            System.clearProperty("sjws.swarm.stream.credit.enabled");
        }
    }

    @Test
    void aMalformedCreditFlagReachesTheModuleLoaderAsRefused() throws Exception {
        // the loader (OptionalModules.load, pinned by OptionalModulesTest) constructs the module reflectively and
        // rethrows a Refused cause, so the process does not start; a plain exception would be logged and skipped
        System.setProperty("sjws.swarm.stream.enabled", "true");
        System.setProperty("sjws.swarm.stream.credit.enabled", "maybe");
        System.setProperty("sjws.swarm.dir", this.dir.resolve("loader").toString());
        try {
            var e = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> SwarmModule.class.getDeclaredConstructor().newInstance());
            var r = assertInstanceOf(sjws_nettask.OptionalModule.Refused.class, e.getCause());
            assertTrue(r.getMessage().contains("sjws.swarm.stream.credit.enabled must be true or false (any case), got"
                    + " \"maybe\""), r.getMessage());
        } finally {
            for (var k : List.of("sjws.swarm.stream.enabled", "sjws.swarm.stream.credit.enabled", "sjws.swarm.dir")) {
                System.clearProperty(k);
            }
        }
    }

    @Test
    void r3AReaderPausedMidResetNeverMakesTheCheckOverAdmit() throws Exception {
        on(8);
        var s = stream();
        for (int seq = 0; seq < 40; seq++) { // the old attempt's reader gets far ahead of any new-attempt window
            credit(chunk(s, seq, 10));
            if (seq == 3) {
                read(s, 4);
            } else if (seq % 4 == 3) {
                read(s, 4, "last-event-id", s.attempt() + "." + (seq - 4));
            }
        }
        assertEquals(40, st(s).readNext);
        assertEquals(s.attempt(), st(s).readAttempt);
        var r = req("GET", s.session(), new byte[0], "accept", SwarmHttp.CONTENT_TYPE, "last-event-id",
                s.attempt() + ".39");
        assertEquals(200, r.status());
        var paused = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var pausedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        var out = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var n = takeover(s);
        credit().midReadPublish = () -> { // the reader is between its stores of the RESET's read position
            if (pausedOnce.compareAndSet(false, true)) {
                paused.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var reader = Thread.ofPlatform().start(() -> {
            try {
                out.set(StreamHttpTest.drainBytesUntil(r, 1 + 8));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        credit(chunk(n, 0, 10)); // RESET: the reader wakes, publishes the new attempt's position and is paused mid-way
        assertTrue(paused.await(10, java.util.concurrent.TimeUnit.SECONDS), "the reader reached the RESET publish");
        for (int seq = 1; seq < 8; seq++) {
            credit(chunk(n, seq, 10)); // fills the new attempt's window: 8 unread
        }
        try {
            assert429(chunk(n, 8, 10)); // a torn read (old position, new attempt) would see an empty window
        } finally {
            credit().midReadPublish = null;
            resume.countDown();
            reader.join(10_000);
        }
        assertNotNull(out.get());
        assertBaseline(st(n));
    }
}
