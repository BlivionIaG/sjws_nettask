package sjws_nettask.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.ServerConfig.AccessLogFormat;

/** #406 / #428: openedKey must describe the inode of the stream we keep. */
final class AccessLogOpenKeyTest {

    @TempDir
    Path dir;

    @Test
    void openFileWithKeyMatchesPathKeyWhenStable() throws Exception {
        Path path = dir.resolve("access.log");
        var opened = AccessLog.openFileWithKey(path);
        try {
            Object pathKey = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            Assumptions.assumeTrue(pathKey != null, "FS without file keys");
            assertEquals(pathKey, opened.key);
            opened.stream.write("x\n".getBytes());
            opened.stream.flush();
            assertTrue(Files.size(path) > 0);
        } finally {
            opened.stream.close();
        }
    }

    @Test
    void startRecordsKeyMatchingOpenedFile() throws Exception {
        Path path = dir.resolve("started.log");
        try (var log = AccessLog.start(path.toString(), AccessLogFormat.COMMON)) {
            log.offer("127.0.0.1", "GET", "/", "HTTP/1.1", 200, 1, 0, null, null);
            // drain
            for (int i = 0; i < 50 && Files.notExists(path); i++) {
                Thread.sleep(20);
            }
            assertNotNull(log);
            assertTrue(Files.exists(path));
        }
    }

    @Test
    void forcesRetryWhenTestSeamSet() throws Exception {
        Path path = dir.resolve("race.log");
        AccessLog.openKeyRaceProbe = new AccessLog.OpenKeyRaceProbe() {
            private boolean once = true;

            @Override
            boolean consumeForceRace() {
                if (once) {
                    once = false;
                    return true;
                }
                return false;
            }
        };
        try {
            var opened = AccessLog.openFileWithKey(path);
            try {
                assertNotNull(opened.stream);
                Object pathKey = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
                Assumptions.assumeTrue(pathKey != null, "FS without file keys");
                assertEquals(pathKey, opened.key);
            } finally {
                opened.stream.close();
            }
        } finally {
            AccessLog.openKeyRaceProbe = AccessLog.OpenKeyRaceProbe.NONE;
        }
    }

    @Test
    void reopenRecreatesMissingSymlinkTargetAs0640() throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX required");
        Path target = dir.resolve("access.log");
        Path link = dir.resolve("current.log");
        Files.writeString(target, "");
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        Files.createSymbolicLink(link, target.getFileName());
        try (var log = AccessLog.start(link.toString(), AccessLogFormat.COMMON)) {
            log.offer("127.0.0.1", "GET", "/a", "HTTP/1.1", 200, 1, 0, "-", "-");
            for (int i = 0; i < 100 && !(Files.exists(target) && Files.size(target) > 0); i++) {
                Thread.sleep(20);
            }
            Path rotated = dir.resolve("access.log.1");
            Files.move(target, rotated);
            // Target missing; symlink remains — reopen must recreate target as 0640, not umask default.
            log.requestReopen();
            for (int i = 0; i < 100 && !Files.exists(target); i++) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(target), "target recreated");
            var perms = Files.getPosixFilePermissions(target);
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"), perms, perms.toString());
            log.offer("127.0.0.1", "GET", "/b", "HTTP/1.1", 200, 1, 0, "-", "-");
            for (int i = 0; i < 100 && !Files.readString(target).contains("/b"); i++) {
                Thread.sleep(20);
            }
            assertTrue(Files.readString(target).contains("GET /b"), Files.readString(target));
        }
    }

    @Test
    void openFileCreatesMissingSymlinkTargetAs0640() throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX required");
        Path target = dir.resolve("fresh.log");
        Path link = dir.resolve("fresh.link");
        Files.createSymbolicLink(link, target.getFileName());
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.notExists(target));
        try (var out = AccessLog.openFile(link)) {
            out.write("x\n".getBytes());
            out.flush();
        }
        assertTrue(Files.exists(target));
        var perms = Files.getPosixFilePermissions(target);
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"), perms, perms.toString());
    }

    @Test
    void openFileThroughExistingSymlinkTargetLeavesPerms() throws Exception {
        Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX required");
        Path target = dir.resolve("preexist.log");
        Path link = dir.resolve("preexist.link");
        Files.writeString(target, "");
        Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        Files.createSymbolicLink(link, target.getFileName());
        AccessLog.ensureSymlinkTargetCreated(link); // existing target branch
        assertFalse(AccessLog.OpenKeyRaceProbe.NONE.consumeForceRace());
        try (var out = AccessLog.openFile(link)) {
            out.write("y\n".getBytes());
            out.flush();
        }
        var perms = Files.getPosixFilePermissions(target);
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"), perms, perms.toString());
    }

    @Test
    void forcesThreeRacesThenOpens() throws Exception {
        Path path = dir.resolve("triple-race.log");
        AccessLog.openKeyRaceProbe = new AccessLog.OpenKeyRaceProbe() {
            private int left = 3;

            @Override
            boolean consumeForceRace() {
                if (left > 0) {
                    left--;
                    return true;
                }
                return false;
            }
        };
        try {
            var opened = AccessLog.openFileWithKey(path);
            try {
                assertNotNull(opened.stream);
            } finally {
                opened.stream.close();
            }
        } finally {
            AccessLog.openKeyRaceProbe = AccessLog.OpenKeyRaceProbe.NONE;
        }
    }
}
