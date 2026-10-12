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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sjws_nettask.h3.H3Fixture.assertParity;

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.util.ResourceLeakDetector;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.HttpDate;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * HTTP/3 end to end: a real {@link Http3Server} on an ephemeral UDP port and Netty's HTTP/3 client
 * in the same JVM. Responses are checked against the HTTP/1.1 serialization of the same handler
 * result ({@link H3Fixture#assertParity}), not against literals.
 */
@Tag("h3")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Http3ServerTest {

    @TempDir
    static Path base;

    Path root;
    StaticFileHandler handler;
    TlsMaterial tls;
    Http3Server server;
    H3TestClient client;
    QuicChannel quic;
    final List<String> leaks = new ArrayList<>();
    final Handler leakHandler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            if (r.getMessage() != null && r.getMessage().contains("LEAK")) {
                Http3ServerTest.this.leaks.add(r.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeAll
    void startServer() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        Logger.getLogger("io.netty.util.ResourceLeakDetector").addHandler(this.leakHandler);
        this.root = H3Fixture.webRoot(base);
        this.handler = new StaticFileHandler(this.root);
        this.tls = TlsMaterial.selfSigned("localhost");
        this.server = new Http3Server(config().build(), this.handler, this.tls).start();
        this.client = new H3TestClient(this.tls.certificate());
        this.quic = this.client.connect(this.server.localAddress());
    }

    ServerConfig.Builder config() {
        return ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0);
    }

    @AfterAll
    void stopServer() throws Exception {
        if (this.server != null) {
            for (var o : new Http3Writer.Outcome[] {Http3Writer.Outcome.SWAPPED, Http3Writer.Outcome.SHRANK,
                Http3Writer.Outcome.STALLED, Http3Writer.Outcome.FAILED}) {
                assertEquals(0, this.server.responses(o), "shared server: unexpected " + o);
            }
        }
        if (this.client != null) {
            this.client.close();
        }
        if (this.server != null) {
            this.server.close();
        }
        // Paranoid leak detection reports when leaked buffers are collected and new ones allocated.
        for (int i = 0; i < 3; i++) {
            System.gc();
            io.netty.buffer.PooledByteBufAllocator.DEFAULT.buffer(1024).release();
            Thread.sleep(50);
        }
        Logger.getLogger("io.netty.util.ResourceLeakDetector").removeHandler(this.leakHandler);
        assertTrue(this.leaks.isEmpty(), "ByteBuf leaks: " + this.leaks);
    }

    H3TestClient.H3Response send(String method, String path, Map<String, String> headers) throws Exception {
        return H3TestClient.request(shared(), method, path, headers).get(30, TimeUnit.SECONDS);
    }

    /** The shared connection, reopened if a slow test let it idle out. */
    QuicChannel shared() throws Exception {
        if (!this.quic.isActive()) {
            this.quic = this.client.connect(this.server.localAddress());
        }
        return this.quic;
    }

    void parity(String method, String path, Map<String, String> headers) throws Exception {
        assertParity(this.handler, method, path, headers, send(method, path, headers));
    }

    void parity(String path) throws Exception {
        parity("GET", path, Map.of());
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    // --- representations ---------------------------------------------------------------------

    @Test
    void textFile() throws Exception {
        var r = send("GET", "/hello.txt", Map.of());
        assertEquals(200, r.status());
        assertEquals("Hello over HTTP/3\n", H3Fixture.text(r));
        assertParity(this.handler, "GET", "/hello.txt", Map.of(), r);
        var lastModified = HttpDate.parse(r.header("last-modified"));
        assertEquals(Files.getLastModifiedTime(this.root.resolve("hello.txt")).toInstant().getEpochSecond(),
                lastModified.getEpochSecond());
        var date = HttpDate.parse(r.header("date"));
        assertTrue(Math.abs(Duration.between(date, java.time.Instant.now()).toSeconds()) <= 5, r.header("date"));
    }

    @Test
    void binaryFileIsByteExact() throws Exception {
        var r = send("GET", "/bin.dat", Map.of());
        assertArrayEquals(Files.readAllBytes(this.root.resolve("bin.dat")), r.body());
        assertTrue(r.dataFrames() > 1, "256 KiB should take several DATA frames");
        assertParity(this.handler, "GET", "/bin.dat", Map.of(), r);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/sub2/", "/style.css", "/empty.txt", "/a%20b+c.txt?x=1#f",
        "/%C3%BCn%C3%AF.txt", "/link-in.txt", "/sub/", "/sub/nested/", "/sub", "/sub?q=1", "/missing.txt",
        "/sub/missing/", "/%zz", "/a%00b", "*", "/../secret.txt", "/%2e%2e/secret.txt", "/sub/../../secret.txt",
        "/link-out", "/.hidden"})
    void getParity(String path) throws Exception {
        parity(path);
    }

    @Test
    void indexAndListing() throws Exception {
        assertTrue(H3Fixture.text(send("GET", "/", Map.of())).contains("root index"));
        assertTrue(H3Fixture.text(send("GET", "/sub2/", Map.of())).contains("sub2 index"));
        var listing = send("GET", "/sub/", Map.of());
        assertEquals(200, listing.status());
        var html = H3Fixture.text(listing);
        assertTrue(html.contains("&lt;script&gt;x.txt"), html);
        assertTrue(html.contains("href=\"/sub/%3Cscript%3Ex.txt\""), html);
        assertTrue(html.contains("nested/"), html);
        assertFalse(html.contains("<script>"), html);
    }

    @Test
    void redirectForDirectoryWithoutSlash() throws Exception {
        var r = send("GET", "/sub?q=1", Map.of());
        assertEquals(301, r.status());
        assertEquals("/sub/?q=1", r.header("location"));
    }

    @Test
    void headHasLengthButNoData() throws Exception {
        for (var path : new String[] {"/hello.txt", "/large.bin", "/sub/", "/missing"}) {
            var r = send("HEAD", path, Map.of());
            assertEquals(0, r.body().length, path);
            assertEquals(0, r.dataFrames(), path);
            assertParity(this.handler, "HEAD", path, Map.of(), r);
        }
        assertEquals(Long.toString(H3Fixture.LARGE), send("HEAD", "/large.bin", Map.of()).header("content-length"));
    }

    @Test
    void notModifiedIfModifiedSince() throws Exception {
        var lastModified = send("GET", "/hello.txt", Map.of()).header("last-modified");
        var conditional = Map.of("if-modified-since", lastModified);
        var r = send("GET", "/hello.txt", conditional);
        assertEquals(304, r.status());
        assertEquals(0, r.dataFrames());
        assertEquals(null, r.header("content-length"));
        assertParity(this.handler, "GET", "/hello.txt", conditional, r);
        parity("HEAD", "/hello.txt", conditional);
        // older date: full response
        parity("GET", "/hello.txt", Map.of("if-modified-since", "Thu, 01 Jan 1970 00:00:00 GMT"));
        parity("GET", "/hello.txt", Map.of("if-modified-since", "not a date"));
    }

    @Test
    void ifNoneMatchAndOtherConditionalsAreForwarded() throws Exception {
        // ETag/If-None-Match arrive with the cache step (S3); whatever the handler decides, HTTP/3 must agree.
        parity("GET", "/hello.txt", Map.of("if-none-match", "\"x\""));
        parity("GET", "/hello.txt", Map.of("if-none-match", "*"));
        parity("GET", "/large.bin", Map.of("range", "bytes=0-9", "if-range", "\"x\"", "accept-encoding", "gzip"));
    }

    @Test
    void forbiddenFile() throws Exception {
        var file = this.root.resolve("private.txt");
        Files.writeString(file, "no");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        try {
            var r = send("GET", "/private.txt", Map.of());
            assertEquals(403, r.status());
            assertParity(this.handler, "GET", "/private.txt", Map.of(), r);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void traversalNeverLeaksTheSecret() throws Exception {
        for (var path : new String[] {"/../secret.txt", "/%2e%2e/secret.txt", "/sub/../../secret.txt",
            "/..%2fsecret.txt", "/link-out", "/%2e%2e%2fsecret.txt", "/sub/%2e%2e/%2e%2e/secret.txt"}) {
            var r = send("GET", path, Map.of());
            assertNotEquals(200, r.status(), path);
            assertFalse(H3Fixture.text(r).contains(H3Fixture.SECRET), path);
            assertParity(this.handler, "GET", path, Map.of(), r);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "OPTIONS", "PATCH", "get"})
    void methodNotAllowed(String method) throws Exception {
        var r = send(method, "/hello.txt", Map.of());
        assertEquals(405, r.status());
        assertEquals(StaticFileHandler.ALLOW, r.header("allow"));
        assertParity(this.handler, method, "/hello.txt", Map.of(), r);
    }

    @Test
    void postBodyIsDiscarded() throws Exception {
        var body = new byte[300_000];
        var r = H3TestClient.request(shared(), "POST", "/hello.txt", Map.of(), body, null).get(30, TimeUnit.SECONDS);
        assertParity(this.handler, "POST", "/hello.txt", Map.of(), r);
        parity("/hello.txt"); // connection still fine
    }

    @Test
    void badEncoding() throws Exception {
        for (var path : new String[] {"/%zz", "/a%00b", "/%"}) {
            var r = send("GET", path, Map.of());
            assertEquals(400, r.status(), path);
            assertParity(this.handler, "GET", path, Map.of(), r);
        }
    }

    @Test
    void largeFileWithFlowControl() throws Exception {
        var r = send("GET", "/large.bin", Map.of());
        assertEquals(200, r.status());
        assertEquals(H3Fixture.LARGE, r.body().length);
        assertEquals(sha256(Files.readAllBytes(this.root.resolve("large.bin"))), sha256(r.body()));
        assertTrue(r.dataFrames() >= H3Fixture.LARGE / Http3Writer.CHUNK, "frames " + r.dataFrames());
        assertParity(this.handler, "GET", "/large.bin", Map.of(), r);
    }

    @Test
    void manyConcurrentStreamsOnOneConnection() throws Exception {
        var paths = new String[] {"/hello.txt", "/bin.dat", "/sub/", "/missing", "/", "/large.bin", "/style.css",
            "/sub", "/%zz", "/a%20b+c.txt"};
        var futures = new ArrayList<CompletableFuture<H3TestClient.H3Response>>();
        for (int i = 0; i < 60; i++) {
            futures.add(H3TestClient.request(shared(), i % 7 == 0 ? "HEAD" : "GET", paths[i % paths.length], Map.of()));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertParity(this.handler, i % 7 == 0 ? "HEAD" : "GET", paths[i % paths.length], Map.of(),
                    futures.get(i).get(60, TimeUnit.SECONDS));
        }
    }

    @Test
    void multipleConnectionsInParallel() throws Exception {
        var connections = new ArrayList<QuicChannel>();
        for (int c = 0; c < 16; c++) {
            connections.add(this.client.connect(this.server.localAddress()));
        }
        var futures = new ArrayList<CompletableFuture<H3TestClient.H3Response>>();
        var paths = new String[] {"/hello.txt", "/bin.dat", "/sub/", "/missing", "/index.html"};
        for (int i = 0; i < 10; i++) {
            for (var c : connections) {
                futures.add(H3TestClient.request(c, "GET", paths[i % paths.length], Map.of()));
            }
        }
        int i = 0;
        for (int round = 0; round < 10; round++) {
            for (int c = 0; c < connections.size(); c++) {
                assertParity(this.handler, "GET", paths[round % paths.length], Map.of(),
                        futures.get(i++).get(60, TimeUnit.SECONDS));
            }
        }
        assertTrue(this.server.connections() >= 17, "connections " + this.server.connections());
        for (var c : connections) {
            c.close().sync();
        }
    }

    @Test
    void missingPathIsAStreamErrorNotAConnectionError() throws Exception {
        var r = H3TestClient.request(shared(), "GET", null, Map.of()).get(30, TimeUnit.SECONDS);
        assertTrue(r.reset() || r.status() >= 400, "malformed request must not succeed: " + r);
        parity("/hello.txt"); // same connection keeps working
    }

    @Test
    void requestMappingCarriesAllCoreFields() {
        var h = new io.netty.handler.codec.http3.DefaultHttp3Headers();
        h.method("GET").path("/x?y").authority("example.org:443").scheme("https");
        h.add("if-none-match", "\"a\"").add("if-none-match", "\"b\"").add("if-modified-since", "d")
                .add("if-range", "r").add("range", "bytes=1-2").add("accept-encoding", "br").add("host", "ignored");
        var request = Http3RequestHandler.toRequest(h);
        assertEquals("GET", request.method());
        assertEquals("/x?y", request.target());
        var rh = request.headers();
        assertEquals("\"a\"", rh.ifNoneMatch()); // first value, same rule as Http1Parser.header
        assertEquals("d", rh.ifModifiedSince());
        assertEquals("r", rh.ifRange());
        assertEquals("bytes=1-2", rh.range());
        assertEquals("br", rh.acceptEncoding());
        assertEquals("example.org:443", rh.host());

        var noAuthority = new io.netty.handler.codec.http3.DefaultHttp3Headers();
        noAuthority.add("host", "h.example");
        var r2 = Http3RequestHandler.toRequest(noAuthority);
        assertEquals("", r2.method());
        assertEquals("", r2.target());
        assertEquals("h.example", r2.headers().host());
        assertEquals(null, r2.headers().range());
    }

    @Test
    void writerDropsConnectionSpecificFields() {
        var headers = sjws_nettask.core.ResponseHeaders.of().add("content-type", "text/plain")
                .add("connection", "close").add("transfer-encoding", "chunked").add("keep-alive", "x")
                .add("upgrade", "h2c").add("proxy-connection", "x").add("te", "trailers").add("date", "old")
                .add("server", "other").add("content-length", "999").add("alt-svc", "h3").add("x-extra", "1");
        var response = new Response(200, headers, sjws_nettask.core.Body.Bytes.of(new byte[3]));
        var frame = Http3Writer.headersFrame(response, java.time.Instant.EPOCH);
        var h = frame.headers();
        assertEquals("200", h.status().toString());
        assertEquals("text/plain", h.get("content-type").toString());
        assertEquals("1", h.get("x-extra").toString());
        assertEquals("3", h.get("content-length").toString());
        assertEquals(1, h.getAll("content-length").size());
        assertEquals(sjws_nettask.HTTP.Http1Writer.SERVER, h.get("server").toString());
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", h.get("date").toString());
        for (var name : new String[] {"connection", "transfer-encoding", "keep-alive", "upgrade",
            "proxy-connection", "te", "alt-svc"}) {
            assertFalse(h.contains(name), name);
        }
        var notModified = Http3Writer.headersFrame(new Response(304, sjws_nettask.core.ResponseHeaders.of(),
                sjws_nettask.core.Body.Empty.INSTANCE), java.time.Instant.EPOCH);
        assertFalse(notModified.headers().contains("content-length"));
    }

    // --- failure paths through the test seam ---------------------------------------------------

    /** A server whose handler can change the file system between deciding and writing. */
    Http3Server seamServer(Function<Request, Response> seam, ServerConfig cfg) throws Exception {
        return new Http3Server(cfg, seam, this.tls).start();
    }

    @Test
    void swappedFileIsRefusedWithStreamReset() throws Exception {
        var file = this.root.resolve("swap.txt");
        var replacement = this.root.resolve("replacement.txt");
        Function<Request, Response> seam = req -> {
            try {
                Files.writeString(file, "original content");
                var response = this.handler.handle(req);
                Files.writeString(replacement, "OTHER FILE, other inode");
                Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return response;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        try (var s = seamServer(seam, config().build()); var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var r = H3TestClient.get(q, "/swap.txt");
            assertTrue(r.reset(), "expected a reset, got " + r.status());
            assertEquals(-1, r.status(), "no head may be sent for a swapped file");
            assertEquals(0, r.body().length);
            assertTrue(H3TestClient.get(q, "/hello.txt").status() == 200, "connection survives");
            waitFor(() -> s.responses(Http3Writer.Outcome.SWAPPED) == 1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fileReplacedBySymlinkIsRefused() throws Exception {
        var file = this.root.resolve("victim.txt");
        Function<Request, Response> seam = req -> {
            try {
                Files.deleteIfExists(file);
                Files.writeString(file, "victim");
                var response = this.handler.handle(req);
                Files.delete(file);
                Files.createSymbolicLink(file, base.resolve("secret.txt"));
                return response;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        try (var s = seamServer(seam, config().build()); var c = new H3TestClient(this.tls.certificate())) {
            var r = H3TestClient.get(c.connect(s.localAddress()), "/victim.txt");
            assertTrue(r.reset(), "expected a reset");
            assertEquals(-1, r.status());
            assertFalse(H3Fixture.text(r).contains(H3Fixture.SECRET));
            waitFor(() -> s.responses(Http3Writer.Outcome.SWAPPED) == 1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fileShrinkingMidSendResetsInsteadOfHanging() throws Exception {
        var file = this.root.resolve("shrink.bin");
        Function<Request, Response> seam = req -> {
            try {
                Files.write(file, new byte[4 * 1024 * 1024]);
                var response = this.handler.handle(req);
                try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
                    ch.truncate(1024 * 1024 + 17); // same inode, shorter
                }
                return response;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        try (var s = seamServer(seam, config().build()); var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var r = H3TestClient.request(q, "GET", "/shrink.bin", Map.of()).get(20, TimeUnit.SECONDS);
            assertTrue(r.reset(), "expected a reset");
            assertTrue(r.body().length < 4 * 1024 * 1024, "short body must not look complete");
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status(), "connection survives");
            waitFor(() -> s.responses(Http3Writer.Outcome.SHRANK) == 1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fileRegionHonoursOffsetAndLength() throws Exception {
        // Range (S5) will hand writers FileBody slices; the HTTP/3 writer must send exactly that region.
        var large = this.root.resolve("large.bin");
        Function<Request, Response> seam = req -> {
            var full = this.handler.handle(new Request("GET", "/large.bin"));
            var body = (sjws_nettask.core.Body.FileBody) full.body();
            var region = new sjws_nettask.core.Body.FileBody(body.realPath(), 100_003, 1_000_000, body.expected());
            return new Response(200, full.headers(), region);
        };
        try (var s = seamServer(seam, config().build()); var c = new H3TestClient(this.tls.certificate())) {
            var r = H3TestClient.get(c.connect(s.localAddress()), "/large.bin");
            var all = Files.readAllBytes(large);
            assertArrayEquals(java.util.Arrays.copyOfRange(all, 100_003, 1_100_003), r.body());
            assertEquals("1000000", r.header("content-length"));
            var out = new java.io.ByteArrayOutputStream();
            sjws_nettask.HTTP.Http1Writer.write(out, seam.apply(null), true);
            var h1 = sjws_nettask.HttpTestSupport.readResponse(new java.io.ByteArrayInputStream(out.toByteArray()));
            assertArrayEquals(h1.body(), r.body(), "same region as HTTP/1");
        }
    }

    @Test
    void handlerExceptionBecomes500() throws Exception {
        Function<Request, Response> seam = req -> {
            throw new IllegalStateException("boom");
        };
        try (var s = seamServer(seam, config().build()); var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var r = H3TestClient.get(q, "/x");
            assertEquals(500, r.status());
            var expected = ((sjws_nettask.core.Body.Bytes) StaticFileHandler.error(500, false).body()).data();
            var bytes = new byte[expected.remaining()];
            expected.duplicate().get(bytes);
            assertArrayEquals(bytes, r.body());
            var head = H3TestClient.request(q, "HEAD", "/x", Map.of()).get(10, TimeUnit.SECONDS);
            assertEquals(500, head.status());
            assertEquals(0, head.dataFrames());
        }
    }

    @Test
    void readerThatStopsReadingIsResetAfterTheWriteTimeout() throws Exception {
        var cfg = config().writeTimeout(Duration.ofMillis(300)).build();
        try (var s = new Http3Server(cfg, this.handler, this.tls).start();
                var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var stalled = new java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.quic.QuicStreamChannel>();
            var future = H3TestClient.request(q, "GET", "/large.bin", Map.of(), null, stream -> {
                stream.config().setAutoRead(false);
                stalled.set(stream);
            });
            Thread.sleep(1500); // window fills, server waits > writeTimeout, then resets
            stalled.get().config().setAutoRead(true);
            stalled.get().read();
            var r = future.get(20, TimeUnit.SECONDS);
            assertTrue(r.reset(), "expected the stalled stream to be reset");
            assertTrue(r.body().length < H3Fixture.LARGE);
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status(), "connection survives");
            waitFor(() -> s.responses(Http3Writer.Outcome.STALLED) == 1);
        }
    }

    static Response bigBytes(int size) {
        var data = new byte[size];
        new java.util.Random(7).nextBytes(data);
        return new Response(200, sjws_nettask.core.ResponseHeaders.of().add("content-type", "application/octet-stream"),
                sjws_nettask.core.Body.Bytes.of(data));
    }

    @Test
    void largeInMemoryBodyIsChunkedAndBackpressured() throws Exception {
        var expected = bigBytes(3 * 1024 * 1024 + 5);
        var cfg = config().writeTimeout(Duration.ofMillis(300)).build();
        try (var s = seamServer(req -> bigBytes(3 * 1024 * 1024 + 5), cfg);
                var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var r = H3TestClient.get(q, "/anything");
            var out = new java.io.ByteArrayOutputStream();
            sjws_nettask.HTTP.Http1Writer.write(out, expected, true);
            var h1 = sjws_nettask.HttpTestSupport.readResponse(new java.io.ByteArrayInputStream(out.toByteArray()));
            assertArrayEquals(h1.body(), r.body());
            assertEquals(h1.headers().get("content-length"), r.header("content-length"));
            assertTrue(r.dataFrames() >= 48, "frames " + r.dataFrames());
            // same body to a reader that stops reading: reset after the write timeout
            var stalled = new java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.quic.QuicStreamChannel>();
            var future = H3TestClient.request(q, "GET", "/x", Map.of(), null, stream -> {
                stream.config().setAutoRead(false);
                stalled.set(stream);
            });
            waitFor(() -> s.responses(Http3Writer.Outcome.STALLED) == 1);
            stalled.get().config().setAutoRead(true);
            stalled.get().read();
            assertTrue(future.get(20, TimeUnit.SECONDS).reset());
        }
    }

    @Test
    void clientCancellingADownloadStopsTheWriter() throws Exception {
        try (var s = new Http3Server(config().writeTimeout(Duration.ofSeconds(20)).build(), this.handler, this.tls)
                .start(); var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var stream = new java.util.concurrent.atomic.AtomicReference<io.netty.handler.codec.quic.QuicStreamChannel>();
            H3TestClient.request(q, "GET", "/large.bin", Map.of(), null, st -> {
                st.config().setAutoRead(false); // the transfer cannot finish before the cancel
                stream.set(st);
            });
            waitFor(() -> stream.get() != null);
            Thread.sleep(300);
            // STOP_SENDING + RESET_STREAM from the client
            stream.get().shutdown(io.netty.handler.codec.http3.Http3ErrorCode.H3_REQUEST_CANCELLED.code());
            waitFor(() -> s.responses(Http3Writer.Outcome.CLOSED) + s.responses(Http3Writer.Outcome.STALLED) == 1);
            assertEquals(1, s.responses(Http3Writer.Outcome.CLOSED), "writer must notice the cancel, not time out");
            stream.get().close();
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status(), "connection survives");
        }
    }

    @Test
    void shutdownInterruptsInFlightResponses() throws Exception {
        var s = new Http3Server(config().writeTimeout(Duration.ofSeconds(30)).build(), this.handler, this.tls).start();
        try (var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            var future = H3TestClient.request(q, "GET", "/large.bin", Map.of(), null,
                    stream -> stream.config().setAutoRead(false));
            Thread.sleep(500); // writer is now parked waiting for flow-control credit
            s.close();
            waitFor(() -> s.responses(Http3Writer.Outcome.CLOSED) == 1);
            assertTrue(q.closeFuture().await(5, TimeUnit.SECONDS));
            assertTrue(future.isDone() || q.closeFuture().isDone());
        } finally {
            s.close();
        }
    }

    @Test
    void trailersAndRequestBodiesAreIgnored() throws Exception {
        var r = H3TestClient.request(shared(), "GET", "/hello.txt", Map.of(), new byte[1000], null, true)
                .get(30, TimeUnit.SECONDS);
        assertParity(this.handler, "GET", "/hello.txt", Map.of(), r);
    }

    // --- TLS, ALPN, limits, lifecycle -----------------------------------------------------------

    @Test
    void pemCertificateIsServedAndPinned() throws Exception {
        var pemDir = Files.createDirectories(base.resolve("pem"));
        var material = TlsMaterial.selfSigned("localhost");
        var cert = pemDir.resolve("cert.pem");
        var key = pemDir.resolve("key.pem");
        Files.writeString(cert, pem("CERTIFICATE", material.certificate().getEncoded()));
        Files.writeString(key, pem("PRIVATE KEY", material.key().getEncoded()));
        var cfg = config().tlsCert(cert).tlsKey(key).build();
        try (var s = new Http3Server(cfg, this.handler).start(); var c = new H3TestClient(material.certificate())) {
            assertFalse(s.tls().selfSigned());
            assertEquals(material.fingerprint(), s.tls().fingerprint());
            assertParity(this.handler, "GET", "/hello.txt", Map.of(), H3TestClient.get(c.connect(s.localAddress()),
                    "/hello.txt"));
        }
        // a client pinning a different certificate must refuse the server
        try (var s = new Http3Server(cfg, this.handler).start();
                var c = new H3TestClient(TlsMaterial.selfSigned("localhost").certificate())) {
            assertThrows(Exception.class, () -> c.connect(s.localAddress()));
        }
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + java.util.Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    @Test
    void selfSignedWhenNoCertificateConfigured() throws Exception {
        var err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        Http3Server s;
        try {
            s = new Http3Server(config().build(), this.handler).start();
        } finally {
            System.setErr(err);
        }
        try (s; var c = new H3TestClient(s.tls().certificate())) {
            assertTrue(s.tls().selfSigned());
            assertEquals(200, H3TestClient.get(c.connect(s.localAddress()), "/hello.txt").status());
        }
    }

    @Test
    void badPemFailsStartCleanly() throws Exception {
        var bad = Files.writeString(base.resolve("bad.pem"), "nothing");
        var cfg = config().tlsCert(bad).tlsKey(bad).build();
        var s = new Http3Server(cfg, this.handler);
        assertThrows(java.io.IOException.class, s::start);
        assertThrows(IllegalStateException.class, s::boundPort);
        s.close();
    }

    @Test
    void alpnMismatchIsRejected() throws Exception {
        try (var c = new H3TestClient(this.tls.certificate(), 5_000, "h2")) {
            assertThrows(Exception.class, () -> c.connect(this.server.localAddress()));
        }
        try (var c = new H3TestClient(null, 5_000, "h3")) { // insecure trust, right ALPN: works
            assertEquals(200, H3TestClient.get(c.connect(this.server.localAddress()), "/hello.txt").status());
        }
    }

    @Test
    void connectionLimit() throws Exception {
        try (var s = new Http3Server(config().h3MaxConnections(2).build(), this.handler, this.tls).start();
                var c = new H3TestClient(this.tls.certificate())) {
            var a = c.connect(s.localAddress());
            var b = c.connect(s.localAddress());
            assertEquals(200, H3TestClient.get(a, "/hello.txt").status());
            assertEquals(200, H3TestClient.get(b, "/hello.txt").status());
            assertTrue(refused(c, s), "third connection must be refused");
            a.close().sync();
            waitFor(() -> s.connections() < 2);
            var d = c.connect(s.localAddress()); // a slot is free again
            assertEquals(200, H3TestClient.get(d, "/hello.txt").status());
        }
    }

    static boolean refused(H3TestClient c, Http3Server s) {
        try {
            var extra = c.connect(s.localAddress());
            var r = H3TestClient.request(extra, "GET", "/hello.txt", Map.of()).get(10, TimeUnit.SECONDS);
            return r.status() != 200;
        } catch (Exception e) {
            return true;
        }
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached");
            Thread.sleep(20);
        }
    }

    @Test
    void idleConnectionsAreClosedByTheServer() throws Exception {
        try (var s = new Http3Server(config().idleTimeout(Duration.ofMillis(400)).build(), this.handler, this.tls)
                .start(); var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status());
            assertTrue(q.closeFuture().await(5, TimeUnit.SECONDS), "server idle timeout should close the connection");
            waitFor(() -> s.connections() == 0);
        }
    }

    @Test
    void cleanShutdownClosesConnectionsAndFreesThePort() throws Exception {
        var s = new Http3Server(config().build(), this.handler, this.tls).start();
        int port = s.boundPort();
        try (var c = new H3TestClient(this.tls.certificate())) {
            var q = c.connect(s.localAddress());
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status());
            assertEquals(1, s.connections());
            s.close();
            assertTrue(q.closeFuture().await(5, TimeUnit.SECONDS), "client must see the close");
            s.close(); // idempotent
        }
        try (var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
            assertEquals(port, socket.getLocalPort());
        }
        assertThrows(IllegalStateException.class, s::start);
    }

    @Test
    void lifecycleMisuse() throws Exception {
        var s = new Http3Server(config().build(), this.handler, this.tls);
        assertThrows(IllegalStateException.class, s::localAddress);
        assertEquals(null, new Http3Server(config().build(), this.handler).tls());
        s.start();
        try {
            assertThrows(IllegalStateException.class, s::start);
            assertNotNull(s.tls());
        } finally {
            s.close();
        }
        s.close();
        new Http3Server(config().build(), this.handler).close(); // never started
    }

    @Test
    void busyPortFailsWithIOException() throws Exception {
        var cfg = config().h3Port(this.server.boundPort()).build();
        var e = assertThrows(java.io.IOException.class, () -> new Http3Server(cfg, this.handler, this.tls).start());
        assertTrue(e.getMessage().contains("udp/127.0.0.1:" + this.server.boundPort()), e.getMessage());
    }

    @Test
    void portDefaultsToTheTcpPort() throws Exception {
        try (var probe = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            int free = probe.getLocalPort();
            probe.close();
            var cfg = ServerConfig.builder(this.root).port(free).h3Host("127.0.0.1").build();
            try (var s = new Http3Server(cfg, this.handler, this.tls).start()) {
                assertEquals(free, s.boundPort());
            }
        }
    }

    @Test
    void parityHelperDetectsDifferences() throws Exception {
        var good = send("GET", "/hello.txt", Map.of());
        assertParity(this.handler, "GET", "/hello.txt", Map.of(), good);
        var mutated = new java.util.HashMap<>(good.headers());
        mutated.put("content-type", "text/html");
        var bad = new H3TestClient.H3Response(good.status(), mutated, good.headerNames(), good.body(),
                good.dataFrames(), false, null);
        assertThrows(AssertionError.class, () -> assertParity(this.handler, "GET", "/hello.txt", Map.of(), bad));
        var badBody = new H3TestClient.H3Response(good.status(), good.headers(), good.headerNames(), new byte[1],
                good.dataFrames(), false, null);
        assertThrows(AssertionError.class, () -> assertParity(this.handler, "GET", "/hello.txt", Map.of(), badBody));
        var extra = new java.util.HashMap<>(good.headers());
        extra.put("connection", "close");
        var withConnection = new H3TestClient.H3Response(good.status(), extra, good.headerNames(), good.body(),
                good.dataFrames(), false, null);
        assertThrows(AssertionError.class,
                () -> assertParity(this.handler, "GET", "/hello.txt", Map.of(), withConnection));
    }
}
