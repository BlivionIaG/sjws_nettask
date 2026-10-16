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

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.swarm.wire.Frame;

/**
 * The unary {@code /swarm/v1} endpoints (agent-swarm.md §11.2). Bodies are swarm.wire v1 frames
 * ({@value #CONTENT_TYPE}); errors are RFC 9457 {@code application/problem+json}.
 * <ul>
 * <li>{@code POST /swarm/v1/tasks/{pool}}: body one {@code TASK} frame (payload = task body). Submits without a
 * reply topic. 202, body a {@code TASK} frame whose payload is the 16-byte task id.</li>
 * <li>{@code POST /swarm/v1/tasks/{pool}:call}: request/reply. Body one {@code TASK} frame. Waits for the reply
 * up to {@code ?deadlineMs=} (relative, default 30 000). 200 with a {@code RESULT} frame, or 504.</li>
 * <li>{@code POST /swarm/v1/tasks/{pool}:claim?max=&visibilityMs=&waitMs=}: 200 with one {@code LEASE} frame per
 * claimed task, or 204. {@code LEASE} payload: {@code [idLen:u16][leaseId UTF-8][leaseDeadlineMs:i64][body]}.</li>
 * <li>{@code POST /swarm/v1/leases/{leaseId}:ack|nack|reject|extend|reply}: fenced settles. {@code nack}
 * takes {@code ?delayMs=}, {@code reject} {@code ?reason=}, {@code extend} {@code ?visibilityMs=}; {@code reply}
 * takes a {@code RESULT} frame, writes it ASYNC and acks once it is durable (§4.4). 204 on OK; 409 when the
 * lease is stale or no longer in flight.</li>
 * </ul>
 * A lease id is {@code pool~offset~attempt}, so the attempt (the fencing token) always travels with it.
 */
final class SwarmHttp implements Handler {

    static final String PREFIX = "/swarm/v1/";
    static final String CONTENT_TYPE = "application/vnd.sjws.swarm.v1";
    /** One frame of the 1 MiB inline limit plus its header and a little slack. */
    static final int MAX_REQUEST = TaskEnvelope.MAX_INLINE_BODY + Frame.HEADER + 1024;
    static final long DEFAULT_DEADLINE_MS = 30_000;
    static final long MAX_WAIT_MS = 30_000;

    private final Swarm swarm;
    private final long workerProducerId;
    private final java.util.concurrent.atomic.AtomicLong replySeq = new java.util.concurrent.atomic.AtomicLong();
    /**
     * Tasks claimed over HTTP, by lease id, so {@code :reply} can use the envelope's replyTo and correlation id
     * without trusting the client for them. Removed on settle; entries whose lease ran out are swept on claim.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Task> claimed = new java.util.concurrent.ConcurrentHashMap<>();

    private final SwarmGate gate;
    /** Requests refused with 401 (no or invalid credential); read by tests and, later, metrics. */
    private final java.util.concurrent.atomic.LongAdder failedAuth = new java.util.concurrent.atomic.LongAdder();

    long failedAuth() {
        return this.failedAuth.sum();
    }

    /**
     * S2b trace propagation (swarm §9). When false, {@code sjws_nettask.swarm.trace} is never referenced, so it is
     * not loaded and nothing is allocated for tracing.
     */
    private final boolean trace;

    /**
     * S2c HTTP leg (PR 4), or null with {@code sjws.swarm.stream.enabled} off: then {@link StreamHttp} is never
     * referenced, so it is not loaded, and the stream routes answer 404.
     */
    private final StreamHttp streams;

    SwarmHttp(Swarm swarm, long workerProducerId, SwarmGate gate) {
        this(swarm, workerProducerId, gate, false);
    }

    SwarmHttp(Swarm swarm, long workerProducerId, SwarmGate gate, boolean trace) {
        this.swarm = swarm;
        this.workerProducerId = workerProducerId;
        this.gate = java.util.Objects.requireNonNull(gate);
        this.trace = trace;
        this.streams = swarm.tasks().streams() == null ? null
                : new StreamHttp(swarm, workerProducerId, this.replySeq, StreamHttp.MAX_SESSIONS_PER_AGENT);
    }

    StreamHttp streams() {
        return this.streams;
    }

