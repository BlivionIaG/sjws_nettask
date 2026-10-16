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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * S2c HTTP leg (agent-swarm.md §5.2, §11.2, §12 Q2; S2c PR 4). Created only when {@code sjws.swarm.stream.enabled}
 * is on; with the flag off {@link SwarmHttp} never references this class, so it is not loaded.
 * <ul>
 * <li>{@code POST /swarm/v1/tasks/{session}:cancel} (S2c PR 5): cancels the stream's task. Allowed for the agent the
 * session is bound to, or an admin ({@link SwarmGate#isAdmin}); any other agent gets 403, an unknown or expired session
 * 404. Idempotent: 200 with {@code {"state":"cancelled"}} the first time and every time after, or the stream's terminal
 * state ({@code ok|error|cancelled}) if it had already ended. The cancel is marked in the node's {@link CancelSet}
 * (so the worker gets {@code CANCELLED} on its next chunk or its next {@code :extend}, whichever comes first), an
 * opaque cancel record is produced on the task's own topic, the reader gets {@code event: end} / {@code cancelled},
 * and the session leaves through {@link #exit} (sink closed, {@code clearStream}, slots released).</li>
 * <li>{@code POST /swarm/v1/tasks/{pool}:stream?deadlineMs=}: submits a streaming call (deadline default 30 s, max 1 h;
 * a stream always has a deadline). 201 with a {@code TASK} frame whose payload is the 16-byte session id, and
 * {@code Location: /swarm/v1/replies/{hex}}.</li>
 * <li>{@code GET /swarm/v1/replies/{session}}: the stream as {@code text/event-stream} (default) or {@code TOKEN}
 * frames ({@code Accept: application/vnd.sjws.swarm.v1}). SSE: {@code retry: 1000} once, then per chunk {@code id:
 * <chunkSeq>}, {@code event: chunk}, the bytes as UTF-8 (invalid UTF-8 is replaced with U+FFFD: SSE is for text, use
 * the binary type for bytes), one {@code data:} line per line. Terminal: {@code event: end} with {@code data:
 * ok|cancelled|error|deadline}. A missing range is never skipped: {@code event: gap} with {@code data: <from> <to>
 * <reason>} ({@code to} is {@code ?} when unknown), then {@code end: error}. Binary terminals are the {@code LAST}
 * {@code TOKEN} frame, or an {@code ERROR|LAST} frame whose payload is {@code deadline} or {@code gap <from> <to>
 * <reason>}. Resume: {@code Last-Event-ID: n} (from n + 1) or {@code ?from=n}; resuming after the end returns the
 * terminal at once.</li>
 * <li>{@code POST /swarm/v1/leases/{leaseId}:chunk}: body one {@code TOKEN} frame (StreamChunk v1 payload; the stream
 * id must be the task's correlation id). 204; 409 when the lease is stale, or {@code cancelled} with {@code
 * "reason":"deadline"} once the task's deadline has passed (the claim stays cached: ack it). A {@code LAST} chunk
 * writes the reply (body = the chunk bytes) and acks once both are durable (§4.4); 504 (retry) if that takes over
 * 30 s.</li>
 * </ul>
 * <b>Session (§12 Q2):</b> a server-minted random 128-bit id bound to the {@link SwarmGate.Agent} id. Unknown,
 * expired or another agent's session: 404. One reader at a time (409). At most {@link #MAX_SESSIONS_PER_AGENT} per
 * agent and {@link #MAX_SESSIONS} per node (503). Chunks are drained into the session as they are produced, also
 * before the first GET, and retained up to {@link #RETAIN_BYTES} and {@code sinkChunks}; older ones are trimmed and
 * a reader that needs them gets a {@code gap} (reason {@code overflow}). {@code LAST} is always kept. Sessions live
 * until {@code LAST} + {@link #GRACE_MS} or the deadline + grace, swept on every stream request (no timer); a node
 * restart loses them.
 * <b>Attempts (S2c PR 5, §12 reset rule):</b> every chunk carries its lease attempt. The sink keeps one attempt; a newer
 * one discards the retained window and the reader gets {@code event: reset} / {@code data: <attempt>} (binary: an empty
 * {@code TOKEN} frame whose chunk has the {@code RESET} flag) and continues from {@code chunkSeq} 0. SSE ids and resume
 * tokens are {@code <attempt>.<chunkSeq>} ({@code Last-Event-ID}, or {@code ?attempt=&from=}); resuming with an older
 * attempt gets {@code RESET(current)} first. A bare {@code chunkSeq} means the current attempt.
 * <b>Sweep:</b> expired sessions and cancel entries are also swept every {@link #SWEEP_MS} by one shared daemon thread,
 * started only when the first {@code StreamHttp} is built (flag on).
 * <b>Cancel across nodes:</b> the record carries the task deadline; whichever node claims it creates the cancelled
 * entry in its own {@link CancelSet} (no prior tracking needed), so a lease held there gets 409 / {@code CANCELLED}.
 * <b>Not yet:</b> ledger or cross-node resume.
 */
final class StreamHttp {

    static final String SSE = "text/event-stream";
    static final long GRACE_MS = 60_000;
    static final long HEARTBEAT_MS = 15_000;
    static final int MAX_SESSIONS_PER_AGENT = 64;
    static final int MAX_SESSIONS = 4096;
    static final long RETAIN_BYTES = 4L << 20;
    static final long SWEEP_MS = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Swarm swarm;
    private final TokenStreams streams;
    private final long producerId;
    private final AtomicLong seq;
    private final int maxPerAgent;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> perAgent = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    StreamHttp(Swarm swarm, long producerId, AtomicLong seq, int maxPerAgent) {
        this.swarm = swarm;
        this.streams = swarm.tasks().streams();
        if (this.streams == null) {
            throw new IllegalStateException("streaming is off");
        }
        this.producerId = producerId;
        this.seq = seq;
        this.maxPerAgent = maxPerAgent;
        startSweeper(this);
    }

    /** One daemon thread for every StreamHttp of the process; created by the first one (stream flag on only). */
    private static java.util.concurrent.ScheduledExecutorService sweeper;
    private static final ReentrantLock SWEEPER_LOCK = new ReentrantLock();
    private final AtomicLong sweeps = new AtomicLong();

    long periodicSweeps() {
        return this.sweeps.get();
    }

    private static void startSweeper(StreamHttp h) {
        java.util.concurrent.ScheduledExecutorService ex;
        SWEEPER_LOCK.lock();
        try {
            if (sweeper == null) {
                sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(r, "sjws-stream-sweep");
                    t.setDaemon(true);
                    return t;
                });
            }
            ex = sweeper;
        } finally {
            SWEEPER_LOCK.unlock();
        }
        var ref = new java.lang.ref.WeakReference<>(h);
        var self = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
        self.set(ex.scheduleWithFixedDelay(() -> {
            var o = ref.get();
            if (o == null || o.swarm.topics().closed()) {
                var f = self.get();
                if (f != null) {
                    f.cancel(false);
                }
                return;
            }
            try {
                o.periodicSweep();
            } catch (RuntimeException e) {
                System.err.println("WARN: swarm: stream sweep: " + e);
            }
        }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS));
    }

    /** The timer's pass: expired sessions, then cancel entries past their deadline + grace. */
    void periodicSweep() {
        this.sweeps.incrementAndGet();
        sweep();
        this.streams.cancels().sweep(now());
        this.streams.purgeFences(now()); // idle fences leave here, off the worker thread (#234 review)
    }

    private long now() {
        return this.swarm.topics().clock().getAsLong();
    }

    int sessionCount() {
        return this.sessions.size();
    }

    /** What a reader gets next at a position. */
    sealed interface Next permits Chunk, Gap, Ended, Reset, Wait { }

    record Chunk(StreamChunk c) implements Next { }

    /** {@code to} is -1 when unknown. */
    record Gap(long from, long to, String reason) implements Next { }

    record Ended(StreamChunk last) implements Next { }

    /** The stream restarted under {@code attempt}: discard what was rendered and read from {@code chunkSeq} 0. */
    record Reset(long attempt) implements Next { }

    enum Wait implements Next { INSTANCE }

    /** One caller-facing stream: the bound agent, the local sink, and the retained chunks. */
    static final class Session {
        final String agent;
        final byte[] correlation;
        final TokenStreams.Sink sink;
        final long deadlineMs;
        final ReentrantLock lock = new ReentrantLock();
        final java.util.concurrent.locks.Condition grew = this.lock.newCondition();
        final ArrayDeque<StreamChunk> retained = new ArrayDeque<>(); // guarded by lock
        long retainedBytes; // guarded by lock
        long trimmedThrough = -1; // guarded by lock: highest seq trimmed out of retained
        final int retainMax;
        final long retainBytes;
        final AtomicBoolean reading = new AtomicBoolean();
        volatile StreamChunk last;
        volatile long expiresMs;
        /** The attempt in the retained window, -1 before the first chunk (§12 reset rule). */
        volatile long attempt = -1;
        long maxSeq = -1; // guarded by lock: highest chunkSeq retained for this attempt
        volatile boolean cancelled; // written under lock
        final AtomicBoolean exited = new AtomicBoolean();
        /** Where the task landed (cancel key); set once after submit. */
        volatile TaskService.Placed placed;
        /** The task-side cancel ran (at most once, by cancel or by submit). */
        final AtomicBoolean cancelApplied = new AtomicBoolean();

        Session(String agent, byte[] correlation, TokenStreams.Sink sink, long deadlineMs, int retainMax,
                long retainBytes) {
            this.agent = agent;
            this.correlation = correlation;
            this.sink = sink;
            this.deadlineMs = deadlineMs;
            this.retainMax = retainMax;
            this.retainBytes = retainBytes;
            this.expiresMs = deadlineMs + GRACE_MS;
        }

        /** Moves what the sink holds into the retained window; never blocks (runs on producer threads too). */
        void drain() {
            this.lock.lock();
            try {
                StreamChunk c;
                boolean any = false;
                while ((c = this.sink.poll0()) != null) {
                    any = true;
                    if (this.cancelled) {
                        continue; // the terminal is in place: nothing may follow it
                    }
                    if (c.reset()) { // a newer attempt: drop the old window, readers get RESET
                        this.retained.clear();
                        this.retainedBytes = 0;
                        this.trimmedThrough = -1;
                        this.maxSeq = -1;
                        this.last = null;
                        this.attempt = c.attempt();
                        continue;
                    }
                    if (this.attempt < 0) {
                        this.attempt = c.attempt();
                    }
                    this.maxSeq = Math.max(this.maxSeq, c.chunkSeq());
                    this.retained.addLast(c);
                    this.retainedBytes += c.size();
                    if (c.last()) {
                        this.last = c;
                    }
                    while (this.retained.size() > 1
                            && (this.retained.size() > this.retainMax || this.retainedBytes > this.retainBytes)) {
                        var old = this.retained.removeFirst(); // never the newest, so never a just-arrived LAST
                        this.retainedBytes -= old.size();
                        this.trimmedThrough = old.chunkSeq();
                    }
                }
                if (any || this.sink.dropped()) {
                    this.grew.signalAll();
                }
            } finally {
                this.lock.unlock();
            }
        }

        /** Waits up to {@code ms} for the retained window to change. */
        void await(long ms) throws InterruptedException {
            this.lock.lock();
            try {
                if (at(this.awaitFrom, this.awaitAttempt) == Wait.INSTANCE) {
                    this.grew.await(ms, TimeUnit.MILLISECONDS);
                }
            } finally {
                this.lock.unlock();
            }
        }

        long awaitFrom; // set by the single reader before await
        long awaitAttempt = -1;

        /** What a reader at {@code from} of {@code attempt} (-1: whatever is current) gets now. */
        Next at(long from, long attempt) {
            drain();
            this.lock.lock();
            try {
                if (attempt >= 0 && this.attempt >= 0 && attempt != this.attempt) {
                    return new Reset(this.attempt);
                }
                if (from <= this.trimmedThrough) {
                    var first = this.retained.peekFirst();
                    return new Gap(from, first == null ? this.trimmedThrough : first.chunkSeq() - 1, "overflow");
                }
                for (var c : this.retained) {
                    if (c.chunkSeq() == from) {
                        return new Chunk(c);
                    }
                    if (c.chunkSeq() > from) {
                        return new Gap(from, c.chunkSeq() - 1, "dropped");
                    }
                }
                var l = this.last;
                if (l != null && from > l.chunkSeq()) {
                    return new Ended(l);
                }
                if (this.sink.dropped()) {
                    return new Gap(from, -1, "overflow");
                }
                return Wait.INSTANCE;
            } finally {
                this.lock.unlock();
            }
        }
    }

    /**
     * Cancel: marks the terminal {@code LAST|CANCELLED} after everything retained (later chunks are discarded);
     * false if the stream had already ended or been cancelled.
     */
    static boolean markCancelled(Session s) {
        s.drain();
        s.lock.lock();
        try {
            if (s.cancelled || s.last != null) {
                return false;
            }
            var b = java.nio.ByteBuffer.wrap(s.correlation);
            var end = StreamChunk.owned(b.getLong(), b.getLong(), Math.max(0, s.attempt), s.maxSeq + 1,
                    Frame.LAST | Frame.CANCELLED, new byte[0]);
            s.retained.addLast(end);
            s.last = end;
            s.cancelled = true;
            s.grew.signalAll();
            return true;
        } finally {
            s.lock.unlock();
        }
    }

    /** {@code POST tasks/{session}:cancel}. */
    Response cancel(SwarmGate.Agent agent, String key, boolean admin) {
        sweep();
        var s = this.sessions.get(key);
        if (s == null || s.expiresMs < now()) {
            return SwarmHttp.problem(404, "not found", "no such session").response();
        }
        if (!s.agent.equals(agent.id()) && !admin) {
            return SwarmHttp.problem(403, "forbidden", "only the submitter or an admin may cancel").response();
        }
        // #234 review 3: a cancel before the submit is placed still cancels. markCancelled ends the session now; the
        // task-side cancel (CancelSet + ledger record) runs here if placed, else submit runs it right after placing
        // (volatile cancelled/placed on both sides, applyCancel at most once).
        if (markCancelled(s)) {
            try {
                applyCancel(s);
            } finally {
                s.expiresMs = Math.min(s.expiresMs, now() + GRACE_MS); // kept for idempotent cancels and the reader
                exit(s);
            }
            this.cancels.incrementAndGet();
        }
        var l = s.last;
        var state = l == null ? "running"
                : (l.flags() & Frame.CANCELLED) != 0 ? "cancelled" : (l.flags() & Frame.ERROR) != 0 ? "error" : "ok";
        return new Response(200, ResponseHeaders.of().set("content-type", "application/json"),
                Body.Bytes.of(("{\"state\":\"" + state + "\"}").getBytes(StandardCharsets.UTF_8)));
    }

    private final AtomicLong cancels = new AtomicLong();

    long cancels() {
        return this.cancels.get();
    }

    /** {@code POST tasks/{pool}:stream}. */
    /** The task side of a cancel: mark this node's CancelSet and produce the ledger record; once, and only when placed. */
    private void applyCancel(Session s) {
        var placed = s.placed;
        if (placed == null || !s.cancelApplied.compareAndSet(false, true)) {
            return;
        }
        this.streams.cancels().cancel(placed.topic(), placed.offset()); // worker: next chunk or :extend
        this.swarm.tasks().produceCancel(placed, s.deadlineMs, s.correlation);
    }

    /** Test hook: runs after the session is visible and before the task is placed. */
    volatile Runnable beforePlace;

    private final AtomicLong cancelRejections = new AtomicLong();

    /** Streams refused with 503 because the node's CancelSet was full (#234 review 4). */
    long cancelRejections() {
        return this.cancelRejections.get();
    }

    Response submit(SwarmGate.Agent agent, String pool, byte[] body, long deadlineMs, String traceparent) {
        sweep();
        if (this.total.incrementAndGet() > MAX_SESSIONS) {
            this.total.decrementAndGet();
            return SwarmHttp.problem(503, "too many streams", "node session limit " + MAX_SESSIONS).response();
        }
        var n = this.perAgent.merge(agent.id(), 1, Integer::sum);
        if (n > this.maxPerAgent) {
            release(agent.id());
            return SwarmHttp.problem(503, "too many streams", "at most " + this.maxPerAgent + " open sessions per agent")
                    .response();
        }
        // #234 review 4: a stream always has cancel and deadline state; a full CancelSet refuses it loudly, before submit
        if (!this.streams.cancels().reserve()) {
            this.cancelRejections.incrementAndGet();
            release(agent.id());
            return SwarmHttp.problem(503, "too many streams", "cancel set limit " + CancelSet.MAX_ENTRIES + "; retry later")
                    .response();
        }
        var correlation = ReplyRouter.newId();
        // #232: the final reply on reply.<node> is the router's, not late; refused past the router's cap
        if (!this.swarm.replies().registerStream(correlation, deadlineMs + GRACE_MS)) {
            this.streams.cancels().unreserve();
            release(agent.id());
            return SwarmHttp.problem(503, "too many streams", "reply router stream limit reached; retry later")
                    .response();
        }
        TokenStreams.Sink sink;
        try {
            sink = this.streams.open(correlation);
        } catch (RuntimeException e) {
            this.swarm.replies().clearStream(correlation);
            this.streams.cancels().unreserve();
            release(agent.id());
            throw e;
        }
        var sid = new byte[16];
        RANDOM.nextBytes(sid);
        var key = HEX.formatHex(sid);
        var s = new Session(agent.id(), correlation, sink, deadlineMs, this.streams.config().sinkChunks(), RETAIN_BYTES);
        sink.onOffer(s::drain); // retain chunks produced before the first GET
        this.sessions.put(key, s);
        // #234 re-review: the CancelSet slot is held until trackReserved takes it over; every failure before that
        // (envelope 413, beforePlace, submitStream) gives it back, so pre-placement errors cannot exhaust the set
        var reserved = true;
        try {
            // the final reply goes to this node's reply topic, registered above (#232)
            var env = new TaskEnvelope(correlation, correlation, this.swarm.replies().topic(), deadlineMs, traceparent,
                    "", "", "", body);
            var hook = this.beforePlace;
            if (hook != null) {
                hook.run();
            }
            var placed = this.swarm.tasks().submitStream(pool, env);
            reserved = false; // trackReserved owns the slot from here (and gives it back itself if apply won)
            this.streams.cancels().trackReserved(placed.topic(), placed.offset(), deadlineMs);
            s.placed = placed;
            if (s.cancelled) { // cancelled before it was placed (#234 review 3): cancel the task now
                applyCancel(s);
            }
        } catch (RuntimeException e) {
            if (reserved) {
                this.streams.cancels().unreserve();
            }
            if (this.sessions.remove(key, s)) {
                exit(s);
            }
            throw e;
        }
        var r = new Response(201, ResponseHeaders.of().set("content-type", SwarmHttp.CONTENT_TYPE),
                Body.Bytes.of(new Frame(Frame.Type.TASK, 0, sid).encode()));
        r.headers().set("location", SwarmHttp.PREFIX + "replies/" + key);
        return r;
    }

    private void release(String agent) {
        this.total.decrementAndGet();
        this.perAgent.computeIfPresent(agent, (k, v) -> v <= 1 ? null : v - 1);
    }

    /**
     * The single session-exit function: every exit path (expiry, submit failure, cancel) ends here, once per session,
     * so {@code clearStream} always runs. A session that exits by cancel stays readable until it is swept.
     */
    private void exit(Session s) {
        if (!s.exited.compareAndSet(false, true)) {
            return;
        }
        try {
            s.sink.close();
        } finally {
            this.swarm.replies().clearStream(s.correlation); // no-op once the LAST reply was read
            release(s.agent);
        }
    }

    /** Drops expired sessions that have no reader; the CAS keeps a reader from attaching to a dropped one. */
    void sweep() {
        long now = now();
        for (var e : this.sessions.entrySet()) {
            var s = e.getValue();
            if (s.expiresMs < now && s.reading.compareAndSet(false, true)) {
                if (this.sessions.remove(e.getKey(), s)) {
                    exit(s);
                }
            }
        }
    }

    /** {@code GET replies/{session}}. */
    Response replies(SwarmGate.Agent agent, String key, Request request, Map<String, String> query)
            throws SwarmHttp.BadRequest {
        sweep();
        var s = this.sessions.get(key);
        if (s == null || !s.agent.equals(agent.id()) || s.expiresMs < now()) {
            return SwarmHttp.problem(404, "not found", "no such session").response();
        }
        long from = SwarmHttp.number(query, "from", 0, 0, 0xFFFF_FFFFL);
        long attempt = SwarmHttp.number(query, "attempt", -1, -1, StreamChunk.MAX_ATTEMPT);
        var lastId = request.headers().values("last-event-id");
        if (!lastId.isEmpty() && !query.containsKey("from")) {
            var tok = lastId.get(0).trim(); // resume token <attempt>.<chunkSeq> (or a bare chunkSeq)
            int dot = tok.indexOf('.');
            try {
                if (dot >= 0) {
                    attempt = Long.parseLong(tok.substring(0, dot));
                    tok = tok.substring(dot + 1);
                }
                from = Long.parseLong(tok) + 1;
                if (attempt < -1 || attempt > StreamChunk.MAX_ATTEMPT || from < 0) {
                    throw new NumberFormatException(tok);
                }
            } catch (NumberFormatException e) {
                throw new SwarmHttp.BadRequest("Last-Event-ID must be <attempt>.<chunkSeq>");
            }
        }
        boolean binary = request.headers().values("accept").stream().anyMatch(a -> a.contains(SwarmHttp.CONTENT_TYPE));
        if (!s.reading.compareAndSet(false, true)) {
            return SwarmHttp.problem(409, "session busy", "one reader at a time").response();
        }
        if (this.sessions.get(key) != s) { // swept between get and CAS
            s.reading.set(false);
            return SwarmHttp.problem(404, "not found", "no such session").response();
        }
        var h = ResponseHeaders.of().set("content-type", binary ? SwarmHttp.CONTENT_TYPE : SSE)
                .set("cache-control", "no-store");
        return new Response(200, h, new Body.Stream(-1, new Reader(this, s, attempt, from, binary)));
    }

    /** Writes the session's chunks from {@code next}; one writer thread. */
    static final class Reader implements Body.Stream.Source {
        private final StreamHttp owner;
        private final Session s;
        private final boolean binary;
        private long next;
        private long attempt;
        private boolean started;
        private boolean ended;
        private boolean closed;

        Reader(StreamHttp owner, Session s, long attempt, long from, boolean binary) {
            this.owner = owner;
            this.s = s;
            this.attempt = attempt;
            this.next = from;
            this.binary = binary;
        }

        @Override
        public ByteBuffer next() throws java.io.IOException {
            if (this.ended) {
                return null;
            }
            if (!this.started && !this.binary) {
                this.started = true;
                return utf8("retry: 1000\n\n");
            }
            long t0 = System.nanoTime();
            while (true) {
                var n = this.s.at(this.next, this.attempt);
                switch (n) {
                    case Reset(var a) -> {
                        this.attempt = a;
                        this.next = 0;
                        var b = java.nio.ByteBuffer.wrap(this.s.correlation);
                        return ByteBuffer.wrap(this.binary ? StreamChunk.reset(b.getLong(), b.getLong(), a).toFrame().encode()
                                : ("event: reset\ndata: " + a + "\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                    case Chunk(var c) -> {
                        this.attempt = c.attempt();
                        this.next = c.chunkSeq() + 1;
                        if (c.last()) {
                            end();
                        }
                        return ByteBuffer.wrap(this.binary ? c.toFrame().encode() : sse(c));
                    }
                    case Ended(var l) -> { // reconnect after the end: the terminal state at once
                        end();
                        return ByteBuffer.wrap(this.binary
                                ? StreamChunk.owned(l.streamHi(), l.streamLo(), l.attempt(), l.chunkSeq(), l.flags(), new byte[0])
                                        .toFrame().encode()
                                : endEvent(l).getBytes(StandardCharsets.UTF_8));
                    }
                    case Gap g -> {
                        end();
                        var what = g.from() + " " + (g.to() < 0 ? "?" : Long.toString(g.to())) + " " + g.reason();
                        return ByteBuffer.wrap(this.binary
                                ? new Frame(Frame.Type.ERROR, Frame.LAST, ("gap " + what).getBytes(StandardCharsets.UTF_8))
                                        .encode()
                                : ("event: gap\ndata: " + what + "\n\nevent: end\ndata: error\n\n")
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    case Wait w -> {
                        if (this.owner.now() > this.s.deadlineMs) {
                            this.ended = true;
                            return ByteBuffer.wrap(this.binary
                                    ? new Frame(Frame.Type.ERROR, Frame.LAST, "deadline".getBytes(StandardCharsets.UTF_8))
                                            .encode()
                                    : "event: end\ndata: deadline\n\n".getBytes(StandardCharsets.UTF_8));
                        }
                        try {
                            this.s.awaitFrom = this.next;
                            this.s.awaitAttempt = this.attempt;
                            this.s.await(250);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new java.io.InterruptedIOException();
                        }
                        if (!this.binary && System.nanoTime() - t0 >= TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MS)) {
                            return utf8(":\n\n");
                        }
                    }
                }
            }
        }

        private void end() {
            this.ended = true;
            this.s.expiresMs = Math.min(this.s.expiresMs, this.owner.now() + GRACE_MS);
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.s.reading.set(false);
            }
        }
    }

    private static ByteBuffer utf8(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    static String endEvent(StreamChunk last) {
        var how = (last.flags() & Frame.CANCELLED) != 0 ? "cancelled" : (last.flags() & Frame.ERROR) != 0 ? "error" : "ok";
        return "id: " + last.attempt() + "." + last.chunkSeq() + "\nevent: end\ndata: " + how + "\n\n";
    }

    /** One SSE event: {@code id}, {@code event: chunk}, one {@code data:} line per line; {@code LAST} adds an end event. */
    static byte[] sse(StreamChunk c) {
        var b = new StringBuilder(c.size() + 48);
        if (c.size() > 0 || !c.last()) {
            b.append("id: ").append(c.attempt()).append('.').append(c.chunkSeq()).append("\nevent: chunk\n");
            var text = new String(c.bytes(), StandardCharsets.UTF_8);
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i == text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                    b.append("data: ").append(text, start, i).append('\n');
                    if (i < text.length() && text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    start = i + 1;
                }
            }
            b.append('\n');
        }
        if (c.last()) {
            b.append(endEvent(c));
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The 409 reason for a CANCELLED lease answer: {@code cancelled} or {@code deadline}. */
    String reason(Task t) {
        var why = this.streams.cancels().reason(TaskService.topic(t.pool(), t.shard()), t.offset(), now());
        if (why != null) {
            return why;
        }
        long dl = t.envelope() == null ? TaskEnvelope.NO_DEADLINE : t.envelope().deadlineMs();
        return dl != TaskEnvelope.NO_DEADLINE && now() > dl ? CancelSet.DEADLINE : CancelSet.CANCELLED;
    }

    /** {@code POST leases/{id}:chunk}; {@code held} is the cached claim (null when not held over HTTP). */
    Response chunk(Task held, Frame f, Runnable release) throws SwarmHttp.BadRequest {
        if (f.type() != Frame.Type.TOKEN) {
            throw new SwarmHttp.BadRequest("expected a TOKEN frame");
        }
        if (held == null) {
            return SwarmHttp.problem(409, "lease not held", "NOT_IN_FLIGHT").response();
        }
        StreamChunk c;
        try {
            c = StreamChunk.decode(f.payload());
        } catch (IllegalArgumentException e) {
            throw new SwarmHttp.BadRequest("TOKEN payload: " + e.getMessage());
        }
        long deadline = held.envelope().deadlineMs();
        if (deadline != TaskEnvelope.NO_DEADLINE && now() > deadline) {
            return SwarmHttp.cancelled("deadline").response(); // the claim stays cached: the worker acks it
        }
        if (!c.last()) {
            var r = this.swarm.tasks().streamChunk(held, this.producerId, this.seq.incrementAndGet(), c);
            if (r == LeaseResult.CANCELLED) { // still held: the worker sends LAST|CANCELLED or acks
                return SwarmHttp.cancelled(reason(held)).response();
            }
            if (r != LeaseResult.OK) {
                release.run();
            }
            return r.ok() ? new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE) : SwarmHttp.leaseProblem(r).response();
        }
        sweep();
        LeaseResult r;
        try {
            long s = this.seq.addAndGet(2) - 1; // LAST uses s, the reply s + 1
            r = this.swarm.tasks().streamLast(held, this.producerId, s, c, "", c.bytes()).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SwarmHttp.problem(503, "unavailable", "interrupted; retry the LAST chunk").response();
        } catch (TimeoutException e) {
            // the ack may still land: not a lease answer; a retried LAST is deduplicated by chunkSeq
            return SwarmHttp.problem(504, "timeout", "LAST not durable within 30 s; retry").response();
        } catch (ExecutionException e) {
            r = LeaseResult.NOT_IN_FLIGHT;
        }
        release.run();
        return r.ok() ? new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE) : SwarmHttp.leaseProblem(r).response();
    }
}
