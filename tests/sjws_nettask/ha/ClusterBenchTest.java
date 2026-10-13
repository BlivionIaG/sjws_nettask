package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ClusterBenchTest {

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
        var cluster = BenchServer.launch(new BenchServer.Options("cluster", 0, root.toString()));
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (cluster.heartbeats() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(cluster.heartbeats() > 0);
            assertEquals("cluster", cluster.mode());
            var side = ClusterSide.start();
            try {
                var voters = side.voters();
                assertEquals(3, voters.voters().size());
                var ids = voters.members();
                var a = ids.get(0).id();
                var b = ids.get(1).id();
                var w = ids.get(2).id();
                assertTrue(ids.get(2).witness());
                assertFalse(ids.get(0).witness());
                assertTrue(voters.policy().hasQuorum(voters.voters(), Set.of(a, b)));
                assertTrue(voters.policy().hasQuorum(voters.voters(), Set.of(a, w)));
                assertFalse(voters.policy().hasQuorum(voters.voters(), Set.of(a)));
                assertFalse(voters.policy().hasQuorum(voters.voters(), Set.of(w)));
            } finally {
                side.close();
            }
        } finally {
            cluster.close();
        }
        assertThrows(IllegalArgumentException.class, () -> ClusterSide.start(Duration.ZERO));
    }
}