    /** The validated ingress traceparent, or {@code ""} (always {@code ""} with tracing off). */
    private String traceparent(Request request) {
        if (!this.trace) {
            return "";
        }
        return sjws_nettask.swarm.trace.SwarmTrace.ingress(
                request.headers().values(sjws_nettask.swarm.trace.SwarmTrace.HEADER));
    }

    @Override
    public Response handle(Request request) {
        // deny by default: authenticate before anything else, so an unauthenticated caller learns nothing
        var agent = this.gate.authenticate(request);
        if (agent == null) {
            this.failedAuth.increment();
            boolean presented = !request.headers().values("authorization").isEmpty();
            var r = problem(401, "unauthorized", "agent credential required").response();
            r.headers().set("www-authenticate",
                    presented ? "Bearer realm=\"sjws-swarm\", error=\"invalid_token\"" : "Bearer realm=\"sjws-swarm\"");
            return r;
        }
        long len = request.body().length();
        if (len > this.gate.maxRequestBytes()) {
            return problem(413, "content too large", "request body larger than " + this.gate.maxRequestBytes())
                    .response();
        }
        if (len < 0) {
            request = request.withBody(capped(request.body(), this.gate.maxRequestBytes()));
        }
        if (!"POST".equals(request.method())) {
            return notPost(agent, request);
        }
        var target = request.target();
        int q = target.indexOf('?');
        var path = q < 0 ? target : target.substring(0, q);
        var query = q < 0 ? Map.<String, String>of() : query(target.substring(q + 1));
        if (!path.startsWith(PREFIX)) {
            return problem(404, "not found", path).response();
        }
        var rest = path.substring(PREFIX.length());
        int colon = rest.lastIndexOf(':');
        var verb = colon < 0 ? "" : rest.substring(colon + 1);
        var noun = colon < 0 ? rest : rest.substring(0, colon);
        try {
            if (noun.startsWith("tasks/")) {
                var pool = decode(noun.substring("tasks/".length()));
                if (!this.gate.allows(agent, pool)) {
                    return forbidden(pool);
                }
                return switch (verb) {
                    case "" -> submit(pool, request, query);
                    case "call" -> call(pool, request, query);
                    case "claim" -> claim(pool, query);
                    case "stream" -> this.streams == null ? problem(404, "unknown task verb", verb).response()
                            : this.streams.submit(agent, pool, taskBody(request),
                                    deadline(query, this.swarm.topics().clock().getAsLong() + DEFAULT_DEADLINE_MS),
                                    traceparent(request));
                    default -> problem(404, "unknown task verb", verb).response();
                };
            }
            if (noun.startsWith("leases/")) {
                var leaseId = decode(noun.substring("leases/".length()));
                var task = parseLease(leaseId);
                if (!this.gate.allows(agent, task.pool())) {
                    return forbidden(task.pool());
                }
                return lease(leaseId, task, verb, request, query);
            }
            return problem(404, "not found", path).response();
        } catch (TooLarge e) {
            return problem(413, "content too large", e.getMessage()).response();
        } catch (BadRequest e) {
            return problem(400, "bad request", e.getMessage()).response();
        } catch (IllegalArgumentException e) {
            return problem(400, "bad request", e.getMessage()).response();
        }
    }

    private Response submit(String pool, Request request, Map<String, String> query) throws BadRequest {
        var body = taskBody(request);
        var id = ReplyRouter.newId();
        long deadline = deadline(query, TaskEnvelope.NO_DEADLINE);
        var env = new TaskEnvelope(id, id, "", deadline, traceparent(request), "", "", "", body);
        // producerId 0: no dedup for anonymous HTTP submits (an Idempotency-Key binding is a follow-up)
        this.swarm.tasks().submit(pool, 0, 0, env);
        return frame(202, new Frame(Frame.Type.TASK, 0, id));
    }

