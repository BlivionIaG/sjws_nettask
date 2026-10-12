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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/**
 * Reverse proxy: forwards the request to one upstream origin and relays its answer.
 * <p>
 * The core {@link Request} carries no body and only the headers in {@code RequestHeaders}, so the
 * proxy forwards those (conditionals, Range, Accept-Encoding) plus {@code X-Forwarded-Host},
 * {@code X-Forwarded-Proto} and an RFC 7239 {@code Forwarded} field. Upstream bodies are buffered
 * up to {@code maxBody} bytes. Connect failures and oversize/garbled answers become 502, timeouts 504.
 */
public final class ProxyHandler implements Handler {

    /** Hop-by-hop and writer-owned fields that must not be relayed. */
    private static final Set<String> DROPPED = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authenticate", "content-length",
            "date", "server", "alt-svc", ":status");

    /**
     * @param upstream base URI, e.g. "http://127.0.0.1:9000" or "http://backend/prefix"
     * @param connectTimeout TCP connect timeout (exceeding it answers 504)
     * @param responseTimeout time until the response head arrives
     * @param maxBody largest upstream body relayed, in bytes
     * @param proto scheme the client used ("http" / "https"), sent as X-Forwarded-Proto
     */
    public record Settings(URI upstream, Duration connectTimeout, Duration responseTimeout, int maxBody, String proto) {

        public Settings {
            Objects.requireNonNull(upstream, "upstream");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(responseTimeout, "responseTimeout");
            Objects.requireNonNull(proto, "proto");
            if (!"http".equals(upstream.getScheme()) && !"https".equals(upstream.getScheme())) {
                throw new IllegalArgumentException("upstream must be http(s): " + upstream);
            }
            if (maxBody < 0) {
                throw new IllegalArgumentException("maxBody < 0");
            }
        }

        public Settings(URI upstream) {
            this(upstream, Duration.ofSeconds(5), Duration.ofSeconds(30), 16 << 20, "http");
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
                .build();
        var b = settings.upstream().toString();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public Settings settings() {
        return this.settings;
    }

    @Override
    public Response handle(Request request) {
        HttpRequest out;
        try {
            out = buildRequest(request);
        } catch (IllegalArgumentException e) {
            return Router.text(400, "Bad Request", request.headOnly());
        }
        try {
            var resp = this.client.send(out, HttpResponse.BodyHandlers.ofInputStream());
            return relay(resp, request.headOnly());
        } catch (HttpTimeoutException e) {
            return Router.text(504, "Gateway Timeout", request.headOnly());
        } catch (IOException e) {
            return Router.text(502, "Bad Gateway", request.headOnly());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Router.text(502, "Bad Gateway", request.headOnly());
        }
    }

    HttpRequest buildRequest(Request request) {
        if (!request.target().startsWith("/")) {
            throw new IllegalArgumentException("not origin-form");
        }
        var b = HttpRequest.newBuilder(URI.create(this.base + request.target()))
                .timeout(this.settings.responseTimeout())
                .method(request.method(), HttpRequest.BodyPublishers.noBody());
        var h = request.headers();
        header(b, "If-None-Match", h.ifNoneMatch());
        header(b, "If-Modified-Since", h.ifModifiedSince());
        header(b, "If-Range", h.ifRange());
        header(b, "Range", h.range());
        header(b, "Accept-Encoding", h.acceptEncoding());
        var proto = this.settings.proto();
        b.header("X-Forwarded-Proto", proto);
        var forwarded = new StringBuilder("proto=").append(proto);
        if (h.host() != null) {
            b.header("X-Forwarded-Host", h.host());
            forwarded.append(";host=\"").append(h.host().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        b.header("Forwarded", forwarded.toString());
        return b.build();
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
            if (DROPPED.contains(name)) {
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
            return Router.text(502, "Bad Gateway", head);
        }
        return new Response(resp.statusCode(), headers, Body.Bytes.of(data), head);
    }
}
