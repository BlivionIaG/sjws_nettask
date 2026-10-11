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
        assertEquals(10_000, c.maxConnections());
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
}
