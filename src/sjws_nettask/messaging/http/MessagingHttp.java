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
package sjws_nettask.messaging.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.buf.BufferBudget;
import sjws_nettask.core.buf.BufferPool;
import sjws_nettask.core.buf.PooledBuffer;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LeaderUnavailableException;
import sjws_nettask.messaging.ledger.RecordBatch;
import sjws_nettask.messaging.ledger.Replica;
import sjws_nettask.messaging.ledger.WriteOutcomeUnknownException;
import sjws_nettask.messaging.ledger.WriteRefusedException;

/**
 * Produce / fetch on plain log topics (docs/design/partition-log.md §2.2), mounted at {@code /messaging/v1}.
 * <ul>
 *   <li>{@code POST /messaging/v1/topics/{t}/records}: body = one or more {@code [len:int][bytes]} records, written as
 *       one batch. Optional {@code Durability: none|async|group}, never weaker than the topic policy; the response's
 *       {@code Durability-Effective} names the mode that applied. 200 {@code {"baseOffset":n,"count":c}} once it holds.</li>
 *   <li>{@code GET /messaging/v1/topics/{t}/records?offset=&maxBytes=}: body = raw {@link RecordBatch} frames, byte-identical
 *       to the on-disk format, from the batch containing {@code offset} (on a compacted topic, the next retained batch when
 *       {@code offset} was compacted away); at least one batch if any, then up to
 *       {@code maxBytes}. {@code Next-Offset} gives the offset to fetch next. Only committed batches are returned.</li>
 * </ul>
 * Errors are RFC 9457 problems.
 */
final class MessagingHttp implements Handler {

    static final String PREFIX = "/messaging/v1/topics/";
    static final String BATCH_TYPE = "application/vnd.sjws.batch";
    static final int MAX_REQUEST = 8 << 20;
    static final long DEFAULT_FETCH_BYTES = 1 << 20;
    static final long MAX_FETCH_BYTES = 16 << 20;
    static final long ACK_TIMEOUT_MS = 30_000;

    /** Retry-After for a fetch shed because the messaging buffer budget is exhausted. */
    static final String RETRY_AFTER_SECONDS = "1";

    private final LogTopics topics;
    private final BufferBudget budget;
    private volatile MessagingHooks hooks = MessagingHooks.NONE;

    MessagingHttp(LogTopics topics) {
        this(topics, BufferPool.shared().budget(BufferPool.MESSAGING));
    }

    MessagingHttp(LogTopics topics, BufferBudget budget) {
        this.topics = topics;
        this.budget = budget;
    }

    void hooks(MessagingHooks h) {
        this.hooks = java.util.Objects.requireNonNull(h);
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        int q = target.indexOf('?');
        var path = q < 0 ? target : target.substring(0, q);
        if (path.startsWith(PREFIX) && path.indexOf("/offsets/", PREFIX.length()) > 0) return offsets(request, path);
        if (!path.startsWith(PREFIX) || !path.endsWith("/records")) return problem(404, "not found", path);
        var topic = URLDecoder.decode(path.substring(PREFIX.length(), path.length() - "/records".length()), StandardCharsets.UTF_8);
        if (!LogTopics.validName(topic)) return problem(400, "bad request", "topic name must match " + LogTopics.NAME);
        try {
            return switch (request.method()) {
                case "POST" -> {
                    if (topic.startsWith("__")) yield problem(400, "bad request", "topics starting with __ are internal");
                    long t0 = System.nanoTime();
                    var r = produce(topic, request, target);
                    this.hooks.produced(topic, r.status(), Math.max(0, request.body().length()), System.nanoTime() - t0);
                    yield r;
                }
                case "GET" -> {
                    long t0 = System.nanoTime();
                    var r = fetch(topic, query(q < 0 ? "" : target.substring(q + 1)), request, target);
                    this.hooks.fetched(topic, r.status(), Math.max(0, r.contentLength()), System.nanoTime() - t0);
                    yield r;
                }
                default -> {
                    var r = problem(405, "method not allowed", "use GET or POST");
                    r.headers().set("allow", "GET, POST");
                    yield r;
                }
            };
        } catch (BadRequest e) {
            return problem(400, "bad request", e.getMessage());
        } catch (IOException e) {
            return logError(e, target);
        } catch (IllegalStateException e) {
            return unavailable(e, target);
        }
    }

