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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.NodeSettings;

class DataPlaneGuardTest {

    @TempDir
    java.nio.file.Path dir;

    @Test
    void tracksLastGoodExpiryAndOperatorCeiling() throws Exception {
        var clock = new AtomicLong(1_000);
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\n  max_staleness: 500ms\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, java.util.Map.of());
        assertEquals(java.time.Duration.ofMillis(500), loaded.document().node().maxStaleness());
        var guard = DataPlaneGuard.forDocument(loaded.document());
        assertTrue(guard.active());
        assertFalse(guard.hasLastGood());
        assertFalse(guard.ready());
        assertFalse(guard.readiness().serving());
        assertFalse(guard.readiness().drain());
        assertTrue(guard.readiness().retrying());

        guard.clock(clock::get);
        var snap = snapshot(2, 1_000, 2_000);
        guard.applied(snap, true);
        assertTrue(guard.hasLastGood());
        assertTrue(guard.ready());
        clock.set(1_501);
        assertTrue(guard.pastMaxStaleness(), "500ms ceiling from issuedAt");
        assertFalse(guard.ready());
        assertTrue(guard.readiness().serving());
        assertFalse(guard.readiness().drain());

        clock.set(1_000);
        guard.maxStaleness(null);
        guard.applied(snapshot(3, 1_000, 1_800), true);
        assertTrue(guard.ready());
        clock.set(1_801);
        assertTrue(guard.pastMaxStaleness());
        assertFalse(guard.ready());
        assertTrue(guard.readiness().serving());
        guard.controlPlane(false);
        assertTrue(guard.readiness().retrying());
        assertFalse(guard.readiness().drain());
    }

    @Test
    void unsignedLastGoodIsIgnoredAndStandaloneIsInactive() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, java.util.Map.of());
        sjws_nettask.config.LastGoodStore.write(loaded.document());
        assertTrue(Files.isRegularFile(sjws_nettask.config.LastGoodStore.file(state)));
        assertTrue(DataApply.tryReadLastGood(state).isEmpty());
        var guard = DataPlaneGuard.forDocument(loaded.document());
        assertFalse(guard.hasLastGood());
        assertFalse(DataPlaneGuard.inactive().active());
        assertTrue(DataPlaneGuard.inactive().ready());
        var standalone = ConfigLoader.load(new String[] {"--config=" + yaml}, java.util.Map.of());
        // still data; construct a standalone document via NodeSettings
        assertEquals(NodeSettings.Mode.DATA, standalone.document().node().mode());
        assertSame(DataPlaneGuard.INACTIVE, DataPlaneGuard.forDocument(
                standalone.document().withNode(NodeSettings.STANDALONE)));
    }

    static SignedSnapshot snapshot(long version, long issued, long expires) {
        var yaml = "schema: 1\nnode:\n  mode: data\n";
        return new SignedSnapshot(1, version, 1, 1, 0, SnapshotCrypto.KIND_FULL, "", new byte[32], issued, expires,
                new byte[16], "k1", new byte[64], 0, yaml);
    }
}