    private Response call(String pool, Request request, Map<String, String> query) throws BadRequest {
        var body = taskBody(request);
        long deadline = deadline(query, this.swarm.topics().clock().getAsLong() + DEFAULT_DEADLINE_MS);
        var f = this.swarm.replies().call(pool, "", body, deadline, traceparent(request));
        try {
            var reply = f.get(Math.max(0, deadline - this.swarm.topics().clock().getAsLong()) + 1_000,
                    TimeUnit.MILLISECONDS);
            var ok = frame(200, new Frame(Frame.Type.RESULT, Frame.LAST, reply.body()));
            if (this.trace) {
                // the stored value may come from an in-process caller: re-validate before it becomes a header
                var tp = sjws_nettask.swarm.trace.SwarmTrace.parse(reply.traceparent());
                if (!tp.isEmpty()) {
                    ok.headers().set(sjws_nettask.swarm.trace.SwarmTrace.HEADER, tp);
                }
            }
            return ok;
        } catch (TimeoutException e) {
            return problem(504, "deadline exceeded", "no reply from " + pool).response();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TimeoutException) {
                return problem(504, "deadline exceeded", "no reply from " + pool).response();
            }
            return problem(503, "unavailable", String.valueOf(e.getCause())).response();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return problem(503, "unavailable", "interrupted").response();
        }
    }

    private Response claim(String pool, Map<String, String> query) throws BadRequest {
        int max = (int) number(query, "max", 1, 1, 256);
        long visibility = number(query, "visibilityMs", 30_000, 1, 3_600_000);
        long wait = number(query, "waitMs", 0, 0, MAX_WAIT_MS);
        sweep();
        var got = this.swarm.tasks().claim(pool, "http", max, visibility, TimeUnit.MILLISECONDS.toNanos(wait));
        if (got.isEmpty()) {
            return new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE);
        }
        var out = new java.io.ByteArrayOutputStream();
        StringBuilder spans = this.trace ? new StringBuilder() : null;
        for (var t : got) {
            if (spans != null) {
                // a child span per claim; the cached envelope carries it, so :reply propagates it
                var child = sjws_nettask.swarm.trace.SwarmTrace.child(t.envelope().traceparent());
                if (!child.isEmpty()) {
                    t = new Task(t.pool(), t.offset(), t.attempt(), t.leaseDeadlineMs(),
                            t.envelope().withTraceparent(child), t.shard());
                }
                spans.append(spans.isEmpty() ? "" : ",").append(child.isEmpty() ? "-" : child);
            }
            var key = leaseId(t);
            this.claimed.put(key, t);
            var id = key.getBytes(StandardCharsets.UTF_8);
            var body = t.envelope().body();
            var p = ByteBuffer.allocate(2 + id.length + 8 + body.length).putShort((short) id.length).put(id)
                    .putLong(t.leaseDeadlineMs()).put(body).array();
            out.writeBytes(new Frame(Frame.Type.LEASE, 0, p).encode());
        }
        var ok = bytes(200, out.toByteArray());
        if (spans != null) {
            ok.headers().set(sjws_nettask.swarm.trace.SwarmTrace.LEASE_HEADER, spans.toString());
        }
        return ok;
    }

    private Response lease(String id, Task t, String verb, Request request, Map<String, String> query)
            throws BadRequest {
        var held = this.claimed.get(id);
        LeaseResult r;
        switch (verb) {
            case "ack" -> r = this.swarm.tasks().ack(t);
            case "nack" -> r = this.swarm.tasks().nack(t, number(query, "delayMs", 0, 0, 3_600_000));
            case "reject" -> r = this.swarm.tasks().reject(t, query.getOrDefault("reason", "rejected"));
            case "extend" -> r = this.swarm.tasks().extend(t.pool(), t.shard(), new long[] {t.offset()}, new int[] {t.attempt()},
                    number(query, "visibilityMs", 30_000, 1, 3_600_000)).get(0);
            case "chunk" -> {
                if (this.streams == null) {
                    return problem(404, "unknown lease verb", verb).response();
                }
                var f = readFrame(request);
                return this.streams.chunk(held, f, () -> this.claimed.remove(id));
            }
            case "reply" -> {
                var f = readFrame(request);
                if (f.type() != Frame.Type.RESULT) {
                    throw new BadRequest("expected a RESULT frame");
                }
                r = held == null ? null : reply(held, f.payload());
                if (r == null) {
                    return problem(409, "lease not held", "task has no reply topic or is not in flight").response();
                }
            }
            default -> {
                return problem(404, "unknown lease verb", verb).response();
            }
        }
        if (!"extend".equals(verb) || !r.ok() && r != LeaseResult.CANCELLED) { // CANCELLED: still held, settle it
            this.claimed.remove(id);
        } else if (held != null && r.ok()) { // CANCELLED: messaging did not extend, so the cached deadline stays
            long until = this.swarm.topics().clock().getAsLong() + number(query, "visibilityMs", 30_000, 1, 3_600_000);
            this.claimed.put(id, held.withDeadline(until));
        }
        if (r.ok()) {
            return new Response(204, ResponseHeaders.of(), Body.Empty.INSTANCE);
        }
        return leaseProblem(r).response();
    }

    /** Non-POST requests: {@code GET replies/{session}} when streaming is on; otherwise 404 there and 405 elsewhere. */
    private Response notPost(SwarmGate.Agent agent, Request request) {
        var target = request.target();
        int q = target.indexOf('?');
        var path = q < 0 ? target : target.substring(0, q);
        var replies = PREFIX + "replies/";
        if (!path.startsWith(replies) || path.length() == replies.length()) {
            return problem(405, "method not allowed", "use POST").withAllow();
        }
        if (this.streams == null) {
            return problem(404, "not found", path).response();
        }
        if (!"GET".equals(request.method())) {
            var r = problem(405, "method not allowed", "use GET").response();
            r.headers().set("allow", "GET");
            return r;
        }
        try {
            return this.streams.replies(agent, decode(path.substring(replies.length())), request,
                    q < 0 ? Map.of() : query(target.substring(q + 1)));
        } catch (BadRequest e) {
            return problem(400, "bad request", e.getMessage()).response();
        }
    }

    static final String CANCELLED_TYPE = "https://sjws.dev/problems/cancelled";

    /** Every non-OK {@link LeaseResult} is a 409; CANCELLED (extend only, #224) carries its own problem type. */
    static Problem leaseProblem(LeaseResult r) {
        return switch (r) {
            case CANCELLED -> cancelled("cancelled");
            case NOT_IN_FLIGHT, STALE_ATTEMPT -> problem(409, "lease not held", r.name());
            case OK -> throw new IllegalArgumentException("OK is not a problem");
        };
    }

    /** 409 cancelled; {@code reason} is {@code cancelled} or {@code deadline} (problem extension member). */
    static Problem cancelled(String reason) {
        return new Problem(409, "cancelled", "task cancelled; stop and ack the lease", CANCELLED_TYPE, reason);
    }

    /** Drops claims whose lease ran out (the messaging lease is authoritative; this is only the envelope cache). */
    private void sweep() {
        long now = this.swarm.topics().clock().getAsLong();
        this.claimed.values().removeIf(t -> t.leaseDeadlineMs() < now);
    }

    int claimedCount() {
        return this.claimed.size();
    }

    /** Writes the reply, then acks; null when the task has no reply topic. */
    private LeaseResult reply(Task task, byte[] body) {
        if (task.envelope().replyTo().isEmpty()) {
            return null;
        }
        try {
            return this.swarm.tasks().reply(task, this.workerProducerId, this.replySeq.incrementAndGet(), "", body)
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LeaseResult.NOT_IN_FLIGHT;
        } catch (ExecutionException | TimeoutException e) {
            return LeaseResult.NOT_IN_FLIGHT;
        }
    }

    static String leaseId(Task t) {
        // shard > 0 rides on the attempt field ("attempt.shard"), so unsharded lease ids are unchanged
        return t.pool() + "~" + t.offset() + "~" + t.attempt() + (t.shard() == 0 ? "" : "." + t.shard());
    }

    static Task parseLease(String id) throws BadRequest {
        int b = id.lastIndexOf('~');
        int a = b <= 0 ? -1 : id.lastIndexOf('~', b - 1);
        if (a <= 0) {
            throw new BadRequest("lease id is pool~offset~attempt");
        }
        try {
            var last = id.substring(b + 1);
            int dot = last.indexOf('.');
            int shard = dot < 0 ? 0 : Integer.parseInt(last.substring(dot + 1));
            if (shard < 0) throw new NumberFormatException("shard");
            return new Task(id.substring(0, a), Long.parseLong(id.substring(a + 1, b)),
                    Integer.parseInt(dot < 0 ? last : last.substring(0, dot)), 0, null, shard);
        } catch (NumberFormatException e) {
            throw new BadRequest("lease id is pool~offset~attempt");
        }
    }

    private static byte[] taskBody(Request request) throws BadRequest {
        var f = readFrame(request);
        if (f.type() != Frame.Type.TASK) {
            throw new BadRequest("expected a TASK frame");
        }
        return f.payload();
    }

    static Frame readFrame(Request request) throws BadRequest {
        var body = request.body();
        if (body.length() > MAX_REQUEST) {
            throw new TooLarge("request body larger than " + MAX_REQUEST);
        }
        var buf = new java.io.ByteArrayOutputStream();
        var chunk = new byte[8192];
        try {
            int n;
            while ((n = body.read(chunk, 0, chunk.length)) >= 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_REQUEST) {
                    throw new TooLarge("request body larger than " + MAX_REQUEST);
                }
            }
        } catch (BodyTooLarge e) {
            throw new TooLarge("request body " + e.getMessage());
        } catch (IOException e) {
            throw new BadRequest("request body: " + e.getMessage());
        }
        var in = ByteBuffer.wrap(buf.toByteArray());
        var f = Frame.decode(in);
        if (f == null || in.hasRemaining()) {
            throw new BadRequest("body must be exactly one frame");
        }
        return f;
    }

    private long deadline(Map<String, String> query, long fallback) throws BadRequest {
        if (!query.containsKey("deadlineMs")) {
            return fallback;
        }
        return this.swarm.topics().clock().getAsLong() + number(query, "deadlineMs", 0, 1, 3_600_000);
    }

    static long number(Map<String, String> query, String name, long dflt, long min, long max) throws BadRequest {
        var v = query.get(name);
        if (v == null) {
            return dflt;
        }
        try {
            long n = Long.parseLong(v);
            if (n < min || n > max) {
                throw new BadRequest(name + " must be in " + min + ".." + max);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be a number");
        }
    }

    static Map<String, String> query(String q) {
        var out = new HashMap<String, String>();
        for (var part : q.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            out.put(decode(eq < 0 ? part : part.substring(0, eq)), eq < 0 ? "" : decode(part.substring(eq + 1)));
        }
        return out;
    }

    static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static Response frame(int status, Frame f) {
        return bytes(status, f.encode());
    }

    private static Response bytes(int status, byte[] data) {
        var h = ResponseHeaders.of().set("content-type", CONTENT_TYPE);
        return new Response(status, h, Body.Bytes.of(data));
    }

    record Problem(int status, String title, String detail, String type, String reason) {
        Problem(int status, String title, String detail) {
            this(status, title, detail, "about:blank", null);
        }

        Problem(int status, String title, String detail, String type) {
            this(status, title, detail, type, null);
        }

        Response response() {
            var json = "{\"type\":" + quote(this.type) + ",\"title\":" + quote(this.title) + ",\"status\":" + this.status
                    + ",\"detail\":" + quote(this.detail) + (this.reason == null ? "" : ",\"reason\":" + quote(this.reason))
                    + "}";
            var h = ResponseHeaders.of().set("content-type", "application/problem+json");
            return new Response(this.status, h, Body.Bytes.of(json.getBytes(StandardCharsets.UTF_8)));
        }

        Response withAllow() {
            var r = response();
            r.headers().set("allow", "POST");
            return r;
        }
    }

    /** A body of unknown length that fails once it passes {@code max} bytes. */
    static sjws_nettask.core.RequestBody capped(sjws_nettask.core.RequestBody body, long max) {
        return new sjws_nettask.core.RequestBody() {
            private long read;

            @Override
            public long length() {
                return -1;
            }

            @Override
            public int read(byte[] into, int off, int len) throws IOException {
                int n = body.read(into, off, len);
                if (n > 0 && (this.read += n) > max) {
                    throw new BodyTooLarge("larger than " + max);
                }
                return n;
            }
        };
    }

    private static Response forbidden(String pool) {
        return problem(403, "forbidden", "agent may not use pool " + pool).response();
    }

    static Problem problem(int status, String title, String detail) {
        return new Problem(status, title, detail == null ? "" : detail);
    }

    static String quote(String s) {
        var b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    static class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }

    /** Answered with 413, for both declared and unknown-length (chunked) bodies. */
    static final class TooLarge extends BadRequest {
        TooLarge(String message) {
            super(message);
        }
    }

    /** Thrown by {@link #capped} when an unknown-length body passes the gate's limit. */
    static final class BodyTooLarge extends IOException {
        BodyTooLarge(String message) {
            super(message);
        }
    }
}
