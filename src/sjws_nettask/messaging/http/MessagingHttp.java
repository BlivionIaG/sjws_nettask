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

    private final LogTopics topics;

    MessagingHttp(LogTopics topics) {
        this.topics = topics;
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
                case "POST" -> produce(topic, request);
                case "GET" -> fetch(topic, query(q < 0 ? "" : target.substring(q + 1)));
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
        var out = new ByteArrayOutputStream();
        long next = offset;
        // TODO(buffer pool, §2.4): sendfile / pooled view instead of re-encoding the decoded batch (same bytes).
        log.reads().lock();
        try {
        long written = 0; // long: compared with the long maxBytes without int overflow
        while (next < committed && written < maxBytes) {
            var b = log.log().read(next);
            if (b == null || b.nextOffset() > committed) break;
            var frame = b.encode();
            if (!fits(written, frame.remaining(), maxBytes)) break;
            out.write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining());
            written += frame.remaining();
            next = b.nextOffset();
        }
        } finally {
            log.reads().unlock();
        }
        var h = ResponseHeaders.of().set("content-type", BATCH_TYPE).set("next-offset", Long.toString(next))
                .set("log-end-offset", Long.toString(committed));
        return new Response(200, h, Body.Bytes.of(out.toByteArray()));
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

    static List<byte[]> records(Request request) throws BadRequest {
        var body = request.body();
        if (body.length() > MAX_REQUEST) throw new BadRequest("request body larger than " + MAX_REQUEST);
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
        var in = ByteBuffer.wrap(buf.toByteArray());
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
