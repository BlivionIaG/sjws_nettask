package sjws_nettask.acme;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.tls.TlsMaterial;

class AcmeTest {

    @TempDir
    Path dir;

    @Test
    void challengeHandlerAnswersOnlyKnownTokens() {
        var board = new ChallengeBoard();
        board.put("token", "token.thumb");
        var handler = new AcmeChallengeHandler(board, request -> new Response(200,
                sjws_nettask.core.ResponseHeaders.of().add("content-type", "text/plain"),
                sjws_nettask.core.Body.Bytes.of("file".getBytes()), request.headOnly()));
        assertNull(new sjws_nettask.core.PathResolver(dir).logical("/.well-known/acme-challenge/token"),
                "a dot component is hidden before any access-rule list runs");
        var hit = handler.handle(new Request("GET", "/.well-known/acme-challenge/token?unused=1"));
        assertEquals(200, hit.status());
        assertEquals("token.thumb", new String(bytes(hit)));
        assertEquals("application/octet-stream", hit.headers().get("content-type"));
        var head = handler.handle(new Request("HEAD", "/.well-known/acme-challenge/token"));
        assertEquals(200, head.status());
        assertTrue(head.headOnly());
        assertEquals("token.thumb".length(), head.contentLength());
        assertEquals(404, handler.handle(new Request("GET", "/.well-known/acme-challenge/missing")).status());
        assertEquals(404, handler.handle(new Request("GET", "/.well-known/acme-challenge/token/extra")).status());
        assertEquals(404, handler.handle(new Request("GET", "/.well-known/acme-challenge/")).status());
        assertEquals("file", new String(bytes(handler.handle(new Request("GET", "/index.html")))));
        board.remove("token");
        assertEquals(404, handler.handle(new Request("GET", "/.well-known/acme-challenge/token")).status());
        board.put("a", "b");
        board.clear();
        assertNull(board.get("a"));
    }

    @Test
    void challengeListenerServesTheToken() throws Exception {
        var board = new ChallengeBoard();
        board.put("abc", "abc.auth");
        try (var server = ChallengeHttpServer.open(0, board)) {
            var client = HttpClient.newHttpClient();
            var good = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/.well-known/acme-challenge/abc")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, good.statusCode());
            assertEquals("abc.auth", good.body());
            var missing = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                    + "/other")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
        }
    }

    @Test
    void storeRoundTripAndDirectoryChange() throws Exception {
        var store = new AcmeStore(dir);
        var first = store.accountKey();
        var again = store.accountKey();
        assertEquals(first.getPublic(), again.getPublic());
        var perms = Files.getPosixFilePermissions(dir.resolve("account.key"));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        assertTrue(Files.readString(dir.resolve("account.key")).contains("-----BEGIN PRIVATE KEY-----"));
        var legacyDir = dir.resolve("legacy");
        Files.createDirectories(legacyDir);
        var writer = new java.io.StringWriter();
        org.shredzone.acme4j.util.KeyPairUtils.writeKeyPair(first, writer);
        Files.writeString(legacyDir.resolve("account.key"), writer.toString());
        assertEquals(first.getPublic(), new AcmeStore(legacyDir).accountKey().getPublic());
        assertNull(store.accountUrl(AcmeSettings.STAGING));
        var url = URI.create("https://example.com/acme/acct/1").toURL();
        store.saveAccount(url, AcmeSettings.STAGING);
        assertEquals(url, store.accountUrl(AcmeSettings.STAGING));
        assertNull(store.accountUrl(AcmeSettings.PRODUCTION));
        assertNull(store.loadCertificate());
        var material = TlsMaterial.selfSigned("example.com");
        Files.writeString(dir.resolve("cert.pem"), pem("CERTIFICATE", material.chain()[0].getEncoded()));
        Files.writeString(dir.resolve("domain.key"), pem("PRIVATE KEY", material.key().getEncoded()));
        var loaded = store.loadCertificate();
        assertEquals(material.chain()[0], loaded.chain()[0]);
    }

    private static String pem(String type, byte[] der) {
        var body = java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    @Test
    @Timeout(20)
    void renewalDelayAndARefusedDirectoryBacksOff() throws Exception {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var delay = AcmeService.renewalDelay(now.plus(Duration.ofDays(90)), Duration.ofDays(30), now);
        assertEquals(Duration.ofDays(60), delay);
        assertTrue(AcmeService.renewalDelay(now.plus(Duration.ofDays(10)), Duration.ofDays(30), now).isNegative());
        var settings = new AcmeSettings(true, "http://127.0.0.1:1/directory", "ops@example.com",
                java.util.List.of("example.com"), dir.resolve("store"), 0, true, Duration.ofDays(30));
        var board = new ChallengeBoard();
        board.put("leftover", "x");
        var published = new java.util.concurrent.atomic.AtomicInteger();
        var service = new AcmeService(settings, board, material -> published.incrementAndGet());
        service.start();
        try {
            Thread.sleep(1500);
        } finally {
            service.close();
        }
        assertEquals(0, published.get());
        assertNull(board.get("leftover"));
        var disabled = new AcmeService(settings, new ChallengeBoard(), material -> {
        });
        disabled.update(AcmeSettings.disabled());
        disabled.close();
    }

    @Test
    void sessionOpensAndTrustProviderRejectsUnknownSchemes() {
        var session = AcmeSessions.open(AcmeSettings.STAGING);
        assertEquals(AcmeSettings.STAGING, session.getServerUri().toString());
        var trust = new AcmeSessions.TrustProvider(insecure());
        assertTrue(trust.accepts(URI.create("https://127.0.0.1:14000/dir")));
        assertFalse(trust.accepts(URI.create("acme://pebble")));
        assertThrows(IllegalArgumentException.class, () -> trust.resolve(URI.create("acme://pebble")));
        assertNotNull(trust.createHttpClient(new org.shredzone.acme4j.connector.NetworkSettings()));
        assertNotNull(AcmeIssuer.safe(new IllegalStateException("line\nsecret")));
    }

    private static javax.net.ssl.SSLContext insecure() {
        try {
            var context = javax.net.ssl.SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[] {new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                }

                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(Response response) {
        var data = ((sjws_nettask.core.Body.Bytes) response.body()).data().duplicate();
        var out = new byte[data.remaining()];
        data.get(out);
        return out;
    }
}
