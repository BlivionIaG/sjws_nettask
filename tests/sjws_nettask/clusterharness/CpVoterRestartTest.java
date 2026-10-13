/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.clusterharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** {@link CpStoreControlPlane#heal()} restarts a killed voter on its own log; it rejoins and catches up. */
@Tag("cluster")
@Timeout(30)
class CpVoterRestartTest {

    @TempDir
    Path dir;

    private String yaml(int maxConnections) {
        return "server:\n  port: 0\n  root: " + dir.toAbsolutePath() + "\n  max_connections: " + maxConnections + "\n";
    }

    @Test
    void killedVoterRestartsFromItsLogAndCatchesUp() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cp = new CpStoreControlPlane(Files.createDirectories(dir.resolve("cp")))) {
            cp.start(3, clock);
            long first = cp.push(yaml(1000));
            var killed = cp.leader().orElseThrow().id();
            awaitApplied(cp, clock, killed, first);
            cp.killLeader();
            assertEquals(-1, cp.appliedVersion(killed));
            clock.advance(Duration.ofMillis(1500));
            var next = cp.leader().orElseThrow();
            assertNotEquals(killed, next.id());
            cp.push(yaml(1001));
            long missed = cp.push(yaml(1002));
            assertTrue(missed > first);

            cp.heal();
            assertTrue(cp.appliedVersion(killed) >= 0, "restarted");
            awaitApplied(cp, clock, killed, missed);
            for (var node : cp.nodes()) {
                assertEquals(missed, cp.appliedVersion(node.id()), node.id());
            }

            // The restarted voter is a full voter again: lose the current leader, and the
            // remaining pair (including the restarted one) still has a majority.
            var second = cp.leader().orElseThrow().id();
            cp.killLeader();
            clock.advance(Duration.ofMillis(1500));
            long after = cp.push(yaml(1003));
            assertTrue(after > missed);
            for (var node : cp.nodes()) {
                if (!node.id().equals(second)) {
                    awaitApplied(cp, clock, node.id(), after);
                }
            }
        }
    }

    private static void awaitApplied(CpStoreControlPlane cp, ClusterClock clock, String id, long version)
            throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (cp.appliedVersion(id) < version) {
            if (System.nanoTime() > end) {
                throw new AssertionError(id + " applied " + cp.appliedVersion(id) + ", wanted " + version);
            }
            clock.advance(Duration.ofMillis(50));
            cp.leader();
            Thread.sleep(5);
        }
    }
}
