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
import sjws_nettask.messaging.ledger.RecordBatch;

/**
 * Produce / fetch on plain log topics (docs/design/partition-log.md §2.2), mounted at {@code /messaging/v1}.
 * <ul>
 *   <li>{@code POST /messaging/v1/topics/{t}/records}: body = one or more {@code [len:int][bytes]} records, written as
 *       one batch. Optional {@code Durability: none|async|group}, never weaker than the topic policy; the response's
 *       {@code Durability-Effective} names the mode that applied. 200 {@code {"baseOffset":n,"count":c}} once it holds.</li>
 *   <li>{@code GET /messaging/v1/topics/{t}/records?offset=&maxBytes=}: body = raw {@link RecordBatch} frames, byte-identical
 *       to the on-disk format, from the batch containing {@code offset}; at least one batch if any, then up to
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
        if (!path.startsWith(PREFIX) || !path.endsWith("/records")) return problem(404, "not found", path);
        var topic = URLDecoder.decode(path.substring(PREFIX.length(), path.length() - "/records".length()), StandardCharsets.UTF_8);
        if (!LogTopics.validName(topic)) return problem(400, "bad request", "topic name must match " + LogTopics.NAME);
        try {
            return switch (request.method()) {
                case "POST" -> {
                    long t0 = System.nanoTime();
                    var r = produce(topic, request);
                    this.hooks.produced(topic, r.status(), Math.max(0, request.body().length()), System.nanoTime() - t0);
                    yield r;
                }
                case "GET" -> {
                    long t0 = System.nanoTime();
                    var r = fetch(topic, query(q < 0 ? "" : target.substring(q + 1)));
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
            return problem(500, "log error", e.toString());
        } catch (IllegalStateException e) {
            return problem(503, "unavailable", e.getMessage());
        }
    }

    private Response produce(String topic, Request request) throws BadRequest, IOException {
        // A request may only strengthen the topic's policy: a weaker Durability header is clamped up to it.
        var durability = atLeast(durability(request.headers().values("durability")), this.topics.durability(topic));
        var records = records(request);
        var log = this.topics.get(topic, true);
        try {
            long base = log.writer().append(records, durability).get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            var json = "{\"baseOffset\":" + base + ",\"count\":" + records.size() + "}";
            var effective = durability != null ? durability : this.topics.durability(topic);
            var h = ResponseHeaders.of().set("content-type", "application/json")
                    .set("durability-effective", effective.name().toLowerCase(java.util.Locale.ROOT));
            return new Response(200, h, Body.Bytes.of(json.getBytes(StandardCharsets.US_ASCII)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem(503, "unavailable", "interrupted");
        } catch (TimeoutException e) {
            return problem(503, "unavailable", "append not acknowledged in " + ACK_TIMEOUT_MS + " ms");
        } catch (ExecutionException e) {
            return problem(503, "unavailable", String.valueOf(e.getCause()));
        }
    }

    private Response fetch(String topic, Map<String, String> query) throws BadRequest, IOException {
        long offset = number(query, "offset", 0, 0, Long.MAX_VALUE);
        long maxBytes = number(query, "maxBytes", DEFAULT_FETCH_BYTES, 1, MAX_FETCH_BYTES);
        var log = this.topics.get(topic, false);
        if (log == null) return problem(404, "no such topic", topic);
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
                var b = log.log().read(next);
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
