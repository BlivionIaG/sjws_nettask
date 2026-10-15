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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.clusterharness.ClusterHarness.DataRole;

/**
 * Regression guard for #196: a {@link ClusterClock#advance} that lands between two voters' ticks
 * must not trigger an election. A 1 ms gap after every voter's tick makes the race likely: with
 * the live-clock RaftClock (before #197) 25 of 90 repetitions failed with "not converged on 2", so
 * 30 repetitions catch a regression with odds far above 99.9%. With the fix all pass, about 0.1 s each.
 */
@Tag("cluster")
@Timeout(20)
class ClusterTickGapStressTest {

    @TempDir
    Path root;

    @RepeatedTest(30)
    void clockAdvanceMidRoundKeepsTheLeader() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello\n", StandardCharsets.UTF_8);
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cluster = ClusterHarness.open(3, 2, root, DataRole.DATA, clock)) {
            var cp = (CpStoreControlPlane) cluster.control();
            cp.interTickGap(Duration.ofMillis(1));
            long v1 = cp.push(yaml(""));
            cp.awaitConverged(v1, Duration.ofSeconds(2));
            long term = cp.leader().orElseThrow().term();
            cp.partitionDataFromControl();
            clock.advance(Duration.ofSeconds(5));
            cp.heal();
            long v2 = cp.push(yaml("  max_connections: 2048\n"));
            cp.awaitConverged(v2, Duration.ofSeconds(2));
            assertEquals(term, cp.leader().orElseThrow().term(), "clock advance mid-round started an election");
        }
    }

    private String yaml(String extra) {
        return "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n" + extra;
    }
}
