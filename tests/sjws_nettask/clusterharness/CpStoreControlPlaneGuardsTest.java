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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Focused guards and error paths on {@link CpStoreControlPlane} that scenario tests only
 * cover indirectly (or not at all). Kill/heal catch-up stays in {@link CpVoterRestartTest}.
 */
@Tag("cluster")
@Timeout(30)
class CpStoreControlPlaneGuardsTest {

    @TempDir
    Path dir;

    @Test
    void startRejectsCountsOtherThanThreeOrFiveAndRefusesASecondStart() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cp = new CpStoreControlPlane(Files.createDirectories(dir.resolve("cp")))) {
            var bad = assertThrows(IllegalArgumentException.class, () -> cp.start(4, clock));
            assertTrue(bad.getMessage().contains("3 or 5"), bad.getMessage());
            var nodes = cp.start(3, clock);
            assertEquals(3, nodes.size());
            assertTrue(cp.available());
            assertTrue(cp.leader().isPresent());
            assertEquals(0, cp.appliedVersion("cp-1"));
            assertThrows(IllegalStateException.class, () -> cp.start(3, clock));
        }
    }

    @Test
    void missingVersionHashNodeAndRollbackFailClosed() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var cp = new CpStoreControlPlane(Files.createDirectories(dir.resolve("cp")))) {
            cp.start(3, clock);
            assertThrows(IllegalArgumentException.class, () -> cp.appliedVersion("no-such-voter"));
            assertThrows(IllegalArgumentException.class, () -> cp.contentHash(99));
            assertThrows(IllegalArgumentException.class, () -> cp.delay("no-such-data", Duration.ofMillis(1)));
            var rollback = assertThrows(IllegalStateException.class, () -> cp.rollback(1));
            assertTrue(rollback.getMessage().startsWith("rollback:"), rollback.getMessage());

            long version = cp.push("server:\n  port: 0\n  root: " + dir.toAbsolutePath() + "\n");
            var hash = cp.contentHash(version);
            assertEquals(64, hash.length(), hash);
            assertThrows(IllegalArgumentException.class, () -> cp.contentHash(version + 1));
        }
    }
}
