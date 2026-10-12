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
                "--cert=/c.pem", "--key=/k.pem", "--alt-svc-max-age=60");
        var c = p.config();
        assertTrue(p.ok(), p.errors().toString());
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
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
        assertEquals(5, p.warnings().size(), p.warnings().toString());
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
                Map.of("WEB_SERVER_TLS_KEY_PASSWORD_FILE", f.toString())).config().tlsKeyPassword());
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
                "--key-password-file", "--alt-svc-max-age", "WEB_SERVER_TLS_KEY_PASSWORD", "--help")) {
            assertTrue(usage.contains(flag), "usage lacks " + flag);
        }
        for (var dropped : java.util.List.of("--max-pipelined", "--small-file-max", "--revalidate-ms")) {
            assertFalse(usage.contains(dropped), dropped + " was trimmed by the design review");
        }
    }
}
