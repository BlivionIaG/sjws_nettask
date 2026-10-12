package sjws_nettask.acme;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.shredzone.acme4j.exception.AcmeException;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.tls.TlsMaterial;

/**
 * HTTP-01 issuance against a local ACME server. Pebble is opt-in; this is what keeps the issuer
 * covered when {@code mvn verify} does not download it.
 */
@Timeout(90)
class AcmeIssuerHttpTest {

    private static final String JSON = "application/json";
    private static final String PROBLEM = "application/problem+json";
    private static final String CHAIN = "application/pem-certificate-chain";
    private static final AtomicInteger NONCES = new AtomicInteger();
    private static final ConcurrentHashMap<String, Phase> PHASES = new ConcurrentHashMap<>();

    private static HttpServer server;
    private static Path storage;
    private static String base;
    private static String pem;
    private static SSLContext ssl;

    @BeforeAll
    static void startServer() throws Exception {
        storage = Files.createTempDirectory("acme-issuer");
        var material = TlsMaterial.selfSigned("example.com");
        pem = pem("CERTIFICATE", material.chain()[0].getEncoded());
        ssl = SSLContext.getInstance("TLS");
        ssl.init(null, null, null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", AcmeIssuerHttpTest::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void happyPathIssuesACertificateAndTheNextCallLogsIn() throws Exception {
        var board = new ChallengeBoard();
        var issued = AcmeIssuer.issue(settings("happy"), board, ssl);
        assertEquals(1, issued.chain().length);
        assertNull(board.get("tok-happy"));
        assertOwnerOnly(storage.resolve("account.key"));
        assertOwnerOnly(storage.resolve("domain.key"));
        assertOwnerOnly(storage.resolve("cert.pem"));
        assertOwnerOnly(storage.resolve("account.url"));
        assertOwnerOnly(storage.resolve("directory"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(storage));
        try (var names = Files.list(storage)) {
            assertTrue(names.noneMatch(path -> path.getFileName().toString().startsWith(".acme-")));
        }
        var again = AcmeIssuer.issue(settings("happy"), board, ssl);
        assertNotNull(again.chain()[0]);
        assertNull(board.get("tok-happy"));
    }

    @Test
    void processingOrderIsPolledUntilValid() throws Exception {
        var issued = AcmeIssuer.issue(settings("processing"), new ChallengeBoard(), ssl);
        assertTrue(issued.chain()[0] instanceof X509Certificate);
    }

    @Test
    void alreadyValidOrderSkipsTheChallenge() throws Exception {
        var board = new ChallengeBoard();
        var issued = AcmeIssuer.issue(settings("valid"), board, ssl);
        assertNotNull(issued.chain()[0]);
        assertNull(board.get("tok-valid"));
    }

    @Test
    void invalidAuthorizationFailsAndClearsTheToken() {
        var board = new ChallengeBoard();
        var error = assertThrows(IOException.class, () -> AcmeIssuer.issue(settings("authz-invalid"), board, ssl));
        assertTrue(error.getMessage().contains("HTTP-01 failed"), error.getMessage());
        assertNull(board.get("tok-authz-invalid"));
    }

    @Test
    void orderThatNeverBecomesReadyFails() {
        var error = assertThrows(IOException.class,
                () -> AcmeIssuer.issue(settings("order-bad"), new ChallengeBoard(), ssl));
        assertTrue(error.getMessage().contains("not ready"), error.getMessage());
    }

    @Test
    void orderThatFailsAfterFinalizeFails() {
        var error = assertThrows(IOException.class,
                () -> AcmeIssuer.issue(settings("order-fail"), new ChallengeBoard(), ssl));
        assertTrue(error.getMessage().contains("ACME order failed"), error.getMessage());
    }

    @Test
    void missingHttp01ChallengeFails() {
        var error = assertThrows(IOException.class,
                () -> AcmeIssuer.issue(settings("no-http"), new ChallengeBoard(), ssl));
        assertTrue(error.getMessage().contains("no HTTP-01"), error.getMessage());
    }

    @Test
    void completedOrderWithoutACertificateFails() {
        var error = assertThrows(IllegalStateException.class,
                () -> AcmeIssuer.issue(settings("no-cert"), new ChallengeBoard(), ssl));
        assertTrue(error.getMessage().contains("not completed"), error.getMessage());
    }

    @Test
    void rejectedAccountWrapsTheAcmeError() {
        var error = assertThrows(IOException.class,
                () -> AcmeIssuer.issue(settings("reject"), new ChallengeBoard(), ssl));
        assertInstanceOf(AcmeException.class, error.getCause());
        assertFalse(error.getMessage().contains("\n"));
        assertTrue(error.getMessage().contains("refused"), error.getMessage());
    }

    @Test
    void interruptDuringTheOrderWaitAbortsIssuance() throws Exception {
        var board = new ChallengeBoard();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                AcmeIssuer.issue(settings("stall"), board, ssl);
                failure.set(new AssertionError("stall returned a certificate"));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "acme-stall");
        worker.start();
        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (board.get("tok-stall") == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(board.get("tok-stall"), "challenge was not published");
        Thread.sleep(200);
        worker.interrupt();
        worker.join(Duration.ofSeconds(15).toMillis());
        assertFalse(worker.isAlive());
        assertInstanceOf(IOException.class, failure.get());
        assertEquals("ACME issuance was interrupted", failure.get().getMessage());
        assertNull(board.get("tok-stall"));
    }

    private static AcmeSettings settings(String mode) {
        return new AcmeSettings(true, base + "/" + mode + "/dir", "ops@example.com", List.of("example.com"), storage,
                0, true, Duration.ofDays(30));
    }

    private static void assertOwnerOnly(Path path) throws IOException {
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path), path.getFileName().toString());
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        var parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length < 3) {
            send(exchange, 404, PROBLEM, problem("not found"), null);
            return;
        }
        var mode = parts[1];
        switch (parts[2]) {
            case "dir" -> send(exchange, 200, JSON, directory(mode), null);
            case "new-nonce" -> send(exchange, 204, JSON, "", null);
            case "new-account" -> {
                if ("reject".equals(mode)) {
                    send(exchange, 400, PROBLEM, problem("directory refused the account"), null);
                } else {
                    send(exchange, 201, JSON, "{\"status\":\"valid\"}", url(mode, "account"));
                }
            }
            case "account" -> send(exchange, 200, JSON, "{\"status\":\"valid\"}", url(mode, "account"));
            case "new-order" -> {
                var createdValid = "valid".equals(mode) || "no-cert".equals(mode);
                send(exchange, 201, JSON, order(mode, createdValid ? "valid" : "pending", "valid".equals(mode)),
                        url(mode, "order"));
            }
            case "order" -> send(exchange, 200, JSON, polledOrder(mode), null);
            case "authz" -> send(exchange, 200, JSON, authorization(mode), null);
            case "chall" -> send(exchange, 200, JSON,
                    challenge(mode, "authz-invalid".equals(mode) ? "invalid" : "valid"), null);
            case "finalize" -> {
                phase(mode).finalized = true;
                var failed = "order-fail".equals(mode);
                var processing = "processing".equals(mode);
                var status = failed ? "invalid" : processing ? "processing" : "valid";
                send(exchange, 200, JSON, order(mode, status, "valid".equals(status)), null);
            }
            case "cert" -> send(exchange, 200, CHAIN, pem, null);
            default -> send(exchange, 404, PROBLEM, problem("unknown " + parts[2]), null);
        }
    }

    private static String polledOrder(String mode) {
        if ("order-bad".equals(mode) || "stall".equals(mode)) {
            return order(mode, "stall".equals(mode) ? "pending" : "invalid", false);
        }
        var phase = phase(mode);
        if (!phase.finalized) {
            return order(mode, "ready", false);
        }
        phase.polls++;
        if ("order-fail".equals(mode)) {
            return order(mode, "invalid", false);
        }
        // The first read after finalize is still in progress, so waitForCompletion has a valid result.
        if ("processing".equals(mode) && phase.polls == 1) {
            return order(mode, "processing", false);
        }
        return order(mode, "valid", true);
    }

    private static String directory(String mode) {
        return "{\"newNonce\":\"" + url(mode, "new-nonce") + "\",\"newAccount\":\"" + url(mode, "new-account")
                + "\",\"newOrder\":\"" + url(mode, "new-order") + "\"}";
    }

    private static String order(String mode, String status, boolean certificate) {
        var json = new StringBuilder();
        json.append("{\"status\":\"").append(status).append('"');
        json.append(",\"identifiers\":[{\"type\":\"dns\",\"value\":\"example.com\"}]");
        json.append(",\"authorizations\":[\"").append(url(mode, "authz")).append("\"]");
        json.append(",\"finalize\":\"").append(url(mode, "finalize")).append('"');
        if (certificate) {
            json.append(",\"certificate\":\"").append(url(mode, "cert")).append('"');
        }
        json.append('}');
        return json.toString();
    }

    private static String authorization(String mode) {
        var status = switch (mode) {
            case "valid", "no-cert", "order-bad" -> "valid";
            default -> "pending";
        };
        var type = "no-http".equals(mode) ? "dns-01" : "http-01";
        return "{\"status\":\"" + status + "\",\"identifier\":{\"type\":\"dns\",\"value\":\"example.com\"},"
                + "\"challenges\":[" + challenge(mode, "pending").replace("\"type\":\"http-01\"", "\"type\":\"" + type + "\"")
                + "]}";
    }

    private static String challenge(String mode, String status) {
        return "{\"type\":\"http-01\",\"status\":\"" + status + "\",\"url\":\"" + url(mode, "chall")
                + "\",\"token\":\"tok-" + mode + "\"}";
    }

    private static String problem(String detail) {
        return "{\"type\":\"urn:ietf:params:acme:error:rejectedIdentifier\",\"detail\":\"" + detail + "\"}";
    }

    private static String url(String mode, String resource) {
        return base + "/" + mode + "/" + resource;
    }

    private static Phase phase(String mode) {
        return PHASES.computeIfAbsent(mode, key -> new Phase());
    }

    private static void send(HttpExchange exchange, int status, String type, String body, String location)
            throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Replay-Nonce", nonce());
        headers.set("Cache-Control", "no-store");
        if (location != null) {
            headers.set("Location", location);
        }
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        headers.set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String nonce() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("nonce-" + NONCES.incrementAndGet()).getBytes(StandardCharsets.US_ASCII));
    }

    private static String pem(String type, byte[] der) {
        var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static final class Phase {
        private volatile boolean finalized;
        private int polls;
    }
}
