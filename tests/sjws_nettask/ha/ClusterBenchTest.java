package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sjws_nettask.cluster.ClusterKey;

@Timeout(15)
class ClusterBenchTest {

    private static final ClusterKey KEY = ClusterKey.of("0123456789abcdef".getBytes(StandardCharsets.UTF_8), null);

    @Test
    void clusterModeRunsASecondNodeAndSingleModeDoesNot() throws Exception {
        var root = Files.createTempDirectory("ha-bench");
        Files.writeString(root.resolve("index.html"), "x");
        var single = BenchServer.launch(new BenchServer.Options("single", 0, root.toString()));
        try {
            assertTrue(single.tcpPort() > 0);
            assertEquals(0, single.heartbeats());
            assertEquals("single", single.mode());
        } finally {
            single.close();
        }
        var clusterOptions = new BenchServer.Options("cluster", 0, root.toString());
        assertThrows(IllegalArgumentException.class, () -> BenchServer.launch(clusterOptions));
        var cluster = BenchServer.launch(clusterOptions, KEY);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (cluster.heartbeats() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(cluster.heartbeats() > 0);
            assertEquals("cluster", cluster.mode());
        } finally {
            cluster.close();
        }
        assertThrows(IllegalArgumentException.class, () -> ClusterSide.start(Duration.ZERO, KEY));
        assertThrows(NullPointerException.class, () -> ClusterSide.start(Duration.ofMillis(20), null));
        var secret = "cli-secret-value-not-logged-99";
        var refused = assertThrows(IllegalArgumentException.class,
                () -> BenchServer.Options.parse(new String[] {"--cluster-key=" + secret}));
        assertFalse(refused.getMessage().contains(secret));
        assertThrows(IllegalArgumentException.class,
                () -> BenchServer.launch(new BenchServer.Options("single", 0, root.toString()), KEY));
        var fromEnv = ClusterKey.fromEnvironment(Map.of(ClusterKey.ENV_CURRENT, "env-cluster-key-0123"));
        assertFalse(fromEnv.toString().contains("env-cluster-key-0123"));
    }
}
