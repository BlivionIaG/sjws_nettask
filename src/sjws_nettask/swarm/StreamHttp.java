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
 * <li>{@code POST /swarm/v1/tasks/{pool}:stream?deadlineMs=}: submits a streaming call. 201 with a {@code TASK}
 * frame whose payload is the 16-byte session id, and {@code Location: /swarm/v1/replies/{hex}}.</li>
 * <li>{@code GET /swarm/v1/replies/{session}}: the stream as {@code text/event-stream} (default; one
 * {@code event: chunk} per chunk, {@code id} = chunkSeq, data = the chunk bytes as UTF-8, one {@code data:} line per
 * line, then {@code event: end} with {@code data: ok|cancelled|error}), or as {@code TOKEN} frames when
 * {@code Accept: application/vnd.sjws.swarm.v1}. Resume with {@code Last-Event-ID: n} (from n + 1) or
 * {@code ?from=n} (from n), out of the session's retained chunks.</li>
 * <li>{@code POST /swarm/v1/leases/{leaseId}:chunk}: body one {@code TOKEN} frame (StreamChunk v1 payload; the stream
 * id must be the task's correlation id). 204; 409 when the lease is stale, or {@code cancelled} with
 * {@code "reason":"deadline"} once the task's deadline has passed. A {@code LAST} chunk writes the reply (body =
 * the chunk bytes) and acks once both are durable (§4.4).</li>
 * </ul>
 * <b>Session (§12 Q2):</b> a server-minted random 128-bit id ({@link SecureRandom}) bound to the
 * {@link SwarmGate.Agent} id. Unknown, expired, or another agent's session: 404 (existence is not revealed). One reader
 * at a time (409 otherwise). Sessions live in memory until {@code LAST} + {@link #GRACE_MS} or the call deadline + grace;
 * a node restart loses them. At most {@code maxSessions} per agent (503 beyond).
 * <b>Not yet (PR 5):</b> attempt in the chunk header and RESET (the §12 reset rule), resume across nodes / from the
 * ledger, cancel.
 */
final class StreamHttp {

    static final String SSE = "text/event-stream";
    static final long GRACE_MS = 60_000;
    static final long HEARTBEAT_MS = 15_000;
    static final int DEFAULT_MAX_SESSIONS = 64;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final Swarm swarm;
    private final TokenStreams streams;
    private final long producerId;
    private final AtomicLong seq;
    private final int maxSessions;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> perAgent = new ConcurrentHashMap<>();

    StreamHttp(Swarm swarm, long producerId, AtomicLong seq, int maxSessions) {
        this.swarm = swarm;
        this.streams = swarm.tasks().streams();
        if (this.streams == null) {
            throw new IllegalStateException("streaming is off");
        }
        this.producerId = producerId;
        this.seq = seq;
        this.maxSessions = maxSessions;
    }

    private long now() {
        return this.swarm.topics().clock().getAsLong();
    }

    int sessionCount() {
        return this.sessions.size();
    }

    /** One caller-facing stream: the bound agent, the local sink, and the chunks retained for resume. */
    static final class Session {
        final String agent;
        final TokenStreams.Sink sink;
        final long deadlineMs;
        final ReentrantLock lock = new ReentrantLock();
        final ArrayDeque<StreamChunk> retained = new ArrayDeque<>(); // guarded by lock
        final int retainMax;
        final AtomicBoolean reading = new AtomicBoolean();
        volatile StreamChunk last;
        volatile long expiresMs;

        Session(String agent, TokenStreams.Sink sink, long deadlineMs, int retainMax) {
            this.agent = agent;
            this.sink = sink;
            this.deadlineMs = deadlineMs;
            this.retainMax = retainMax;
            this.expiresMs = deadlineMs == TaskEnvelope.NO_DEADLINE ? Long.MAX_VALUE : deadlineMs + GRACE_MS;
        }

        /** Pulls what the sink has into the retained window (oldest dropped past retainMax). */
        void pump(long waitMs) throws InterruptedException {
            var c = this.sink.poll(waitMs, TimeUnit.MILLISECONDS);
            if (c == null) {
                return;
            }
            this.lock.lock();
            try {
                for (; c != null; c = this.sink.poll(0, TimeUnit.MILLISECONDS)) {
                    this.retained.addLast(c);
                    if (this.retained.size() > this.retainMax) {
                        this.retained.removeFirst();
                    }
                    if (c.last()) {
                        this.last = c;
                    }
                }
            } finally {
                this.lock.unlock();
            }
        }

        /** The first retained chunk with seq >= from; null when none yet; throws Gone when from was dropped. */
        StreamChunk at(long from) throws Gone {
            this.lock.lock();
            try {
                var first = this.retained.peekFirst();
                if (first != null && from < first.chunkSeq()) {
                    throw new Gone();
                }
                for (var c : this.retained) {
                    if (c.chunkSeq() >= from) {
                        return c;
                    }
                }
                return null;
            } finally {
                this.lock.unlock();
            }
        }
    }

    static final class Gone extends Exception {
        Gone() {
            super(null, null, false, false);
        }
    }

    /** {@code POST tasks/{pool}:stream}. */
    Response submit(SwarmGate.Agent agent, String pool, byte[] body, long deadlineMs, String traceparent) {
        sweep();
        var count = this.perAgent.get(agent.id());
        if (count == null) {
            var fresh = new AtomicLong();
            count = this.perAgent.putIfAbsent(agent.id(), fresh);
            count = count == null ? fresh : count;
        }
        if (count.incrementAndGet() > this.maxSessions) {
            count.decrementAndGet();
            return SwarmHttp.problem(503, "too many streams", "at most " + this.maxSessions + " open sessions per agent")
                    .response();
        }
        var correlation = ReplyRouter.newId();
        var sink = this.streams.open(correlation);
        var sid = new byte[16];
        RANDOM.nextBytes(sid);
        var key = HEX.formatHex(sid);
        this.sessions.put(key, new Session(agent.id(), sink, deadlineMs, this.streams.config().sinkChunks()));
        try {
            // the final reply goes to the caller's reply topic; the stream itself is read from the sink
            var env = new TaskEnvelope(correlation, correlation, this.swarm.replies().topic(), deadlineMs, traceparent,
                    "", "", "", body);
            this.swarm.tasks().submit(pool, 0, 0, env);
        } catch (RuntimeException e) {
            drop(key);
            throw e;
        }
        var r = new Response(201, ResponseHeaders.of().set("content-type", SwarmHttp.CONTENT_TYPE),
                Body.Bytes.of(new Frame(Frame.Type.TASK, 0, sid).encode()));
        r.headers().set("location", SwarmHttp.PREFIX + "replies/" + key);
        return r;
    }

    private void drop(String key) {
        var s = this.sessions.remove(key);
        if (s != null) {
            s.sink.close();
            var c = this.perAgent.get(s.agent);
            if (c != null) {
                c.decrementAndGet();
            }
        }
    }

    /** Drops expired sessions. */
    void sweep() {
        long now = now();
        for (var e : this.sessions.entrySet()) {
            if (e.getValue().expiresMs < now && !e.getValue().reading.get()) {
                drop(e.getKey());
            }
        }
    }

    /** {@code GET replies/{session}}. */
    Response replies(SwarmGate.Agent agent, String key, Request request, Map<String, String> query)
            throws SwarmHttp.BadRequest {
        var s = this.sessions.get(key);
        if (s == null || !s.agent.equals(agent.id()) || s.expiresMs < now()) {
            return SwarmHttp.problem(404, "not found", "no such session").response();
        }
        long from = SwarmHttp.number(query, "from", 0, 0, 0xFFFF_FFFFL);
        var lastId = request.headers().values("last-event-id");
        if (!lastId.isEmpty() && !query.containsKey("from")) {
            try {
                from = Long.parseLong(lastId.get(0).trim()) + 1;
            } catch (NumberFormatException e) {
                throw new SwarmHttp.BadRequest("Last-Event-ID must be a chunkSeq");
            }
        }
        boolean binary = request.headers().values("accept").stream().anyMatch(a -> a.contains(SwarmHttp.CONTENT_TYPE));
        if (!s.reading.compareAndSet(false, true)) {
            return SwarmHttp.problem(409, "session busy", "one reader at a time").response();
        }
        try {
            s.at(from);
        } catch (Gone g) {
            s.reading.set(false);
            return SwarmHttp.problem(410, "gone", "chunks before " + from + " are no longer retained").response();
        }
        var h = ResponseHeaders.of().set("content-type", binary ? SwarmHttp.CONTENT_TYPE : SSE)
                .set("cache-control", "no-store");
        return new Response(200, h, new Body.Stream(-1, new Reader(this, key, s, from, binary)));
    }

    /** Writes the session's chunks from {@code next}; one writer thread. */
    static final class Reader implements Body.Stream.Source {
        private final StreamHttp owner;
        private final String key;
        private final Session s;
        private final boolean binary;
        private long next;
        private boolean ended;
        private boolean closed;

        Reader(StreamHttp owner, String key, Session s, long from, boolean binary) {
            this.owner = owner;
            this.key = key;
            this.s = s;
            this.next = from;
            this.binary = binary;
        }

        @Override
        public ByteBuffer next() throws java.io.IOException {
            if (this.ended) {
                return null;
            }
            long waited = 0;
            while (true) {
                StreamChunk c;
                try {
                    c = this.s.at(this.next);
                } catch (Gone g) {
                    throw new java.io.IOException("reader fell behind the retained window");
                }
                if (c != null) {
                    this.next = c.chunkSeq() + 1;
                    if (c.last()) {
                        this.ended = true;
                        this.s.expiresMs = Math.min(this.s.expiresMs, this.owner.now() + GRACE_MS);
                    }
                    return ByteBuffer.wrap(this.binary ? c.toFrame().encode() : sse(c));
                }
                if (this.owner.now() > this.s.deadlineMs) {
                    this.ended = true;
                    return ByteBuffer.wrap(this.binary
                            ? new Frame(Frame.Type.ERROR, Frame.LAST, "deadline".getBytes(StandardCharsets.UTF_8)).encode()
                            : "event: end\ndata: deadline\n\n".getBytes(StandardCharsets.UTF_8));
                }
                try {
                    this.s.pump(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException();
                }
                waited += 250;
                if (!this.binary && waited >= HEARTBEAT_MS) {
                    return ByteBuffer.wrap(":\n\n".getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                this.s.reading.set(false);
                if (this.ended && this.s.last != null) {
                    this.owner.sweep();
                }
            }
        }
    }

    /** One SSE event: {@code id}, {@code event: chunk}, one {@code data:} line per line; {@code LAST} adds an end event. */
    static byte[] sse(StreamChunk c) {
        var b = new StringBuilder(c.size() + 48);
        if (c.size() > 0 || !c.last()) {
            b.append("id: ").append(c.chunkSeq()).append("\nevent: chunk\n");
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
            var how = (c.flags() & Frame.CANCELLED) != 0 ? "cancelled" : (c.flags() & Frame.ERROR) != 0 ? "error" : "ok";
            b.append("id: ").append(c.chunkSeq()).append("\nevent: end\ndata: ").append(how).append("\n\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
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
            return SwarmHttp.cancelled("deadline").response();
        }
        if (!c.last()) {
            var r = this.swarm.tasks().streamChunk(held, this.producerId, this.seq.incrementAndGet(), c);
            if (r != LeaseResult.OK) {
                release.run();
            }
            return r.ok() ? new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE) : SwarmHttp.leaseProblem(r).response();
        }
        LeaseResult r;
        try {
            long s = this.seq.addAndGet(2) - 1; // LAST uses s, the reply s + 1
            r = this.swarm.tasks().streamLast(held, this.producerId, s, c, "", c.bytes()).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = LeaseResult.NOT_IN_FLIGHT;
        } catch (ExecutionException | TimeoutException e) {
            r = LeaseResult.NOT_IN_FLIGHT;
        }
        release.run();
        return r.ok() ? new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE) : SwarmHttp.leaseProblem(r).response();
    }
}