    static final java.util.regex.Pattern OFFSET_JSON = java.util.regex.Pattern.compile("\\{\\s*\"offset\"\\s*:\\s*(\\d{1,19})\\s*}\\s*");

    /**
     * {@code PUT|GET /messaging/v1/topics/{t}/offsets/{group}} (durable-log-plan §2). PUT body {@code {"offset":n}}, the
     * next offset to fetch: 416 below the log start, 400 above the log end; stored in {@code __offsets} with GROUP
     * durability unless the topic policy or a {@code Durability} header (which may only strengthen it) says otherwise.
     * GET returns {@code {"offset":n,"lag":end-n}} or 404 when the group never committed.
     */
    private Response offsets(Request request, String path) {
        int at = path.indexOf("/offsets/", PREFIX.length());
        var topic = URLDecoder.decode(path.substring(PREFIX.length(), at), StandardCharsets.UTF_8);
        var group = URLDecoder.decode(path.substring(at + "/offsets/".length()), StandardCharsets.UTF_8);
        if (!LogTopics.validName(topic) || topic.startsWith("__")) return problem(400, "bad request", "bad topic name");
        if (!LogTopics.validName(group)) return problem(400, "bad request", "group name must match " + LogTopics.NAME);
        try {
            var log = this.topics.get(topic, false);
            if (log == null) return problem(404, "no such topic", topic);
            long end = log.writer().snapshot().nextOffset();
            var o = this.topics.offsets();
            switch (request.method()) {
                case "GET" -> {
                    long c = o.committed(topic, group);
                    if (c < 0) return problem(404, "no committed offset", group);
                    return json(200, "{\"offset\":" + c + ",\"lag\":" + Math.max(0, end - c) + "}", null);
                }
                case "PUT" -> {
                    var m = OFFSET_JSON.matcher(new String(small(request), StandardCharsets.UTF_8));
                    if (!m.matches()) throw new BadRequest("body must be {\"offset\":n}");
                    long off;
                    try { off = Long.parseLong(m.group(1)); } catch (NumberFormatException e) { throw new BadRequest("offset too large"); }
                    long start = log.log().startOffset();
                    if (off < start) return problem(416, "offset out of range", "log starts at " + start);
                    if (off > end) return problem(400, "bad request", "offset " + off + " is past the log end " + end);
                    var dflt = this.topics.durability(LogOffsets.TOPIC);
                    var d = atLeast(durability(request.headers().values("durability")), dflt);
                    var olog = this.topics.get(LogOffsets.TOPIC, false);
                    var fenced = olog == null ? null : fence(olog, request, request.target());
                    if (fenced != null) return fenced;
                    o.commit(topic, group, off, d).get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    this.hooks.offsetCommitted(topic, group, Math.max(0, end - off));
                    return json(200, "{\"offset\":" + off + "}", (d != null ? d : dflt).name().toLowerCase(java.util.Locale.ROOT));
                }
                default -> {
                    var r = problem(405, "method not allowed", "use GET or PUT");
                    r.headers().set("allow", "GET, PUT");
                    return r;
                }
            }
        } catch (BadRequest e) {
            return problem(400, "bad request", e.getMessage());
        } catch (IOException e) {
            return logError(e, request.target());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem(503, "unavailable", "interrupted");
        } catch (TimeoutException e) {
            // Like a produce timeout: the commit may still land (#254). Idempotent, but say so rather than "unavailable".
            return outcomeUnknown(offsetsReplica(), request.target());
        } catch (ExecutionException e) {
            return failed(e, request.target(), true);
        } catch (LeaderUnavailableException e) {
            return notLeader(e, request.target());
        } catch (IllegalStateException e) {
            return unavailable(e, request.target());
        }
    }

