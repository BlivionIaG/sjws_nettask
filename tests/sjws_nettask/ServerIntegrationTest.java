package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.HttpTestSupport.Response;

/**
 * Starts the real accept loop (ClientManager + Client threads) on an ephemeral
 * loopback port and talks HTTP to it. Document root is the repo's ./HTML
 * (hard-coded default; surefire runs with the project basedir as cwd).
 */
class ServerIntegrationTest {

    private static final int TIMEOUT_MS = 4000;

    private ServerSocket serverSocket;
    private ClientManager manager;
    private Thread acceptThread;
    private int port;

    @BeforeEach
    void start() throws IOException {
        assertTrue(Files.exists(Path.of("HTML", "index.html")), "tests must run from the repo root");
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        port = serverSocket.getLocalPort();
        manager = new ClientManager(serverSocket);
        acceptThread = new Thread(manager, "accept-loop");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @AfterEach
    void stop() throws Exception {
        serverSocket.close(); // makes accept() throw -> ClientManager.close()
        acceptThread.join(2000);
    }

    @Test
    void getRootServesIndexHtml() throws IOException {
        Response r = HttpTestSupport.get(port, "/", TIMEOUT_MS);
        assertEquals(200, r.statusCode());
        assertTrue(r.bodyText().contains("It works"), r.bodyText());
        assertEquals("text/html", r.headers().get("content-type"));
        assertEquals(String.valueOf(Files.size(Path.of("HTML", "index.html"))), r.headers().get("content-length"));
    }

    @Test
    void getExplicitFile() throws IOException {
        Response r = HttpTestSupport.get(port, "/index.html", TIMEOUT_MS);
        assertEquals(200, r.statusCode());
        assertTrue(r.bodyText().contains("Home Page Test"));
    }

    @Test
    void keepAliveServesTwoRequestsOnOneConnection() throws IOException {
        try (var s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(TIMEOUT_MS);
            for (int i = 0; i < 2; i++) {
                HttpTestSupport.write(s.getOutputStream(), "GET /index.html HTTP/1.1\r\nHost: localhost\r\n\r\n");
                Response r = HttpTestSupport.readResponse(s.getInputStream());
                assertEquals(200, r.statusCode(), "request #" + (i + 1));
                assertTrue(r.bodyText().contains("It works"));
            }
        }
    }

    @Test
    void handlesConcurrentClients() throws Exception {
        int n = 32;
        var pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<Integer> c = () -> HttpTestSupport.get(port, "/index.html", 10_000).statusCode();
                results.add(pool.submit(c));
            }
            for (var f : results) {
                assertEquals(200, f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Tag("bug")
    @Test
    void BUG_requestLatencyIsNotDominatedBySleep() throws IOException {
        // Client.run() sleeps 100 ms after EVERY line it reads. The request line is
        // answered after one sleep, but each header line then costs another 100 ms,
        // so the NEXT request on a keep-alive connection waits ~(headers+2)*100 ms.
        String req = "GET /index.html HTTP/1.1\r\nHost: localhost\r\n"
                + "Accept: */*\r\nAccept-Language: en\r\nAccept-Encoding: identity\r\n"
                + "User-Agent: t\r\nCache-Control: no-cache\r\nPragma: no-cache\r\n\r\n";
        try (var s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(TIMEOUT_MS);
            long t0 = System.nanoTime();
            for (int i = 0; i < 3; i++) {
                HttpTestSupport.write(s.getOutputStream(), req);
                assertEquals(200, HttpTestSupport.readResponse(s.getInputStream()).statusCode());
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 300, "3 tiny keep-alive requests took " + ms + " ms");
        }
    }

    @Tag("bug")
    @Test
    void BUG_standardHttpClientCanFetchRoot() throws Exception {
        // java.net.http rejects the "HTTP/null 200 OK" status line.
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).build();
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .timeout(Duration.ofSeconds(4)).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("It works"));
    }

    @Tag("bug")
    @Test
    void BUG_missingFileReturns404() throws IOException {
        Response r = getOrNull("/definitely-missing.html");
        assertNotNull(r, "no response at all for a missing file (client hangs until timeout)");
        assertEquals(404, r.statusCode());
    }

    @Tag("bug")
    @Test
    void BUG_pathTraversalOutsideDocRootIsRejected() throws IOException {
        // ./HTML/../README.md resolves to the repo README, outside the doc root.
        Response r = getOrNull("/../README.md");
        if (r != null && r.statusLine() != null) {
            assertFalse(r.bodyText().contains("Simple Java Web Server"),
                    "README.md from outside the document root was served");
            assertNotEquals(200, r.statusCode());
        } else {
            fail("expected a 400/403/404 response, got none");
        }
    }

    @Tag("bug")
    @Test
    void BUG_malformedRequestLineGets400AndConnectionSurvivesOrCloses() throws IOException {
        // "GET" alone -> message.split(" ")[2] throws ArrayIndexOutOfBoundsException
        // inside Client.run(); only IOException is caught, so the thread dies and the
        // socket is never closed (client waits forever).
        try (var s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(2500);
            HttpTestSupport.write(s.getOutputStream(), "GET\r\n\r\n");
            try {
                Response r = HttpTestSupport.readResponse(s.getInputStream());
                if (r.statusLine() != null) {
                    assertEquals(400, r.statusCode());
                } // else: connection closed cleanly -> acceptable
            } catch (SocketTimeoutException e) {
                fail("server neither answered nor closed the connection after a malformed request line");
            }
        }
    }

    @Tag("bug")
    @ParameterizedTest(name = "BUG: {0} request gets some response")
    @ValueSource(strings = {"HEAD", "POST", "PUT", "DELETE"})
    void BUG_nonGetMethodsGetAResponse(String method) throws IOException {
        Response r;
        try {
            r = HttpTestSupport.rawRequest(port,
                    method + " /index.html HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n", 2000);
        } catch (SocketTimeoutException e) {
            r = null;
        }
        assertNotNull(r == null ? null : r.statusLine(),
                method + " got no response (expected 200 for HEAD, 405/501 otherwise)");
    }

    @Tag("bug")
    @Test
    void BUG_finishedClientsAreRemovedFromRegistry() throws Exception {
        for (int i = 0; i < 10; i++) {
            HttpTestSupport.get(port, "/index.html", TIMEOUT_MS); // client closes after reading
        }
        Thread.sleep(500); // let Client threads observe EOF and run close()
        var field = ClientManager.class.getDeclaredField("threads");
        field.setAccessible(true);
        List<?> threads = (List<?>) field.get(manager);
        // close() only sets the slot to null, so the list grows by one per connection forever.
        assertTrue(threads.size() < 10, "registry holds " + threads.size() + " slots after 10 finished connections");
    }

    private Response getOrNull(String path) throws IOException {
        try {
            return HttpTestSupport.get(port, path, 2500);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    // --- Process-level: exercises main() configuration handling -------------

    @TempDir
    Path tmp;

    @Tag("bug")
    @Test
    void BUG_mainHonoursWebServerDirEnvVar() throws Exception {
        Files.writeString(tmp.resolve("only-here.txt"), "from WEB_SERVER_DIR");
        int freePort;
        try (var probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.Sjws_nettask", String.valueOf(freePort));
        pb.environment().put("WEB_SERVER_DIR", tmp.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try {
            var out = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = out.readLine()) != null && !line.startsWith("Server started")) {
                // wait for startup
            }
            assertNotNull(line, "server did not start");
            Response r;
            try {
                r = HttpTestSupport.get(freePort, "/only-here.txt", 2500);
            } catch (SocketTimeoutException e) {
                r = null;
            }
            // main() computes contentPath but never passes it anywhere.
            assertNotNull(r == null ? null : r.statusLine(), "WEB_SERVER_DIR ignored: file not served");
            assertEquals("from WEB_SERVER_DIR", r.bodyText());
        } finally {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
