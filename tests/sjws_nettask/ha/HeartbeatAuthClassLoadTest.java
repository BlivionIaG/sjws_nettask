package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HA off does not load the heartbeat authenticator. The cluster-mode run is the control that
 * shows the class-load log would have named it.
 */
class HeartbeatAuthClassLoadTest {

    private static final String AUTH = "sjws_nettask.cluster.HeartbeatAuthenticator";
    private static final String KEY = "sjws_nettask.cluster.ClusterKey";
    private static final String SECRET = "classload-cluster-key-0123456789";

    @TempDir
    Path dir;

    @Test
    void singleModeLoadsNoAuthenticatorEvenWhenTheEnvKeyIsSet() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html"));
        var text = run(List.of(), "bench port=", 0, "sjws_nettask.ha.BenchServer", "--mode=single", "--port=0",
                "--root=" + root);
        assertFalse(text.contains(AUTH), text);
        assertFalse(text.contains(KEY), text);
        assertFalse(text.contains(SECRET), text);
    }

    @Test
    void clusterModeWithoutAKeyFailsClosedAndDoesNotEchoAFlag() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html-missing"));
        var text = run(List.of(), "ERROR:", 64, "sjws_nettask.ha.BenchServer", "--mode=cluster", "--port=0",
                "--root=" + root, "--cluster-key=" + SECRET);
        assertFalse(text.contains(SECRET), text);
        assertTrue(text.contains("not accepted on the command line"), text);
    }

    @Test
    void clusterModeWithoutAnEnvKeyFailsClosed() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html-nokey"));
        var text = run(List.of(), "no cluster key", 64, "sjws_nettask.ha.BenchServer", "--mode=cluster", "--port=0",
                "--root=" + root);
        assertTrue(text.contains("no cluster key"), text);
        assertFalse(text.contains(AUTH), text);
    }

    @Test
    void clusterModeWithTheEnvKeyLoadsTheAuthenticator() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html-on"));
        var text = run(List.of(SECRET), "bench port=", 0, "sjws_nettask.ha.BenchServer", "--mode=cluster", "--port=0",
                "--root=" + root);
        assertTrue(text.contains(AUTH), text);
        assertFalse(text.contains(SECRET), text);
    }

    @Test
    void configFileWithHaOffLoadsNoAuthenticator() throws Exception {
        var text = run(List.of(SECRET), "Server started:", 0, "sjws_nettask.Sjws_nettask", "--config=" + yaml(false, true));
        assertFalse(text.contains(AUTH), text);
        assertFalse(text.contains(KEY), text);
        assertFalse(text.contains(SECRET), text);
    }

    @Test
    void enabledHaWithoutAKeyFailsClosedWithoutPrintingThePreviousKey() throws Exception {
        var text = run(List.of(), "no cluster key", 64, "sjws_nettask.Sjws_nettask", "--config=" + yaml(true, false));
        assertFalse(text.contains(SECRET), text);
        assertTrue(text.contains("no cluster key"), text);
    }

    private Path yaml(boolean enabled, boolean includeKey) throws Exception {
        var root = Files.createDirectories(this.dir.resolve("site-" + enabled + "-" + includeKey));
        var yaml = this.dir.resolve("sjws-" + System.nanoTime() + ".yaml");
        var ha = "ha:\n  enabled: " + enabled + "\n";
        if (includeKey) {
            ha += "  cluster_key: \"" + SECRET + "\"\n";
        } else if (enabled) {
            ha += "  cluster_key_previous: \"" + SECRET + "\"\n";
        }
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n" + ha);
        return yaml;
    }

    /**
     * {@code envKey} is placed in {@code WEB_SERVER_HA_CLUSTER_KEY} when non-empty. The value must not
     * show up in the class-load log.
     */
    private String run(List<String> envKey, String ready, int expectExit, String... main) throws Exception {
        var log = this.dir.resolve("classes-" + System.nanoTime() + ".log");
        var cmd = new ArrayList<String>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xlog:class+load=info");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.addAll(List.of(main));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().remove("WEB_SERVER_HA_CLUSTER_KEY");
        pb.environment().remove("WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS");
        pb.environment().remove("WEB_SERVER_DIR");
        pb.environment().remove("WEB_SERVER_PORT");
        if (!envKey.isEmpty()) {
            pb.environment().put("WEB_SERVER_HA_CLUSTER_KEY", envKey.get(0));
        }
        var process = pb.start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            var text = "";
            while (System.nanoTime() < deadline) {
                text = Files.exists(log) ? Files.readString(log) : "";
                if (text.contains(ready) || !process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            Thread.sleep(200);
            text = Files.readString(log);
            assertTrue(text.contains(ready), text);
            if (expectExit != 0) {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEqualsExit(expectExit, process.exitValue(), text);
            }
            return text;
        } finally {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    private static void assertEqualsExit(int expected, int actual, String text) {
        assertTrue(expected == actual, "exit " + actual + "\n" + text);
    }
}
