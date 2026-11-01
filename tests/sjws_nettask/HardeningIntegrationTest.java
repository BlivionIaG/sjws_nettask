package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.HttpTestSupport.Response;

/**
 * Network-level tests for the review fixes: strict parsing ends in "status + close",
 * deadlines, connection cap, pipelining, Expect, absolute-form, process exit codes.
 */
class HardeningIntegrationTest {

    private static final int TIMEOUT_MS = 4000;

    @TempDir
    Path root;

    private ServerSocketChannel serverSocket;
    private ClientManager manager;
    private Thread acceptThread;
    private int port;

    private void start(ServerConfig config) throws IOException {
        serverSocket = ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
        port = serverSocket.socket().getLocalPort();
        manager = new ClientManager(serverSocket, config);
        acceptThread = new Thread(manager, "accept-loop");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void start() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello");
        start(ServerConfig.defaults(root.toString()));
    }

    @AfterEach
    void stop() throws Exception {
        if (serverSocket != null) {
            serverSocket.close();
            acceptThread.join(2000);
        }
    }

    private Socket connect() throws IOException {
        var s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(TIMEOUT_MS);
        return s;
    }

    /** Sends raw bytes, expects exactly one response with the given status, then EOF. */
    private Response expectStatusThenClose(String raw, int status) throws IOException {
        try (var s = connect()) {
            s.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            Response r = HttpTestSupport.readResponse(s.getInputStream());
            assertEquals(status, r.statusCode(), r.rawHead());
            assertEquals("close", r.headers().get("connection"));
            assertEquals(-1, s.getInputStream().read(), "connection must be closed after the error");
            return r;
        }
    }

    /**
     * Verification V1 for HTTP/1.1 (#327): one address gets at most maxConnectionsPerIp connections.
     * Over the cap the accept loop sends a canned cleartext 503 + Retry-After: 1, then closes.
     * The response is generic (no limit, count or client address). Under the cap is unchanged.
     * The refused socket is read without writing a request so the assertion does not race the
     * non-blocking drain/close (Codex P2). Concurrent request delivery is covered separately.
     */
    @Test
    void connectionsPerAddressAreCapped() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        start(ServerConfig.builder(root).maxConnectionsPerIp(2).build());
        try (var a = connect(); var b = connect()) {
            waitUntil(() -> manager.activeConnections() == 2);
            try (var c = connect()) {
                // No client request: avoids racing refuseWith503's one-shot drain + close.
                Response r = HttpTestSupport.readResponse(c.getInputStream());
                assertEquals(503, r.statusCode(), r.rawHead());
                assertEquals("1", r.headers().get("retry-after"), r.rawHead());
                assertEquals("close", r.headers().get("connection"), r.rawHead());
                assertEquals("0", r.headers().get("content-length"), r.rawHead());
                String raw = r.rawHead().toLowerCase(java.util.Locale.ROOT);
                assertFalse(raw.contains("max-connections") || raw.contains("per-ip") || raw.contains("per_ip"),
                        "must not name the limit: " + r.rawHead());
                assertFalse(raw.contains("256") || raw.contains("refused") || raw.contains("limit"),
                        "must not name a count or limit token: " + r.rawHead());
                assertFalse(raw.contains("127.0.0.1") || raw.contains("address"),
                        "must not name the client: " + r.rawHead());
                assertEquals(-1, c.getInputStream().read(), "EOF after the 503");
            }
            waitUntil(() -> manager.refusedPerIp() == 1);
            a.close();
            waitUntil(() -> manager.activeConnections() == 1);
            try (var d = connect()) {
                d.getOutputStream().write("GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                assertEquals(200, HttpTestSupport.readResponse(d.getInputStream()).statusCode(), "slot freed");
            }
        }
        assertEquals(ServerConfig.DEFAULT_MAX_CONNECTIONS_PER_IP, ServerConfig.defaults(root.toString()).maxConnectionsPerIp());
    }

    /**
     * #327 best-effort path: a request that races the refuse drain may see the canned 503 or a
     * TCP reset. Either outcome is accepted; the counter still increments.
     */
    @Test
    void perIpRefuseWithRacedRequestIsBestEffort() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        start(ServerConfig.builder(root).maxConnectionsPerIp(1).build());
        try (var held = connect()) {
            waitUntil(() -> manager.activeConnections() == 1);
            try (var c = connect()) {
                c.getOutputStream().write("GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                c.getOutputStream().flush();
                try {
                    Response r = HttpTestSupport.readResponse(c.getInputStream());
                    // Empty rawHead (peer closed/reset with no bytes) is a permitted best-effort
                    // outcome; statusCode() would NPE on a null statusLine (Codex P2).
                    // Do not return early: refusedPerIp must be asserted on every path (Security N2).
                    if (r.rawHead() != null && !r.rawHead().isEmpty()) {
                        if (r.statusCode() == 503) {
                            assertEquals("1", r.headers().get("retry-after"), r.rawHead());
                            // EOF or reset after the 503 are both fine on this path.
                            try {
                                c.getInputStream().read();
                            } catch (IOException ignored) {
                                // reset by peer after the 503
                            }
                        } else {
                            fail("only 503 or an empty/reset read, got: " + r.rawHead());
                        }
                    }
                } catch (IOException e) {
                    assertTrue(e.getMessage() == null
                                    || e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("reset")
                                    || e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("closed"),
                            "unexpected I/O on raced refuse: " + e);
                }
            }
            waitUntil(() -> manager.refusedPerIp() >= 1);
        }
    }