    static Response json(int status, String body, String durabilityEffective) {
        var h = ResponseHeaders.of().set("content-type", "application/json");
        if (durabilityEffective != null) h.set("durability-effective", durabilityEffective);
        return new Response(status, h, Body.Bytes.of(body.getBytes(StandardCharsets.US_ASCII)));
    }

    /** A small request body (at most 1 KiB). */
    static byte[] small(Request request) throws BadRequest {
        var body = request.body();
        var out = new ByteArrayOutputStream();
        var chunk = new byte[256];
        try {
            int n;
            while ((n = body.read(chunk, 0, chunk.length)) >= 0) {
                out.write(chunk, 0, n);
                if (out.size() > 1024) throw new BadRequest("body larger than 1024 bytes");
            }
        } catch (IOException e) {
            throw new BadRequest("request body: " + e.getMessage());
        }
        return out.toByteArray();
    }

    /** The offsets topic's replica, for a leader hint; null when not replicated or not loadable. */
    private Replica offsetsReplica() {
        try {
            var olog = this.topics.get(LogOffsets.TOPIC, false);
            return olog == null ? null : olog.replica();
        } catch (IOException e) {
            return null;
        }
    }

    private Response produce(String topic, Request request, String target) throws BadRequest, IOException {
        // A request may only strengthen the topic's policy: a weaker Durability header is clamped up to it.
        var durability = atLeast(durability(request.headers().values("durability")), this.topics.durability(topic));
        var records = records(request);
        var log = this.topics.get(topic, true);
        var fenced = fence(log, request, target);
        if (fenced != null) return fenced;
        try {
            long base = log.appender().append(records, durability).get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            var json = "{\"baseOffset\":" + base + ",\"count\":" + records.size() + "}";
            var effective = durability != null ? durability : this.topics.durability(topic);
            var h = ResponseHeaders.of().set("content-type", "application/json")
                    .set("durability-effective", effective.name().toLowerCase(java.util.Locale.ROOT));
            return new Response(200, h, Body.Bytes.of(json.getBytes(StandardCharsets.US_ASCII)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem(503, "unavailable", "interrupted");
        } catch (TimeoutException e) {
            // The append may still commit after we stop waiting: outcome unknown, never a redirect (#254).
            return outcomeUnknown(log.replica(), target);
        } catch (ExecutionException e) {
            return failed(e, target, true);
        } catch (LeaderUnavailableException e) {
            return notLeader(e, target);
        }
    }

    private Response fetch(String topic, Map<String, String> query, Request request, String target) throws BadRequest, IOException {
        long offset = number(query, "offset", 0, 0, Long.MAX_VALUE);
        long maxBytes = number(query, "maxBytes", DEFAULT_FETCH_BYTES, 1, MAX_FETCH_BYTES);
        var log = this.topics.get(topic, false);
        if (log == null) return problem(404, "no such topic", topic);
        if (log.replica() != null && leaderRead(query, request)) { // sketch §5: default reads this node's applied data
            try {
                log.replica().leaderRead().get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return problem(503, "unavailable", "interrupted");
            } catch (TimeoutException e) {
                return problem(503, "unavailable", "leader read not confirmed in " + ACK_TIMEOUT_MS + " ms");
            } catch (ExecutionException e) {
                return failed(e, target, false);
            } catch (LeaderUnavailableException e) {
                return notLeader(e, target);
            }
        }
        long committed = log.writer().snapshot().nextOffset();
        long start = log.log().startOffset();
        if (offset < start) return problem(416, "offset out of range", "log starts at " + start);
        long next = offset;
        var batches = new ArrayList<RecordBatch>();
        int largest = 0;
        long total = 0; // long: compared with the long maxBytes without int overflow
        log.reads().readLock().lock();
        try {
            while (next < committed && total < maxBytes) {
                var b = log.topicCompacted() ? log.log().readCeil(next) : log.log().read(next); // compacted: skip gaps
                if (b == null || b.nextOffset() > committed) break;
                int size = RecordBatch.encodedSize(b.records());
                if (!fits(total, size, maxBytes)) break;
                batches.add(b);
                largest = Math.max(largest, size);
                total += size;
                next = b.nextOffset();
            }
        } finally {
            log.reads().readLock().unlock();
        }
        var h = ResponseHeaders.of().set("content-type", BATCH_TYPE).set("next-offset", Long.toString(next))
                .set("log-end-offset", Long.toString(committed));
        if (batches.isEmpty()) return new Response(200, h, Body.Bytes.of(new byte[0]));
        // Frames are encoded off-lock into one pooled buffer of the messaging budget, reused chunk by chunk; under
        // budget pressure the fetch is shed (503 + Retry-After) instead of allocating (partition-log.md §2.4).
        var buf = this.budget.tryAcquire(chunkBytes(largest, total));
        if (buf == null) {
            var r = problem(503, "busy", "messaging buffer budget exhausted");
            r.headers().set("retry-after", RETRY_AFTER_SECONDS);
            return r;
        }
        return new Response(200, h, new Body.Stream(total, new FrameSource(batches, buf)));
    }

    /** Chunk size, sized from the frames: the largest frame (so one frame fits a chunk), or the batch total up to
     * {@link #MIN_CHUNK} when frames are small; capped at {@link BufferPool#MAX_CLASS} (larger frames go out as heap). */
    static int chunkBytes(int largestFrame, long total) {
        return (int) Math.min(BufferPool.MAX_CLASS, Math.min(total, Math.max(largestFrame, MIN_CHUNK)));
    }

    static final int MIN_CHUNK = 16 << 10;

    /** Streams whole frames; a frame larger than the pooled buffer goes out as its own heap buffer. */
    static final class FrameSource implements Body.Stream.Source {
        private final List<RecordBatch> batches;
        private PooledBuffer buf;
        private int i;

        FrameSource(List<RecordBatch> batches, PooledBuffer buf) {
            this.batches = batches;
            this.buf = buf;
        }

        @Override
        public ByteBuffer next() {
            if (this.i >= this.batches.size() || this.buf == null) return null;
            var b = this.batches.get(this.i);
            var out = this.buf.buffer().clear();
            if (RecordBatch.encodedSize(b.records()) > out.capacity()) {
                this.i++;
                return b.encode();
            }
            while (this.i < this.batches.size()) {
                b = this.batches.get(this.i);
                if (RecordBatch.encodedSize(b.records()) > out.remaining()) break;
                RecordBatch.encode(b.baseOffset(), b.timestampMs(), b.records(), out);
                this.i++;
            }
            return out.flip();
        }

        @Override
        public void close() {
            if (this.buf != null) {
                this.buf.release();
                this.buf = null;
            }
        }
    }

    /** Whether a frame of {@code frameBytes} may follow {@code written} bytes: the first frame always fits. */
    static boolean fits(long written, long frameBytes, long maxBytes) {
        return written == 0 || written + frameBytes <= maxBytes;
    }

    /**
     * The per-append mode actually used: {@code requested} unless it is weaker than the topic's writer default, in
     * which case the default (null keeps the writer default). Strength follows {@link Durability} order:
     * NONE < ASYNC < GROUP < EACH.
     */
    static Durability atLeast(Durability requested, Durability topicDefault) {
        if (requested == null || requested.ordinal() >= topicDefault.ordinal()) return requested;
        return topicDefault;
    }

    static Durability durability(List<String> values) throws BadRequest {
        if (values.isEmpty()) return null;
        return switch (values.get(0).trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> Durability.NONE;
            case "async" -> Durability.ASYNC;
            case "group" -> Durability.GROUP;
            default -> throw new BadRequest("durability must be none, async or group");
        };
    }

    /**
     * The request's records. A body of known length up to {@link BufferPool#MAX_LARGE} is read into one buffer of
     * the messaging budget (released before return); an unknown length falls back to a growing heap buffer.
     */
    List<byte[]> records(Request request) throws BadRequest {
        var body = request.body();
        long len = body.length();
        if (len > MAX_REQUEST) throw new BadRequest("request body larger than " + MAX_REQUEST);
        if (len >= 0 && len <= BufferPool.MAX_LARGE) {
            if (len == 0) throw new BadRequest("no records");
            var pb = len <= BufferPool.MAX_CLASS ? this.budget.acquire((int) len) : this.budget.acquireLarge((int) len);
            try {
                var b = pb.buffer().clear().limit((int) len);
                var chunk = new byte[(int) Math.min(len, 8192)];
                try {
                    int n;
                    while (b.hasRemaining() && (n = body.read(chunk, 0, Math.min(chunk.length, b.remaining()))) >= 0) b.put(chunk, 0, n);
                    if (b.hasRemaining() || body.read(chunk, 0, 1) >= 0) throw new BadRequest("request body does not match its length");
                } catch (IOException e) {
                    throw new BadRequest("request body: " + e.getMessage());
                }
                return parse(b.flip());
            } finally {
                pb.release();
            }
        }
        var buf = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        try {
            int n;
            while ((n = body.read(chunk, 0, chunk.length)) >= 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_REQUEST) throw new BadRequest("request body larger than " + MAX_REQUEST);
            }
        } catch (IOException e) {
            throw new BadRequest("request body: " + e.getMessage());
        }
        return parse(ByteBuffer.wrap(buf.toByteArray()));
    }

    static List<byte[]> parse(ByteBuffer in) throws BadRequest {
        var out = new ArrayList<byte[]>();
        while (in.hasRemaining()) {
            if (in.remaining() < 4) throw new BadRequest("truncated record length");
            int len = in.getInt();
            if (len < 0 || len > in.remaining()) throw new BadRequest("record length " + len + " exceeds body");
            var r = new byte[len];
            in.get(r);
            out.add(r);
        }
        if (out.isEmpty()) throw new BadRequest("no records");
        return out;
    }

    static long number(Map<String, String> q, String name, long dflt, long min, long max) throws BadRequest {
        var v = q.get(name);
        if (v == null || v.isEmpty()) return dflt;
        try {
            long n = Long.parseLong(v);
            if (n < min || n > max) throw new BadRequest(name + " must be in " + min + ".." + max);
            return n;
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be a number");
        }
    }

    /** {@code ?consistency=leader} or {@code Read-Consistency: leader}. */
    private static boolean leaderRead(Map<String, String> query, Request request) {
        if ("leader".equalsIgnoreCase(query.get("consistency"))) return true;
        for (var v : request.headers().values("read-consistency")) if ("leader".equalsIgnoreCase(v.trim())) return true;
        return false;
    }

    /**
     * Replicated topics only: a write on a node that is not the ready leader, or carrying an {@code If-Term} other than
     * the current term, is refused before it is proposed (sketch §3 stopgap, §6). Null when the write may proceed.
     */
    private static Response fence(LogTopics.Log log, Request request, String target) {
        var rep = log.replica();
        if (rep == null) return null;
        var ifTerm = request.headers().values("if-term");
        if (!ifTerm.isEmpty()) {
            long t;
            try { t = Long.parseLong(ifTerm.get(0).trim()); } catch (NumberFormatException e) { return problem(400, "bad request", "If-Term must be a number"); }
            long now = rep.term();
            if (t != now) {
                var r = problem(409, "stale term", "If-Term " + t + " but the partition is at term " + now);
                r.headers().set("sjws-term", Long.toString(now));
                return r;
            }
        }
        if (!rep.leaderReady()) return notLeader(new LeaderUnavailableException("not the ready leader", rep.term(), rep.leaderName(), rep.leaderUrl()), target);
        return null;
    }

    /**
     * A replicated write or leader read whose future failed for a reason other than leadership. The internal error
     * (storage paths, group names, exception text) is logged here and never sent to the client (#246). A write gets
     * the generic outcome-unknown problem: it may or may not commit (a group that failed closed, a lost leader), so
     * the client retries it as after a timeout and deduplicates. A read has no outcome, so it is plain unavailable.
     */
    static Response failed(ExecutionException e, String target, boolean write) {
        Throwable c = e.getCause();
        while (c instanceof java.util.concurrent.CompletionException && c.getCause() != null) c = c.getCause();
        if (c instanceof LeaderUnavailableException l) return notLeader(l, target);
        if (write && c instanceof WriteRefusedException r) return refused(r, target);
        FAILURES.log(System.Logger.Level.WARNING, (write ? "write" : "leader read") + " on " + target + " failed", c);
        if (write && c instanceof WriteOutcomeUnknownException u) return outcomeUnknown(u.term(), u.leaderName(), u.leaderUrl(), target);
        if (write) return outcomeUnknown(-1, null, null, target);
        var r = problem(503, "unavailable", "leader read failed");
        r.headers().set("retry-after", RETRY_AFTER_SECONDS);
        return r;
    }

    static final String OUTCOME_UNKNOWN_TYPE = "https://sjws.dev/problems/outcome-unknown";

    /** Problem type for a write the log refused before appending: nothing landed, resending cannot duplicate. */
    static final String NOT_APPENDED_TYPE = "https://sjws.dev/problems/not-appended";

    /**
     * A write refused before it entered the log (#254): a retryable refusal (backpressure, STRICT copies
     * unreachable, storage error rolled back or group fenced) is 503 + Retry-After, an unacceptable batch 400; both
     * with the not-appended type and a generic detail (the cause is logged one-line, not sent).
     */
    static Response refused(WriteRefusedException e, String target) {
        LOG.log(System.Logger.Level.DEBUG, () -> "write on " + target + " refused: " + e.getMessage());
        int status = e.retryable() ? 503 : 400;
        var json = "{\"type\":\"" + NOT_APPENDED_TYPE + "\",\"title\":\"not appended\",\"status\":" + status
                + ",\"detail\":\"" + (e.retryable() ? "the log refused the write for now and nothing was appended;"
                        + " retry it" : "the log refused this batch and nothing was appended") + "\"}";
        var h = ResponseHeaders.of().set("content-type", "application/problem+json");
        if (e.retryable()) h.set("retry-after", RETRY_AFTER_SECONDS);
        return new Response(status, h, Body.Bytes.of(json.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@link #outcomeUnknown(long, String, String, String)} with the partition's current leader as the hint. */
    static Response outcomeUnknown(Replica rep, String target) {
        if (rep == null) return outcomeUnknown(-1, null, null, target);
        return outcomeUnknown(rep.term(), rep.leaderName(), rep.leaderUrl(), target);
    }

    /**
     * A write the log accepted whose fate is unknown (leadership lost, failed closed, closed, or no ack in
     * {@link #ACK_TIMEOUT_MS}): 503 + Retry-After with the outcome-unknown problem type and the leader as a hint
     * ({@code sjws-leader}, {@code sjws-term}, and {@code leader}/{@code leaderUrl} in the body), <b>never</b> a 307.
     * A redirect resends the body, and topic produce has no idempotency key, so a write that does commit would be
     * stored twice (#254). The client reads back (from the leader) or retries with its own deduplication.
     */
    static Response outcomeUnknown(long term, String leaderName, String leaderUrl, String target) {
        var json = new StringBuilder("{\"type\":\"").append(OUTCOME_UNKNOWN_TYPE).append("\",\"title\":\"outcome unknown\",")
                .append("\"status\":503,\"detail\":\"the write may or may not have been committed; read back or retry it")
                .append(" with deduplication, as after a timeout\"");
        var h = ResponseHeaders.of().set("content-type", "application/problem+json").set("retry-after", RETRY_AFTER_SECONDS);
        if (term >= 0) {
            json.append(",\"term\":").append(term);
            h.set("sjws-term", Long.toString(term));
        }
        if (leaderName != null) {
            json.append(",\"leader\":").append(quote(leaderName));
            h.set("sjws-leader", leaderName);
        }
        if (leaderUrl != null) {
            var base = leaderUrl.endsWith("/") ? leaderUrl.substring(0, leaderUrl.length() - 1) : leaderUrl;
            json.append(",\"leaderUrl\":").append(quote(base + target));
        }
        json.append('}');
        return new Response(503, h, Body.Bytes.of(json.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static final System.Logger LOG = System.getLogger(MessagingHttp.class.getName());

    /** Per-request failure logs: a full trace once per {@link #TRACE_INTERVAL} per exception type, else one line. */
    static final java.time.Duration TRACE_INTERVAL = java.time.Duration.ofSeconds(30);
    private static final ThrottledLog FAILURES = new ThrottledLog(LOG, TRACE_INTERVAL);

    /** A log I/O failure: 500 with a generic detail; the exception (paths, errno text) is logged, not sent (#246). */
    static Response logError(IOException e, String target) {
        FAILURES.log(System.Logger.Level.ERROR, "messaging log I/O failed on " + target, e);
        return problem(500, "log error", "messaging log I/O failed");
    }

    /**
     * The messaging log cannot serve this request (closed, or the topic is not set up for it on this node): 503 with
     * a generic detail and Retry-After; the exception text is logged, not sent (#246).
     */
    static Response unavailable(IllegalStateException e, String target) {
        FAILURES.log(System.Logger.Level.WARNING, "messaging log unavailable on " + target, e);
        var r = problem(503, "unavailable", "messaging log unavailable");
        r.headers().set("retry-after", RETRY_AFTER_SECONDS);
        return r;
    }

    /** 307 to the leader when known (method and body preserved), else 503 + Retry-After; RFC 9457 type not-leader. */
    static Response notLeader(LeaderUnavailableException e, String target) {
        boolean redirect = e.leaderUrl() != null;
        var json = "{\"type\":\"" + NOT_LEADER_TYPE + "\",\"title\":\"not leader\",\"status\":" + (redirect ? 307 : 503)
                + ",\"detail\":" + quote(String.valueOf(e.getMessage())) + ",\"term\":" + e.term() + "}";
        var h = ResponseHeaders.of().set("content-type", "application/problem+json").set("sjws-term", Long.toString(e.term()));
        if (e.leaderName() != null) h.set("sjws-leader", e.leaderName());
        if (redirect) {
            var base = e.leaderUrl();
            h.set("location", (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + target);
        } else {
            h.set("retry-after", RETRY_AFTER_SECONDS);
        }
        return new Response(redirect ? 307 : 503, h, Body.Bytes.of(json.getBytes(StandardCharsets.UTF_8)));
    }

    static final String NOT_LEADER_TYPE = "https://sjws.dev/problems/not-leader";

    static Map<String, String> query(String q) {
        var out = new HashMap<String, String>();
        for (var part : q.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            out.put(URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8),
                    eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static Response problem(int status, String title, String detail) {
        var json = "{\"type\":\"about:blank\",\"title\":" + quote(title) + ",\"status\":" + status
                + ",\"detail\":" + quote(detail == null ? "" : detail) + "}";
        var h = ResponseHeaders.of().set("content-type", "application/problem+json");
        return new Response(status, h, Body.Bytes.of(json.getBytes(StandardCharsets.UTF_8)));
    }

    static String quote(String s) {
        var b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static final class BadRequest extends Exception {
        BadRequest(String m) { super(m); }
    }
}
