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
}
