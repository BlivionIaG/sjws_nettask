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
package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.TestCommands;
import sjws_nettask.cp.KeyRing;
import sjws_nettask.cp.LastGoodFiles;

/** File handling for missing-config recovery: signed-only with a bootstrap pin, NOFOLLOW, owner and mode. */
class RecoveryFileHandlingTest {

    @TempDir
    Path dir;

    private Path state;
    private Path yaml;
    private ConfigDocument document;

    @BeforeEach
    void dataNode() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX filesystem");
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        state = dir.resolve("state");
        yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(args(), Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        document = loaded.document();
    }

    @Test
    void anUnsignedStateDirLastGoodIsRefusedWhenABootstrapPinIsSet() throws Exception {
        var pin = signedLastGood();
        LastGoodStore.write(LastGoodStore.file(state), document);
        Files.delete(yaml);
        var pinned = ConfigLoader.load(args(), Map.of(), pin);
        assertFalse(pinned.ok(), "with a pin only a signed last-good is a boot source");
        assertTrue(pinned.errors().get(0).contains("is not signed"), pinned.errors().toString());
        assertTrue(ConfigLoader.load(args(), Map.of()).ok(), "without a pin the unsigned file still recovers");
    }

    @Test
    void anUnsignedLegacyFileIsRefusedWhenABootstrapPinIsSet() throws Exception {
        var pin = dir.resolve("root.pin");
        var keys = keys();
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        var legacy = LastGoodStore.beside(yaml);
        LastGoodStore.write(legacy, document);
        Files.delete(yaml);
        var pinned = ConfigLoader.load(args(), Map.of(), pin);
        assertFalse(pinned.ok());
        assertTrue(pinned.errors().get(0).contains(legacy.toAbsolutePath() + " is not signed"),
                pinned.errors().toString());
        var unpinned = ConfigLoader.load(args(), Map.of());
        assertTrue(unpinned.ok(), unpinned.errors().toString());
    }

    @Test
    void aSignedLastGoodStillRecoversUnderThePin() throws Exception {
        var pin = signedLastGood();
        Files.delete(yaml);
        var pinned = ConfigLoader.load(args(), Map.of(), pin);
        assertTrue(pinned.ok(), pinned.errors().toString());
        assertEquals(NodeSettings.Mode.DATA, pinned.document().node().mode());
        assertTrue(pinned.warnings().stream().anyMatch(w -> w.contains(LastGoodStore.file(state).toAbsolutePath()
                .toString())), pinned.warnings().toString());
    }

