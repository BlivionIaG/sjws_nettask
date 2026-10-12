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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Reverse proxy to one upstream origin (phase 1: bodyless requests, buffered responses).
 * <p>
 * One {@link HttpClient} per instance (built once, keep-alive pool, virtual-thread executor).
 * End-to-end request headers from {@code headers().fields()} are forwarded; hop-by-hop fields,
 * fields named in {@code Connection}, and incoming forwarding fields are replaced:
 * {@code X-Forwarded-For} / {@code Forwarded} are extended only when the peer is a trusted proxy,
 * {@code X-Forwarded-Proto} follows {@code request.secure()} unless configured, and
 * {@code X-Forwarded-Host} carries the client's Host.
 * <p>
 * Errors: a request with a body is 501 (phase 1); connect/IO failures and oversize bodies 502;
 * connect or response timeouts 504; a non-origin-form target or an unusable method 400.
 */
public final class ProxyHandler implements Handler {

    /** Never forwarded upstream: hop-by-hop, set by the client library, or recomputed here. */
    private static final Set<String> REQUEST_DROPPED = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "host", "content-length",
            "expect", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded", "http2-settings");

    /** Hop-by-hop and writer-owned fields that must not be relayed to the client. */
    private static final Set<String> RESPONSE_DROPPED = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authenticate", "content-length",
            "date", "server", "alt-svc", ":status");

    /**
     * @param upstream base URI, e.g. "http://127.0.0.1:9000" or "http://backend/prefix"
     * @param connectTimeout TCP connect timeout (exceeding it answers 504)
     * @param responseTimeout time until the response head arrives (504)
     * @param maxBody largest upstream body relayed, in bytes (larger: 502)
     * @param proto "http" / "https" for X-Forwarded-Proto, or null to follow {@code request.secure()}
     * @param trustedProxies peers whose X-Forwarded-For / Forwarded are extended instead of replaced
     */
    public record Settings(URI upstream, Duration connectTimeout, Duration responseTimeout, int maxBody, String proto,
            List<Cidr> trustedProxies) {

        public Settings {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(responseTimeout, "responseTimeout");
            if (!"http".equals(upstream.getScheme()) && !"https".equals(upstream.getScheme())) {
                throw new IllegalArgumentException("upstream must be http(s): " + upstream);
            }
            if (upstream.getRawQuery() != null || upstream.getRawFragment() != null) {
                throw new IllegalArgumentException("upstream must not have a query or fragment: " + upstream);
            }
            if (maxBody < 0) {
                throw new IllegalArgumentException("maxBody < 0");
            }
            if (proto != null && !proto.equals("http") && !proto.equals("https")) {
                throw new IllegalArgumentException("proto must be http, https or null: " + proto);
            }
            trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        }

        public Settings(URI upstream) {
            this(upstream, Duration.ofSeconds(5), Duration.ofSeconds(30), 16 << 20, null, List.of());
        }
    }

    private final Settings settings;
    private final HttpClient client;
    private final String base;

    public ProxyHandler(Settings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        var b = settings.upstream().toString();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public Settings settings() {
        return this.settings;
    }

    @Override
    public Response handle(Request request) {
        boolean head = request.headOnly();
        if (hasBody(request)) {
            return StaticFileHandler.error(501, head);
        }
        HttpRequest out;
        try {
            out = buildRequest(request);
        } catch (IllegalArgumentException e) {
            return StaticFileHandler.error(400, head);
        }
        try {
            return relay(this.client.send(out, HttpResponse.BodyHandlers.ofInputStream()), head);
        } catch (HttpTimeoutException e) {
            return StaticFileHandler.error(504, head);
        } catch (IOException e) {
            return StaticFileHandler.error(502, head);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StaticFileHandler.error(502, head);
        }
    }

    static boolean hasBody(Request request) {
        var h = request.headers();
        if (!h.values("transfer-encoding").isEmpty()) {
            return true;
        }
        for (var v : h.values("content-length")) {
            if (!v.equals("0")) {
                return true;
            }
        }
        return false;
    }

    HttpRequest buildRequest(Request request) {
        if (!request.target().startsWith("/")) {
            throw new IllegalArgumentException("not origin-form");
        }
        var b = HttpRequest.newBuilder(URI.create(this.base + request.target()))
                .timeout(this.settings.responseTimeout())
                .method(request.method(), HttpRequest.BodyPublishers.noBody());
        var h = request.headers();
        var connectionTokens = h.values("connection");
        for (var e : h.fields().entrySet()) {
            var name = e.getKey();
            if (REQUEST_DROPPED.contains(name) || name.startsWith(":") || listed(connectionTokens, name)) {
                continue;
            }
            for (var v : e.getValue()) {
                b.header(name, v);
            }
        }
        if (h.fields().isEmpty()) { // typed fields only (tests, or a transport without the map)
            header(b, "If-None-Match", h.ifNoneMatch());
            header(b, "If-Modified-Since", h.ifModifiedSince());
            header(b, "If-Range", h.ifRange());
            header(b, "Range", h.range());
            header(b, "Accept-Encoding", h.acceptEncoding());
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
            b.header("X-Forwarded-For", xff.toString());
        }
        b.header("X-Forwarded-Proto", proto);
        if (h.host() != null) {
            b.header("X-Forwarded-Host", h.host());
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
        b.header("Forwarded", fwd.toString());
        return b.build();
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

    private static void header(HttpRequest.Builder b, String name, String value) {
        if (value != null) {
            b.header(name, value);
        }
    }

    Response relay(HttpResponse<InputStream> resp, boolean head) throws IOException {
        var headers = ResponseHeaders.of();
        for (var e : resp.headers().map().entrySet()) {
            var name = e.getKey().toLowerCase(Locale.ROOT);
            if (RESPONSE_DROPPED.contains(name)) {
                continue;
            }
            for (var v : e.getValue()) {
                try {
                    headers.add(name, v);
                } catch (IllegalArgumentException bad) {
                    // not representable: drop the field rather than fail the response
                }
            }
        }
        byte[] data;
        try (var in = resp.body()) {
            data = in.readNBytes(this.settings.maxBody() + 1);
        }
        if (data.length > this.settings.maxBody()) {
            return StaticFileHandler.error(502, head);
        }
        return new Response(resp.statusCode(), headers, data.length == 0 ? Body.Empty.INSTANCE : Body.Bytes.of(data), head);
    }
}
