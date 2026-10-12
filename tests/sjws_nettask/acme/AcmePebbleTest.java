package sjws_nettask.acme;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.tls.TlsMaterial;

/**
 * Issues a real HTTP-01 certificate against Pebble. The linux/amd64 release binaries are
 * downloaded into {@code target/pebble-v2.10.1} unless {@code PEBBLE_BIN} and
 * {@code PEBBLE_CHALLTESTSRV_BIN} point at them. Other platforms skip.
 */
class AcmePebbleTest {

    private static final String VERSION = "v2.10.1";

    @TempDir
    Path dir;

    @Test
    @Timeout(180)
    void issuesACertificateAgainstPebble() throws Exception {
        assumeTrue(linuxAmd64(), "Pebble release binaries are published for linux/amd64");
        var pebbleBin = binary("pebble", "pebble-linux-amd64");
        var challBin = binary("pebble-challtestsrv", "pebble-challtestsrv-linux-amd64");
        var ports = freePorts(4);
        int dnsPort = ports[0];
        int dnsMgmt = ports[1];
        int listen = ports[2];
        int admin = ports[3];
        var tls = TlsMaterial.selfSigned("localhost");
        var cert = dir.resolve("pebble.crt");
        var key = dir.resolve("pebble.key");
        Files.writeString(cert, pem("CERTIFICATE", tls.chain()[0].getEncoded()));
        Files.writeString(key, pem("PRIVATE KEY", tls.key().getEncoded()));
        var storage = dir.resolve("acme");
        var dnsLog = dir.resolve("chall.log");
        var caLog = dir.resolve("pebble.log");
        var board = new ChallengeBoard();
        Process dns = null;
        Process ca = null;
        try (var http = ChallengeHttpServer.open(0, board)) {
            Files.writeString(dir.resolve("pebble.json"), """
                    {
                      "pebble": {
                        "listenAddress": "127.0.0.1:%d",
                        "managementListenAddress": "127.0.0.1:%d",
                        "certificate": "%s",
                        "privateKey": "%s",
                        "httpPort": %d,
                        "tlsPort": 0,
                        "ocspResponderURL": "",
                        "externalAccountBindingRequired": false,
                        "retryAfter": { "authz": 0, "order": 0 }
                      }
                    }
                    """.formatted(listen, admin, cert, key, http.port()));
            dns = process(dnsLog, challBin.toString(),
                    "-defaultIPv4", "127.0.0.1",
                    "-defaultIPv6", "",
                    "-http01", "",
                    "-https01", "",
                    "-tlsalpn01", "",
                    "-doh", "",
                    "-dnsserver", "127.0.0.1:" + dnsPort,
                    "-management", "127.0.0.1:" + dnsMgmt);
            awaitPort(dnsPort, dns, dnsLog);
            ca = process(caLog, pebbleBin.toString(),
                    "-config", dir.resolve("pebble.json").toString(),
                    "-dnsserver", "127.0.0.1:" + dnsPort);
            awaitPort(listen, ca, caLog);
            var directory = "https://127.0.0.1:" + listen + "/dir";
            var ssl = trustAll();
            var probe = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI.create(directory)).timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(200, probe.statusCode(), probe.body() + tail(caLog));
            assertTrue(probe.body().contains("newAccount"), probe.body());
            var settings = new AcmeSettings(true, directory, "ops@example.com", List.of("example.com"), storage,
                    http.port(), true, Duration.ofDays(30));
            var issued = AcmeIssuer.issue(settings, board, ssl);
            assertDnsSan(issued.chain()[0], "example.com");
            assertTrue(issued.chain()[0].getNotAfter().toInstant().isAfter(Instant.now()));
            var stored = new AcmeStore(storage).loadCertificate();
            assertNotNull(stored);
            assertEquals(issued.chain()[0], stored.chain()[0]);
            assertEquals(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(storage.resolve("account.key")));
            assertTrue(Files.readString(storage.resolve("account.key")).contains("-----BEGIN PRIVATE KEY-----"));
            var again = AcmeIssuer.issue(settings, board, ssl);
            assertDnsSan(again.chain()[0], "example.com");
        } catch (Exception e) {
            throw new AssertionError(e.getMessage() + tail(dnsLog) + tail(caLog), e);
        } finally {
            stop(ca);
            stop(dns);
        }
    }

    private Process process(Path log, String... command) throws IOException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (command[0].endsWith("pebble") && !command[0].endsWith("pebble-challtestsrv")) {
            builder.environment().put("PEBBLE_VA_NOSLEEP", "1");
        }
        return builder.start();
    }

    private static void assertDnsSan(X509Certificate certificate, String name) throws Exception {
        var sans = certificate.getSubjectAlternativeNames();
        assertNotNull(sans);
        var dns = new ArrayList<String>();
        for (var san : sans) {
            if (((Number) san.get(0)).intValue() == 2) {
                dns.add(String.valueOf(san.get(1)));
            }
        }
        assertTrue(dns.contains(name), dns.toString());
    }

    private static void awaitPort(int port, Process process, Path log) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("process exited " + process.exitValue() + tail(log));
            }
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                Thread.sleep(50);
            }
        }
        fail("timed out waiting for 127.0.0.1:" + port + tail(log));
    }

    private static String tail(Path log) throws IOException {
        if (!Files.isRegularFile(log)) {
            return "";
        }
        var text = Files.readString(log);
        if (text.length() > 4000) {
            text = text.substring(text.length() - 4000);
        }
        return "\n--- " + log.getFileName() + " ---\n" + text;
    }

    private static void stop(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static int[] freePorts(int count) throws IOException {
        var held = new ServerSocket[count];
        var ports = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                held[i] = new ServerSocket(0);
                ports[i] = held[i].getLocalPort();
            }
            return ports;
        } finally {
            for (var socket : held) {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    private static boolean linuxAmd64() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "");
        return os.contains("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    private static Path binary(String name, String artifact) throws Exception {
        var override = System.getenv(name.equals("pebble") ? "PEBBLE_BIN" : "PEBBLE_CHALLTESTSRV_BIN");
        if (override != null && !override.isBlank()) {
            var path = Path.of(override);
            assertTrue(Files.isExecutable(path), path + " is not executable");
            return path;
        }
        var root = Path.of("target", "pebble-" + VERSION);
        var binary = root.resolve(name);
        if (Files.isRegularFile(binary)) {
            binary.toFile().setExecutable(true);
            return binary;
        }
        Files.createDirectories(root);
        var archive = root.resolve(artifact + ".tar.gz");
        var url = "https://github.com/letsencrypt/pebble/releases/download/" + VERSION + "/" + artifact + ".tar.gz";
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofFile(archive));
        assertEquals(200, response.statusCode(), "download " + url);
        var extract = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", root.toString()).start();
        assertEquals(0, extract.waitFor());
        var nested = root.resolve(artifact).resolve("linux").resolve("amd64").resolve(name);
        Files.move(nested, binary, StandardCopyOption.REPLACE_EXISTING);
        binary.toFile().setExecutable(true);
        return binary;
    }

    private static String pem(String type, byte[] der) {
        var body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static SSLContext trustAll() throws Exception {
        var context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String auth) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String auth) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return context;
    }
}
