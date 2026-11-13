/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.NodeSettings;
import sjws_nettask.cp.KeyRing;
import sjws_nettask.cp.LastGoodFiles;

/** Missing-config recovery of a pinned last-good keeps the data role the bootstrap selects. */
class PinnedRecoveryRoleTest {

    @TempDir
    Path dir;

    @Test
    void pinnedRecoveryKeepsTheDataRole() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var local = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertEquals(NodeSettings.Mode.STANDALONE, local.document().node().mode());
        long now = System.currentTimeMillis();
        var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("root.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        var snap = keys.sign(local.document(), 4, 1L, 1L, now, now + 3_600_000L, 0L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        Files.delete(yaml);

        var args = new String[] {"--config=" + yaml, "--state-dir=" + state};
        var recovered = ConfigLoader.load(args, Map.of(), pin);
        assertTrue(recovered.ok(), recovered.errors().toString());
        assertEquals(NodeSettings.Mode.DATA, recovered.document().node().mode());

        var opt = Daemon.parse(new String[] {"--bootstrap=" + dir.resolve("bootstrap"), "--config=" + yaml},
                Map.of());
        var err = new ByteArrayOutputStream();
        var choice = Sjws_nettask.RoleChoice.resolve(opt, recovered.document(),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertTrue(choice.ok(), err.toString(StandardCharsets.UTF_8));
        assertEquals("data", choice.processMode());

        var unpinned = ConfigLoader.load(args, Map.of());
        assertTrue(!unpinned.ok(), "without the pin the signed last-good is still not a boot source");
    }
}
