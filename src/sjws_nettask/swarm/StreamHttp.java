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
 * <attempt>.<chunkSeq>}, {@code event: chunk}, the bytes as UTF-8 (invalid UTF-8 is replaced with U+FFFD: SSE is for
 * text, use the binary type for bytes), one {@code data:} line per line. A worker {@code LAST|ERROR} with a non-empty
 * payload is {@code event: failure} (never {@code error}: browsers reserve that for transport failures) carrying the
 * text, then {@code event: end} / {@code data: error}; an empty {@code LAST|ERROR} is only {@code end}. A gap-induced
 * {@code end: error} stays {@code event: gap} + {@code end}, with no {@code failure} event. Other terminals:
 * {@code event: end} with {@code data: ok|cancelled|deadline}. Binary terminals are the {@code LAST} {@code TOKEN}
 * frame (including its {@code LAST|ERROR} payload), or an {@code ERROR|LAST} frame whose payload is {@code deadline}
 * or {@code gap <from> <to> <reason>}. Resume: {@code Last-Event-ID: <attempt>.n} (from n + 1) or {@code ?from=n};
 * resuming after the end returns the terminal at once (SSE re-emits {@code failure} when non-empty, then {@code end};
 * binary returns the real terminal bytes, which stay charged to the node budget until grace expiry).</li>
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
    /**
     * #257 R1, credit on only: chunks the session retains beyond the credit window (sinkChunks, {@link #RETAIN_BYTES}).
     * Checked chunks overshoot the window by at most two (the liveness exemption and one torn read, §5.2), and a
     * {@code LAST} is never refused; with this much room the retain trim only ever removes chunks already read.
     */
    static final int CREDIT_RETAIN_SLACK = 3;
    static final long SWEEP_MS = 5_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    /**
     * #261 Q1: the {@code :claim} response header (stream flag on, and only when the batch has a stream task) that
     * tells an HTTP worker, per {@code LEASE} in frame order, the stream id and attempt of a stream task, so it never
     * parses the lease id.
     */
    static final String LEASE_STREAM_HEADER = "sjws-lease-stream";
    /**
     * #261 R1: sent with {@value #LEASE_STREAM_HEADER}: this node's reorder window ({@code sjws.swarm.stream.window}).
     * The SDK keeps its issued seq span below min(its configured window, this value), or 1 when the header is absent.
     */
    static final String STREAM_WINDOW_HEADER = "sjws-stream-window";

    /** The {@value #STREAM_WINDOW_HEADER} value, formatted once (the window is read once at startup). */
    String windowHeader() {
        return this.windowHeader;
    }

    /** A builder holding the {@code -,} entries of the {@code unary} unary leases that came before the first stream task. */
    static StringBuilder leaseStreamPrefix(int unary) {
        var b = new StringBuilder(2 * unary + 48);
        for (int i = 0; i < unary; i++) {
            b.append("-,");
        }
        return b;
    }

    /**
     * Appends one {@value #LEASE_STREAM_HEADER} entry for {@code t}: {@code <32 lower-case hex of the correlation id>/<attempt
     * as an unsigned decimal u32>} for a stream task ({@link TokenStreams#isStreamTask}), else {@code -}. The attempt is
     * the lease's, the value the admit stamps on every chunk and CREDIT carries; never the lease id's shard suffix.
     */
    static StringBuilder appendLeaseStream(StringBuilder b, Task t) {
        return appendLeaseStream(b, t, TokenStreams.isStreamTask(t.envelope()));
    }

    /** {@link #appendLeaseStream(StringBuilder, Task)} with the predicate already evaluated by the caller (one call per task). */
    static StringBuilder appendLeaseStream(StringBuilder b, Task t, boolean stream) {
        var env = t.envelope();
        if (!stream) {
            return b.append('-');
        }
        return b.append(HEX.toHexDigits(env.correlationHi())).append(HEX.toHexDigits(env.correlationLo())).append('/')
                .append(Integer.toUnsignedLong(t.attempt()));
    }

    private final Swarm swarm;
    private final TokenStreams streams;
    private final long producerId;
    private final AtomicLong seq;
    private final int maxPerAgent;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> perAgent = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    /**
     * S2c PR 6 CREDIT, or null with {@code sjws.swarm.stream.credit.enabled} off: then {@link StreamCredit} is never
     * loaded, no session carries credit state and {@code :chunk} answers exactly as before.
     */
    private final StreamCredit credit;
    private final String windowHeader;

    StreamHttp(Swarm swarm, long producerId, AtomicLong seq, int maxPerAgent) {
        this.swarm = swarm;
        this.streams = swarm.tasks().streams();
        if (this.streams == null) {
            throw new IllegalStateException("streaming is off");
        }
        this.producerId = producerId;
        this.seq = seq;
        this.maxPerAgent = maxPerAgent;
        this.windowHeader = Integer.toString(this.streams.config().window());
        this.credit = this.streams.config().credit()
                ? new StreamCredit(this.streams.config().sinkChunks(), RETAIN_BYTES, this.streams.budget()) : null;
        if (this.credit != null) {
            System.err.println("swarm: stream credit on, window=" + this.credit.sinkChunks + " chunks + "
                    + (RETAIN_BYTES >> 20) + " MiB");
        }
        startSweeper(this);
    }

    /** The node's credit state (tests and counters); null with the credit sub-flag off. */
    StreamCredit credit() {
        return this.credit;
    }

    /**
     * S2c PR 8 (N2): Prometheus appendix for {@code /metrics}. Credit-off returns empty so flag-off
     * emits nothing and does not load {@link StreamBudget}/{@link StreamCredit}.
     */
    String statsText() {
        if (this.credit == null) {
            return StreamStats.prometheusCreditOff();
        }
        return StreamStats.prometheus(this.credit, this.streams.sinkDrops());
    }

    /**
     * One daemon thread for every live StreamHttp of the process (stream flag on only): created by the first one,
     * shut down when the last one's swarm closes (#181: it used to outlive every close).
     */
    private static java.util.concurrent.ScheduledExecutorService sweeper;
    /** StreamHttps whose sweep is scheduled on {@link #sweeper}; guarded by {@link #SWEEPER_LOCK}. */
    private static int sweeperUsers;
    private static final ReentrantLock SWEEPER_LOCK = new ReentrantLock();
    static final String SWEEPER_THREAD = "sjws-stream-sweep";
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
                    var t = new Thread(r, SWEEPER_THREAD);
                    t.setDaemon(true);
                    return t;
                });
            }
            ex = sweeper;
            sweeperUsers++;
        } finally {
            SWEEPER_LOCK.unlock();
        }
        var ref = new java.lang.ref.WeakReference<>(h);
        var self = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                var f = self.get();
                if (f != null) {
                    f.cancel(false);
                }
                releaseSweeper();
            }
        };
        self.set(ex.scheduleWithFixedDelay(() -> {
            var o = ref.get();
            if (o == null || o.swarm.topics().closed()) {
                release.run();
                return;
            }
            try {
                o.periodicSweep();
            } catch (RuntimeException e) {
                System.err.println("WARN: swarm: stream sweep: " + e);
            }
        }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS));
        h.swarm.onClose(release);
    }

    /** One StreamHttp is done with the sweeper; the last one stops its thread (and waits for it, off that thread). */
    private static void releaseSweeper() {
        java.util.concurrent.ScheduledExecutorService stop = null;
        SWEEPER_LOCK.lock();
        try {
            if (--sweeperUsers == 0) {
                stop = sweeper;
                sweeper = null;
            }
        } finally {
            SWEEPER_LOCK.unlock();
        }
        if (stop == null) {
            return;
        }
        stop.shutdownNow(); // only cancelled sweeps can be queued; a running one finishes
        if (!Thread.currentThread().getName().equals(SWEEPER_THREAD)) {
            try {
                stop.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The timer's pass: expired sessions, then cancel entries past their deadline + grace. */
    void periodicSweep() {
        this.sweeps.incrementAndGet();
        sweep();
        if (this.credit != null && this.credit.budget.pressured()) {
            pressureSweep(); // S2c PR 6b: reader-less sessions trim their read chunks too (#267 §4.3)
        }
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
        /** S2c PR 6: this session's published credit state; null with the credit sub-flag off (set before submit). */
        volatile StreamCredit.State credit;

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
                var cr = this.credit; // null with the credit sub-flag off
                // S2c PR 6b: residency is charged to the node budget, unless the session already left (closed)
                var budget = cr == null || cr.closed ? null : cr.owner.budget;
                StreamChunk c;
                boolean any = false;
                while ((c = this.sink.poll0()) != null) {
                    any = true;
                    if (this.cancelled) {
                        continue; // the terminal is in place: nothing may follow it
                    }
                    if (c.reset()) { // a newer attempt: drop the old window, readers get RESET
                        if (budget != null) {
                            budget.resident(cr, -this.retainedBytes); // the LAST ticket was rebooked by the sink
                        }
                        this.retained.clear();
                        this.retainedBytes = 0;
                        this.trimmedThrough = -1;
                        this.maxSeq = -1;
                        this.last = null;
                        if (cr != null) {
                            cr.reset();
                        }
                        this.attempt = c.attempt(); // published last (#248 r2 N2)
                        continue;
                    }
                    if (this.attempt < 0) {
                        this.attempt = c.attempt();
                    }
                    this.maxSeq = Math.max(this.maxSeq, c.chunkSeq());
                    this.retained.addLast(c);
                    this.retainedBytes += c.size();
                    if (cr != null) {
                        cr.appended(c.size(), this.maxSeq);
                    }
                    if (budget != null) {
                        budget.resident(cr, c.size());
                        if (c.last()) {
                            budget.convertLast(cr); // the LAST is resident: its ticket is released (#267 §3.4)
                        }
                    }
                    if (c.last()) {
                        this.last = c;
                    }
                    while (this.retained.size() > 1
                            && (this.retained.size() > this.retainMax || this.retainedBytes > this.retainBytes)) {
                        var old = this.retained.removeFirst(); // never the newest, so never a just-arrived LAST
                        this.retainedBytes -= old.size();
                        this.trimmedThrough = old.chunkSeq();
                        if (cr != null) {
                            cr.trimmed();
                        }
                        if (budget != null) {
                            budget.resident(cr, -old.size());
                        }
                    }
                }
                if (any || this.sink.dropped()) {
                    this.grew.signalAll();
                }
            } finally {
                this.lock.unlock();
            }
        }

        /**
         * S2c PR 6b pressure trim (#267 §4.3, Q5), under the session lock: drops the oldest retained chunks that are
         * before {@code readNext} (read), keeping a resume tail of at least one read chunk and at least
         * {@link StreamBudget#TRANSPORT_WINDOW} read bytes, so bytes handed to the transport survive a connection blip.
         * Unread chunks are never trimmed. A reader resuming before the tail gets {@code gap … overflow}.
         */
        void trimRead(StreamCredit.State cr, long readNext) {
            if (cr.closed) {
                return;
            }
            var budget = cr.owner.budget;
            StreamChunk f;
            while (this.retained.size() > 1 && (f = this.retained.peekFirst()) != null && !f.last()
                    && readNext - f.chunkSeq() >= 2) { // f is read, and so is at least one chunk after it
                long readInRetained = cr.offsetOf(readNext, f.chunkSeq()) - cr.offsetOf(f.chunkSeq(), f.chunkSeq());
                if (readInRetained - f.size() < StreamBudget.TRANSPORT_WINDOW) {
                    return;
                }
                this.retained.removeFirst();
                this.retainedBytes -= f.size();
                this.trimmedThrough = f.chunkSeq();
                cr.trimmed();
                budget.resident(cr, -f.size());
                budget.trimmed(f.size());
            }
        }

        /**
         * #272 review R1: before a node refusal, trims this session's read chunks down to the resume tail under the
         * session lock, from the reader's published position (the {@link #pressureSweep} rule). True when anything was
         * trimmed, so the refused chunk is worth re-admitting once.
         */
        boolean trimBeforeRefusal(StreamCredit.State cr) {
            this.lock.lock();
            try {
                long ra = cr.readAttempt; // published last by the reader: then readNext belongs to it
                if (ra < 0 || ra != this.attempt) {
                    return false;
                }
                long before = this.retainedBytes;
                trimRead(cr, cr.readNext);
                return this.retainedBytes < before;
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
                var cr = this.credit;
                if (cr != null && cr.owner.budget.pressured()) {
                    trimRead(cr, from); // S2c PR 6b: the reader trims its own read chunks under pressure (#267 §4.3)
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
            var cr = s.credit;
            if (cr != null) {
                cr.pushOffset(cr.appendedBytes); // parallel to retained; the marker is not a chunk of the window
            }
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
        // S2c PR 6b (#267 §4.5): the session's LAST ticket; refused at the admit watermark before any other state exists
        var budget = this.credit == null ? null : this.credit.budget;
        if (budget != null && !budget.admitSession()) {
            release(agent.id());
            var r = SwarmHttp.problem(503, "too many streams", "stream memory budget").response();
            r.headers().set("retry-after", "1");
            return r;
        }
        // #234 review 4: a stream always has cancel and deadline state; a full CancelSet refuses it loudly, before submit
        if (!this.streams.cancels().reserve()) {
            this.cancelRejections.incrementAndGet();
            abandon(budget);
            release(agent.id());
            return SwarmHttp.problem(503, "too many streams", "cancel set limit " + CancelSet.MAX_ENTRIES + "; retry later")
                    .response();
        }
        var correlation = ReplyRouter.newId();
        // #232: the final reply on reply.<node> is the router's, not late; refused past the router's cap
        if (!this.swarm.replies().registerStream(correlation, deadlineMs + GRACE_MS)) {
            this.streams.cancels().unreserve();
            abandon(budget);
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
            abandon(budget);
            release(agent.id());
            throw e;
        }
        var sid = new byte[16];
        RANDOM.nextBytes(sid);
        var key = HEX.formatHex(sid);
        int sinkChunks = this.streams.config().sinkChunks();
        var s = this.credit == null ? new Session(agent.id(), correlation, sink, deadlineMs, sinkChunks, RETAIN_BYTES)
                : new Session(agent.id(), correlation, sink, deadlineMs, sinkChunks + CREDIT_RETAIN_SLACK,
                        RETAIN_BYTES + CREDIT_RETAIN_SLACK * (long) SwarmHttp.MAX_REQUEST); // #257 R1: retain ⊇ window + 3
        sink.onOffer(s::drain); // retain chunks produced before the first GET
        if (this.credit != null) {
            sink.credit = this.credit.register(s); // before the task exists, so before its first chunk
        }
        this.sessions.put(key, s);
        // #234 re-review: the CancelSet slot is held until trackReserved takes it over; every failure before that
        // (envelope 413, beforePlace, submitStream) gives it back, so pre-placement errors cannot exhaust the set
        var reserved = true;
        try {
            // the final reply goes to this node's reply topic, registered above (#232)
            var env = new TaskEnvelope(correlation, correlation, this.swarm.replies().topic(), deadlineMs, traceparent,
                    this.streams.shardsTracestate(), "", "", body); // writers use the submitter's shard count
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
                forget(s);
                exit(s);
            }
            throw e;
        }
        var r = new Response(201, ResponseHeaders.of().set("content-type", SwarmHttp.CONTENT_TYPE),
                Body.Bytes.of(new Frame(Frame.Type.TASK, 0, sid).encode()));
        r.headers().set("location", SwarmHttp.PREFIX + "replies/" + key);
        return r;
    }

    /** A submit failed before its session took over the LAST ticket (S2c PR 6b); budget is null with credit off. */
    private static void abandon(StreamBudget budget) {
        if (budget != null) {
            budget.abandonSession();
        }
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

    /** A session left the table: its stream no longer has credit on this node. */
    private void forget(Session s) {
        if (this.credit != null) {
            this.credit.unregister(s);
            this.credit.budget.close(s); // S2c PR 6b: closed flag, then every residual charge released once
        }
    }

    /** S2c PR 6b: under pressure, every session trims to its published read position (sweep thread). */
    void pressureSweep() {
        for (var s : this.sessions.values()) {
            var cr = s.credit;
            if (cr == null) {
                continue;
            }
            // reader sessions too (#272 review, non-blocking): a waiting reader trims only when it wakes, and the
            // published readNext counts only chunks already handed to the reader
            s.lock.lock();
            try {
                long ra = cr.readAttempt; // published last by the reader: then readNext belongs to it
                if (ra == s.attempt && ra >= 0) {
                    s.trimRead(cr, cr.readNext);
                }
            } finally {
                s.lock.unlock();
            }
        }
    }

    /** Drops expired sessions that have no reader; the CAS keeps a reader from attaching to a dropped one. */
    void sweep() {
        long now = now();
        for (var e : this.sessions.entrySet()) {
            var s = e.getValue();
            if (s.expiresMs < now && s.reading.compareAndSet(false, true)) {
                if (this.sessions.remove(e.getKey(), s)) {
                    forget(s);
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
            var cr = s.credit;
            if (cr != null) { // S2c PR 6: a (re)attached reader publishes where it reads from; a rewind moves back
                s.lock.lock();
                try {
                    long a = attempt >= 0 ? attempt : s.attempt;
                    cr.publishRead(a, a == s.attempt ? cr.offsetOf(from, firstSeq(s)) : 0, from);
                } finally {
                    s.lock.unlock();
                }
            }
        }

        private static long firstSeq(Session s) {
            var f = s.retained.peekFirst();
            return f == null ? -1 : f.chunkSeq();
        }

        /** S2c PR 6: chunk {@code c} was handed to the transport; it counts as read. */
        private void read(StreamHttp.Session s, StreamCredit.State cr, StreamChunk c) {
            long bytes;
            if (c.attempt() == cr.readAttempt && c.chunkSeq() == cr.readNext) {
                bytes = cr.readBytes + c.size(); // the reader advances one chunk at a time: offset(c) + size(c)
            } else {
                s.lock.lock();
                try {
                    bytes = cr.offsetOf(c.chunkSeq(), firstSeq(s)) + c.size();
                } finally {
                    s.lock.unlock();
                }
            }
            cr.publishRead(c.attempt(), bytes, c.chunkSeq() + 1);
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
                        var cr = this.s.credit;
                        if (cr != null) {
                            cr.publishRead(a, 0, 0);
                        }
                        var b = java.nio.ByteBuffer.wrap(this.s.correlation);
                        return ByteBuffer.wrap(this.binary ? StreamChunk.reset(b.getLong(), b.getLong(), a).toFrame().encode()
                                : ("event: reset\ndata: " + a + "\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                    case Chunk(var c) -> {
                        this.attempt = c.attempt();
                        this.next = c.chunkSeq() + 1;
                        var cr = this.s.credit;
                        if (cr != null) {
                            read(this.s, cr, c);
                        }
                        if (c.last()) {
                            end();
                        }
                        return ByteBuffer.wrap(this.binary ? c.toFrame().encode() : sse(c));
                    }
                    case Ended(var l) -> { // reconnect after the end: the terminal state at once (#282)
                        end();
                        // LAST bytes stay charged to the #272 node budget until GRACE_MS sweep, so re-emitting them
                        // adds no uncharged residency (Architecture #282 answer 4)
                        return ByteBuffer.wrap(this.binary
                                ? l.toFrame().encode()
                                : terminalSse(l).getBytes(StandardCharsets.UTF_8));
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

    /**
     * Appends one SSE event ({@code id}, {@code event: <name>}, one {@code data:} line per line). Used for ordinary
     * {@code chunk} events and for {@code failure} (#282): the name {@code failure} is pinned so browsers do not treat
     * the text as an EventSource transport {@code error}.
     */
    static void appendSseEvent(StringBuilder b, StreamChunk c, String event) {
        b.append("id: ").append(c.attempt()).append('.').append(c.chunkSeq()).append("\nevent: ").append(event)
                .append('\n');
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

    /**
     * Reconnect-after-end SSE body (#282): {@code event: failure} (when {@code LAST|ERROR} and non-empty) then
     * {@code endEvent}. Gap/cancelled/ok/deadline paths never call this with a failure payload.
     */
    static String terminalSse(StreamChunk last) {
        if ((last.flags() & Frame.ERROR) != 0 && last.size() > 0) {
            var b = new StringBuilder(last.size() + 64);
            appendSseEvent(b, last, "failure");
            b.append(endEvent(last));
            return b.toString();
        }
        return endEvent(last);
    }

    /**
     * One SSE event: {@code id}, {@code event: chunk}, one {@code data:} line per line; {@code LAST} adds an end
     * event. A {@code LAST|ERROR} with a non-empty payload is {@code event: failure} then {@code end} (#282), never a
     * {@code chunk}; an empty {@code LAST|ERROR} is only {@code end}.
     */
    static byte[] sse(StreamChunk c) {
        var b = new StringBuilder(c.size() + 48);
        boolean errorLast = c.last() && (c.flags() & Frame.ERROR) != 0;
        if (errorLast) {
            if (c.size() > 0) {
                appendSseEvent(b, c, "failure");
            }
            b.append(endEvent(c));
            return b.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (c.size() > 0 || !c.last()) {
            appendSseEvent(b, c, "chunk");
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
        if (c.last() && this.credit != null && c.size() > StreamBudget.LAST_MAX) {
            // S2c PR 6b, L3 (#267 Q3), credit on only: checked before anything is written; the lease stays held, so the
            // worker resends the data as ordinary chunks followed by a small LAST
            var r = new SwarmHttp.Problem(413, "LAST too large", "with stream credit on a LAST chunk carries at most "
                    + StreamBudget.LAST_MAX + " bytes; send the data as chunks, then a small LAST", StreamCredit.TYPE,
                    "last-too-large").response();
            return r;
        }
        long deadline = held.envelope().deadlineMs();
        if (deadline != TaskEnvelope.NO_DEADLINE && now() > deadline) {
            return SwarmHttp.cancelled("deadline").response(); // the claim stays cached: the worker acks it
        }
        if (!c.last()) {
            var cr = this.credit;
            if (cr != null && !c.reset() && c.streamHi() == held.envelope().correlationHi()
                    && c.streamLo() == held.envelope().correlationLo()) { // else the admit refuses it (400) as before
                return creditChunk(cr, held, c, release);
            }
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

    /**
     * S2c PR 6, credit sub-flag on: a non-{@code LAST} chunk (agent-swarm.md §5.2 <i>Backpressure</i>). Precedence: the
     * 400/409/deadline answers above, then (only when credit would refuse) a read-only cancel lookup, then {@code 429}
     * {@code problems/credit}, then the admit. The reservation is taken before the producer seq is allocated, so a
     * {@code 429} leaves no trace, and released exactly once in the {@code finally} after the admit.
     */
    private Response creditChunk(StreamCredit cr, Task held, StreamChunk c, Runnable release) {
        var s = cr.session(c.streamHi(), c.streamLo());
        if (s == null) { // the session lives on another node (or is gone): credit unknown, never refused
            var r = this.swarm.tasks().streamChunk(held, this.producerId, this.seq.incrementAndGet(), c);
            var answer = admitted(r, held, release);
            if (answer == null) {
                cr.unknownSession();
                return new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE);
            }
            return answer;
        }
        var st = s.credit;
        long la = held.attempt() & StreamChunk.MAX_ATTEMPT;
        long sa = s.attempt;
        boolean reservedOne = false;
        long charged = 0;
        if (sa < 0 || la >= sa) { // an older attempt skips the check: the admit answers 409 STALE_ATTEMPT
            int v = cr.reserveVerdict(s, st, la, c.chunkSeq(), c.size());
            if (v == StreamCredit.REFUSE) {
                return refuse(cr, held, s, st, la, c, false);
            }
            // S2c PR 6b (#267 §3.1, §4.4): the node budget after the session; the filler and a newer attempt are never
            // refused; an out-of-order chunk that opens a gap also needs room for the gap ticket
            boolean opensGap = v == StreamCredit.ADMIT && la == s.attempt && c.chunkSeq() > st.maxSeq + 1
                    && st.heldChunks.get() == 0;
            boolean exempt = v != StreamCredit.ADMIT;
            charged = cr.budget.admit(st, c.size(), exempt, opensGap);
            if (charged < 0 && s.trimBeforeRefusal(st)) {
                // #272 review R1 (#267 §4.3, "trim … before refusing"): the session's own read chunks went first;
                // re-admit once, with the same exemption as the first call (an exempt chunk is never refused, so this
                // is only reached with exempt == false)
                charged = cr.budget.admit(st, c.size(), exempt, opensGap);
            }
            if (charged < 0) {
                cr.release(st, c.size());
                return refuse(cr, held, s, st, la, c, true);
            }
            reservedOne = true;
        }
        LeaseResult r;
        try {
            r = this.swarm.tasks().streamChunk(held, this.producerId, this.seq.incrementAndGet(), c);
        } finally {
            if (reservedOne) {
                cr.release(st, c.size()); // exactly once, after the chunk was published or discarded (#248 r4 B2)
                cr.budget.release(charged); // the same finally: request-scoped, released exactly once (#267 §3.1)
            }
        }
        var answer = admitted(r, held, release);
        return answer != null ? answer : creditFrame(cr.snapshot(s, st, c.streamHi(), c.streamLo(), la));
    }

    /** The admit's refusal answer, or null when it was accepted. */
    private Response admitted(LeaseResult r, Task held, Runnable release) {
        if (r == LeaseResult.OK) {
            return null;
        }
        if (r == LeaseResult.CANCELLED) { // still held: the worker sends LAST|CANCELLED or acks
            return SwarmHttp.cancelled(reason(held)).response();
        }
        release.run();
        return SwarmHttp.leaseProblem(r).response();
    }

    /**
     * No credit: {@code 409} {@code cancelled} if the task is cancelled (lookup on the refusal path only), else 429;
     * {@code node} (S2c PR 6b) marks a refusal by the node budget: the same 429 with {@code "scope":"node"}.
     */
    private Response refuse(StreamCredit cr, Task held, Session s, StreamCredit.State st, long la, StreamChunk c,
            boolean node) {
        var why = this.streams.cancels().reason(TaskService.topic(held.pool(), held.shard()), held.offset(), now());
        if (why != null) {
            return SwarmHttp.cancelled(why).response();
        }
        if (la == s.attempt && c.chunkSeq() <= st.maxSeq) {
            // #248 r5 C2: a retry of a chunk already accepted in this attempt; nothing to admit, the snapshot answers.
            // #257 N3: this skips the fence, so a duplicate of a fence-retired same-numbered attempt also gets 200 here
            // (not 409 STALE_ATTEMPT); nothing is written, and the worker's next new chunk meets the fence.
            return creditFrame(cr.snapshot(s, st, c.streamHi(), c.streamLo(), la));
        }
        if (node) {
            cr.budget.nodeRefused();
            return nodeRefusal();
        }
        cr.refused();
        var r = new SwarmHttp.Problem(429, "no credit", "stream credit exhausted; retry the same chunk", StreamCredit.TYPE,
                "credit").response();
        r.headers().set("retry-after", "1");
        return r;
    }

    /**
     * S2c PR 6b (#267 Q4): a refusal by the node stream-memory budget: {@code 429} {@code problems/credit} with
     * {@code "scope":"node"} and {@code Retry-After: 1}; session-scope refusals keep their shape (no scope member).
     */
    private static Response nodeRefusal() {
        var json = "{\"type\":\"" + StreamCredit.TYPE + "\",\"title\":\"no credit\",\"status\":429,\"detail\":"
                + "\"node stream memory budget exhausted; retry the same chunk\",\"reason\":\"credit\",\"scope\":\"node\"}";
        return new Response(429, ResponseHeaders.of().set("content-type", "application/problem+json").set("retry-after", "1"),
                Body.Bytes.of(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static Response creditFrame(sjws_nettask.swarm.wire.Credit credit) {
        return new Response(200, ResponseHeaders.of().set("content-type", SwarmHttp.CONTENT_TYPE),
                Body.Bytes.of(credit.toFrame().encode()));
    }
}