    @Test
    void aSymlinkedPinIsRefusedOnTheRecoveryPath() throws Exception {
        var pin = signedLastGood();
        var link = dir.resolve("link.pin");
        Files.createSymbolicLink(link, pin.getFileName());
        Files.delete(yaml);
        var loaded = ConfigLoader.load(args(), Map.of(), link);
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().get(0).contains("symlink"), loaded.errors().toString());
    }

    @Test
    void aSymlinkedStateDirLastGoodIsRefused() throws Exception {
        var real = dir.resolve("elsewhere.json");
        LastGoodStore.write(real, document);
        var json = LastGoodStore.file(state);
        Files.createDirectories(json.getParent());
        Files.createSymbolicLink(json, real);
        Files.delete(yaml);
        var loaded = ConfigLoader.load(args(), Map.of());
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().get(0).contains("symlink"), loaded.errors().toString());
    }

    @Test
    void aSymlinkedLegacyFileIsRefused() throws Exception {
        var real = dir.resolve("elsewhere.last-good");
        LastGoodStore.write(real, document);
        Files.createSymbolicLink(LastGoodStore.beside(yaml), real);
        Files.delete(yaml);
        var loaded = ConfigLoader.load(args(), Map.of());
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().get(0).contains("symlink"), loaded.errors().toString());
    }

    @Test
    void aGroupOrWorldWritableLastGoodIsRefused() throws Exception {
        var json = LastGoodStore.file(state);
        LastGoodStore.write(json, document);
        Files.delete(yaml);
        for (var mode : new String[] {"rw-rw----", "rw-----w-"}) {
            Files.setPosixFilePermissions(json, PosixFilePermissions.fromString(mode));
            var loaded = ConfigLoader.load(args(), Map.of());
            assertFalse(loaded.ok(), mode);
            assertTrue(loaded.errors().get(0).contains("group- or world-writable"), loaded.errors().toString());
        }
        Files.setPosixFilePermissions(json, PosixFilePermissions.fromString("rw-------"));
        assertTrue(ConfigLoader.load(args(), Map.of()).ok());
    }

    @Test
    void aGroupWritableRecoveryDirectoryIsRefused() throws Exception {
        var json = LastGoodStore.file(state);
        LastGoodStore.write(json, document);
        Files.delete(yaml);
        Files.setPosixFilePermissions(json.getParent(), PosixFilePermissions.fromString("rwxrwx---"));
        var loaded = ConfigLoader.load(args(), Map.of());
        assertFalse(loaded.ok());
        assertTrue(loaded.errors().get(0).contains("parent directory"), loaded.errors().toString());
    }

    @Test
    void aLastGoodOwnedByAnotherUserIsRefused() throws Exception {
        assumeTrue(Files.exists(Path.of("/proc/self")), "numeric uid from /proc");
        var json = LastGoodStore.file(state);
        LastGoodStore.write(json, document);
        Files.setPosixFilePermissions(json, PosixFilePermissions.fromString("rw-r--r--"));
        Files.delete(yaml);
        var me = System.getProperty("user.name");
        assumeTrue(canChown(json, me), "sudo -n chown is not available");
        chown(json, "nobody");
        try {
            int uid = ((Integer) Files.getAttribute(json, "unix:uid")).intValue();
            int self = ((Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue();
            assertTrue(uid != self && uid != 0, "chown took effect");
            var loaded = ConfigLoader.load(args(), Map.of());
            assertFalse(loaded.ok());
            assertTrue(loaded.errors().get(0).contains("must be owned by root or this process"),
                    loaded.errors().toString());
        } finally {
            chown(json, me);
        }
    }

    @Test
    void withoutAPinAnUnsignedLastGoodStillRecovers() throws Exception {
        var json = LastGoodStore.file(state);
        LastGoodStore.write(json, document);
        Files.delete(yaml);
        var fromState = ConfigLoader.load(args(), Map.of());
        assertTrue(fromState.ok(), fromState.errors().toString());
        assertEquals(NodeSettings.Mode.DATA, fromState.document().node().mode());
        Files.delete(json);
        var legacy = LastGoodStore.beside(yaml);
        LastGoodStore.write(legacy, document);
        var fromLegacy = ConfigLoader.load(args(), Map.of());
        assertTrue(fromLegacy.ok(), fromLegacy.errors().toString());
        assertTrue(fromLegacy.warnings().stream().anyMatch(w -> w.contains(legacy.toAbsolutePath().toString())),
                fromLegacy.warnings().toString());
        Files.delete(legacy);
        var none = ConfigLoader.load(args(), Map.of());
        assertFalse(none.ok());
        assertTrue(none.errors().get(0).contains("Cannot read config file"), none.errors().toString());
    }

    private String[] args() {
        return new String[] {"--config=" + yaml, "--state-dir=" + state};
    }

    private static KeyRing keys() {
        return KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
    }

    /** Writes a signed last-good for {@link #document} into the state dir and returns its pin. */
    private Path signedLastGood() throws Exception {
        var keys = keys();
        var pin = dir.resolve("root.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var snap = keys.sign(document, 3, 1L, 1L, now, now + 3_600_000L, 0L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        return pin;
    }

    private static boolean canChown(Path path, String owner) {
        var sudo = TestCommands.find("sudo");
        if (sudo == null) {
            return false;
        }
        try {
            var proc = new ProcessBuilder(sudo, "-n", "chown", owner, path.toString()).redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes();
            return proc.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void chown(Path path, String owner) throws Exception {
        var proc = new ProcessBuilder(TestCommands.resolve("sudo"), "-n", "chown", owner, path.toString())
                .redirectErrorStream(true).start();
        String out = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(), out);
    }
}
