package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;

class ReloadServerTest {

    @TempDir
    Path dir;

    @Test
    void reaperUpdateChangesTheNextDeadline() {
        var reaper = new ConnectionReaper(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(30));
        try {
            assertEquals(Duration.ofSeconds(30).toNanos(), reaper.idleNanos());
            reaper.update(Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofSeconds(6));
            assertEquals(Duration.ofSeconds(4).toNanos(), reaper.idleNanos());
        } finally {
            reaper.close();
        }
    }

    @Test
    void reloadSwapsTheRootAndKeepsABadFile() throws Exception {
        var first = dir.resolve("one");
        var second = dir.resolve("two");
        Files.createDirectory(first);
        Files.createDirectory(second);
        Files.writeString(first.resolve("index.html"), "one");
        Files.writeString(second.resolve("index.html"), "two");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            int port = server.tcpPort();
            assertEquals("one", HttpTestSupport.get(port, "/", 2000).bodyText());
            Files.writeString(yaml, "server:\n  port: 9\n  root: " + second.toAbsolutePath() + "\n  idle_timeout: 4\n");
            var result = server.reload();
            assertTrue(result.applied(), result.errors().toString());
            assertTrue(result.needsRestart().stream().anyMatch(w -> w.contains("server.port")));
            assertEquals("two", HttpTestSupport.get(port, "/", 2000).bodyText());
            assertEquals(port, server.tcpPort());
            assertEquals(Duration.ofSeconds(4), server.config().idleTimeout());
            Files.writeString(yaml, "server: [\n");
            var rejected = server.reload();
            assertFalse(rejected.applied());
            assertFalse(rejected.errors().isEmpty());
            assertEquals("two", HttpTestSupport.get(port, "/", 2000).bodyText());
        }
    }

    @Test
    void keepAliveSeesTheSwappedHandler() throws Exception {
        var first = dir.resolve("a");
        var second = dir.resolve("b");
        Files.createDirectory(first);
        Files.createDirectory(second);
        Files.writeString(first.resolve("index.html"), "alpha");
        Files.writeString(second.resolve("index.html"), "beta");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        try (var server = SjwsServer.start(ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of()))) {
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), server.tcpPort())) {
                socket.setSoTimeout(2000);
                HttpTestSupport.write(socket.getOutputStream(),
                        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n");
                assertEquals("alpha", HttpTestSupport.readResponse(socket.getInputStream()).bodyText());
                Files.writeString(yaml, "server:\n  port: 0\n  root: " + second.toAbsolutePath() + "\n");
                ReloadHook.run();
                HttpTestSupport.write(socket.getOutputStream(),
                        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                assertEquals("beta", HttpTestSupport.readResponse(socket.getInputStream()).bodyText());
            }
        }
    }

    @Test
    void reloadWithoutAFileFailsAndLeavesTheSnapshot() throws Exception {
        Files.createDirectory(dir.resolve("html"));
        Files.writeString(dir.resolve("html").resolve("index.html"), "stay");
        var config = ServerConfig.defaults(dir.resolve("html")).withPort(0);
        try (var server = SjwsServer.start(config)) {
            var rejected = server.reload();
            assertFalse(rejected.applied());
            assertTrue(rejected.errors().get(0).contains("no config file"));
            assertEquals("stay", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
        }
    }

    @Test
    void enabledRoutingDispatchesToTheUpstream() throws Exception {
        var root = dir.resolve("www");
        Files.createDirectory(root);
        Files.writeString(root.resolve("index.html"), "static");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: %s
                routing:
                  enabled: true
                  upstreams:
                    api: { url: "http://127.0.0.1:9", connect_timeout: 5s, response_timeout: 30s, max_body: 16m }
                  routes:
                    - prefix: /api
                      proxy: api
                """.formatted(root.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            assertEquals("static", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            // port 9 (discard) is closed: the proxied route answers 502, not the document root
            assertTrue(HttpTestSupport.get(server.tcpPort(), "/api/x", 5000).bodyText().contains("502 Bad Gateway"));
        }
    }
}
