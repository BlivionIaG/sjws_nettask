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
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Body;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.gateway.RequestSizeFilter;
import sjws_nettask.tls.TlsMaterial;

/**
 * #289: HTTP/2 (TLS and h2c) request bodies reach the handler as a pull-style
 * {@link sjws_nettask.core.RequestBody}: declared and unknown length, larger than the stream window,
 * 413 over a handler/filter limit, content-length mismatch, empty body, trailers, filter short-circuit
 * and {@link RequestSizeFilter}.
 */
class RequestBodyHttp2Test {

    private static final int ECHO_CAP = 4 << 20;
    private static final TlsMaterial TLS = TlsMaterial.selfSigned("localhost");

    @TempDir
    Path root;

    private Http2Server server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
    }

    static byte[] readCapped(Request request, int cap) throws IOException {
        var out = new ByteArrayOutputStream();
        var buf = new byte[8192];
        int n;
        while ((n = request.body().read(buf, 0, buf.length)) >= 0) {
            out.write(buf, 0, n);
            if (out.size() > cap) {
                return null;
            }
        }
        return out.toByteArray();
    }

    static Response echo(Request request) {
        if (request.body().length() > ECHO_CAP) {
            return StaticFileHandler.error(413, false);
        }
        byte[] body;
        try {
            body = readCapped(request, ECHO_CAP);
        } catch (IOException e) {
            return StaticFileHandler.error(400, false);
        }
        if (body == null) {
            return StaticFileHandler.error(413, false);
        }
        var h = ResponseHeaders.of();
        h.set("x-declared", Long.toString(request.body().length()));
        h.set("x-received", Integer.toString(body.length));
        h.set("x-sha256", sha256(body));
        h.set("x-protocol", request.protocol());
        return new Response(200, h, new Body.Bytes(ByteBuffer.wrap(body)));
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private Http2Server startEcho() throws Exception {
        return startEcho((request, next) -> {
            var response = next.proceed(request);
            response.headers().add("x-filter", "h2");
            return response;
        });
    }

    private Http2Server startEcho(sjws_nettask.core.RequestFilter other) throws Exception {
        var files = new StaticFileHandler(this.root);
        var chain = RequestFilters.create().add(other)
                .add((request, next) -> request.target().startsWith("/echo") ? echo(request) : next.proceed(request))
                .build(files);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0)
                .writeTimeout(Duration.ofSeconds(30)).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();
        this.server.armHandler(chain);
        return this.server;
    }

    private SSLContext trusting() throws Exception {
        var ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("sjws", TLS.certificate());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private HttpClient h2Client() throws Exception {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(trusting())
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpClient h2cClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private URI tls(String path) {
        return URI.create("https://localhost:" + this.server.port() + path);
    }

    private URI h2c(String path) {
        return URI.create("http://localhost:" + this.server.h2cPort() + path);
    }

    private static HttpResponse<byte[]> post(HttpClient c, URI uri, byte[] body) throws Exception {
        return c.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> put(HttpClient c, URI uri, byte[] body) throws Exception {
        return c.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void bodiesReachTheHandlerOverH2AndH2c() throws Exception {
        startEcho();
        var random = new Random(289);
        var small = "hello h2".getBytes(StandardCharsets.UTF_8);
        // TLS h2 POST
        var c = h2Client();
        var r = post(c, tls("/echo"), small);
        assertEquals(200, r.statusCode(), () -> new String(r.body(), StandardCharsets.UTF_8));
        assertEquals(HttpClient.Version.HTTP_2, r.version());
        assertEquals("h2", r.headers().firstValue("x-filter").orElse(null), "the chain ran");
        assertEquals("8", r.headers().firstValue("x-declared").orElse(null));
        assertEquals("hello h2", new String(r.body(), StandardCharsets.UTF_8));
        assertEquals("HTTP/2", r.headers().firstValue("x-protocol").orElse(null));
        // large PUT over the 64 KiB stream window
        var large = new byte[3 << 20];
        random.nextBytes(large);
        var l = put(c, tls("/echo"), large);
        assertEquals(200, l.statusCode());
        assertEquals(Integer.toString(large.length), l.headers().firstValue("x-received").orElse(null));
        assertEquals(sha256(large), l.headers().firstValue("x-sha256").orElse(null));
        // empty declared body
        var e = post(c, tls("/echo"), new byte[0]);
        assertEquals(200, e.statusCode());
        assertEquals("0", e.headers().firstValue("x-received").orElse(null));
        // h2c POST
        var clear = h2cClient();
        var h = post(clear, h2c("/echo"), "h2c body".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, h.statusCode());
        assertEquals(HttpClient.Version.HTTP_2, h.version());
        assertEquals("h2c body", new String(h.body(), StandardCharsets.UTF_8));
        var hl = put(clear, h2c("/echo"), large);
        assertEquals(200, hl.statusCode());
        assertEquals(sha256(large), hl.headers().firstValue("x-sha256").orElse(null));
    }

    @Test
    void anOversizedBodyIsRefusedAndTheConnectionStaysUsable() throws Exception {
        startEcho();
        var c = h2Client();
        var big = new byte[ECHO_CAP + 4096];
        var refused = post(c, tls("/echo"), big);
        assertEquals(413, refused.statusCode());
        var ok = post(c, tls("/echo"), "after".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, ok.statusCode());
        assertEquals("after", new String(ok.body(), StandardCharsets.UTF_8));
    }

    @Test
    void anUnreadBodyIsHeldByFlowControlNotBuffered() throws Exception {
        var most = new AtomicInteger();
        startEcho((request, next) -> {
            if (!request.target().equals("/slow")) {
                return next.proceed(request);
            }
            var body = (Http2RequestBody) request.body();
            try {
                for (int i = 0; i < 20; i++) {
                    Thread.sleep(25);
                    most.accumulateAndGet(body.buffered(), Math::max);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return echo(request);
        });
        var data = new byte[4 << 20];
        new Random(7).nextBytes(data);
        var r = post(h2Client(), tls("/slow"), data);
        assertEquals(200, r.statusCode(), () -> new String(r.body(), StandardCharsets.UTF_8));
        assertEquals(sha256(data), r.headers().firstValue("x-sha256").orElse(null));
        assertTrue(most.get() > 0, "the client did send while the handler waited");
        // HIGH + one default stream window (64 KiB) plus a little slack for in-flight frames
        assertTrue(most.get() <= 2 * Http2RequestBody.HIGH + 65535,
                "at most about HIGH + one stream window is buffered, was " + most.get());
    }

    @Test
    void aShortCircuitFilterDoesNotLeakTheBody() throws Exception {
        var terminalHit = new AtomicBoolean();
        startEcho((request, next) -> {
            if (request.target().startsWith("/deny")) {
                return StaticFileHandler.error(403, request.headOnly());
            }
            terminalHit.set(true);
            return next.proceed(request);
        });
        var body = new byte[200_000];
        new Random(9).nextBytes(body);
        var denied = post(h2Client(), tls("/deny"), body);
        assertEquals(403, denied.statusCode());
        assertFalse(terminalHit.get(), "the terminal must not run");
        var ok = post(h2Client(), tls("/echo"), "alive".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, ok.statusCode());
        assertEquals("alive", new String(ok.body(), StandardCharsets.UTF_8));
    }

    @Test
    void requestSizeFilterRejectsAnOversizedDeclaredBody() throws Exception {
        var size = RequestSizeFilter.create(
                MiddlewareSpec.of("request-size", 0, null, java.util.Map.of("max-bytes", "1024")));
        var terminalHit = new AtomicBoolean();
        var files = new StaticFileHandler(this.root);
        var chain = RequestFilters.create().add(size).add((request, next) -> {
            if (request.target().startsWith("/echo")) {
                terminalHit.set(true);
                return echo(request);
            }
            return next.proceed(request);
        }).build(files);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();
        this.server.armHandler(chain);
        var refused = post(h2Client(), tls("/echo"), new byte[2048]);
        assertEquals(413, refused.statusCode());
        assertFalse(terminalHit.get(), "RequestSizeFilter must short-circuit before the terminal");
        var ok = post(h2Client(), tls("/echo"), new byte[512]);
        assertEquals(200, ok.statusCode());
        assertTrue(terminalHit.get());
        assertEquals("512", ok.headers().firstValue("x-received").orElse(null));
    }

    /**
     * Trailers after DATA end the body; a content-length that does not match the DATA is rejected
     * (codec or body). Driven with {@link RawH2} over h2c.
     */
    @Test
    void trailersAndContentLengthMismatchOverH2c() throws Exception {
        startEcho();
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            // POST with trailers: HEADERS + DATA + HEADERS(END_STREAM)
            var body = "trail".getBytes(StandardCharsets.ISO_8859_1);
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1,
                    RawH2.block("POST", "/echo", "content-length", Integer.toString(body.length)));
            h2.frame(RawH2.DATA, 0, 1, body);
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS | RawH2.END_STREAM, 1, RawH2.blockTrailers("x-trailer", "1"));
            var trail = readResponse(h2, 1);
            assertEquals(200, trail.status(), "trailers must end the body for the handler");
            assertEquals("trail", new String(trail.body(), StandardCharsets.UTF_8));
        }
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            // declare 5 bytes, send 3 with END_STREAM → mismatch
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1, RawH2.block("POST", "/echo", "content-length", "5"));
            h2.frame(RawH2.DATA, RawH2.END_STREAM, 1, new byte[] {'a', 'b', 'c'});
            var mismatch = readResponseOrReset(h2, 1);
            assertTrue(mismatch.status() == 400 || mismatch.reset(),
                    "content-length mismatch must not echo as 200: status=" + mismatch.status() + " reset="
                            + mismatch.reset());
        }
        // connection still usable after the mismatch stream
        var ok = post(h2cClient(), h2c("/echo"), "ok".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, ok.statusCode());
        assertEquals("ok", new String(ok.body(), StandardCharsets.UTF_8));
    }

    private record H2Exchange(int status, byte[] body, boolean reset) {
    }

    private static H2Exchange readResponse(RawH2 h2, int stream) throws IOException {
        var out = new ByteArrayOutputStream();
        Integer status = null;
        for (RawH2.Frame f; (f = h2.read()) != null;) {
            if (f.stream() != stream) {
                continue;
            }
            if (f.type() == RawH2.HEADERS) {
                status = statusFrom(f.payload());
            } else if (f.type() == RawH2.DATA) {
                out.write(f.payload());
            } else if (f.type() == RawH2.RST_STREAM) {
                throw new AssertionError("RST_STREAM error=" + f.errorCode());
            }
            if ((f.flags() & RawH2.END_STREAM) != 0) {
                break;
            }
        }
        assertTrue(status != null, "no response headers");
        return new H2Exchange(status, out.toByteArray(), false);
    }

    private static H2Exchange readResponseOrReset(RawH2 h2, int stream) throws IOException {
        Integer status = null;
        for (RawH2.Frame f; (f = h2.read()) != null;) {
            if (f.type() == RawH2.RST_STREAM && f.stream() == stream) {
                return new H2Exchange(-1, new byte[0], true);
            }
            if (f.type() == RawH2.GOAWAY) {
                return new H2Exchange(-1, new byte[0], true);
            }
            if (f.stream() != stream) {
                continue;
            }
            if (f.type() == RawH2.HEADERS) {
                status = statusFrom(f.payload());
            }
            if ((f.flags() & RawH2.END_STREAM) != 0) {
                break;
            }
        }
        return new H2Exchange(status == null ? -1 : status, new byte[0], false);
    }

    /** Indexed :status from Netty's encoder (static table) or a literal field value. */
    private static int statusFrom(byte[] hpack) {
        for (int i = 0; i < hpack.length; i++) {
            int b = hpack[i] & 0xff;
            if (b == 0x88) {
                return 200;
            }
            if (b == 0x8c) {
                return 400;
            }
            if (b == 0x8d) {
                return 403;
            }
            if (b == 0x8e) {
                return 404;
            }
            if (b == 0x8f) {
                return 500;
            }
        }
        var s = new String(hpack, StandardCharsets.ISO_8859_1);
        for (int code : new int[] {100, 200, 400, 403, 404, 413, 500}) {
            if (s.contains(Integer.toString(code))) {
                return code;
            }
        }
        return -1;
    }

    /**
     * #289 P2: Expect: 100-continue must get an interim 100 before the client sends DATA; a rejecting
     * filter must not send 100 (final response only).
     */
    @Test
    void expectContinueReceives100BeforeBody() throws Exception {
        startEcho();
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            var body = "ping".getBytes(StandardCharsets.ISO_8859_1);
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1,
                    RawH2.block("POST", "/echo", "content-length", Integer.toString(body.length), "expect",
                            "100-continue"));
            Integer interim = null;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.stream() == 1 && f.type() == RawH2.HEADERS) {
                    interim = statusFrom(f.payload());
                    break;
                }
            }
            assertEquals(100, interim, "server must send 100 Continue before the body arrives");
            h2.frame(RawH2.DATA, RawH2.END_STREAM, 1, body);
            var finalResponse = readResponse(h2, 1);
            assertEquals(200, finalResponse.status());
            assertEquals("ping", new String(finalResponse.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void expectContinueIsSkippedWhenAFilterRejectsWithoutReading() throws Exception {
        var size = RequestSizeFilter.create(
                MiddlewareSpec.of("request-size", 0, null, java.util.Map.of("max-bytes", "8")));
        var files = new StaticFileHandler(this.root);
        var chain = RequestFilters.create().add(size).add((request, next) -> {
            if (request.target().startsWith("/echo")) {
                return echo(request);
            }
            return next.proceed(request);
        }).build(files);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();
        this.server.armHandler(chain);
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1,
                    RawH2.block("POST", "/echo", "content-length", "64", "expect", "100-continue"));
            // no DATA: filter rejects on declared length
            Integer first = null;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.stream() == 1 && f.type() == RawH2.HEADERS) {
                    first = statusFrom(f.payload());
                    if ((f.flags() & RawH2.END_STREAM) != 0 || first != 100) {
                        break;
                    }
                }
                if (f.stream() == 1 && f.type() == RawH2.RST_STREAM) {
                    break;
                }
            }
            assertEquals(413, first, "rejecting filter must answer 413 without a prior 100, got " + first);
        }
    }
}