    /** #327: the per-IP refuse drain buffer is ThreadLocal, never shared across accept/virtual threads. */
    @Test
    void perIpRefuseDrainBufferIsThreadConfined() throws Exception {
        var fromA = new java.util.concurrent.atomic.AtomicReference<java.nio.ByteBuffer>();
        var fromB = new java.util.concurrent.atomic.AtomicReference<java.nio.ByteBuffer>();
        var t1 = new Thread(() -> fromA.set(ClientManager.perIpRefuseDrainBufferForTest()), "drain-a");
        var t2 = new Thread(() -> fromB.set(ClientManager.perIpRefuseDrainBufferForTest()), "drain-b");
        t1.start();
        t2.start();
        t1.join(2000);
        t2.join(2000);
        assertNotNull(fromA.get());
        assertNotNull(fromB.get());
        assertNotSame(fromA.get(), fromB.get(), "each thread must see its own drain buffer");
        assertSame(ClientManager.perIpRefuseDrainBufferForTest(), ClientManager.perIpRefuseDrainBufferForTest(),
                "same thread reuses the same buffer");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "condition not reached");
            Thread.sleep(10);
        }
    }

    @ParameterizedTest(name = "[{index}] smuggling vector -> 400 + close")
    @ValueSource(strings = {
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\nContent-Length: 41\r\n\r\nGET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n",
        "GET / HTTP/1.1\r\nHost: h\r\nContent-Length : 41\r\n\r\nGET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n",
        "GET / HTTP/1.1\r\nHost: h\r\nContent-Length: +5\r\n\r\nxxxxx",
        "GET / HTTP/1.1\r\nHost: h\r\nX: a\rContent-Length: 5\r\n\r\nxxxxx",
        "GET / HTTP/1.1\r\nHost: h\r\nX: a\r\n Content-Length: 5\r\n\r\nxxxxx",
        "GET / HTTP/1.1\r\nHost: h\r\nContent-Length: 5, 6\r\n\r\nxxxxxx"
    })
    void smugglingVectorsAre400AndClose(String raw) throws IOException {
        start();
        expectStatusThenClose(raw, 400);
    }

    @Test
    void transferEncodingWithContentLengthIs400AndClose() throws IOException {
        start();
        expectStatusThenClose("POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n"
                + "0\r\n\r\nGET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n", 400);
    }

    @ParameterizedTest
    @CsvSource({
        "'GET / HTTP/2.0\r\nHost: h\r\n\r\n', 505",
        "'BREW / HTTP/1.1\r\nHost: h\r\n\r\n', 501",
        "'GET / HTTP/1.1\r\n\r\n', 400",
        "'GET / HTTP/1.1\r\nHost: h\r\nExpect: teapot\r\n\r\n', 417"
    })
    void protocolErrors(String raw, int status) throws IOException {
        start();
        expectStatusThenClose(raw.replace("\\r\\n", "\r\n"), status);
    }

    @Test
    void limits414And431() throws IOException {
        start();
        expectStatusThenClose("GET /" + "a".repeat(10_000) + " HTTP/1.1\r\nHost: h\r\n\r\n", 414);
        expectStatusThenClose("GET / HTTP/1.1\r\nHost: h\r\nX: " + "a".repeat(10_000) + "\r\n\r\n", 431);
        var many = new StringBuilder("GET / HTTP/1.1\r\nHost: h\r\n");
        for (int i = 0; i < 150; i++) {
            many.append("X-").append(i).append(": 1\r\n");
        }
        expectStatusThenClose(many.append("\r\n").toString(), 431);
    }

    @Test
    void pipelinedRequestsAreAnsweredInOrder() throws IOException {
        start();
        Files.writeString(root.resolve("b.txt"), "bee");
        try (var s = connect()) {
            HttpTestSupport.write(s.getOutputStream(),
                    "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n"
                    + "HEAD /b.txt HTTP/1.1\r\nHost: h\r\n\r\n"
                    + "GET /b.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            InputStream in = s.getInputStream();
            assertEquals("hello", HttpTestSupport.readResponse(in).bodyText());
            Response head = HttpTestSupport.readResponse(in, true);
            assertEquals(200, head.statusCode());
            assertEquals("3", head.headers().get("content-length"));
            assertEquals("bee", HttpTestSupport.readResponse(in).bodyText());
        }
    }

    @Test
    void trickledRequestIsCutOffAtDeadline() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        start(ServerConfig.defaults(root.toString()).withTimeouts(java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(800)));
        try (var s = connect()) {
            var out = s.getOutputStream();
            long t0 = System.nanoTime();
            var req = "GET /hello.txt HTTP/1.1\r\nHost: h\r\nX-Slow: " + "a".repeat(100);
            Response r = null;
            try {
                for (char c : req.toCharArray()) { // one byte every 50 ms: never idle long enough for SO_TIMEOUT
                    out.write(c);
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                // server closed while we were still trickling: expected
            }
            r = HttpTestSupport.readResponse(s.getInputStream());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (r.statusLine() != null) {
                assertEquals(408, r.statusCode());
            }
            assertEquals(-1, s.getInputStream().read());
            assertTrue(ms < 4000, "took " + ms + " ms");
        }
    }

    @Test
    void idleKeepAliveIsNotCutByHeadDeadline() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        start(ServerConfig.defaults(root.toString()).withTimeouts(java.time.Duration.ofSeconds(5), java.time.Duration.ofMillis(300)));
        try (var s = connect()) {
            Thread.sleep(800); // idle longer than the head deadline before sending anything
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals("hello", HttpTestSupport.readResponse(s.getInputStream()).bodyText());
        }
    }

    @Test
    void absoluteFormTarget() throws IOException {
        start();
        Response r = HttpTestSupport.rawRequest(port, "GET http://x/hello.txt HTTP/1.1\r\nHost: x\r\n\r\n", TIMEOUT_MS);
        assertEquals(200, r.statusCode());
        assertEquals("hello", r.bodyText());
    }

    @Test
    void headOn404() throws IOException {
        start();
        Response r = HttpTestSupport.rawRequest(port, "HEAD /missing HTTP/1.1\r\nHost: h\r\n\r\n", TIMEOUT_MS);
        assertEquals(404, r.statusCode());
        assertEquals(0, r.body().length);
    }

    @Test
    void ifModifiedSinceGives304() throws IOException {
        start();
        Response first = HttpTestSupport.get(port, "/hello.txt", TIMEOUT_MS);
        String lm = first.headers().get("last-modified");
        assertNotNull(lm);
        Response r = HttpTestSupport.rawRequest(port,
                "GET /hello.txt HTTP/1.1\r\nHost: h\r\nIf-Modified-Since: " + lm + "\r\n\r\n", TIMEOUT_MS);
        assertEquals(304, r.statusCode());
    }

    @Test
    void directoryWithoutSlashRedirectsOverTheWire() throws IOException {
        start();
        Files.createDirectory(root.resolve("sub"));
        Response r = HttpTestSupport.get(port, "/sub?a=b", TIMEOUT_MS);
        assertEquals(301, r.statusCode());
        assertEquals("/sub/?a=b", r.headers().get("location"));
    }

    @Test
    void unreadableDirectoryIs403OverTheWire() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        start();
        var locked = Files.createDirectory(root.resolve("locked"));
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        try {
            assertEquals(403, HttpTestSupport.get(port, "/locked/", TIMEOUT_MS).statusCode());
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void errorWithHugeBodyIsAnsweredWithoutDraining() throws IOException {
        start();
        long t0 = System.nanoTime();
        // Claims 1 GB but sends nothing: the 405 must come immediately, then close.
        expectStatusThenClose("PUT /x HTTP/1.1\r\nHost: h\r\nContent-Length: 1000000000\r\n\r\n", 405);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000);
    }

    @Test
    void expect100ContinueOnUnsupportedMethodGetsFinalStatus() throws IOException {
        start();
        expectStatusThenClose("POST /x HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 5\r\n\r\n", 405);
    }

    @Test
    void expect100ContinueOnGetWithBody() throws IOException {
        start();
        try (var s = connect()) {
            HttpTestSupport.write(s.getOutputStream(),
                    "GET /hello.txt HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 4\r\n\r\n");
            Response interim = HttpTestSupport.readResponse(s.getInputStream());
            assertEquals(100, interim.statusCode());
            HttpTestSupport.write(s.getOutputStream(), "body");
            Response r = HttpTestSupport.readResponse(s.getInputStream());
            assertEquals(200, r.statusCode());
            assertEquals("hello", r.bodyText());
            // body was consumed, connection is still usable
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals(200, HttpTestSupport.readResponse(s.getInputStream()).statusCode());
        }
    }

    @Test
    void connectionCapQueuesExtraClients() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        start(ServerConfig.defaults(root.toString()).withMaxConnections(2));
        try (var a = connect(); var b = connect(); var c = connect()) {
            HttpTestSupport.write(a.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            HttpTestSupport.write(b.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            assertEquals(200, HttpTestSupport.readResponse(a.getInputStream()).statusCode());
            assertEquals(200, HttpTestSupport.readResponse(b.getInputStream()).statusCode());
            assertEquals(2, manager.activeConnections());

            HttpTestSupport.write(c.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\n\r\n");
            c.setSoTimeout(700);
            assertThrows(SocketTimeoutException.class, () -> c.getInputStream().read(),
                    "third client must wait while two connections are open");
            a.close(); // frees a slot
            c.setSoTimeout(TIMEOUT_MS);
            assertEquals("hello", HttpTestSupport.readResponse(c.getInputStream()).bodyText());
        }
    }

    // --- process level ----------------------------------------------------------

    private StringBuilder lastLog = new StringBuilder();

    /** Reads the child's output until a line starts with {@code prefix}; the lines go to {@link #lastLog}. */
    private void waitForLine(Process p, String prefix) throws IOException {
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        this.lastLog = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            this.lastLog.append(line).append('\n');
            if (line.startsWith(prefix)) {
                return;
            }
        }
        throw new AssertionError("child exited before '" + prefix + "':\n" + this.lastLog);
    }

    private Process launch(String... args) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var cmd = new java.util.ArrayList<>(java.util.List.of(javaBin, "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.Sjws_nettask"));
        cmd.addAll(java.util.List.of(args));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("WEB_SERVER_DIR");
        pb.environment().remove("WEB_SERVER_PORT");
        return pb.start();
    }

    @Test
    void portInUseExitsNonZeroWithClearMessage() throws Exception {
        try (var taken = new ServerSocket(0)) {
            Process p = launch(String.valueOf(taken.getLocalPort()), root.toString());
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "should exit");
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, p.exitValue(), out);
            assertTrue(out.contains("Cannot listen on port " + taken.getLocalPort()), out);
        }
    }

    @Test
    void missingDocRootExitsNonZeroWithClearMessage() throws Exception {
        Process p = launch("18099", root.resolve("nope").toString());
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "should exit");
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(2, p.exitValue(), out);
        assertTrue(out.contains("does not exist"), out);
    }

    @Test
    void helpPrintsUsageAndExitsZero() {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var code = new AtomicInteger(-1);
        Sjws_nettask.main(new String[] {"--help"}, Map.of(), out, out, code::set);
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, code.get(), text);
        assertTrue(text.contains("Usage:") && text.contains("--h3-port"), text);
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void portZeroBindsEphemeralAndPrintsBoundPort() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        Process p = null;
        try {
            p = launch("0", root.toString());
            waitForLine(p, "Server started: HTTP/1.1 on tcp/");
            var line = this.lastLog.toString().lines()
                    .filter(l -> l.startsWith("Server started: HTTP/1.1 on tcp/"))
                    .findFirst().orElseThrow();
            int bound = Integer.parseInt(line.substring("Server started: HTTP/1.1 on tcp/".length()).split("[^0-9]", 2)[0]);
            assertTrue(bound > 0 && bound <= 65535, line);
            assertEquals("hello", HttpTestSupport.get(bound, "/hello.txt", TIMEOUT_MS).bodyText());
        } finally {
            if (p != null) {
                p.destroy();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void configErrorsExitWithUsageCode() throws Exception {
        Process p = launch("18097", root.toString(), "--h3", "--cert=/only/cert.pem");
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(Sjws_nettask.EXIT_USAGE, p.exitValue(), out);
        assertTrue(out.contains("ERROR: --cert and --key must be given together"), out);
    }

    @Test
    @org.junit.jupiter.api.Timeout(30) // waitForLine blocks on the child's output
    void h3BusyUdpPortFallsBackToHttp1() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        // HTTP/3 defaults to the TCP port number on UDP 0.0.0.0: hold that UDP port so the bind fails.
        Process p = null;
        try {
            try (var busyUdp = new java.net.DatagramSocket(port)) {
                p = launch(String.valueOf(port), root.toString(), "--h3");
                waitForLine(p, "Server started: HTTP/1.1 on tcp/");
            }
            var log = this.lastLog;
            assertTrue(log.toString().contains("WARN: HTTP/3 not started"), log.toString());
            assertTrue(log.toString().contains("udp/0.0.0.0:" + port), log.toString());
            assertTrue(log.toString().contains("HTTP/3 unavailable"), log.toString());
            Response r = HttpTestSupport.get(port, "/hello.txt", TIMEOUT_MS);
            assertEquals("hello", r.bodyText());
            assertNull(r.headers().get("alt-svc"));
        } finally {
            if (p != null) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
