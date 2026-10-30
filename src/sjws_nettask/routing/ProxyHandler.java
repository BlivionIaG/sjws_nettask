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
package sjws_nettask.routing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Reverse proxy to one upstream origin over a Netty keep-alive connection pool
 * ({@link UpstreamPool}), streaming both ways.
 * <p>
 * The request body ({@code request.body()}, attached by HTTP/1.1 for {@code Content-Length}
 * bodies) is copied upstream chunk by chunk, each write awaited. The response is a
 * {@link Body.Stream}: the writer pulls the next upstream chunk only after the previous one was
 * sent, and the channel reads from the upstream socket only when asked, so memory per exchange
 * stays at about one socket read.
 * <p>
 * End-to-end request headers from {@code headers().fields()} are forwarded; hop-by-hop fields,
 * fields named in {@code Connection}, and incoming forwarding fields are replaced:
 * {@code X-Forwarded-For} / {@code Forwarded} are extended only when the peer is a trusted proxy,
 * {@code X-Forwarded-Proto} follows {@code request.secure()} unless configured, and
 * {@code X-Forwarded-Host} carries the client's Host. {@code Host} is the upstream's authority, or
 * the client's with {@code preserveHost}.
 * <p>
 * Errors: a chunked request body is 501 (not supported yet); a body over {@code maxBody} 413;
 * connect/IO failures before the response head 502 (one retry on a fresh connection when a
 * pooled one turned out stale and the request had no body); connect or response-head timeouts
 * 504; a non-origin-form target or an unusable method 400. After the head, a failure aborts the
 * client connection/stream (never a short response).
 */
public final class ProxyHandler implements Handler, AutoCloseable {

    /** Never forwarded upstream: hop-by-hop, framing, or recomputed here. */
    private static final Set<String> REQUEST_DROPPED = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "host", "content-length",
            "expect", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded", "http2-settings");

