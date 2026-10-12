package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * HTTP/2 end to end: ALPN (h2, http/1.1 fallback, no ALPN), parity with the core handler over the JDK
 * client, concurrent streams on one connection, h2c (Upgrade and prior knowledge), and the hardening
 * limits driven byte by byte with {@link RawH2}: stream cap, rapid reset, CONTINUATION flood, header
 * list size, flow control (window respected, stalled stream reset without hurting the connection).
 */
class Http2ServerTest {

    static final int BIG = 3 * 1024 * 1024 + 17;
    static final TlsMaterial TLS = TlsMaterial.selfSigned("localhost");

    @TempDir
    static Path root;
    static byte[] big;

    private Http2Server server;
    private StaticFileHandler handler;

    @BeforeAll
    static void files() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello h2\n");
        Files.writeString(root.resolve("index.html"), "<html>idx</html>");
        big = new byte[BIG];
        new Random(42).nextBytes(big);
        Files.write(root.resolve("big.bin"), big);
    }

    private Http2Server start(Http2Server.Limits limits, Duration writeTimeout) throws Exception {
        var config = ServerConfig.builder(root).h2Enabled(true).h2Port(0).h2cPort(0).writeTimeout(writeTimeout).build();
        this.handler = new StaticFileHandler(root);
        this.server = new Http2Server(config, this.handler, TLS, limits).start();
        return this.server;
    }

    private Http2Server start() throws Exception {
        return start(Http2Server.Limits.DEFAULT, Duration.ofSeconds(30));
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    static SSLContext trusting() throws Exception {
        var ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("sjws", TLS.certificate());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private HttpClient client(HttpClient.Version version) throws Exception {
        return HttpClient.newBuilder().version(version).sslContext(trusting()).connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private URI tls(String path) {
        return URI.create("https://localhost:" + this.server.port() + path);
    }

    private HttpResponse<byte[]> send(HttpClient c, String method, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(tls(path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            b.headers(headers);
        }
        return c.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** ALPN result of a raw TLS handshake offering {@code protocols} (none: no ALPN extension). */
    private String alpn(String... protocols) throws Exception {
        try (var s = (SSLSocket) trusting().getSocketFactory().createSocket("localhost", this.server.port())) {
            var p = s.getSSLParameters();
            p.setServerNames(java.util.List.of(new SNIHostName("localhost")));
            if (protocols.length > 0) {
                p.setApplicationProtocols(protocols);
            }
            s.setSSLParameters(p);
            s.startHandshake();
            var selected = s.getApplicationProtocol();
            if (!"h2".equals(selected)) { // the fallback must actually serve HTTP/1.1
                s.getOutputStream().write("GET /hello.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                var text = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), text);
                assertTrue(text.endsWith("hello h2\n"), text);
                assertTrue(text.contains("Connection: close\r\n"), text);
            }
            return selected;
        }
    }

    @Test
    void alpnSelectsH2AndFallsBackToHttp11() throws Exception {
        start();
        assertEquals("h2", alpn("h2", "http/1.1"));
        assertEquals("h2", alpn("http/1.1", "h2")); // server preference
        assertEquals("http/1.1", alpn("http/1.1"));
        assertEquals("", alpn()); // no ALPN at all: HTTP/1.1
    }

    @Test
    void basicGetAndParityWithTheCoreHandler() throws Exception {
        start();
        var c = client(HttpClient.Version.HTTP_2);
        var r = send(c, "GET", "/hello.txt");
        assertEquals(HttpClient.Version.HTTP_2, r.version());
        assertEquals(200, r.statusCode());
        assertEquals("hello h2\n", new String(r.body(), StandardCharsets.UTF_8));
        var core = this.handler.handle(new Request("GET", "/hello.txt"));
        assertEquals(core.headers().get("etag"), r.headers().firstValue("etag").orElseThrow());
        assertEquals("nosniff", r.headers().firstValue("x-content-type-options").orElseThrow());
        assertEquals("9", r.headers().firstValue("content-length").orElseThrow());
        assertTrue(r.headers().firstValue("date").isPresent());
        assertTrue(r.headers().firstValue("server").isPresent());

        var etag = core.headers().get("etag");
        assertEquals(304, send(c, "GET", "/hello.txt", "If-None-Match", etag).statusCode());
        var range = send(c, "GET", "/hello.txt", "Range", "bytes=0-4");
        assertEquals(206, range.statusCode());
        assertEquals("hello", new String(range.body(), StandardCharsets.UTF_8));
        assertEquals(416, send(c, "GET", "/hello.txt", "Range", "bytes=100-").statusCode());
        var head = send(c, "HEAD", "/hello.txt");
        assertEquals(200, head.statusCode());
        assertEquals(0, head.body().length);
        var options = send(c, "OPTIONS", "/");
        assertEquals(200, options.statusCode());
        assertEquals(StaticFileHandler.ALLOW, options.headers().firstValue("allow").orElseThrow());
        var post = send(c, "POST", "/hello.txt");
        assertEquals(405, post.statusCode());
        assertEquals(StaticFileHandler.ALLOW, post.headers().firstValue("allow").orElseThrow());
        assertEquals(404, send(c, "GET", "/missing").statusCode());
        assertEquals(200, send(c, "GET", "/").statusCode());
        var bigResponse = send(c, "GET", "/big.bin");
        assertArrayEquals(big, bigResponse.body());
    }

    @Test
    void concurrentStreamsShareOneConnection() throws Exception {
        start();
        var c = client(HttpClient.Version.HTTP_2);
        send(c, "GET", "/hello.txt"); // open the connection first, so the 64 below multiplex on it
        var futures = new ArrayList<CompletableFuture<HttpResponse<byte[]>>>();
        for (int i = 0; i < 64; i++) {
            var path = i % 8 == 0 ? "/big.bin" : "/hello.txt";
            futures.add(c.sendAsync(HttpRequest.newBuilder(tls(path)).build(), HttpResponse.BodyHandlers.ofByteArray()));
        }
        for (int i = 0; i < futures.size(); i++) {
            var r = futures.get(i).get();
            assertEquals(200, r.statusCode());
            assertEquals(HttpClient.Version.HTTP_2, r.version());
            assertEquals(i % 8 == 0 ? BIG : 9, r.body().length);
        }
        assertEquals(1, this.server.connections());
    }

    @Test
    void h2cByUpgradeAndHttp11OnTheCleartextPort() throws Exception {
        start();
        var c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
        var r = c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.server.h2cPort() + "/hello.txt"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_2, r.version()); // Upgrade: h2c, answered on stream 1
        assertEquals("hello h2\n", r.body());
        var h1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + this.server.h2cPort() + "/hello.txt"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, h1.version());
        assertEquals("hello h2\n", h1.body());
    }

    /** Reads until stream {@code stream} ends; returns its status and body bytes. */
    private static int[] response(RawH2 h2, int stream) throws IOException {
        int status = -1;
        int bytes = 0;
        for (RawH2.Frame f; (f = h2.read()) != null;) {
            if (f.stream() != stream) {
                continue;
            }
            if (f.type() == RawH2.HEADERS) {
                status = f.payload().length; // HPACK-encoded; presence is what matters here
            } else if (f.type() == RawH2.DATA) {
                bytes += f.payload().length;
                if (f.payload().length > 0) {
                    h2.windowUpdate(0, f.payload().length);
                    h2.windowUpdate(stream, f.payload().length);
                }
            } else if (f.type() == RawH2.RST_STREAM) {
                return new int[] {-2, f.errorCode()};
            }
            if ((f.flags() & RawH2.END_STREAM) != 0) {
                return new int[] {status, bytes};
            }
        }
        return new int[] {-3, bytes};
    }

    @Test
    void h2cPriorKnowledge() throws Exception {
        start();
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.get(1, "/hello.txt");
            var r = response(h2, 1);
            assertTrue(r[0] > 0, "HEADERS received");
            assertEquals(9, r[1]);
        }
    }

    @Test
    void streamsOverTheCapAreRefused() throws Exception {
        start(new Http2Server.Limits(4, 16 * 1024, 100, 10, 16, 4096, 512), Duration.ofSeconds(30));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) { // window 0: responses can't finish, streams stay open
            for (int i = 0; i < 7; i++) {
                h2.get(1 + 2 * i, "/big.bin");
            }
            var refused = new HashMap<Integer, Integer>();
            for (RawH2.Frame f; refused.size() < 3 && (f = h2.read()) != null;) {
                if (f.type() == RawH2.RST_STREAM) {
                    refused.put(f.stream(), f.errorCode());
                }
                assertNotEquals(RawH2.GOAWAY, f.type());
            }
            assertEquals(java.util.Map.of(9, 7, 11, 7, 13, 7), refused, "REFUSED_STREAM (7) for streams 5..7");
        }
    }

    @Test
    void rapidResetClosesTheConnectionWithEnhanceYourCalm() throws Exception {
        start(new Http2Server.Limits(100, 16 * 1024, 50, 10, 16, 4096, 512), Duration.ofSeconds(30));
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            RawH2.Frame goAway = null;
            try {
                for (int i = 0; i < 200; i++) {
                    int id = 1 + 2 * i;
                    h2.get(id, "/big.bin");
                    h2.rst(id, 8); // CANCEL
                }
            } catch (IOException e) { // the server already closed: fine
            }
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.GOAWAY && goAway == null) {
                    goAway = f;
                }
            }
            assertNotNull(goAway, "GOAWAY expected");
            assertEquals(0xb, goAway.errorCode(), "ENHANCE_YOUR_CALM first");
            assertEquals(-1, h2.socket.getInputStream().read(), "connection closed");
        }
    }

    @Test
    void continuationFloodClosesTheConnection() throws Exception {
        start();
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            var block = RawH2.block("GET", "/hello.txt");
            h2.frame(RawH2.HEADERS, 0, 1, block); // no END_HEADERS
            for (int i = 0; i < 20; i++) { // more than the limit (16); stop there so the GOAWAY isn't lost to a TCP reset
                h2.frame(RawH2.CONTINUATION, 0, 1, new byte[0]);
            }
            RawH2.Frame goAway = null;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.GOAWAY) {
                    goAway = f;
                }
            }
            assertNotNull(goAway, "GOAWAY expected");
        }
    }

    @Test
    void oversizedHeaderListIsNotServed() throws Exception {
        start();
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            var block = RawH2.block("GET", "/hello.txt", "x-big", "a".repeat(15_000), "x-big2", "b".repeat(15_000));
            h2.frame(RawH2.HEADERS, 0, 1, java.util.Arrays.copyOfRange(block, 0, 16_000));
            h2.frame(RawH2.CONTINUATION, RawH2.END_HEADERS | RawH2.END_STREAM, 1,
                    java.util.Arrays.copyOfRange(block, 16_000, block.length));
            boolean refused = false;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                assertNotEquals(RawH2.DATA, f.type(), "the request must not be served");
                if (f.type() == RawH2.GOAWAY || f.type() == RawH2.RST_STREAM
                        || (f.type() == RawH2.HEADERS && f.stream() == 1)) { // 431 or reset
                    refused = true;
                    break;
                }
            }
            assertTrue(refused);
        }
    }

    @Test
    void flowControlRespectsThePeerWindow() throws Exception {
        start();
        int window = 1024;
        try (var h2 = new RawH2(this.server.h2cPort(), window)) {
            h2.get(1, "/big.bin");
            var digest = MessageDigest.getInstance("SHA-256");
            long received = 0;
            long granted = window; // stream window; the connection window starts at 65535
            long connGranted = 65535;
            boolean end = false;
            while (!end) {
                var f = h2.read();
                assertNotNull(f, "server stalled or closed after " + received + " bytes");
                assertNotEquals(RawH2.RST_STREAM, f.type());
                assertNotEquals(RawH2.GOAWAY, f.type());
                if (f.type() != RawH2.DATA || f.stream() != 1) {
                    continue;
                }
                received += f.payload().length;
                digest.update(f.payload());
                assertTrue(received <= granted, "sent beyond the stream window: " + received + " > " + granted);
                assertTrue(received <= connGranted, "sent beyond the connection window");
                end = (f.flags() & RawH2.END_STREAM) != 0;
                if (!end && received == granted) { // window exhausted: grant another 64 KiB
                    h2.windowUpdate(1, 65536);
                    h2.windowUpdate(0, 65536);
                    granted += 65536;
                    connGranted += 65536;
                }
            }
            assertEquals(BIG, received);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(big), digest.digest());
        }
    }

    @Test
    void stalledStreamIsResetAndTheConnectionKeepsServing() throws Exception {
        start(Http2Server.Limits.DEFAULT, Duration.ofSeconds(1));
        try (var h2 = new RawH2(this.server.h2cPort(), 1024)) {
            h2.get(1, "/big.bin"); // never granted more than 1 KiB
            Integer code = null;
            for (RawH2.Frame f; code == null && (f = h2.read()) != null;) {
                if (f.type() == RawH2.RST_STREAM && f.stream() == 1) {
                    code = f.errorCode();
                }
            }
            assertNotNull(code, "the stalled stream must be reset after the write timeout");
            h2.get(3, "/hello.txt");
            var r = response(h2, 3);
            assertEquals(9, r[1]);
        }
    }

    @Test
    void inflightCapRefusesStreams() throws Exception {
        start(new Http2Server.Limits(100, 16 * 1024, 100, 10, 16, 2, 512), Duration.ofSeconds(30));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) {
            for (int i = 0; i < 4; i++) {
                h2.get(1 + 2 * i, "/big.bin");
            }
            int refused = 0;
            for (RawH2.Frame f; refused < 2 && (f = h2.read()) != null;) {
                if (f.type() == RawH2.RST_STREAM && f.errorCode() == 7) {
                    refused++;
                }
            }
            assertEquals(2, refused);
            assertTrue(this.server.refusedStreams() >= 2);
        }
    }
}
