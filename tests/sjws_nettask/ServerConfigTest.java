package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerConfigTest {

    private static ServerConfig.Parsed parse(Map<String, String> env, String... args) {
        return ServerConfig.parse(args, env);
    }

    @Test
    void defaults() {
        var p = parse(Map.of());
        var c = p.config();
        assertEquals(8080, c.port());
        assertEquals(Path.of("./HTML"), c.root());
        assertEquals(2048, c.maxConnections()); // design review M11 (was 10000)
        assertEquals(Duration.ofSeconds(30), c.idleTimeout());
        assertEquals(Duration.ofSeconds(10), c.headerTimeout());
        assertTrue(p.warnings().isEmpty());
    }

    @Test
    void positionalArgsAndFlagsAnywhere() {
        var c = parse(Map.of(), "--idle-timeout=5", "9000", "--max-connections=7", "/srv/www", "--header-timeout=2").config();
        assertEquals(9000, c.port());
        assertEquals(Path.of("/srv/www"), c.root());
        assertEquals(7, c.maxConnections());
        assertEquals(Duration.ofSeconds(5), c.idleTimeout());
        assertEquals(Duration.ofSeconds(2), c.headerTimeout());
    }

    @Test
    void environmentOverridesArguments() {
        var env = Map.of("WEB_SERVER_PORT", "9100", "WEB_SERVER_DIR", "/env/root",
                "WEB_SERVER_MAX_CONNECTIONS", "3", "WEB_SERVER_IDLE_TIMEOUT", "4", "WEB_SERVER_HEADER_TIMEOUT", "1");
        var c = parse(env, "9000", "/arg/root", "--max-connections=7").config();
        assertEquals(9100, c.port());
        assertEquals(Path.of("/env/root"), c.root());
        assertEquals(3, c.maxConnections());
        assertEquals(Duration.ofSeconds(4), c.idleTimeout());
        assertEquals(Duration.ofSeconds(1), c.headerTimeout());
    }

    @Test
    void invalidValuesFallBackWithWarnings() {
        var p = parse(Map.of("WEB_SERVER_MAX_CONNECTIONS", "lots"), "80.5", "--idle-timeout=-1", "--bogus", "dir", "extra");
        assertEquals(8080, p.config().port());
        assertEquals(ServerConfig.DEFAULT_MAX_CONNECTIONS, p.config().maxConnections());
        assertEquals(ServerConfig.DEFAULT_IDLE_TIMEOUT, p.config().idleTimeout());
        assertEquals(5, p.warnings().size(), p.warnings().toString());
        var invalidPortEnv = parse(Map.of("WEB_SERVER_PORT", "99999999999"));
        assertEquals(8080, invalidPortEnv.config().port());
        assertEquals(1, invalidPortEnv.warnings().size());
    }

    @Test
    void recordValidatesLimits() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.defaults("x").withMaxConnections(0));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.defaults("x").withPort(70000));
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.defaults("x").withTimeouts(Duration.ZERO, Duration.ofSeconds(1)));
    }

    @Test
    void supersetFieldsHaveInertDefaults() {
        var c = ServerConfig.defaults("www");
        assertEquals(Duration.ofSeconds(30), c.writeTimeout());
        assertFalse(c.h3Enabled());
        assertEquals(ServerConfig.H3_PORT_SAME_AS_TCP, c.h3Port());
        assertEquals(c.port(), c.effectiveH3Port());
        assertEquals("0.0.0.0", c.h3Host());
        assertEquals(1024, c.h3MaxConnections());
        assertNull(c.tlsCert());
        assertNull(c.tlsKey());
        assertNull(c.tlsKeyPassword());
        assertEquals(Duration.ofDays(1), c.altSvcMaxAge());
        assertEquals(64L * 1024 * 1024, c.cacheBytes());
        assertNull(c.accessLog());
    }

    @Test
    void builderRoundTripsEveryField() {
        var c = ServerConfig.builder(Path.of("/r")).port(1).maxConnections(2).idleTimeout(Duration.ofSeconds(3))
                .headerTimeout(Duration.ofSeconds(4)).writeTimeout(Duration.ofSeconds(5)).h3Enabled(true).h3Port(0)
                .h3Host("127.0.0.1").h3MaxConnections(6).tlsCert(Path.of("c.pem")).tlsKey(Path.of("k.pem"))
                .tlsKeyPassword("pw").altSvcMaxAge(Duration.ofSeconds(7)).cacheBytes(8).accessLog("-").build();
        assertEquals(c, c.toBuilder().build());
        assertEquals(0, c.effectiveH3Port());
        assertEquals(c.withPort(9).toBuilder().port(1).build(), c);
    }

    @Test
    void passwordIsMaskedInToString() {
        var c = ServerConfig.builder(Path.of("/r")).tlsCert(Path.of("c")).tlsKey(Path.of("k"))
                .tlsKeyPassword("hunter2").build();
        assertFalse(c.toString().contains("hunter2"), c.toString());
        assertTrue(c.toString().contains("tlsKeyPassword=****"));
    }

    @Test
    void certAndKeyGoTogether() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder(Path.of("/r")).tlsCert(Path.of("c")).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder(Path.of("/r")).tlsKey(Path.of("k")).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3Port(-2).build());
    }

    // --- W1: HTTP/3 and TLS flags ------------------------------------------------------

    @Test
    void h3FlagsFromCommandLine() {
        var p = parse(Map.of(), "--h3", "--h3-port=0", "--h3-host=127.0.0.1", "--h3-max-connections=5",
                "--cert=/c.pem", "--key=/k.pem", "--alt-svc", "--alt-svc-max-age=60");
        var c = p.config();
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(1, p.warnings().size(), "only the plaintext Alt-Svc warning: " + p.warnings());
        assertTrue(c.altSvc());
        assertTrue(c.h3Enabled());
        assertEquals(0, c.h3Port());
        assertEquals("127.0.0.1", c.h3Host());
        assertEquals(5, c.h3MaxConnections());
        assertEquals(Path.of("/c.pem"), c.tlsCert());
        assertEquals(Path.of("/k.pem"), c.tlsKey());
        assertEquals(Duration.ofSeconds(60), c.altSvcMaxAge());
    }

    @Test
    void h3FlagsFromEnvironmentOverrideArguments() {
        var env = Map.of("WEB_SERVER_H3", "false", "WEB_SERVER_H3_PORT", "8443", "WEB_SERVER_H3_HOST", "::1",
                "WEB_SERVER_H3_MAX_CONNECTIONS", "9", "WEB_SERVER_TLS_CERT", "/e/c", "WEB_SERVER_TLS_KEY", "/e/k",
                "WEB_SERVER_ALT_SVC_MA", "0");
        var c = parse(env, "--h3", "--h3-port=1", "--cert=/a/c", "--key=/a/k", "--alt-svc-max-age=5").config();
        assertFalse(c.h3Enabled());
        assertEquals(8443, c.h3Port());
        assertEquals("::1", c.h3Host());
        assertEquals(9, c.h3MaxConnections());
        assertEquals(Path.of("/e/c"), c.tlsCert());
        assertEquals(Duration.ZERO, c.altSvcMaxAge());
    }

    @Test
    void recordRejectsOutOfRangeValues() {
        var ok = ServerConfig.builder(Path.of("/r"));
        assertThrows(IllegalArgumentException.class, () -> ok.port(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).port(65536).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3Port(-2).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3Port(65536).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).maxConnections(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3MaxConnections(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).maxConnectionsPerIp(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3MaxConnectionsPerIp(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).idleTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder(Path.of("/r")).writeTimeout(Duration.ofDays(30)).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder(Path.of("/r")).altSvcMaxAge(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3MinRate(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).cacheBytes(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).tlsCert(Path.of("/c")).build());
        assertThrows(NullPointerException.class, () -> ServerConfig.builder(null).build());
        assertThrows(NullPointerException.class, () -> ServerConfig.builder(Path.of("/r")).h3Host(null).build());
        assertEquals(65535, ServerConfig.builder(Path.of("/r")).port(65535).h3Port(65535).build().effectiveH3Port());
    }

    @Test
    void passwordFileWithoutTrailingNewlineIsUsedAsIs(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        for (var content : new String[] {"secret", "secret\n", "secret\r\n"}) {
            var f = java.nio.file.Files.writeString(tmp.resolve("pw"), content);
            var p = ServerConfig.parse(new String[] {"--h3", "--cert=/c", "--key=/k", "--key-password-file=" + f}, Map.of());
            assertEquals("secret", p.config().tlsKeyPassword(), content);
        }
    }

    @Test
    void booleanAndValuelessOptions() {
        assertFalse(parse(Map.of(), "--h3", "--alt-svc=no").config().altSvc());
        assertTrue(parse(Map.of(), "--h3", "--alt-svc=yes").config().altSvc());
        assertTrue(parse(Map.of(), "--allow-unlistable-dirs=true").config().allowUnlistableDirs());
        var missing = parse(Map.of(), "--max-connections", "--cache-bytes");
        assertEquals(ServerConfig.DEFAULT_MAX_CONNECTIONS, missing.config().maxConnections());
        assertEquals(ServerConfig.DEFAULT_CACHE_BYTES, missing.config().cacheBytes());
        assertTrue(missing.warnings().stream().anyMatch(w -> w.contains("Invalid --max-connections (value 'null')")),
                missing.warnings().toString());
        assertTrue(missing.warnings().stream().anyMatch(w -> w.contains("Invalid --cache-bytes (value 'null')")),
                missing.warnings().toString());
    }

    @Test
    void altSvcHeaderValueChecksThePort() {
        assertEquals("h3=\":443\"; ma=60", AltSvc.headerValue(443, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> AltSvc.headerValue(0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AltSvc.headerValue(65536, Duration.ZERO));
        assertEquals("h3=\":65535\"; ma=0", AltSvc.headerValue(65535, Duration.ZERO));
    }

    /** N6: the accept backlog is configurable (1024 by default instead of the JDK's 50). */
    @Test
    void backlogOption() {
        assertEquals(1024, parse(Map.of()).config().backlog());
        assertEquals(4096, parse(Map.of(), "--backlog=4096").config().backlog());
        assertEquals(10, parse(Map.of("WEB_SERVER_BACKLOG", "10"), "--backlog=4096").config().backlog(), "environment wins");
        var bad = parse(Map.of(), "--backlog=0");
        assertEquals(1024, bad.config().backlog());
        assertFalse(bad.warnings().isEmpty());
        assertEquals(7, ServerConfig.builder(Path.of("/r")).backlog(7).build().toBuilder().build().backlog());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).backlog(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).backlog(65536).build());
        assertTrue(ServerConfig.defaults("/r").toString().contains("backlog=1024"));
    }

    /** Verification V7: the weaker search-only-directory fallback is opt-in. */
    @Test
    void allowUnlistableDirsIsOptIn() {
        assertFalse(parse(Map.of()).config().allowUnlistableDirs());
        assertTrue(parse(Map.of(), "--allow-unlistable-dirs").config().allowUnlistableDirs());
        assertFalse(parse(Map.of(), "--allow-unlistable-dirs=false").config().allowUnlistableDirs());
        assertTrue(parse(Map.of("WEB_SERVER_ALLOW_UNLISTABLE_DIRS", "yes")).config().allowUnlistableDirs());
        assertFalse(parse(Map.of("WEB_SERVER_ALLOW_UNLISTABLE_DIRS", "0"), "--allow-unlistable-dirs").config()
                .allowUnlistableDirs(), "environment wins");
        var p = parse(Map.of(), "--allow-unlistable-dirs");
        assertTrue(p.ok() && p.warnings().isEmpty(), p.warnings().toString());
        var c = ServerConfig.builder(Path.of("/r")).allowUnlistableDirs(true).build();
        assertTrue(c.toBuilder().build().allowUnlistableDirs());
        assertTrue(c.toString().contains("allowUnlistableDirs=true"));
        assertTrue(ServerConfig.usage().contains("--allow-unlistable-dirs"));
        assertTrue(ServerConfig.usage().contains("WEB_SERVER_ALLOW_UNLISTABLE_DIRS"));
    }

    /** Verification V1/V5: per-address caps and the HTTP/3 minimum rate. */
    @Test
    void perAddressAndRateFlags() {
        var d = parse(Map.of()).config();
        assertEquals(ServerConfig.DEFAULT_MAX_CONNECTIONS_PER_IP, d.maxConnectionsPerIp());
        assertEquals(16, d.h3MaxConnectionsPerIp());
        assertEquals(2048, d.h3MinRate());
        var p = parse(Map.of(), "--h3", "--max-connections-per-ip=3", "--h3-max-connections-per-ip=4",
                "--h3-min-rate=0");
        assertTrue(p.ok(), p.errors().toString());
        assertEquals(3, p.config().maxConnectionsPerIp());
        assertEquals(4, p.config().h3MaxConnectionsPerIp());
        assertEquals(0, p.config().h3MinRate(), "0 disables the check");
        var env = Map.of("WEB_SERVER_MAX_CONNECTIONS_PER_IP", "5", "WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP", "6",
                "WEB_SERVER_H3_MIN_RATE", "4096", "WEB_SERVER_H3", "true");
        var e = parse(env, "--max-connections-per-ip=3").config();
        assertEquals(5, e.maxConnectionsPerIp());
        assertEquals(6, e.h3MaxConnectionsPerIp());
        assertEquals(4096, e.h3MinRate());
        var bad = parse(Map.of(), "--h3", "--max-connections-per-ip=0", "--h3-max-connections-per-ip=x",
                "--h3-min-rate=-1");
        assertEquals(ServerConfig.DEFAULT_MAX_CONNECTIONS_PER_IP, bad.config().maxConnectionsPerIp());
        assertEquals(ServerConfig.DEFAULT_H3_MAX_CONNECTIONS_PER_IP, bad.config().h3MaxConnectionsPerIp());
        assertEquals(ServerConfig.DEFAULT_H3_MIN_RATE, bad.config().h3MinRate());
        assertEquals(3, bad.warnings().size(), bad.warnings().toString());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder(Path.of("/r")).maxConnectionsPerIp(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder(Path.of("/r")).h3MaxConnectionsPerIp(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder(Path.of("/r")).h3MinRate(-1).build());
        var shown = ServerConfig.builder(Path.of("/r")).maxConnectionsPerIp(9).build().toString();
        assertTrue(shown.contains("maxConnectionsPerIp=9"), shown);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true", "1,true", "YES,true", "on,true",
        "false,false", "0,false", "No,false", "off,false"})
    void h3Booleans(String value, boolean expected) {
        assertEquals(expected, parse(Map.of("WEB_SERVER_H3", value)).config().h3Enabled());
        assertEquals(expected, parse(Map.of(), "--h3=" + value).config().h3Enabled());
    }

    @Test
    void invalidBooleanWarnsAndKeepsPrevious() {
        var p = parse(Map.of("WEB_SERVER_H3", "maybe"), "--h3");
        assertTrue(p.config().h3Enabled());
        assertTrue(p.warnings().get(0).contains("WEB_SERVER_H3"), p.warnings().toString());
    }

    @Test
    void noH3AfterH3Disables() {
        assertFalse(parse(Map.of(), "--h3", "--no-h3").config().h3Enabled());
    }

    @Test
    void invalidH3ValuesFallBack() {
        var p = parse(Map.of(), "--h3", "--h3-port=70000", "--h3-max-connections=0", "--h3-host=",
                "--alt-svc-max-age=-5", "--cert");
        var c = p.config();
        assertEquals(ServerConfig.H3_PORT_SAME_AS_TCP, c.h3Port());
        assertEquals(ServerConfig.DEFAULT_H3_MAX_CONNECTIONS, c.h3MaxConnections());
        assertEquals(ServerConfig.DEFAULT_H3_HOST, c.h3Host());
        assertEquals(ServerConfig.DEFAULT_ALT_SVC_MAX_AGE, c.altSvcMaxAge());
        assertNull(c.tlsCert());
        assertEquals(6, p.warnings().size(), p.warnings().toString()); // + "--alt-svc-max-age has no effect"
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("Missing value for --cert")));
    }

    @Test
    void certWithoutKeyIsAnError() {
        var p = parse(Map.of(), "--h3", "--cert=/c.pem");
        assertFalse(p.ok());
        assertTrue(p.errors().get(0).contains("--cert and --key must be given together"), p.errors().toString());
        assertNull(p.config().tlsCert());
        assertFalse(parse(Map.of("WEB_SERVER_TLS_KEY", "/k"), "--h3").ok());
    }

    @Test
    void passwordFromEnvironment() {
        var p = parse(Map.of("WEB_SERVER_TLS_KEY_PASSWORD", "s3cret"), "--h3", "--cert=/c", "--key=/k");
        assertEquals("s3cret", p.config().tlsKeyPassword());
        assertFalse(p.config().toString().contains("s3cret"));
    }

    @Test
    void passwordFromFileStripsOneTrailingNewline(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        var f = tmp.resolve("pw");
        java.nio.file.Files.writeString(f, "pa ss\n");
        var p = ServerConfig.parse(new String[] {"--h3", "--cert=/c", "--key=/k", "--key-password-file=" + f}, Map.of());
        assertEquals("pa ss", p.config().tlsKeyPassword());
        java.nio.file.Files.writeString(f, "x\r\n");
        assertEquals("x", ServerConfig.parse(new String[] {"--cert=/c", "--key=/k"},
                Map.of("WEB_SERVER_H3", "true", "WEB_SERVER_TLS_KEY_PASSWORD_FILE", f.toString())).config().tlsKeyPassword());
    }

    @Test
    void passwordFileErrorsAreReportedWithoutTheContent() {
        var p = ServerConfig.parse(new String[] {"--h3", "--cert=/c", "--key=/k", "--key-password-file=/nope/pw"},
                Map.of(), f -> {
                    throw new java.nio.file.NoSuchFileException(f.toString());
                });
        assertFalse(p.ok());
        assertTrue(p.errors().get(0).contains("/nope/pw"), p.errors().toString());
    }

    @Test
    void environmentPasswordWinsOverFileWithWarning() {
        var p = ServerConfig.parse(new String[] {"--cert=/c", "--key=/k", "--key-password-file=/pw"},
                Map.of("WEB_SERVER_TLS_KEY_PASSWORD", "env"), f -> "file");
        assertEquals("env", p.config().tlsKeyPassword());
        assertEquals(1, p.warnings().stream().filter(w -> w.contains("using the variable")).count());
    }

    @Test
    void passwordFileIsNotReadWithoutH3() {
        var p = ServerConfig.parse(new String[] {"--cert=/c", "--key=/k", "--key-password-file=/nope/pw"}, Map.of(),
                f -> {
                    throw new java.io.IOException("must not be read");
                });
        assertTrue(p.errors().isEmpty(), p.errors().toString());
        assertNull(p.config().tlsKeyPassword());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("--key-password-file") && w.contains("no effect")),
                p.warnings().toString());
    }

    @Test
    void passwordOnCommandLineIsRefusedAndNeverEchoed() {
        var p = parse(Map.of(), "--h3", "--cert=/c", "--key=/k", "--key-password=hunter2");
        assertNull(p.config().tlsKeyPassword());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("not accepted on the command line")));
        assertTrue(p.warnings().stream().noneMatch(w -> w.contains("hunter2")), p.warnings().toString());
    }

    @Test
    void passwordWithoutKeyIsIgnored() {
        var p = parse(Map.of("WEB_SERVER_TLS_KEY_PASSWORD", "x"), "--h3");
        assertNull(p.config().tlsKeyPassword());
        assertTrue(p.warnings().stream().anyMatch(w -> w.contains("without --key")));
    }

    @Test
    void h3OptionsWithoutH3Warn() {
        var p = parse(Map.of(), "--h3-port=1", "--cert=/c", "--key=/k");
        assertTrue(p.ok());
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().get(0).contains("HTTP/3 is disabled, so --h3-port, --cert, --key have no effect"),
                p.warnings().get(0));
    }

    @Test
    void help() {
        assertTrue(parse(Map.of(), "--help").helpRequested());
        assertTrue(parse(Map.of(), "8081", "-h").helpRequested());
        assertFalse(parse(Map.of()).helpRequested());
        var usage = ServerConfig.usage();
        for (var flag : java.util.List.of("--max-connections", "--idle-timeout", "--header-timeout", "--h3 ",
                "--no-h3", "--h3-port", "--h3-host", "--h3-max-connections", "--cert", "--key=",
                "--key-password-file", "--alt-svc-max-age", "WEB_SERVER_TLS_KEY_PASSWORD", "--help", "--alt-svc ",
                "--no-alt-svc", "WEB_SERVER_ALT_SVC=", "--write-timeout", "WEB_SERVER_WRITE_TIMEOUT",
                "--max-connections-per-ip", "WEB_SERVER_MAX_CONNECTIONS_PER_IP", "--h3-max-connections-per-ip",
                "WEB_SERVER_H3_MAX_CONNECTIONS_PER_IP", "--h3-min-rate", "WEB_SERVER_H3_MIN_RATE")) {
            assertTrue(usage.contains(flag), "usage lacks " + flag);
        }
        for (var dropped : java.util.List.of("--max-pipelined", "--small-file-max", "--revalidate-ms")) {
            assertFalse(usage.contains(dropped), dropped + " was trimmed by the design review");
        }
    }

    @Test
    void altSvcIsOptInWithAPlaintextWarning() {
        var off = parse(Map.of(), "--h3");
        assertFalse(off.config().altSvc(), "off by default");
        assertTrue(off.warnings().isEmpty(), off.warnings().toString());
        var on = parse(Map.of(), "--h3", "--alt-svc");
        assertTrue(on.config().altSvc());
        assertEquals(1, on.warnings().size());
        assertTrue(on.warnings().get(0).contains("browsers ignore Alt-Svc from http:// origins"));
        assertTrue(on.warnings().get(0).contains("curl --http3-only"));
        assertTrue(parse(Map.of("WEB_SERVER_ALT_SVC", "true"), "--h3").config().altSvc());
        assertFalse(parse(Map.of("WEB_SERVER_ALT_SVC", "false"), "--h3", "--alt-svc").config().altSvc(), "env wins");
        assertFalse(parse(Map.of(), "--h3", "--alt-svc", "--no-alt-svc").config().altSvc());
        var noH3 = parse(Map.of(), "--alt-svc");
        assertTrue(noH3.warnings().stream().anyMatch(w -> w.contains("--alt-svc") && w.contains("add --h3")),
                noH3.warnings().toString());
        var maOnly = parse(Map.of(), "--h3", "--alt-svc-max-age=5");
        assertTrue(maOnly.warnings().contains("--alt-svc-max-age has no effect without --alt-svc"),
                maOnly.warnings().toString());
        var bad = parse(Map.of(), "--h3", "--alt-svc=maybe");
        assertFalse(bad.config().altSvc(), "an invalid value keeps the default");
    }

    @Test
    void writeTimeoutFlagAndEnvironment() {
        assertEquals(ServerConfig.DEFAULT_WRITE_TIMEOUT, parse(Map.of()).config().writeTimeout());
        assertEquals(Duration.ofSeconds(5), parse(Map.of(), "--write-timeout=5").config().writeTimeout());
        assertEquals(Duration.ofSeconds(7),
                parse(Map.of("WEB_SERVER_WRITE_TIMEOUT", "7"), "--write-timeout=5").config().writeTimeout());
        var bad = parse(Map.of(), "--write-timeout=0");
        assertEquals(ServerConfig.DEFAULT_WRITE_TIMEOUT, bad.config().writeTimeout());
        assertEquals(1, bad.warnings().size());
    }

    @Test
    void invalidBooleanKeepsTheDefault() { // code review N-1: --h3=maybe used to enable HTTP/3
        var p = parse(Map.of(), "--h3=maybe");
        assertFalse(p.config().h3Enabled());
        assertTrue(p.warnings().get(0).contains("--h3"), p.warnings().toString());
        assertTrue(parse(Map.of(), "--h3", "--h3=maybe").config().h3Enabled(), "keeps the previous value");
    }

    // --- S3: --cache-bytes ---------------------------------------------------------------------

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,0", "65536,65536", "64k,65536", "64K,65536", "64m,67108864",
        "1g,1073741824", "16g,17179869184"})
    void cacheBytesFlag(String value, long expected) {
        var p = parse(Map.of(), "--cache-bytes=" + value);
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
        assertEquals(expected, p.config().cacheBytes());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"-1", "17g", "1t", "64 m", "1.5m", "", "m",
        "999999999999"})
    void invalidCacheBytesWarnAndKeepTheDefault(String value) {
        var p = parse(Map.of(), "--cache-bytes=" + value);
        assertEquals(ServerConfig.DEFAULT_CACHE_BYTES, p.config().cacheBytes());
        assertEquals(1, p.warnings().size(), p.warnings().toString());
        assertTrue(p.warnings().get(0).contains("--cache-bytes"), p.warnings().get(0));
    }

    @Test
    void cacheBytesEnvironmentOverridesArgument() {
        assertEquals(0, parse(Map.of("WEB_SERVER_CACHE_BYTES", "0"), "--cache-bytes=1m").config().cacheBytes());
        assertTrue(ServerConfig.usage().contains("--cache-bytes"));
    }

    @Test
    void accessLogAndMetricsFlags() {
        var off = parse(Map.of()).config();
        assertNull(off.accessLog());
        assertEquals(ServerConfig.AccessLogFormat.COMBINED, off.accessLogFormat());
        assertEquals(ServerConfig.MetricsExposure.OFF, off.metricsExposure());
        assertFalse(off.healthz());
        assertFalse(off.observe());
        assertTrue(off.toString().contains("accessLogFormat=COMBINED"));
        assertTrue(off.toString().contains("metricsExposure=OFF"));
        assertTrue(off.toString().contains("healthz=false"));

        var on = parse(Map.of(), "--access-log=-", "--access-log-format=json", "--metrics=on").config();
        assertEquals("-", on.accessLog());
        assertEquals(ServerConfig.AccessLogFormat.JSON, on.accessLogFormat());
        assertEquals(ServerConfig.MetricsExposure.ON, on.metricsExposure());
        assertEquals(on, on.toBuilder().build());

        assertEquals(ServerConfig.MetricsExposure.LOCALHOST, parse(Map.of(), "--metrics").config().metricsExposure());
        assertEquals(ServerConfig.MetricsExposure.OFF, parse(Map.of(), "--metrics", "--no-metrics").config().metricsExposure());
        assertEquals(ServerConfig.AccessLogFormat.COMMON,
                parse(Map.of(), "--access-log=/tmp/a.log", "--access-log-format=common").config().accessLogFormat());
        assertEquals(ServerConfig.AccessLogFormat.COMBINED,
                parse(Map.of(), "--access-log=/tmp/a.log", "--access-log-format=combined").config().accessLogFormat());
        var bareFormat = parse(Map.of(), "--access-log-format");
        assertEquals(ServerConfig.AccessLogFormat.COMBINED, bareFormat.config().accessLogFormat());
        assertTrue(bareFormat.warnings().stream().anyMatch(w -> w.contains("Missing value for --access-log-format")),
                bareFormat.warnings().toString());
        assertEquals(ServerConfig.MetricsExposure.OFF, parse(Map.of(), "--metrics=off").config().metricsExposure());

        var env = parse(Map.of("WEB_SERVER_ACCESS_LOG", "/env.log", "WEB_SERVER_ACCESS_LOG_FORMAT", "JSON",
                "WEB_SERVER_METRICS", "localhost"), "--access-log=-", "--access-log-format=common", "--metrics=on");
        assertTrue(env.warnings().isEmpty(), env.warnings().toString());
        assertEquals("/env.log", env.config().accessLog());
        assertEquals(ServerConfig.AccessLogFormat.JSON, env.config().accessLogFormat());
        assertEquals(ServerConfig.MetricsExposure.LOCALHOST, env.config().metricsExposure());

        var bad = parse(Map.of(), "--access-log-format=xml", "--metrics=everywhere", "--access-log");
        assertEquals(ServerConfig.AccessLogFormat.COMBINED, bad.config().accessLogFormat());
        assertEquals(ServerConfig.MetricsExposure.LOCALHOST, bad.config().metricsExposure());
        assertNull(bad.config().accessLog());
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("access-log-format") && w.contains("xml")),
                bad.warnings().toString());
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("without --access-log")), bad.warnings().toString());
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("everywhere")), bad.warnings().toString());
        assertTrue(bad.warnings().stream().anyMatch(w -> w.contains("Missing value for --access-log")),
                bad.warnings().toString());

        var blank = parse(Map.of("WEB_SERVER_METRICS", "  ", "WEB_SERVER_ACCESS_LOG_FORMAT", ""), "--access-log=/x");
        assertEquals(ServerConfig.MetricsExposure.OFF, blank.config().metricsExposure());
        assertEquals(ServerConfig.AccessLogFormat.COMBINED, blank.config().accessLogFormat());
        assertFalse(blank.warnings().isEmpty());
        assertThrows(NullPointerException.class,
                () -> ServerConfig.builder(Path.of("/r")).accessLogFormat(null).build());
        assertThrows(NullPointerException.class,
                () -> ServerConfig.builder(Path.of("/r")).metricsExposure(null).build());

        assertTrue(parse(Map.of(), "--healthz").config().healthz());
        assertTrue(parse(Map.of(), "--healthz=on").config().healthz());
        assertTrue(parse(Map.of(), "--healthz").config().observe());
        assertFalse(parse(Map.of(), "--healthz", "--no-healthz").config().healthz());
        assertTrue(parse(Map.of(), "--access-log=/tmp/a.log").config().observe());
        assertTrue(parse(Map.of(), "--metrics=localhost").config().observe());
        assertFalse(parse(Map.of(), "--metrics=off").config().observe());
        var healthEnv = parse(Map.of("WEB_SERVER_HEALTHZ", "false"), "--healthz");
        assertFalse(healthEnv.config().healthz());
        assertTrue(healthEnv.warnings().isEmpty(), healthEnv.warnings().toString());
        var badHealth = parse(Map.of("WEB_SERVER_HEALTHZ", "maybe"), "--healthz=no");
        assertFalse(badHealth.config().healthz());
        assertTrue(badHealth.warnings().stream().anyMatch(w -> w.contains("WEB_SERVER_HEALTHZ")),
                badHealth.warnings().toString());
        assertTrue(ServerConfig.builder(Path.of("/r")).healthz(true).build().healthz());
    }

    @Test
    void fileBaselineKeepsUnlistableDirsHealthzAndKeyPassword() {
        var baseline = ServerConfig.builder(Path.of("site"))
                .allowUnlistableDirs(true)
                .healthz(true)
                .tlsCert(Path.of("c"))
                .tlsKey(Path.of("k"))
                .tlsKeyPassword("file-secret")
                .build();
        var parsed = ServerConfig.parse(new String[0], Map.of(), baseline);
        assertTrue(parsed.ok(), parsed.errors().toString());
        assertTrue(parsed.config().allowUnlistableDirs());
        assertTrue(parsed.config().healthz());
        assertEquals("file-secret", parsed.config().tlsKeyPassword());
        assertFalse(parsed.config().toString().contains("file-secret"));
    }
}
