package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/** HTTP/1.1 fallback on the h2c port, request refusals, connection caps, idle timeout, shutdown. */
class Http2EdgeTest {

    @TempDir
    static Path root;

    private Http2Server server;

    @BeforeAll
    static void files() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello h2\n");
        Files.write(root.resolve("big.bin"), new byte[2 * 1024 * 1024]);
    }

    private Http2Server start(ServerConfig.Builder b, sjws_nettask.core.Handler handler) throws Exception {
        var config = b.h2Enabled(true).h2Port(0).h2cPort(0).build();
        this.server = new Http2Server(config, handler, Http2ServerTest.TLS, Http2Server.Limits.DEFAULT).start();
        return this.server;
    }

    private Http2Server start() throws Exception {
        return start(ServerConfig.builder(root), new StaticFileHandler(root));
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    private Socket h2c() throws IOException {
        var s = new Socket(InetAddress.getLoopbackAddress(), this.server.h2cPort());
        s.setSoTimeout(10_000);
        return s;
    }

    /** Reads one HTTP/1.1 response (head + Content-Length body) and returns it as text. */
    static String response(InputStream in) throws IOException {
        var sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            sb.append((char) b);
        }
        var head = sb.toString();
        var m = java.util.regex.Pattern.compile("(?i)content-length: (\\d+)").matcher(head);
        if (m.find() && !head.startsWith("HTTP/1.1 304")) {
            sb.append(new String(in.readNBytes(Integer.parseInt(m.group(1))), StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    private static void write(Socket s, String text) throws IOException {
        s.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void http11KeepAliveServesSeveralRequests() throws Exception {
        start();
        try (var s = h2c()) {
            write(s, "GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\nHEAD /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n");
            var first = response(s.getInputStream());
            assertTrue(first.startsWith("HTTP/1.1 200 OK"), first);
            assertTrue(first.contains("Connection: keep-alive"), first);
            assertTrue(first.contains("X-Content-Type-Options: nosniff"), first);
            assertTrue(first.endsWith("hello h2\n"), first);
            var sb = new StringBuilder(); // HEAD: head only
            while (!sb.toString().endsWith("\r\n\r\n")) {
                sb.append((char) s.getInputStream().read());
            }
            assertTrue(sb.toString().startsWith("HTTP/1.1 200 OK") && sb.toString().contains("Content-Length: 9"));
            write(s, "GET /big.bin HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            var big = response(s.getInputStream());
            assertTrue(big.contains("Content-Length: 2097152"));
            assertEquals(-1, s.getInputStream().read());
        }
    }

    @Test
    void http10ClosesAfterTheResponse() throws Exception {
        start();
        try (var s = h2c()) {
            write(s, "GET /hello.txt HTTP/1.0\r\n\r\n");
            var r = response(s.getInputStream());
            assertTrue(r.startsWith("HTTP/1.1 200 OK"), r);
            assertTrue(r.contains("Connection: close"), r);
            assertEquals(-1, s.getInputStream().read());
        }
    }

    @Test
    void refusalsCloseTheConnection() throws Exception {
        start();
        for (var request : new String[] {"GET /hello.txt HTTP/1.1\r\n\r\n", // no Host
            "GET /hello.txt HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n", // two
            "BREW /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n", // unknown method
            "GET /a\u0001b HTTP/1.1\r\nHost: x\r\n\r\n"}) { // invalid target
            try (var s = h2c()) {
                write(s, request);
                var r = response(s.getInputStream());
                assertNotNull(r, request);
                assertTrue(r.startsWith("HTTP/1.1 400") || r.startsWith("HTTP/1.1 501"), request + " -> " + r);
                assertTrue(r.contains("Connection: close"), r);
                assertEquals(-1, s.getInputStream().read());
            }
        }
    }

    @Test
    void handlerFailureIs500OnBothProtocols() throws Exception {
        start(ServerConfig.builder(root), request -> {
            throw new IllegalStateException("boom");
        });
        try (var s = h2c()) {
            write(s, "GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(response(s.getInputStream()).startsWith("HTTP/1.1 500"));
        }
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.get(1, "/hello.txt");
            boolean headers = false;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.HEADERS && f.stream() == 1) {
                    headers = true;
                }
                if ((f.flags() & RawH2.END_STREAM) != 0 && f.stream() == 1) {
                    break;
                }
            }
            assertTrue(headers);
        }
    }

    @Test
    void unknownMethodOverH2IsAnswered() throws Exception {
        start();
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS | RawH2.END_STREAM, 1, RawH2.block("BREW", "/hello.txt"));
            int data = 0;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.DATA) {
                    data += f.payload().length;
                }
                if (f.stream() == 1 && (f.flags() & RawH2.END_STREAM) != 0) {
                    break;
                }
            }
            assertEquals(StaticFileHandler.error(501, false).contentLength(), data);
        }
    }

    @Test
    void connectionCapsCloseExtraConnections() throws Exception {
        start(ServerConfig.builder(root).maxConnections(1), new StaticFileHandler(root));
        try (var first = h2c()) {
            write(first, "GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n"); // admitted before the second connects
            assertTrue(response(first.getInputStream()).startsWith("HTTP/1.1 200"));
            try (var second = h2c()) {
                assertEquals(-1, second.getInputStream().read(), "over --max-connections");
            }
        }
        this.server.close();
        start(ServerConfig.builder(root).maxConnectionsPerIp(1), new StaticFileHandler(root));
        try (var first = h2c()) {
            write(first, "GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n"); // admitted before the second connects
            assertTrue(response(first.getInputStream()).startsWith("HTTP/1.1 200"));
            try (var second = h2c()) {
                assertEquals(-1, second.getInputStream().read(), "over --max-connections-per-ip");
            }
        }
    }

    @Test
    void idleConnectionsAreClosed() throws Exception {
        start(ServerConfig.builder(root).idleTimeout(Duration.ofMillis(300)), new StaticFileHandler(root));
        try (var s = h2c()) {
            long t0 = System.nanoTime();
            assertEquals(-1, s.getInputStream().read());
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
        }
    }

    @Test
    void stoppedAcceptingRefusesNewConnections() throws Exception {
        start();
        this.server.stopAccepting();
        try (var s = h2c()) {
            assertEquals(-1, s.getInputStream().read());
        }
    }

    @Test
    void closeIsIdempotentAndSafeBeforeStart() throws Exception {
        var config = ServerConfig.builder(root).h2Enabled(true).h2Port(0).build();
        var s = new Http2Server(config, new StaticFileHandler(root));
        s.close();
        s.close();
        assertEquals("HTTP/2", s.name());
        start();
        assertEquals(-1, new Http2Server(ServerConfig.builder(root).h2Enabled(true).h2Port(0).build(),
                new StaticFileHandler(root), Http2ServerTest.TLS, Http2Server.Limits.DEFAULT).start().h2cPort());
    }

    @Test
    void busyPortFailsStartCleanly() throws Exception {
        start();
        var config = ServerConfig.builder(root).h2Enabled(true).h2Port(this.server.port()).build();
        var other = new Http2Server(config, new StaticFileHandler(root), Http2ServerTest.TLS, Http2Server.Limits.DEFAULT);
        var e = assertThrows(IOException.class, other::start);
        assertTrue(e.getMessage().contains("could not start HTTP/2"), e.getMessage());
    }

    @Test
    void resolvesTlsMaterialFromTheConfiguration() throws Exception {
        var s = new Http2Server(ServerConfig.builder(root).h2Enabled(true).h2Port(0).build(), new StaticFileHandler(root));
        try {
            s.start();
            assertTrue(s.tls().selfSigned());
            assertTrue(s.port() > 0);
        } finally {
            s.close();
        }
    }

    @Test
    void responseHeadersDropConnectionSpecificFields() {
        var r = new Response(200, sjws_nettask.core.ResponseHeaders.of().add("connection", "x").add("vary", "a"),
                sjws_nettask.core.Body.Empty.INSTANCE);
        var h = Http2StreamHandler.headers(r, "d");
        assertNull(h.get("connection"));
        assertEquals("a", h.get("vary").toString());
        assertEquals("200", h.status().toString());
        assertEquals("0", h.get("content-length").toString());
        var notModified = new Response(304, sjws_nettask.core.ResponseHeaders.of(), sjws_nettask.core.Body.Empty.INSTANCE);
        assertNull(Http2StreamHandler.headers(notModified, "d").get("content-length"));
    }

    @Test
    void emptyPathIsRefused() {
        var req = new sjws_nettask.core.Request("GET", "");
        assertEquals(400, Http2StreamHandler.refusal(req).status());
        assertNull(Http2StreamHandler.refusal(new sjws_nettask.core.Request("GET", "/")));
    }
}