    /** Hop-by-hop and writer-owned fields that must not be relayed to the client. */
    private static final Set<String> RESPONSE_DROPPED = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authenticate", "content-length",
            "date", "server", "alt-svc");

    private static final int BODY_CHUNK = 64 * 1024;

    /**
     * @param upstream base URI, e.g. "http://127.0.0.1:9000" or "http://backend/prefix"
     * @param connectTimeout TCP (and TLS) connect timeout (exceeding it answers 504)
     * @param responseTimeout time from the request being sent until the response head (504)
     * @param idleReadTimeout longest gap between upstream body chunks (then the response is aborted)
     * @param maxBody largest request body forwarded, in bytes (larger: 413)
     * @param proto "http" / "https" for X-Forwarded-Proto, or null to follow {@code request.secure()}
     * @param trustedProxies peers whose X-Forwarded-For / Forwarded are extended instead of replaced
     * @param preserveHost send the client's Host instead of the upstream's authority
     * @param maxIdle idle keep-alive connections kept per upstream
     */
    public record Settings(URI upstream, Duration connectTimeout, Duration responseTimeout, Duration idleReadTimeout,
            int maxBody, String proto, List<Cidr> trustedProxies, boolean preserveHost, int maxIdle) {

        public Settings {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(responseTimeout, "responseTimeout");
            Objects.requireNonNull(idleReadTimeout, "idleReadTimeout");
            if (!"http".equals(upstream.getScheme()) && !"https".equals(upstream.getScheme())) {
                throw new IllegalArgumentException("upstream must be http(s): " + upstream);
            }
            if (upstream.getHost() == null) {
                throw new IllegalArgumentException("upstream needs a host: " + upstream);
            }
            if (upstream.getRawQuery() != null || upstream.getRawFragment() != null) {
                throw new IllegalArgumentException("upstream must not have a query or fragment: " + upstream);
            }
            if (maxBody < 0 || maxIdle < 0) {
                throw new IllegalArgumentException("maxBody and maxIdle must be >= 0");
            }
            if (proto != null && !proto.equals("http") && !proto.equals("https")) {
                throw new IllegalArgumentException("proto must be http, https or null: " + proto);
            }
            trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        }

        /** The phase-1 shape: idle read 30 s, no preserve_host, 256 idle connections. */
        public Settings(URI upstream, Duration connectTimeout, Duration responseTimeout, int maxBody, String proto,
                List<Cidr> trustedProxies) {
            this(upstream, connectTimeout, responseTimeout, Duration.ofSeconds(30), maxBody, proto, trustedProxies,
                    false, 256);
        }

        public Settings(URI upstream) {
            this(upstream, Duration.ofSeconds(5), Duration.ofSeconds(30), 16 << 20, null, List.of());
        }
    }

    private final Settings settings;
    private final UpstreamPool pool;
    private final String basePath;
    private final String authority;
    /** Both null unless configured: the plain path then makes one attempt, as before. */
    private final Resilience.Retry retry;
    private final Resilience.RetryBudget retryBudget;
    private final Resilience.CircuitBreaker breaker;

    /**
     * Responses produced by local admission (peer-budget share exhausted) rather than an upstream.
     * {@link EndpointBalancer} must not count these against endpoint health or retry them as 503s.
     */
    private static final Set<Response> LOCAL_REFUSALS = ConcurrentHashMap.newKeySet();

    public ProxyHandler(Settings settings) {
        this(settings, ByteBudget.shared());
    }

    /** @param budget the global in-flight body-byte budget (F5); tests pass their own */
    public ProxyHandler(Settings settings, ByteBudget budget) {
        this(settings, budget, null);
    }

    /** @param resilience retries and circuit breaking, or null for neither */
    public ProxyHandler(Settings settings, ByteBudget budget, Resilience resilience) {
        this.settings = settings;
        this.retry = resilience == null ? null : resilience.retry();
        this.retryBudget = this.retry == null ? null : new Resilience.RetryBudget(this.retry.budgetPercent());
        this.breaker = resilience == null || resilience.circuit() == null ? null
                : new Resilience.CircuitBreaker(resilience.circuit());
        this.pool = new UpstreamPool(settings.upstream(), settings.connectTimeout(), settings.maxIdle(), budget);
        var path = settings.upstream().getRawPath();
        this.basePath = path == null || path.isEmpty() || path.equals("/") ? ""
                : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        this.authority = settings.upstream().getRawAuthority();
    }

    /** The in-flight byte budget this proxy counts against (hook for the F5 slow-client test). */
    public ByteBudget budget() {
        return this.pool.budget();
    }

    /**
     * 503 when this peer already holds its in-flight share (#78). Mid-stream backpressure still
     * pauses reads; a new request fails fast instead of waiting on that peer's queue.
     */
    private Response peerBudgetRefused(Request request, boolean head) {
        var budget = this.pool.budget();
        if (budget.peerLimit() <= 0) {
            return null;
        }
        if (budget.exhausted(budget.peerStripe(request.peer()))) {
            return localRefusal(StaticFileHandler.error(503, head));
        }
        return null;
    }

    /** Marks a response as a local admission refusal (not an upstream outcome). */
    static Response localRefusal(Response response) {
        LOCAL_REFUSALS.add(response);
        return response;
    }

    /** True when {@code response} was produced by local admission, not by an upstream. */
    static boolean isLocalRefusal(Response response) {
        return response != null && LOCAL_REFUSALS.contains(response);
    }

    public Settings settings() {
        return this.settings;
    }

    /** Idle pooled connections (for tests and metrics). */
    public int idleConnections() {
        return this.pool.idleCount();
    }

    /** Closes the pool; exchanges in flight finish and close their connections. */
    @Override
    public void close() {
        this.pool.close();
    }

    /** See {@link UpstreamPool#retire()}. */
    void retire() {
        this.pool.retire();
    }

    @Override
    public Response handle(Request request) {
        boolean head = request.headOnly();
        if (!request.headers().values("transfer-encoding").isEmpty()) {
            return StaticFileHandler.error(501, head);
        }
        long length = request.body().length();
        if (length < 0 || request.body() == sjws_nettask.core.RequestBody.EMPTY && hasDeclaredBody(request)) {
            return StaticFileHandler.error(501, head); // a body the transport could not attach
        }
        if (length > this.settings.maxBody()) {
            return StaticFileHandler.error(413, head);
        }
        var refused = peerBudgetRefused(request, head);
        if (refused != null) {
            return refused;
        }
        HttpRequest out;
        try {
            out = buildRequest(request, length);
        } catch (IllegalArgumentException e) {
            return StaticFileHandler.error(400, head);
        }
        if (this.retry == null && this.breaker == null) {
            try {
                return exchange(request, out, length, true);
            } catch (IOException e) {
                return StaticFileHandler.error(status(e), head);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return StaticFileHandler.error(502, head);
            }
        }
        return resilient(request, out, length, head);
    }

    /**
     * One exchange for {@link EndpointBalancer}, without this handler's own retry or breaker:
     * a request this handler rejects comes back as its error response, a failed exchange as the
     * exception (classify it with {@link #kind(IOException)}, answer it with {@link #status}).
     */
    Response attempt(Request request) throws IOException, InterruptedException {
        boolean head = request.headOnly();
        if (!request.headers().values("transfer-encoding").isEmpty()) {
            return StaticFileHandler.error(501, head);
        }
        long length = request.body().length();
        if (length < 0 || request.body() == sjws_nettask.core.RequestBody.EMPTY && hasDeclaredBody(request)) {
            return StaticFileHandler.error(501, head);
        }
        if (length > this.settings.maxBody()) {
            return StaticFileHandler.error(413, head);
        }
        var refused = peerBudgetRefused(request, head);
        if (refused != null) {
            return refused;
        }
        HttpRequest out;
        try {
            out = buildRequest(request, length);
        } catch (IllegalArgumentException e) {
            return StaticFileHandler.error(400, head);
        }
        return exchange(request, out, length, true);
    }

    static int status(IOException e) {
        return e instanceof UpstreamPool.ConnectTimeout || e instanceof ResponseTimeout ? 504 : 502;
    }

    static Resilience.On kind(IOException e) {
        if (e instanceof UpstreamPool.ConnectTimeout || e instanceof UpstreamPool.ConnectFailed) {
            return Resilience.On.CONNECT;
        }
        return e instanceof ResponseTimeout ? Resilience.On.TIMEOUT : Resilience.On.RESET;
    }

    static Resilience.On kind(int status) {
        return switch (status) {
            case 502 -> Resilience.On.STATUS_502;
            case 503 -> Resilience.On.STATUS_503;
            case 504 -> Resilience.On.STATUS_504;
            default -> null;
        };
    }

    /**
     * Classifies an attempt outcome for retry and passive health. Local admission refusals
     * (peer-budget share) are not upstream failures.
     */
    static Resilience.On kind(Response response) {
        if (isLocalRefusal(response)) {
            return null;
        }
        return kind(response.status());
    }

    /** Methods a retry cannot duplicate the effect of (RFC 9110 §9.2.2). */
    static boolean idempotent(String method) {
        return switch (method) {
            case "GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE" -> true;
            default -> false;
        };
    }

    /**
     * Circuit check (once per request), then up to {@code 1 + attempts} tries; the breaker records
     * the request's final outcome. A failed attempt is retried only when it
     * is listed in {@code on}, the request is idempotent and bodyless, and the retry budget has a
     * token; nothing has reached the client before a retry (a retried 5xx body is discarded).
     */
    private Response resilient(Request request, HttpRequest out, long length, boolean head) {
        var retry = this.retry;
        boolean mayRetry = retry != null && length == 0 && idempotent(request.method());
        if (retry != null) {
            this.retryBudget.deposit();
        }
        if (this.breaker != null && !this.breaker.allow()) {
            var open = StaticFileHandler.error(503, head);
            open.headers().add("retry-after", Long.toString(this.breaker.retryAfterSeconds()));
            return open;
        }
        int tries = 0;
        while (true) {
            Resilience.On failed;
            Response response = null;
            int errorStatus = 0;
            try {
                response = exchange(request, tries == 0 ? out : rebuild(out), length, true);
                failed = kind(response);
            } catch (IOException e) {
                failed = kind(e);
                errorStatus = status(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (this.breaker != null) {
                    this.breaker.release(); // the client went away: no verdict on the upstream
                }
                return StaticFileHandler.error(502, head);
            }
            boolean again = failed != null && mayRetry && tries < retry.attempts() && retry.on().contains(failed)
                    && this.retryBudget.withdraw();
            if (!again) {
                // one verdict per request, after its retries: a retry that recovers is a success
                if (this.breaker != null) {
                    if (failed == null) {
                        this.breaker.success();
                    } else {
                        this.breaker.failure();
                    }
                }
                if (failed == null) {
                    return response;
                }
                return response != null ? response : StaticFileHandler.error(errorStatus, head);
            }
            if (response != null) {
                Body.discard(response.body()); // the 5xx is replaced by the next attempt
            }
            tries++;
        }
    }

    /** Circuit state, for tests and metrics; CLOSED when no breaker is configured. */
    String circuitState() {
        return this.breaker == null ? "CLOSED" : this.breaker.state().name();
    }

    private static final class ResponseTimeout extends IOException {
        ResponseTimeout() {
            super("no response head in time");
        }
    }

    /** A body announced by Content-Length that the transport did not attach (HTTP/2 today). */
    private static boolean hasDeclaredBody(Request request) {
        for (var v : request.headers().values("content-length")) {
            if (!v.equals("0")) {
                return true;
            }
        }
        return false;
    }

    private Response exchange(Request request, HttpRequest out, long length, boolean mayRetry)
            throws IOException, InterruptedException {
        var lease = this.pool.acquire();
        var ch = lease.channel();
        var budget = this.pool.budget();
        var ex = new UpstreamPool.Exchange(ch, this.settings.idleReadTimeout(), budget,
                budget.peerStripe(request.peer()));
        HttpResponse response;
        try {
            if (length > 0) {
                ex.start();
                await(ch.writeAndFlush(out), this.settings.responseTimeout());
                sendBody(request, ch, length);
                ex.armHeadTimer(this.settings.responseTimeout());
            } else {
                ex.send(out, this.settings.responseTimeout());
            }
            response = ex.head.get(); // the event loop's head timer bounds this wait
        } catch (ExecutionException e) {
            ex.abandon();
            ch.close();
            if (e.getCause() instanceof UpstreamPool.HeadTimeout) {
                throw new ResponseTimeout();
            }
            if (lease.reused() && mayRetry && length == 0) {
                return exchange(request, rebuild(out), length, false); // stale keep-alive connection
            }
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (IOException | RuntimeException | InterruptedException e) {
            ex.abandon();
            ch.close();
            throw e;
        }
        return relay(response, ex, request.headOnly());
    }

    /** A fresh copy of a bodyless request (the first one was consumed by the failed write). */
    private static HttpRequest rebuild(HttpRequest out) {
        var copy = new DefaultFullHttpRequest(out.protocolVersion(), out.method(), out.uri(), Unpooled.EMPTY_BUFFER);
        copy.headers().set(out.headers());
        return copy;
    }

    private void sendBody(Request request, io.netty.channel.Channel ch, long length) throws IOException, InterruptedException {
        var body = request.body();
        long sent = 0;
        byte[] chunk = new byte[(int) Math.min(BODY_CHUNK, length)];
        while (sent < length) {
            int n = body.read(chunk, 0, (int) Math.min(chunk.length, length - sent));
            if (n < 0) {
                throw new IOException("request body ended early");
            }
            sent += n;
            await(ch.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(chunk, 0, n))),
                    this.settings.responseTimeout());
        }
        await(ch.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), this.settings.responseTimeout());
    }

    /**
     * Parks the request thread until the write completed (backpressure towards the client); an
     * upstream that stops reading for {@code timeout} is a 504.
     */
    private static void await(io.netty.channel.ChannelFuture write, Duration timeout)
            throws IOException, InterruptedException {
        var done = new CompletableFuture<Void>();
        write.addListener(f -> done.complete(null));
        try {
            done.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new ResponseTimeout();
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
        if (!write.isSuccess()) {
            throw new IOException("upstream write failed: " + write.cause(), write.cause());
        }
    }

    HttpRequest buildRequest(Request request, long length) {
        if (!request.target().startsWith("/")) {
            throw new IllegalArgumentException("not origin-form");
        }
        var method = HttpMethod.valueOf(request.method()); // validates the token
        var uri = this.basePath + request.target();
        HttpRequest out = length > 0 ? new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri)
                : new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, Unpooled.EMPTY_BUFFER);
        var b = out.headers();
        var h = request.headers();
        var host = this.settings.preserveHost() && h.host() != null ? h.host() : this.authority;
        b.set("Host", host);
        var connectionTokens = h.values("connection");
        for (var e : h.fields().entrySet()) {
            var name = e.getKey();
            if (REQUEST_DROPPED.contains(name) || name.startsWith(":") || listed(connectionTokens, name)) {
                continue;
            }
            for (var v : e.getValue()) {
                b.add(name, v);
            }
        }
        if (h.fields().isEmpty()) { // typed fields only (tests, or a transport without the map)
            header(b, "If-None-Match", h.ifNoneMatch());
            header(b, "If-Modified-Since", h.ifModifiedSince());
            header(b, "If-Range", h.ifRange());
            header(b, "Range", h.range());
            header(b, "Accept-Encoding", h.acceptEncoding());
        }
        if (length > 0) {
            b.set("Content-Length", Long.toString(length));
        }
        var proto = this.settings.proto() != null ? this.settings.proto() : request.secure() ? "https" : "http";
        boolean trusted = trusted(request);
        var peer = request.peer() == null || request.peer().getAddress() == null ? null
                : request.peer().getAddress().getHostAddress();

        var xff = new StringBuilder();
        if (trusted) {
            for (var v : h.values("x-forwarded-for")) {
                xff.append(xff.isEmpty() ? "" : ", ").append(v);
            }
        }
        if (peer != null) {
            xff.append(xff.isEmpty() ? "" : ", ").append(peer);
        }
        if (!xff.isEmpty()) {
            b.set("X-Forwarded-For", xff.toString());
        }
        b.set("X-Forwarded-Proto", proto);
        if (h.host() != null) {
            b.set("X-Forwarded-Host", h.host());
        }
        var fwd = new StringBuilder();
        if (trusted) {
            for (var v : h.values("forwarded")) {
                fwd.append(fwd.isEmpty() ? "" : ", ").append(v);
            }
        }
        fwd.append(fwd.isEmpty() ? "" : ", ");
        if (peer != null) {
            fwd.append("for=").append(peer.indexOf(':') >= 0 ? "\"[" + peer + "]\"" : peer).append(';');
        }
        fwd.append("proto=").append(proto);
        if (h.host() != null) {
            fwd.append(";host=").append(quote(h.host()));
        }
        b.set("Forwarded", fwd.toString());
        validate(b);
        return out;
    }

    /** Netty does not check values on add; refuse CR/LF/NUL like {@code ResponseHeaders}. */
    private static void validate(HttpHeaders headers) {
        for (var e : headers) {
            var v = e.getValue();
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\r' || c == '\n' || c == 0 || (c < 0x20 && c != '\t')) {
                    throw new IllegalArgumentException("invalid header value for " + e.getKey());
                }
            }
        }
    }

    private boolean trusted(Request request) {
        for (var c : this.settings.trustedProxies()) {
            if (c.contains(request.peer())) {
                return true;
            }
        }
        return false;
    }

    private static boolean listed(List<String> connection, String name) {
        for (var v : connection) {
            for (var token : v.split(",")) {
                if (token.trim().equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void header(HttpHeaders b, String name, String value) {
        if (value != null) {
            b.set(name, value);
        }
    }

    private Response relay(HttpResponse resp, UpstreamPool.Exchange ex, boolean head) throws IOException, InterruptedException {
        var headers = ResponseHeaders.of();
        var connectionTokens = resp.headers().getAll("connection");
        for (var e : resp.headers()) {
            var name = e.getKey().toLowerCase(Locale.ROOT);
            if (RESPONSE_DROPPED.contains(name) || listed(connectionTokens, name)) {
                continue;
            }
            try {
                headers.add(name, e.getValue());
            } catch (IllegalArgumentException bad) {
                // not representable: drop the field rather than fail the response
            }
        }
        int status = resp.status().code();
        ex.keepAlive = HttpUtil.isKeepAlive(resp);
        long length = HttpUtil.getContentLength(resp, -1L);
        if (HttpUtil.isTransferEncodingChunked(resp)) {
            length = -1;
        }
        boolean noContent = head || status == 204 || status == 304 || status < 200;
        if (noContent) { // finish the exchange now so the connection goes back to the pool
            var source = new Source(ex, this.pool, this.settings.idleReadTimeout());
            source.drainToEnd();
            source.close();
            var body = head && length >= 0 ? new Body.Stream(length, Source.EMPTY) : Body.Empty.INSTANCE;
            return new Response(status, headers, body, head);
        }
        return new Response(status, headers, new Body.Stream(length, new Source(ex, this.pool,
                this.settings.idleReadTimeout())), false);
    }

    /** The response body as the writer pulls it; returns the connection to the pool at the end. */
    static final class Source implements Body.Stream.Source {
        static final Body.Stream.Source EMPTY = new Body.Stream.Source() {
            @Override
            public ByteBuffer next() {
                return null;
            }

            @Override
            public void close() {
            }
        };

        private final UpstreamPool.Exchange ex;
        private final UpstreamPool pool;
        private final Duration idle;
        private ByteBuf current;
        private boolean closed;

        Source(UpstreamPool.Exchange ex, UpstreamPool pool, Duration idle) {
            this.ex = ex;
            this.pool = pool;
            this.idle = idle;
        }

        @Override
        public ByteBuffer next() throws IOException {
            releaseCurrent();
            if (this.ex.ended) {
                return null;
            }
            try {
                var o = this.ex.take();
                if (UpstreamPool.isEnd(o)) {
                    return null;
                }
                this.current = (ByteBuf) o;
                return UpstreamPool.view(this.current);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        void drainToEnd() throws IOException {
            while (next() != null) {
                // discard
            }
        }

        private void releaseCurrent() {
            if (this.current != null) {
                this.current.release();
                this.current = null;
            }
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            releaseCurrent();
            if (this.ex.ended) {
                this.pool.release(this.ex.channel, this.ex.keepAlive);
            } else {
                this.ex.abandon();
                this.pool.release(this.ex.channel, false);
            }
        }
    }
}
