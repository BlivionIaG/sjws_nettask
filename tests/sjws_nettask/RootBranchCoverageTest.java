package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.SnapshotVerifier;
import sjws_nettask.core.RequestFilters;

/** Root-package branches the 0.96 gate still misses. */
class RootBranchCoverageTest {

    @TempDir
    Path dir;

    @Test
    void pidReleaseSkipsAReplacedFileAndSurfacesABlockedDelete() throws Exception {
        var replaced = this.dir.resolve("replaced.pid");
        var claim = PidFile.claim(replaced);
        assertTrue(claim.acquired());
        Files.writeString(replaced, "999999\n1\n");
        claim.file().close();

        var blocked = this.dir.resolve("locked");
        Files.createDirectory(blocked);
        var pid = blocked.resolve("sjws.pid");
        var held = PidFile.claim(pid);
        assertTrue(held.acquired());
        var saved = Files.getPosixFilePermissions(blocked);
        Files.setPosixFilePermissions(blocked, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            assertThrows(IOException.class, held.file()::close);
        } finally {
            Files.setPosixFilePermissions(blocked, saved);
            Files.deleteIfExists(pid);
        }
        assertEquals(-1, PidFile.parse("42").startedMillis());
        assertFalse(PidFile.parse("42").running());
    }

    @Test
    void bindContentFlagCoversEachFallback() {
        var bare = Daemon.parse(new String[] {"--bind-content"}, Map.of());
        assertEquals(Boolean.TRUE, bare.bindContent());
        var off = Daemon.parse(new String[] {"--bind-content=false", "--bind-content=yes"}, Map.of());
        assertEquals(Boolean.TRUE, off.bindContent());
        var onThenOff = Daemon.parse(new String[] {"--bind-content=true", "--bind-content=no"}, Map.of());
        assertEquals(Boolean.FALSE, onThenOff.bindContent());
        var invalid = Daemon.parse(new String[] {"--bind-content=maybe"}, Map.of());
        assertTrue(invalid.warnings().stream().anyMatch(w -> w.contains("bind-content")));
        var fromEnv = Daemon.parse(new String[] {"--bind-content=false"}, Map.of("WEB_SERVER_BIND_CONTENT", "true"));
        assertEquals(Boolean.TRUE, fromEnv.bindContent());
        assertTrue(fromEnv.bindFromEnv());
    }

    @Test
    void dataNodeSighupNamesEveryChangedControlPlaneSection() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html"));
        var state = Files.createDirectory(this.dir.resolve("state"));
        var yaml = this.dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                schema: 1
                version: 1
                node:
                  mode: data
                  state_dir: %s
                server:
                  port: 0
                  root: %s
                """.formatted(state.toAbsolutePath(), root.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded, SnapshotVerifier.noop())) {
            Files.writeString(yaml, """
                    schema: 1
                    version: 2
                    node:
                      mode: data
                      state_dir: %s
                    server:
                      port: 0
                      root: %s
                    routing:
                      routes:
                        - { prefix: /x, static: true }
                    middleware:
                      - name: trace
                    access:
                      allow_dotfiles: true
                    acme:
                      directory: https://acme.example/directory
                    messaging:
                      defaults:
                        partitions: 1
                    """.formatted(state.toAbsolutePath(), root.toAbsolutePath()));
            var reloaded = server.reload();
            assertFalse(reloaded.applied(), reloaded.errors().toString());
            var detail = reloaded.errors().toString();
            assertTrue(detail.contains("routing"), detail);
            assertTrue(detail.contains("middleware"), detail);
            assertTrue(detail.contains("access"), detail);
            assertTrue(detail.contains("acme"), detail);
            assertTrue(detail.contains("topics"), detail);
        }
    }

    @Test
    void startupHooksAndADataRoleWithoutBootstrap() throws Exception {
        var err = new java.io.ByteArrayOutputStream();
        var error = new PrintStream(err);
        var root = Files.createDirectory(this.dir.resolve("site"));
        var yaml = this.dir.resolve("mode.yaml");
        Files.writeString(yaml, "node:\n  mode: data\nserver:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        int usage = Sjws_nettask.run(new String[] {"--config=" + yaml}, Map.of(),
                new PrintStream(new java.io.ByteArrayOutputStream()), error, H3Transports.defaultFactory(), server -> {
                });
        assertEquals(Sjws_nettask.EXIT_USAGE, usage);
        assertTrue(err.toString().contains("requires --bootstrap"));

        var html = Files.createDirectory(this.dir.resolve("hooks"));
        try (var server = SjwsServer.start(ServerConfig.builder(html).port(0).build())) {
            var out = new java.io.ByteArrayOutputStream();
            int code = await(server, new PrintStream(out), error, null, new ServiceHooks() {
                @Override
                public void onListening(SjwsServer started, Path pidFile, Duration shutdownTimeout) {
                }

                @Override
                public void onShutdown() {
                }

                @Override
                public String startupLine() {
                    return null;
                }
            });
            assertEquals(Sjws_nettask.EXIT_USAGE, code);
            assertTrue(err.toString().contains("requires --pid-file"));
        }
        try (var server = SjwsServer.start(ServerConfig.builder(html).port(0).build())) {
            var out = new java.io.ByteArrayOutputStream();
            int code = await(server, new PrintStream(out), error, this.dir.resolve("hook.pid"), new ServiceHooks() {
                @Override
                public void onListening(SjwsServer started, Path pidFile, Duration shutdownTimeout) {
                }

                @Override
                public void onShutdown() {
                }

                @Override
                public String startupLine() {
                    return "  ";
                }
            });
            assertEquals(Sjws_nettask.EXIT_OK, code);
            assertFalse(out.toString().contains("  \n"));
        }
    }

    @Test
    void http2WithoutCleartextAndASecondChainOpen() throws Exception {
        var html = Files.createDirectory(this.dir.resolve("h2"));
        Files.writeString(html.resolve("index.html"), "hi");
        try (var server = SjwsServer.start(ServerConfig.builder(html).port(0).h2Enabled(true).h2Port(0).build())) {
            var described = server.describe();
            assertTrue(described.contains("HTTP/2 on tcp/"), described);
            assertFalse(described.contains("h2c"), described);
            var first = server.chainControl(RequestFilters.create(), Set.of());
            assertSame(first, server.chainControl(RequestFilters.create(), Set.of()));
        }
    }

    @Test
    void drainClosesAHalfReadConnection() throws Exception {
        try (var reaper = new ConnectionReaper(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
                SocketChannel channel = SocketChannel.open()) {
            reaper.beginDrain();
            var timer = reaper.register(channel);
            timer.head();
            reaper.sweep(System.nanoTime());
            assertEquals(0, reaper.tracked());
        }
    }

    @SuppressWarnings("unchecked")
    private static int await(SjwsServer server, PrintStream out, PrintStream err, Path pid, ServiceHooks hooks)
            throws Exception {
        Method method = Sjws_nettask.class.getDeclaredMethod("awaitServing", SjwsServer.class, ServerConfig.class,
                PrintStream.class, PrintStream.class, Consumer.class, Duration.class, Path.class, ServiceHooks.class,
                long.class, boolean.class);
        method.setAccessible(true);
        Consumer<SjwsServer> started = SjwsServer::close;
        return (int) method.invoke(null, server, server.config(), out, err, started, Duration.ofSeconds(1), pid, hooks,
                -1L, true);
    }
}
