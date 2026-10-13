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
package sjws_nettask.admin.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sjws_nettask.admin.cp.CpClient.Section;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cp.AdminServer;
import sjws_nettask.cp.CpStore;
import sjws_nettask.cp.KeyRing;

/** The console's live client against a real in-process control plane over mTLS. */
class HttpCpClientTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    static Path dir;
    static Path root;
    static Pem server;
    static Pem admin;
    static Pem viewer;
    static Pem stranger;
    static SSLContext adminTls;

    /** A keytool-made identity, exported as PEM files. */
    record Pem(Path cert, Path key, X509Certificate certificate, PrivateKey privateKey) {
    }

    static Pem identity(String name, String san) throws Exception {
        var store = dir.resolve(name + ".p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "a", "-keyalg", "EC", "-dname", "CN=" + name,
                "-ext", "san=" + san, "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        var ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        var key = (PrivateKey) ks.getKey("a", "changeit".toCharArray());
        var cert = (X509Certificate) ks.getCertificate("a");
        var enc = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        var certFile = Files.writeString(dir.resolve(name + ".pem"),
                "-----BEGIN CERTIFICATE-----\n" + enc.encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n");
        var keyFile = Files.writeString(dir.resolve(name + "-key.pem"),
                "-----BEGIN PRIVATE KEY-----\n" + enc.encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        return new Pem(certFile, keyFile, cert, key);
    }

    @BeforeAll
    static void identities() throws Exception {
        dir = Files.createTempDirectory("cp-console");
        root = Files.createDirectory(dir.resolve("html"));
        server = identity("server", "ip:127.0.0.1,dns:localhost");
        admin = identity("admin", "uri:urn:sjws:admin:root");
        viewer = identity("viewer", "uri:urn:sjws:viewer:nobody");
        stranger = identity("stranger", "ip:127.0.0.1");
        adminTls = HttpCpClient.tls(admin.cert(), admin.key(), null, server.cert());
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** Server side: its own key, trusting the operator certificates (pinned, self-signed). */
    static SSLContext serverTls() throws Exception {
        var ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("s", server.privateKey(), "x".toCharArray(), new X509Certificate[] {server.certificate()});
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "x".toCharArray());
        var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("admin", admin.certificate());
        trust.setCertificateEntry("viewer", viewer.certificate());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    /** A running control plane with one activated version, and its admin listener. */
    static final class Cp implements AutoCloseable {
        final CpStore store;
        final AdminServer http;

        Cp(Path stateDir, boolean elect) throws Exception {
            var clock = new FakeClock(0);
            this.store = CpStore.open(stateDir, KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2),
                    clock);
            if (elect) {
                clock.advance(Duration.ofMillis(100));
                this.store.drive();
                assertTrue(this.store.leadership().leaderReady());
                assertEquals(200, this.store.propose("server:\n  port: 0\n  root: " + root + "\n").status());
                assertEquals(200, this.store.activate(NOW, TTL).status());
            }
            this.http = AdminServer.startTls(this.store, NOW, TTL, serverTls());
        }

        String endpoint() {
            return "127.0.0.1:" + this.http.port();
        }

        @Override
        public void close() {
            this.http.close();
            this.store.close();
        }
    }

    static int deadPort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void readsClusterAndActivatedConfig() throws Exception {
        try (var cp = new Cp(dir.resolve("read"), true)) {
            var c = new HttpCpClient(cp.endpoint(), adminTls);
            assertEquals(cp.endpoint(), c.endpoint());
            var cluster = c.cluster();
            assertNotEquals("-", cluster.leader());
            assertTrue(cluster.term() >= 1);
            assertEquals(1, cluster.headVersion());
            assertEquals(1, cluster.nodes().size());
            var n = cluster.nodes().get(0);
            assertEquals("cp-1", n.id());
            assertEquals("LEADER", n.role());
            assertEquals("?", n.health());
            assertEquals(-1, n.lag());
            var bundle = c.config(1);
            assertEquals(1, bundle.version());
            assertEquals(List.of(), bundle.entries(Section.ACCESS));
            assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.config(7)).kind());
            assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.audit(0, 10)).kind());
            assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> c.rolloutStatus("x")).kind());
        }
    }

    @Test
    void editAccessCanaryActivateRollbackThroughFailover() throws Exception {
        try (var cp = new Cp(dir.resolve("flow"), true)) {
            var dead = "127.0.0.1:" + deadPort();
            var f = new FailoverCpClient(List.of(dead, cp.endpoint()), ep -> new HttpCpClient(ep, adminTls));
            var con = new CpConsole(f, false);
            con.reset();
            assertEquals(List.of(dead + " unavailable -> " + cp.endpoint()), f.events());
            con.add(Section.ACCESS, "deny:/secret");
            var v = con.validate();
            assertTrue(v.ok(), v.errors().toString());
            assertTrue(v.warnings().get(v.warnings().size() - 1).startsWith("checked locally"));
            var canary = con.apply("track=canary");
            assertEquals(Rollout_RUNNING, canary.state());
            assertEquals(2, canary.version());
            assertEquals(List.of("track=canary"), cp.store.selectors());
            assertEquals(canary, f.rolloutStatus(canary.id()));
            assertEquals(1, f.head(), "staged, not activated yet");
            var done = con.promote("*");
            assertEquals(CpClient.Rollout.State.DONE, done.state());
            assertEquals(2, done.version());
            assertEquals(2, f.head());
            assertEquals(List.of("deny:/secret"), f.config(2).entries(Section.ACCESS));
            assertTrue(cp.store.activated().canonicalYaml().contains("deny:/secret"));
            assertEquals(3, con.rollback(1));
            assertEquals(List.of(), f.config(3).entries(Section.ACCESS));
            assertEquals(CpException.Kind.NOT_FOUND, assertThrows(CpException.class, () -> f.rollback(99, null)).kind());
        }
    }

    static final CpClient.Rollout.State Rollout_RUNNING = CpClient.Rollout.State.RUNNING;

    @Test
    void conflictsReadOnlySectionsAndServerRejections() throws Exception {
        try (var cp = new Cp(dir.resolve("reject"), true)) {
            var c = new HttpCpClient(cp.endpoint(), adminTls);
            var head = c.config(1);
            assertEquals(CpException.Kind.CONFLICT,
                    assertThrows(CpException.class, () -> c.stage(head, 0, null)).kind());
            var readOnly = head.with(Section.TOPICS, List.of("orders"));
            var e = assertThrows(CpException.class, () -> c.stage(readOnly, 1, null));
            assertEquals(CpException.Kind.INVALID, e.kind());
            assertTrue(e.getMessage().contains("topics: read-only"), e.getMessage());
            var v = c.validate(readOnly.with(Section.ACCESS, List.of("bogus")));
            assertEquals(2, v.errors().size(), v.errors().toString());
            // stage does not pre-validate: the CP's strict loader rejects the rule (400)
            var serverSide = assertThrows(CpException.class,
                    () -> c.stage(head.with(Section.ACCESS, List.of("bogus")), 1, null));
            assertEquals(CpException.Kind.INVALID, serverSide.kind());
            // quoting keeps an injected line inside the rule string: no "rbac:" key reaches the document
            var injected = c.validate(head.with(Section.ACCESS, List.of("allow:/x\nrbac: y")));
            assertTrue(injected.ok(), injected.errors().toString());
            assertEquals(2, c.stage(head.with(Section.ACCESS, List.of("deny:/a")), 1, null));
            var second = assertThrows(CpException.class,
                    () -> c.stage(head.with(Section.ACCESS, List.of("deny:/b")), 1, null));
            assertEquals(CpException.Kind.CONFLICT, second.kind(), "a proposal is already staged");
            assertEquals(CpException.Kind.INVALID, assertThrows(CpException.class, () -> c.rollout(2, "zone", null)).kind());
        }
    }

    @Test
    void authenticationAndAvailability() throws Exception {
        try (var cp = new Cp(dir.resolve("auth"), true); var notReady = new Cp(dir.resolve("unelected"), false)) {
            var viewerTls = HttpCpClient.tls(viewer.cert(), viewer.key(), null, server.cert());
            var asViewer = new HttpCpClient(cp.endpoint(), viewerTls);
            assertEquals(CpException.Kind.FORBIDDEN, assertThrows(CpException.class, asViewer::head).kind());
            // a certificate the CP does not trust: the handshake fails
            var strangerTls = HttpCpClient.tls(stranger.cert(), stranger.key(), null, server.cert());
            assertEquals(CpException.Kind.UNAVAILABLE,
                    assertThrows(CpException.class, () -> new HttpCpClient(cp.endpoint(), strangerTls).head()).kind());
            // a CP certificate the console does not trust
            var wrongTrust = HttpCpClient.tls(admin.cert(), admin.key(), null, admin.cert());
            assertEquals(CpException.Kind.UNAVAILABLE,
                    assertThrows(CpException.class, () -> new HttpCpClient(cp.endpoint(), wrongTrust).head()).kind());
            // leader not ready: 503 is retryable
            var waiting = new HttpCpClient(notReady.endpoint(), adminTls);
            assertEquals(0, waiting.head());
            assertEquals(CpException.Kind.UNAVAILABLE, assertThrows(CpException.class, () -> waiting.rollback(1, null)).kind());
            assertEquals("-", waiting.cluster().leader());
        }
    }

    @Test
    void nonJsonReplyIsUnavailable() throws Exception {
        var https = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(serverTls()));
        https.createContext("/", ex -> {
            var body = "oops".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(502, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        https.start();
        try {
            var c = new HttpCpClient("127.0.0.1:" + https.getAddress().getPort(), adminTls);
            var e = assertThrows(CpException.class, c::head);
            assertEquals(CpException.Kind.UNAVAILABLE, e.kind());
            assertTrue(e.getMessage().contains("non-JSON reply (HTTP 502)"), e.getMessage());
        } finally {
            https.stop(0);
        }
    }

    @Test
    void interruptedCallIsUnavailable() throws Exception {
        var c = new HttpCpClient("127.0.0.1:" + deadPort(), adminTls);
        Thread.currentThread().interrupt();
        try {
            assertEquals(CpException.Kind.UNAVAILABLE, assertThrows(CpException.class, c::head).kind());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void tlsMaterialErrors() throws Exception {
        var empty = Files.writeString(dir.resolve("empty.pem"), "");
        assertThrows(java.security.GeneralSecurityException.class,
                () -> HttpCpClient.tls(admin.cert(), admin.key(), null, empty));
        assertThrows(java.io.IOException.class,
                () -> HttpCpClient.tls(admin.cert(), dir.resolve("missing.pem"), null, server.cert()));
    }

    // ---------- live main ----------

    private static String main(String... args) throws Exception {
        var bytes = new ByteArrayOutputStream();
        int code = CpConsoleMain.run(args, new ByteArrayInputStream("n\nq\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return code + "|" + bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void liveMainConnectsWithPemFiles() throws Exception {
        try (var cp = new Cp(dir.resolve("main"), true)) {
            var out = main("--endpoints=" + cp.endpoint(), "--cert=" + admin.cert(), "--key=" + admin.key(),
                    "--trust=" + server.cert(), "--no-color");
            assertTrue(out.startsWith("0|sjws control plane  via " + cp.endpoint()), out);
            assertTrue(out.contains("cp-1     LEADER    ?      ?     ?"), out);
            var pw = Files.writeString(dir.resolve("pw"), "unused\n");
            var withPassword = main("--endpoints=" + cp.endpoint(), "--cert=" + admin.cert(), "--key=" + admin.key(),
                    "--trust=" + server.cert(), "--key-password-file=" + pw, "--no-color");
            assertTrue(withPassword.startsWith("0|") || withPassword.startsWith("64|ERROR: cannot load"), withPassword);
        }
        assertTrue(main("--endpoints=a:1", "--cert=x", "--key=y", "--trust=" + server.cert())
                .startsWith("64|ERROR: cannot load the TLS material"));
        assertTrue(main("--endpoints=a:1", "--cert=" + admin.cert(), "--key=" + admin.key(), "--trust=t",
                "--key-password-file=" + dir.resolve("nope")).startsWith("64|ERROR: cannot load"));
        assertTrue(main("--endpoints=a:1").startsWith("64|ERROR: live mode needs"));
        assertTrue(main("--endpoints=a:1", "--cert=c").startsWith("64|ERROR: live mode needs"));
        assertTrue(main("--endpoints=a:1", "--cert=c", "--key=k").startsWith("64|ERROR: live mode needs"));
        assertTrue(main("--cert=c", "--key=k", "--trust=t").startsWith("64|ERROR: live mode needs"));
        for (var bad : List.of("--cert=", "--key=", "--trust=", "--key-password-file=")) {
            assertTrue(main(bad).startsWith("64|ERROR: unknown argument"), bad);
        }
        var fake = FakeCpClient.demo(java.time.Clock.systemUTC());
        var bytes = new ByteArrayOutputStream();
        assertEquals(0, CpConsoleMain.run(new String[] {"--endpoints=e1"}, new ByteArrayInputStream(new byte[0]),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), fake::endpoint));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("via e1"));
    }

    // ---------- pieces ----------

    @Test
    void miniJson() {
        var m = MiniJson.object(" {\"a\":[1,-2,true,false,null,{}],\"s\":\"x\\\"\\\\\\/\\n\\r\\t\\b\\f\\u0041\",\"e\":[]} ");
        assertEquals(List.of(1L, -2L, true, false, new java.util.HashMap<>() {{ }}).size(), 5);
        var list = (List<?>) m.get("a");
        assertEquals(1L, list.get(0));
        assertEquals(-2L, list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
        assertNull(list.get(4));
        assertEquals(Map.of(), list.get(5));
        assertEquals("x\"\\/\n\r\t\b\fA", m.get("s"));
        assertEquals(List.of(), m.get("e"));
        for (var bad : List.of("", "{", "{\"a\"}", "{\"a\":1,}", "{a:1}", "[1", "\"x", "\"\\u12\"", "\"\\uzzzz\"",
                "1 2", "-", "99999999999999999999", "x", "\"\\")) {
            assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> MiniJson.object("[]"));
        assertNull(MiniJson.parse(" null"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse(null));
    }

    @Test
    void canonicalYamlSections() {
        var yaml = """
                schema: 1
                version: 3
                tls:
                  cert: "a.pem"
                acme:
                  enabled: false
                routing:
                  enabled: true
                middleware:
                  - name: "observe"
                    order: 0
                  - name: "headers"
                    order: 1
                access:
                  deny: [".git"]
                  rules:
                    - "allow:/a"
                    - "deny:/q\\"uote\\\\"
                  allow_dotfiles: false
                messaging:
                  enabled: true
                """;
        var b = CanonicalYaml.sections(3, yaml);
        assertEquals(List.of("enabled: true"), b.entries(Section.ROUTES));
        assertEquals(List.of("observe", "headers"), b.entries(Section.FILTERS));
        assertEquals(List.of("allow:/a", "deny:/q\"uote\\"), b.entries(Section.ACCESS));
        assertEquals(List.of("cert: \"a.pem\"", "enabled: false"), b.entries(Section.CERTS));
        assertEquals(List.of("enabled: true"), b.entries(Section.TOPICS));
        var replaced = CanonicalYaml.withRules(yaml, List.of("deny:/z"));
        assertEquals(List.of("deny:/z"), CanonicalYaml.rules(replaced));
        assertTrue(replaced.contains("  deny: [\".git\"]\n"));
        assertTrue(replaced.contains("  allow_dotfiles: false\n"));
        assertTrue(replaced.contains("messaging:\n  enabled: true\n"));
        assertEquals(List.of(), CanonicalYaml.rules(CanonicalYaml.withRules(yaml, List.of())));
        var noAccess = "schema: 1\nserver:\n  port: 0\n";
        var added = CanonicalYaml.withRules(noAccess, List.of("allow:/b"));
        assertEquals("schema: 1\nserver:\n  port: 0\naccess:\n  rules:\n    - \"allow:/b\"\n", added);
        assertEquals(noAccess, CanonicalYaml.withRules(noAccess, List.of()));
        assertEquals("access:\n  rules:\n    - \"x\"", CanonicalYaml.withRules("", List.of("x")).strip());
        assertEquals("plain", CanonicalYaml.unquote(" plain "));
        assertEquals("\"", CanonicalYaml.unquote("\""));
        assertEquals("a\nb\rc\\", CanonicalYaml.unquote(CanonicalYaml.quote("a\nb\rc\\")));
        assertEquals("x\\", CanonicalYaml.unquote("\"x\\\""), "dangling backslash kept");
        assertEquals(List.of("v: 1"), CanonicalYaml.block("k: inline\n  v: 1\n", "k"));
        assertEquals("\"\\u0001\\\"\"", HttpCpClient.jsonString("\u0001\""));
        assertEquals(7, HttpCpClient.MISSING.size());
    }

    @Test
    void consoleShowsUnknownHealthPlainly() {
        var c = new CpClient() {
            @Override public ClusterView cluster() {
                return new ClusterView("cp-1", 2, 1, List.of(new NodeView("cp-1", "LEADER", Map.of(), "?", -1, -1)));
            }
            @Override public long head() { return 1; }
            @Override public ConfigBundle config(long v) { return new ConfigBundle(1, Map.of()); }
            @Override public Validation validate(ConfigBundle b) { return new Validation(List.of(), List.of()); }
            @Override public long stage(ConfigBundle b, long base, String k) { return 2; }
            @Override public Rollout rollout(long g, String s, String k) { return null; }
            @Override public Rollout rolloutStatus(String id) { return null; }
            @Override public long rollback(long g, String k) { return 2; }
            @Override public List<AuditEntry> audit(long a, int l) { return new ArrayList<>(); }
        };
        var con = new CpConsole(c, true);
        con.handle("n");
        var screen = con.render();
        assertTrue(screen.contains("LEADER    ?      ?     ?"), screen);
    }
}
