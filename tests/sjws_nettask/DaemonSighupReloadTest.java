package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: daemon child {@code SIGHUP} → {@link ReloadHook} → live config swap.
 * <p>
 * The daemon child must not leave {@code SIGHUP} ignored. HotSpot will not replace {@code SIG_IGN},
 * and CI runners often inherit ignore; {@link ReloadHook#installSighup()} restores {@code SIG_DFL}
 * first. This test forces that inheritance on the parent so the child path is exercised the same
 * way as under GitHub Actions.
 */
@Timeout(60)
@EnabledOnOs(OS.LINUX)
class DaemonSighupReloadTest {

    private static final Transport.Factory NO_H3 = (c, h) -> {
        throw new AssertionError("HTTP/3 must not be created");
    };

    @TempDir
    Path tmp;

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    void daemonChildSighupAppliesConfigAndKeepsInvalidReload() throws Exception {
        var first = Files.createDirectory(tmp.resolve("one"));
        var second = Files.createDirectory(tmp.resolve("two"));
        Files.writeString(first.resolve("marker.txt"), "one");
        Files.writeString(second.resolve("marker.txt"), "two");
        int port = freePort();
        var yaml = tmp.resolve("sjws.yaml");
        writeConfig(yaml, port, first);

        var pid = tmp.resolve("daemon.pid");
        var log = tmp.resolve("daemon.log");

        // Inherit SIG_IGN into the child the way CI does. Without restore-in-installSighup the
        // hangup is a no-op and the assertions below fail on master.
        assertTrue(Daemon.ignoreSignal("HUP"), "need sun.misc.Signal to simulate inherited ignore");

        int code = Sjws_nettask.run(new String[] {
                "--daemon",
                "--config=" + yaml,
                "--shutdown-timeout=2",
                "--pid-file=" + pid,
                "--log-file=" + log
        }, Map.of(), out, err, NO_H3, s -> {
        });
        assertEquals(Sjws_nettask.EXIT_OK, code, errBytes.toString(StandardCharsets.UTF_8)
                + outBytes.toString(StandardCharsets.UTF_8)
                + (Files.exists(log) ? Files.readString(log) : ""));
        try {
            long child = waitUntilRunning(pid);
            waitFor(() -> bodyIs(port, "/marker.txt", "one"), 5_000, "initial root");

            writeConfig(yaml, port, second);
            var kill = new ProcessBuilder(TestCommands.resolve("kill"), "-HUP", Long.toString(child)).start();
            assertEquals(0, kill.waitFor());
            waitFor(() -> bodyIs(port, "/marker.txt", "two"), 5_000,
                    "SIGHUP must apply the new root (child must not leave HUP ignored)");
            assertTrue(ProcessHandle.of(child).orElseThrow().isAlive(), "SIGHUP must not kill the child");

            Files.writeString(yaml, "server: [\n");
            kill = new ProcessBuilder(TestCommands.resolve("kill"), "-HUP", Long.toString(child)).start();
            assertEquals(0, kill.waitFor());
            // Give the child a moment to attempt the bad reload, then confirm the live snapshot stayed.
            Thread.sleep(300);
            assertEquals("two", HttpTestSupport.get(port, "/marker.txt", 2_000).bodyText(),
                    "invalid YAML on reload must keep the previous snapshot");
            assertTrue(ProcessHandle.of(child).orElseThrow().isAlive());
            assertTrue(Files.readString(log).contains("reload") || Files.readString(log).toLowerCase().contains("fail")
                    || Files.readString(log).contains("ERROR"),
                    "log should mention the failed reload:\n" + Files.readString(log));

            outBytes.reset();
            errBytes.reset();
            assertEquals(Sjws_nettask.EXIT_OK, Sjws_nettask.run(
                    new String[] {"--stop", "--pid-file=" + pid, "--shutdown-timeout=2"}, Map.of(), out, err, NO_H3,
                    s -> {
                    }));
            waitFor(() -> !ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false), 5_000, "process exit");
        } finally {
            Daemon.stop(pid, Duration.ofSeconds(2), out, err);
        }
    }

    private static void writeConfig(Path yaml, int port, Path root) throws IOException {
        Files.writeString(yaml, "server:\n  port: " + port + "\n  root: " + root.toAbsolutePath() + "\n");
    }

    private static boolean bodyIs(int port, String path, String expected) {
        try {
            var response = HttpTestSupport.get(port, path, 1_000);
            return response.statusCode() == 200 && expected.equals(response.bodyText());
        } catch (IOException e) {
            return false;
        }
    }

    private static long waitUntilRunning(Path pid) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            var snap = PidFile.read(pid);
            if (snap.running()) {
                return snap.pid();
            }
            Thread.sleep(20);
        }
        fail("pid file did not become ready: " + PidFile.read(pid));
        return -1;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs, String what) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for " + what);
    }

    private static int freePort() throws Exception {
        try (var channel = java.nio.channels.ServerSocketChannel.open()) {
            channel.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            return ((java.net.InetSocketAddress) channel.getLocalAddress()).getPort();
        }
    }
}
