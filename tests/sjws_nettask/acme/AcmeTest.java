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
import org.junit.jupiter.api.AfterEach;
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

    /** {@code renewalDelayAndARefusedDirectoryBacksOff} leaves the renew thread writing this directory. */
    @AfterEach
    void awaitAcmeWritersBeforeTempDirCleanup() throws InterruptedException {
        AcmeCleanup.awaitWriters();
    }

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
        assertEquals(404, handler.handle(new Request("GET", "/.well-known/acme-challenge/bad.token")).status());
        assertEquals(404, handler.handle(new Request("GET", "/.well-known/acme-challenge/a b")).status());
        var dotted = handler.handle(new Request("HEAD", "/.well-known/acme-challenge/../secret"));
        assertEquals(404, dotted.status());
        assertTrue(dotted.headOnly());
        var longToken = "a".repeat(128);
        board.put(longToken, "long-auth");
        assertEquals(200, handler.handle(new Request("GET", "/.well-known/acme-challenge/" + longToken)).status());
        assertEquals(404, handler.handle(new Request("GET",
                "/.well-known/acme-challenge/" + longToken + "b")).status());
        board.put("token_1-ok", "ok-auth");
        var shaped = handler.handle(new Request("GET", "/.well-known/acme-challenge/token_1-ok?x=1"));
        assertEquals(200, shaped.status());
        assertEquals("ok-auth", new String(bytes(shaped)));
        assertEquals("file", new String(bytes(handler.handle(new Request("GET", "/index.html")))));
        board.remove("absent");
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
        assertEquals(dir, store.directory());
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
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(dir.resolve("account.url")));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(dir.resolve("directory")));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(dir));
        assertNull(store.accountUrl(AcmeSettings.PRODUCTION));
        assertNull(store.loadCertificate());
        var material = TlsMaterial.selfSigned("example.com");
        Files.writeString(dir.resolve("cert.pem"), pem("CERTIFICATE", material.chain()[0].getEncoded()));
        Files.writeString(dir.resolve("domain.key"), pem("PRIVATE KEY", material.key().getEncoded()));
        var loaded = store.loadCertificate();
        assertEquals(material.chain()[0], loaded.chain()[0]);
    }

    @Test
    void pkcs8RejectsATruncatedBodyGarbageAndANonRsaKey() throws Exception {
        var truncated = dir.resolve("truncated");
        Files.createDirectories(truncated);
        Files.writeString(truncated.resolve("account.key"),
                "-----BEGIN PRIVATE KEY-----\nMIIB\n");
        var truncatedError = assertThrows(java.io.IOException.class, () -> new AcmeStore(truncated).accountKey());
        assertTrue(truncatedError.getMessage().contains("invalid PKCS#8"));

        var garbage = dir.resolve("garbage");
        Files.createDirectories(garbage);
        Files.writeString(garbage.resolve("account.key"),
                "-----BEGIN PRIVATE KEY-----\n!!!!\n-----END PRIVATE KEY-----\n");
        assertThrows(java.io.IOException.class, () -> new AcmeStore(garbage).accountKey());

        var ecDir = dir.resolve("ec");
        Files.createDirectories(ecDir);
        var generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        Files.writeString(ecDir.resolve("account.key"), pem("PRIVATE KEY", generator.generateKeyPair().getPrivate().getEncoded()));
        var ecError = assertThrows(java.io.IOException.class, () -> new AcmeStore(ecDir).accountKey());
        assertTrue(ecError.getMessage().contains("invalid PKCS#8"), ecError.getMessage());

        var generated = java.security.KeyPairGenerator.getInstance("RSA");
        generated.initialize(2048);
        var crt = (java.security.interfaces.RSAPrivateCrtKey) generated.generateKeyPair().getPrivate();
        var nonCrt = java.security.KeyFactory.getInstance("RSA").generatePrivate(
                new java.security.spec.RSAPrivateKeySpec(crt.getModulus(), crt.getPrivateExponent()));
        var nonCrtDir = dir.resolve("noncrt");
        Files.createDirectories(nonCrtDir);
        Files.writeString(nonCrtDir.resolve("account.key"), pem("PRIVATE KEY", nonCrt.getEncoded()));
        var nonCrtError = assertThrows(java.io.IOException.class, () -> new AcmeStore(nonCrtDir).accountKey());
        assertTrue(nonCrtError.getMessage().contains("not an RSA key"), nonCrtError.getMessage());
    }

    @Test
    void secretsAreWrittenOnAFilesystemWithoutPosixPermissions() throws Exception {
        var zip = dir.resolve("store.zip");
        var uri = java.net.URI.create("jar:" + zip.toUri());
        try (var fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))) {
            assertFalse(fs.supportedFileAttributeViews().contains("posix"));
            var root = fs.getPath("acme");
            var store = new AcmeStore(root);
            var key = store.accountKey();
            assertNotNull(key.getPublic());
            assertEquals(key.getPublic(), store.accountKey().getPublic());
            var url = URI.create("https://example.com/acme/acct/9").toURL();
            store.saveAccount(url, "http://ca.example/dir");
            assertEquals(url, store.accountUrl("http://ca.example/dir"));
            assertNull(store.accountUrl("http://other.example/dir"));
            assertTrue(Files.readString(root.resolve("account.key")).contains("-----BEGIN PRIVATE KEY-----"));
        }
    }

    @Test
    void accountUrlAndCertificateNeedBothFiles() throws Exception {
        var markerOnly = dir.resolve("marker-only");
        Files.createDirectories(markerOnly);
        Files.writeString(markerOnly.resolve("directory"), AcmeSettings.STAGING + "\n");
        assertNull(new AcmeStore(markerOnly).accountUrl(AcmeSettings.STAGING));
        var urlOnly = dir.resolve("url-only");
        Files.createDirectories(urlOnly);
        Files.writeString(urlOnly.resolve("account.url"), "https://example.com/acme/acct/1\n");
        assertNull(new AcmeStore(urlOnly).accountUrl(AcmeSettings.STAGING));
        var certOnly = dir.resolve("cert-only");
        Files.createDirectories(certOnly);
        Files.writeString(certOnly.resolve("cert.pem"), "not a cert");
        assertNull(new AcmeStore(certOnly).loadCertificate());
        var keyOnly = dir.resolve("key-only");
        Files.createDirectories(keyOnly);
        Files.writeString(keyOnly.resolve("domain.key"), "not a key");
        assertNull(new AcmeStore(keyOnly).loadCertificate());
    }

    @Test
    void challengeListenerCoversPartialHeadsAndResponsesWithoutBytes() throws Exception {
        var handler = new AcmeChallengeHandler(new ChallengeBoard(), request -> {
            if ("HEAD".equals(request.method())) {
                return new Response(200, sjws_nettask.core.ResponseHeaders.of().add("x-test", "1"),
                        sjws_nettask.core.Body.Bytes.of("secret".getBytes()), true);
            }
            return new Response(204, sjws_nettask.core.ResponseHeaders.of(), sjws_nettask.core.Body.Empty.INSTANCE);
        });
        assertTrue(new String(exchange(handler)).startsWith("HTTP/1.1 400 "));
        var split = exchange(handler,
                "GET /index HTTP/1.1\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "Host: example.com\r\nX-Other: 1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(new String(split).startsWith("HTTP/1.1 204 "), new String(split));
        var bare = exchange(handler, "GET\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(new String(bare).startsWith("HTTP/1.1 204 "));
        var odd = exchange(handler, "GET noslash HTTP/1.1\r\n:weird\r\n\r\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(new String(odd).startsWith("HTTP/1.1 204 "));
        var head = exchange(handler, "HEAD / HTTP/1.1\r\nHost: h\r\n\r\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var text = new String(head);
        assertTrue(text.startsWith("HTTP/1.1 200 "), text);
        assertFalse(text.contains("secret"));
    }

    private static byte[] exchange(sjws_nettask.core.Handler handler, byte[]... writes) throws Exception {
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var response = new java.util.concurrent.CompletableFuture<byte[]>();
            var worker = Thread.ofVirtual().start(() -> {
                try (var client = new java.net.Socket()) {
                    client.connect(server.getLocalSocketAddress());
                    var input = client.getInputStream();
                    var out = client.getOutputStream();
                    for (var write : writes) {
                        out.write(write);
                        out.flush();
                    }
                    client.shutdownOutput();
                    response.complete(input.readAllBytes());
                } catch (Exception e) {
                    response.completeExceptionally(e);
                }
            });
            try (var accepted = server.accept()) {
                ChallengeHttpServer.serve(accepted, handler);
            }
            worker.join(5_000);
            return response.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
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
        assertEquals("IllegalStateException", AcmeIssuer.safe(new IllegalStateException((String) null)));
        assertEquals("IllegalStateException", AcmeIssuer.safe(new IllegalStateException("   ")));
        assertEquals("x".repeat(300), AcmeIssuer.safe(new IllegalStateException("x".repeat(400))));
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
