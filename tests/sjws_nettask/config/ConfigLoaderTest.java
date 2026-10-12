package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;

class ConfigLoaderTest {

    private static final String SAMPLE = """
            routing:
              enabled: false
              upstreams:
                api: { url: "http://127.0.0.1:9000", connect_timeout: 5s, response_timeout: 30s, max_body: 16m }
              routes:
                - host: "example.com"
                  prefix: "/api"
                  rewrite: "/"
                  proxy: api
                - prefix: "/old"
                  redirect: { status: 301, location: "https://example.com/new/", keep_path: true }
                - prefix: "/"
                  static: true
              forwarded_proto: auto
            """;

    @TempDir
    Path dir;

    private Path write(String yaml) throws Exception {
        var file = dir.resolve("sjws.yaml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void noFileIsDefaultsAndAcmeFlagsAreNotUnknown() {
        var loaded = ConfigLoader.load(new String[] {"--acme-email=ops@example.com"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertNull(loaded.document().configFile());
        assertEquals(8080, loaded.document().server().port());
        assertEquals("ops@example.com", loaded.document().acme().email());
        assertFalse(loaded.document().acme().enabled());
        assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
    }

    @Test
    void helpWinsBeforeAMissingFile() {
        var loaded = ConfigLoader.load(new String[] {"--help", "--config=/no/such/file"}, Map.of());
        assertTrue(loaded.helpRequested());
        assertTrue(loaded.ok());
    }

    @Test
    void missingFileIsAnError() {
        var loaded = ConfigLoader.load(new String[] {"--config=/no/such/sjws.yaml"}, Map.of());
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().get(0).contains("Cannot read config file"));
    }

    @Test
    void emptyFileIsDefaults() throws Exception {
        var file = write("# nothing\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(ServerConfig.DEFAULT_PORT, loaded.document().server().port());
        assertEquals(file.toAbsolutePath().normalize(), loaded.document().configFile().toAbsolutePath().normalize());
        assertFalse(loaded.document().routing().enabled());
    }

    @Test
    void sampleRoutingParsesAndDoesNotWarnJustForExisting() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write(SAMPLE)}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
        var routing = loaded.document().routing();
        assertEquals(ForwardedProto.AUTO, routing.forwardedProto());
        assertEquals(3, routing.routes().size());
        var proxy = (RouteSpec.Action.Proxy) routing.routes().routes().get(0).action();
        assertEquals("api", proxy.upstream().name());
        assertEquals(Duration.ofSeconds(5), proxy.upstream().connectTimeout());
        assertEquals(Duration.ofSeconds(30), proxy.upstream().responseTimeout());
        assertEquals(16 << 20, proxy.upstream().maxBody());
        assertEquals(Duration.ofSeconds(30), proxy.upstream().idleReadTimeout());
        assertFalse(proxy.upstream().preserveHost());
        var redirect = (RouteSpec.Action.Redirect) routing.routes().routes().get(1).action();
        assertEquals(301, redirect.status());
        assertTrue(redirect.keepPath());
        assertInstanceOf(RouteSpec.Action.Static.class, routing.routes().routes().get(2).action());
    }

    @Test
    void enabledRoutingWarnsAndIsNotRequiredToDispatch() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write(SAMPLE.replace("enabled: false", "enabled: true"))},
                Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("not implemented")));
    }

    @Test
    void fileThenArgumentsThenEnvironment() throws Exception {
        var file = write("""
                server:
                  port: 19080
                  root: ./from-file
                  idle_timeout: 9
                """);
        var loaded = ConfigLoader.load(
                new String[] {"--config=" + file, "--idle-timeout=4", String.valueOf(19081)},
                Map.of("WEB_SERVER_PORT", "19082", "WEB_SERVER_DIR", "/from-env"));
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(19082, loaded.document().server().port());
        assertEquals(Path.of("/from-env"), loaded.document().server().root());
        assertEquals(Duration.ofSeconds(4), loaded.document().server().idleTimeout());
    }

    @Test
    void relativeRootIsResolvedAgainstTheFile() throws Exception {
        var file = write("server:\n  root: site\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(file.getParent().resolve("site").normalize(), loaded.document().server().root());
    }

    @Test
    void environmentConfigPathWins() throws Exception {
        var ignored = write("server:\n  port: 1\n");
        var chosen = dir.resolve("other.yaml");
        Files.writeString(chosen, "server:\n  port: 2\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + ignored}, Map.of("WEB_SERVER_CONFIG", chosen.toString()));
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(2, loaded.document().server().port());
    }

    @Test
    void strictFileErrorsNameThePath() throws Exception {
        assertTrue(error("server:\n  ports: 1\n").contains("server.ports"));
        assertTrue(error("routing:\n  routes:\n    - prefix: api\n      static: true\n").contains("prefix"));
        assertTrue(error("routing:\n  routes:\n    - prefix: /x\n      proxy: missing\n").contains("unknown upstream"));
        assertTrue(error("routing:\n  routes:\n    - prefix: /x\n      proxy: api\n      static: true\n").contains("exactly one"));
        assertTrue(error("""
                routing:
                  upstreams:
                    api: { url: "http://127.0.0.1:9" }
                  routes:
                    - prefix: /old
                      redirect: { status: 300, location: "https://example.com/" }
                """).contains("301"));
        assertTrue(error("""
                routing:
                  routes:
                    - prefix: /old
                      redirect: { status: 301, location: "https://example.com/\\r\\nX" }
                """).contains("control"));
        assertTrue(error("tls:\n  key_password: secret\n").contains("key_password"));
        assertTrue(error("tls:\n  cert: a.pem\n").contains("together"));
        assertTrue(error("server:\n  port: 1\nserver:\n  port: 2\n").toLowerCase().contains("duplicate")
                || error("server:\n  port: 1\nserver:\n  port: 2\n").contains("port"));
        assertTrue(error("nope: true\n").contains("Unknown key"));
        assertTrue(error("- just\n- a\n- list\n").contains("mapping"));
    }

    @Test
    void shadowIsAWarning() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write("""
                routing:
                  routes:
                    - prefix: /
                      static: true
                    - prefix: /api
                      static: true
                """)}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("shadowed")));
    }

    @Test
    void middlewareIsStoredAndWarned() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write("""
                middleware:
                  chain:
                    - name: security-headers
                      params: { enabled: true, count: 2 }
                """)}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals("security-headers", loaded.document().middleware().steps().get(0).name());
        assertEquals("true", loaded.document().middleware().steps().get(0).params().get("enabled"));
        assertEquals("2", loaded.document().middleware().steps().get(0).params().get("count"));
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("not applied")));
    }

    @Test
    void acmeValidationAndMutualExclusion() throws Exception {
        assertTrue(error("""
                acme:
                  enabled: true
                  email: not-an-email
                  domains: ["*.example.com"]
                  accept_terms: true
                """).contains("wildcard") || error("""
                acme:
                  enabled: true
                  email: not-an-email
                  domains: ["*.example.com"]
                  accept_terms: true
                """).contains("email"));
        var enabled = error("""
                tls:
                  cert: cert.pem
                  key: key.pem
                acme:
                  enabled: true
                  email: ops@example.com
                  domains: [example.com]
                  accept_terms: true
                """);
        assertTrue(enabled.contains("cannot both"), enabled);
        var loaded = ConfigLoader.load(new String[] {"--config=" + write("""
                acme:
                  directory: production
                  email: ops@example.com
                  domains: [Example.COM]
                  renew_before: 30d
                """), "--acme", "--acme-accept-terms", "--acme-domain=from.example"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.document().acme().enabled());
        assertEquals(sjws_nettask.config.AcmeSettings.PRODUCTION, loaded.document().acme().directory());
        assertEquals(java.util.List.of("from.example"), loaded.document().acme().domains());
    }

    @Test
    void acmeEnvironmentOverridesTheFlag() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--acme"}, Map.of("WEB_SERVER_ACME", "false"));
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertFalse(loaded.document().acme().enabled());
    }

    @Test
    void badAcmeFlagIsAnError() {
        var loaded = ConfigLoader.load(new String[] {"--acme-http-port=nope"}, Map.of());
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().get(0).contains("acme-http-port"));
    }

    @Test
    void yamlPasswordIsReadOnlyWhenHttp3StaysOn() throws Exception {
        var pass = dir.resolve("secret");
        Files.writeString(pass, "s3cret\n");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ServerConfig.PasswordFileReader reader = path -> {
            calls.incrementAndGet();
            return ServerConfig.readPasswordFile(path);
        };
        var off = ConfigLoader.load(new String[] {"--config=" + write("""
                tls:
                  cert: cert.pem
                  key: key.pem
                  key_password_file: secret
                """)}, Map.of(), reader);
        assertTrue(off.ok(), off.errors().toString());
        assertEquals(0, calls.get());
        assertNull(off.document().server().tlsKeyPassword());
        var on = ConfigLoader.load(new String[] {"--config=" + write("""
                http3:
                  enabled: true
                tls:
                  cert: cert.pem
                  key: key.pem
                  key_password_file: secret
                """), "--h3"}, Map.of(), reader);
        assertTrue(on.ok(), on.errors().toString());
        assertEquals(1, calls.get());
        assertEquals("s3cret", on.document().server().tlsKeyPassword());
    }

    @Test
    void baselineKeepsFileValuesUntilOverridden() {
        var file = ServerConfig.builder(Path.of("/file")).port(7).idleTimeout(Duration.ofSeconds(8)).build();
        var parsed = ServerConfig.parse(new String[] {"--idle-timeout=3"}, Map.of("WEB_SERVER_PORT", "9"), file);
        assertTrue(parsed.ok());
        assertEquals(9, parsed.config().port());
        assertEquals(Path.of("/file"), parsed.config().root());
        assertEquals(Duration.ofSeconds(3), parsed.config().idleTimeout());
    }

    @Test
    void everyNewFlagIsRecognized() {
        var parsed = ServerConfig.parse(new String[] {"--config=sjws.yaml", "--acme", "--no-acme",
                "--acme-directory=staging", "--acme-email=a@b.c", "--acme-domain=example.com",
                "--acme-storage=./acme", "--acme-http-port=80", "--acme-accept-terms", "--acme-renew-before=30d"},
                Map.of());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
    }

    @Test
    void durationsAndSizes() {
        assertEquals(Duration.ofSeconds(5), Durations.parse("5", "t"));
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms", "t"));
        assertEquals(Duration.ofMinutes(1), Durations.parse("1m", "t"));
        assertEquals(16L << 20, Sizes.parse("16m", Integer.MAX_VALUE, "s"));
        assertThrows(ConfigException.class, () -> Durations.parse("0", "t"));
        assertThrows(ConfigException.class, () -> Sizes.parse("nope", 10, "s"));
    }

    @Test
    void trustedProxiesAndForwardedProto() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write("""
                routing:
                  forwarded_proto: https
                  trusted_proxies: ["127.0.0.1/8", "2001:db8::/32"]
                  routes:
                    - prefix: /
                      static: true
                """)}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(ForwardedProto.HTTPS, loaded.document().routing().forwardedProto());
        assertEquals(2, loaded.document().routing().trustedProxies().size());
        assertTrue(error("routing:\n  trusted_proxies: [example.com]\n").contains("not a name"));
    }

    private String error(String yaml) throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write(yaml)}, Map.of());
        assertFalse(loaded.ok(), yaml);
        return loaded.errors().get(0);
    }
}
