package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
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
        var loaded = ConfigLoader.load(new String[] {"--config=/no/such/sjws.yaml", "--state-dir=" + dir}, Map.of());
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
    void enabledRoutingLoadsWithoutADispatchWarning() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write(SAMPLE.replace("enabled: false", "enabled: true"))},
                Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.document().routing().enabled());
        assertTrue(loaded.warnings().stream().noneMatch(w -> w.contains("not implemented")), loaded.warnings().toString());
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
    void middlewareListKeepsFileOrderAndRepeatsNames() throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write("""
                middleware:
                  - name: reverse-proxy
                    order: 100
                    route: /api/*
                    options:
                      upstream: http://127.0.0.1:9000
                      preserve-host: true
                      limit: 5
                  - name: access-log
                    options: { format: combined }
                  - name: access-log
                    route: "*"
                    order: 0
                """)}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var specs = loaded.document().middleware();
        assertEquals(3, specs.size());
        assertEquals("reverse-proxy", specs.get(0).name());
        assertEquals(100, specs.get(0).order());
        assertEquals("/api/*", specs.get(0).route());
        assertEquals("http://127.0.0.1:9000", specs.get(0).options().get("upstream"));
        assertEquals("true", specs.get(0).options().get("preserve-host"));
        assertEquals("5", specs.get(0).options().get("limit"));
        assertEquals("access-log", specs.get(1).name());
        assertEquals(0, specs.get(1).order());
        assertNull(specs.get(1).route());
        assertEquals("combined", specs.get(1).options().get("format"));
        assertEquals("access-log", specs.get(2).name());
        assertNull(specs.get(2).route());
        assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains("not applied")));
        var empty = ConfigLoader.load(new String[] {"--config=" + write("middleware: []\n")}, Map.of());
        assertTrue(empty.ok(), empty.errors().toString());
        assertTrue(empty.document().middleware().isEmpty());
        assertTrue(empty.warnings().stream().noneMatch(w -> w.contains("middleware")));
        assertTrue(error("middleware:\n  chain: []\n").contains("list"));
        assertTrue(error("middleware:\n  - name: x\n    route: api/*\n").contains("route"));
        assertTrue(error("middleware:\n  - name: x\n    route: 1\n").contains("string"));
        assertTrue(error("middleware:\n  - name: x\n    route: \"/a*b\"\n").contains("route"));
        assertTrue(error("middleware:\n  - order: 1\n").contains("name"));
        assertTrue(error("middleware:\n  - name: \" \"\n").contains("name"));
        var listed = ConfigLoader.load(new String[] {"--config=" + write("""
                middleware:
                  - name: gate
                    route: /*
                  - name: exact
                    route: /only
                access:
                  deny:
                    - "*.bak"
                  rules: ["deny:/private"]
                """)}, Map.of());
        assertTrue(listed.ok(), listed.errors().toString());
        assertEquals("/*", listed.document().middleware().get(0).route());
        assertEquals("/only", listed.document().middleware().get(1).route());
        assertEquals(List.of("*.bak"), listed.document().access().deny());
        assertEquals(List.of("deny:/private"), listed.document().access().rules());
        assertTrue(error("access:\n  rules: [\"allow:\"]\n").contains("pattern"));
        assertTrue(error("access:\n  rules: [\"nope\"]\n").contains("allow:"));
    }

    @Test
    void accessSectionMatchesTheRuleFlagsAndAcmeOpensTheChallenge() throws Exception {
        var file = write("""
                access:
                  deny: "*.bak,*~"
                  rules:
                    - hide:/drafts
                    - "ALLOW: /.well-known/acme-challenge"
                  allow_dotfiles: false
                  require_owner: www-data
                  require_world_readable: true
                acme:
                  enabled: true
                  email: ops@example.com
                  domains: [example.com]
                  accept_terms: true
                """);
        var bytes = Files.readAllBytes(file);
        var loaded = ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var access = loaded.document().access();
        assertEquals(List.of("*.bak", "*~"), access.deny());
        assertEquals(List.of("hide:/drafts", AccessSettings.ACME_CHALLENGE_RULE), access.rules());
        assertFalse(access.allowDotfiles());
        assertEquals("www-data", access.requireOwner());
        assertTrue(access.requireWorldReadable());
        assertEquals(sjws_nettask.config.ConfigCodec.sha256(loaded.document().canonicalText()),
                loaded.document().canonicalHash());
        assertNotEquals(sha256(bytes), loaded.document().canonicalHash());
        assertTrue(loaded.warnings().stream().noneMatch(w -> w.contains("not enforced")));
        var injected = ConfigLoader.load(new String[] {"--config=" + write("""
                acme:
                  enabled: true
                  email: ops@example.com
                  domains: [example.com]
                  accept_terms: true
                """)}, Map.of());
        assertEquals(List.of(AccessSettings.ACME_CHALLENGE_RULE), injected.document().access().rules());
        assertTrue(injected.warnings().stream().noneMatch(w -> w.contains("not enforced")));
        assertTrue(error("access:\n  rules: [permit:/x]\n").contains("allow:"));
        assertTrue(ConfigLoader.standalone(ServerConfig.defaults(dir)).document().canonicalHash()
                .matches("[0-9a-f]{64}"));
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
        assertEquals(64L << 10, Sizes.parse("64K", Integer.MAX_VALUE, "s"));
        assertEquals(1L << 30, Sizes.parse("1g", Long.MAX_VALUE, "s"));
        assertEquals(0L, Sizes.parse("0", 10, "s"));
        assertEquals(Duration.ofHours(2), Durations.parse("2h", "t"));
        assertEquals(Duration.ofDays(1), Durations.parse("1d", "t"));
        assertEquals(Duration.ofSeconds(3), Durations.parse(" 3s ", "t"));
        assertThrows(ConfigException.class, () -> Durations.parse("0", "t"));
        assertThrows(ConfigException.class, () -> Durations.parse(null, "t"));
        assertThrows(ConfigException.class, () -> Durations.parse("  ", "t"));
        assertThrows(ConfigException.class, () -> Durations.parse("1w", "t"));
        assertThrows(ConfigException.class, () -> Durations.parse("0ms", "t"));
        assertThrows(ConfigException.class, () -> Sizes.parse("nope", 10, "s"));
        assertThrows(ConfigException.class, () -> Sizes.parse(null, 10, "s"));
        assertThrows(ConfigException.class, () -> Sizes.parse("   ", 10, "s"));
        assertThrows(ConfigException.class, () -> Sizes.parse("9", 4, "s"));
        assertThrows(ConfigException.class, () -> Sizes.parse("9000000000g", Long.MAX_VALUE, "s"));
    }

    @Test
    void accessSettingsDefaultIsEachFlag() {
        assertTrue(AccessSettings.DEFAULT.isDefault());
        assertFalse(new AccessSettings(List.of("/secret"), List.of(), false, null, false).isDefault());
        assertFalse(new AccessSettings(List.of(), List.of("deny:/x"), false, null, false).isDefault());
        assertFalse(new AccessSettings(List.of(), List.of(), true, null, false).isDefault());
        assertFalse(new AccessSettings(List.of(), List.of(), false, "0", false).isDefault());
        assertFalse(new AccessSettings(List.of(), List.of(), false, null, true).isDefault());
        var injected = AccessSettings.DEFAULT.withAcmeChallenge();
        assertSame(injected, injected.withAcmeChallenge());
        assertFalse(injected.isDefault());
    }

    @Test
    void acmePortMustFitInATcpPortAndADedicatedListenerMatchesThatPort() {
        assertThrows(IllegalArgumentException.class, () -> settings(-1));
        assertThrows(IllegalArgumentException.class, () -> settings(65536));
        var dedicated = settings(80);
        assertTrue(dedicated.onMainListener(80));
        assertFalse(dedicated.onMainListener(8080));
        assertTrue(AcmeSettings.disabled().onMainListener(9));
    }

    private static AcmeSettings settings(int port) {
        return new AcmeSettings(true, "http://127.0.0.1/dir", "ops@example.com", List.of("example.com"),
                Path.of("acme"), port, true, Duration.ofDays(30));
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

    private static String sha256(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        var hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private String error(String yaml) throws Exception {
        var loaded = ConfigLoader.load(new String[] {"--config=" + write(yaml)}, Map.of());
        assertFalse(loaded.ok(), yaml);
        return loaded.errors().get(0);
    }
}
