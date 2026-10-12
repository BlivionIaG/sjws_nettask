package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import sjws_nettask.ServerConfig;

class RouteTableTest {

    private static Upstream upstream() {
        return new Upstream("api", URI.create("http://127.0.0.1:9"), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofSeconds(30), 1024, false);
    }

    private static RouteSpec route(String host, boolean wildcard, String suffix, String prefix) {
        return new RouteSpec(host, wildcard, suffix, prefix, null, new RouteSpec.Action.Proxy(upstream()));
    }

    @Test
    void segmentBoundaryAndFirstMatch() {
        var table = new RouteTable(List.of(
                route("example.com", false, null, "/api"),
                route(null, false, null, "/old"),
                route(null, false, null, "/")));
        assertEquals("/api", table.match("Example.COM:443", "/api").prefix());
        assertEquals("/api", table.match("example.com", "/api/v1").prefix());
        assertEquals("/api", table.match("example.com", "/api?x=1").prefix());
        assertEquals("/", table.match("example.com", "/apix").prefix());
        assertEquals("/old", table.match(null, "/old").prefix());
        assertEquals("/", table.match("other.test", "/api").prefix());
        assertNull(new RouteTable(List.of(route("example.com", false, null, "/api"))).match("other.test", "/api"));
        assertEquals("/", table.match("example.com", "/").prefix());
    }

    @Test
    void wildcardHostDoesNotMatchTheApex() {
        var wild = route("*.example.com", true, ".example.com", "/");
        assertTrue(wild.matchesHost("www.example.com"));
        assertTrue(wild.matchesHost("WWW.Example.COM:8443"));
        assertFalse(wild.matchesHost("example.com"));
        assertFalse(wild.matchesHost("notexample.com"));
        assertFalse(wild.matchesHost(null));
        var v6 = new RouteSpec("::1", false, null, "/", null, new RouteSpec.Action.Static());
        assertTrue(v6.matchesHost("[::1]:8080"));
        assertFalse(v6.matchesHost("[::2]"));
    }

    @Test
    void shadows() {
        var catchAll = new RouteTable(List.of(
                new RouteSpec(null, false, null, "/", null, new RouteSpec.Action.Static()),
                route(null, false, null, "/api")));
        assertEquals(1, catchAll.shadows().size());
        var wild = new RouteTable(List.of(
                route("*.example.com", true, ".example.com", "/"),
                route("www.example.com", false, null, "/app"),
                route("example.com", false, null, "/"),
                route("*.foo.example.com", true, ".foo.example.com", "/v")));
        assertTrue(wild.shadows().stream().anyMatch(w -> w.contains("routes[1]")));
        assertTrue(wild.shadows().stream().anyMatch(w -> w.contains("routes[3]")));
        assertFalse(wild.shadows().stream().anyMatch(w -> w.contains("routes[2]")));
        assertTrue(RouteTable.prefixCovers("/api", "/api/v1"));
        assertFalse(RouteTable.prefixCovers("/api", "/apiv1"));
    }

    @Test
    void liveSnapshotSwapsWithoutSharingTheHandler() throws Exception {
        var root = java.nio.file.Files.createTempDirectory("snap");
        var server = ServerConfig.defaults(root);
        var board = new sjws_nettask.acme.ChallengeBoard();
        var first = ConfigSnapshot.compile(ConfigDocument.defaults(server), board, 1);
        var live = new LiveConfig(first);
        assertSame(first.files(), live.handler());
        var second = ConfigSnapshot.compile(ConfigDocument.defaults(server.withPort(9)), board, 2);
        live.set(second);
        assertSame(second, live.get());
        assertEquals(2, live.get().generation());
        assertNotSame(first.files(), live.handler());
    }
}
