package sjws_nettask.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.ServerConfig.AccessLogFormat;

/** #406 / #428: resolved-leaf open, 0640 create, rotation key binding. */
final class AccessLogOpenKeyTest {

    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> MODE_0640 = PosixFilePermissions.fromString("rw-r-----");

    @TempDir
    Path dir;

    private static void assumePosix(Path dir) {
        Assumptions.assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX required");
    }

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

    @Test
    void reopenRecreatesMissingSymlinkTargetAs0640() throws Exception {
        assumePosix(dir);
        Path target = dir.resolve("access.log");
        Path link = dir.resolve("current.log");
        Files.writeString(target, "");
        Files.setPosixFilePermissions(target, MODE_0640);
        Files.createSymbolicLink(link, target.getFileName());
        try (var log = AccessLog.start(link.toString(), AccessLogFormat.COMMON)) {
            log.offer("127.0.0.1", "GET", "/a", "HTTP/1.1", 200, 1, 0, "-", "-");
            for (int i = 0; i < 100 && !(Files.exists(target) && Files.size(target) > 0); i++) {
                Thread.sleep(20);
            }
            Path rotated = dir.resolve("access.log.1");
            Files.move(target, rotated);
            log.requestReopen();
            for (int i = 0; i < 100 && !Files.exists(target); i++) {
                Thread.sleep(20);
            }
            assertTrue(Files.exists(target), "target recreated");
            assertEquals(MODE_0640, Files.getPosixFilePermissions(target));
            log.offer("127.0.0.1", "GET", "/b", "HTTP/1.1", 200, 1, 0, "-", "-");
            for (int i = 0; i < 100 && !Files.readString(target).contains("/b"); i++) {
                Thread.sleep(20);
            }
            assertTrue(Files.readString(target).contains("GET /b"), Files.readString(target));
        }
    }

    @Test
    void openFileCreatesMissingSymlinkTargetAs0640() throws Exception {
        assumePosix(dir);
        Path target = dir.resolve("fresh.log");
        Path link = dir.resolve("fresh.link");
        Files.createSymbolicLink(link, target.getFileName());
        assertTrue(Files.notExists(target));
        try (var out = AccessLog.openFile(link)) {
            out.write("x\n".getBytes());
            out.flush();
        }
        assertTrue(Files.exists(target));
        assertEquals(MODE_0640, Files.getPosixFilePermissions(target));
    }

    @Test
    void openFileThroughExistingSymlinkTargetLeavesPerms() throws Exception {
        assumePosix(dir);
        Path target = dir.resolve("preexist.log");
        Path link = dir.resolve("preexist.link");
        Files.writeString(target, "");
        Files.setPosixFilePermissions(target, MODE_0640);
        Files.createSymbolicLink(link, target.getFileName());
        assertFalse(AccessLog.OpenKeyRaceProbe.NONE.consumeForceRace());
        try (var out = AccessLog.openFile(link)) {
            out.write("y\n".getBytes());
            out.flush();
        }
        assertEquals(MODE_0640, Files.getPosixFilePermissions(target));
    }

    @Test
    void symlinkedParentDirectoryOpens() throws Exception {
        assumePosix(dir);
        Path realParent = Files.createDirectories(dir.resolve("real-parent"));
        Path parentLink = dir.resolve("parent-link");
        Files.createSymbolicLink(parentLink, realParent.getFileName());
        Path log = parentLink.resolve("under-link.log");
        try (var out = AccessLog.openFile(log)) {
            out.write("z\n".getBytes());
            out.flush();
        }
        Path realLog = realParent.resolve("under-link.log");
        assertTrue(Files.exists(realLog));
        assertEquals(MODE_0640, Files.getPosixFilePermissions(realLog));
    }

    @Test
    void dirlinkDotDotCreatesAtKernelResolvedPath() throws Exception {
        assumePosix(dir);
        // nest/dirlink -> ../real, configured nest/dirlink/../x.log
        // lexical normalize lands in nest/x.log; the kernel walks dirlink to real, then .. to dir.
        Files.createDirectories(dir.resolve("real"));
        Path nest = Files.createDirectories(dir.resolve("nest"));
        Files.createSymbolicLink(nest.resolve("dirlink"), Path.of("..").resolve("real"));
        Path configured = nest.resolve("dirlink").resolve("..").resolve("x.log");
        Path lexicalWrong = nest.resolve("x.log");
        Path kernelPlace = dir.resolve("x.log");
        assertFalse(configured.normalize().equals(kernelPlace), "test must distinguish lexical normalize from kernel");
        try (var out = AccessLog.openFile(configured)) {
            out.write("k\n".getBytes());
            out.flush();
        }
        assertTrue(Files.exists(kernelPlace), "kernel-resolved " + kernelPlace);
        assertEquals(kernelPlace.toRealPath(), AccessLog.resolveLogLeaf(configured).toRealPath());
        assertEquals(MODE_0640, Files.getPosixFilePermissions(kernelPlace));
        assertTrue(Files.notExists(lexicalWrong), "no stray file at lexical " + lexicalWrong);
    }

    @Test
    void symlinkTargetDirlinkDotDotCreatesAtKernelPath() throws Exception {
        assumePosix(dir);
        Files.createDirectories(dir.resolve("real"));
        Path nest = Files.createDirectories(dir.resolve("nest"));
        Files.createSymbolicLink(nest.resolve("dirlink"), Path.of("..").resolve("real"));
        Path link = dir.resolve("current.log");
        // Symlink target itself contains dirlink/../x.log.
        Path target = Path.of("nest").resolve("dirlink").resolve("..").resolve("x.log");
        Files.createSymbolicLink(link, target);
        Path lexicalWrong = nest.resolve("x.log");
        Path kernelPlace = dir.resolve("x.log");
        try (var out = AccessLog.openFile(link)) {
            out.write("t\n".getBytes());
            out.flush();
        }
        assertTrue(Files.exists(kernelPlace), "kernel-resolved " + kernelPlace);
        assertEquals(MODE_0640, Files.getPosixFilePermissions(kernelPlace));
        assertTrue(Files.readString(kernelPlace).contains("t"));
        assertTrue(Files.notExists(lexicalWrong), "no stray file at lexical " + lexicalWrong);
    }

    @Test
    void nestedDanglingSymlinkTargetCreatedAs0640() throws Exception {
        assumePosix(dir);
        Path ultimate = dir.resolve("ultimate.log");
        Path mid = dir.resolve("mid.link");
        Path outer = dir.resolve("outer.link");
        Files.createSymbolicLink(mid, ultimate.getFileName());
        Files.createSymbolicLink(outer, mid.getFileName());
        assertTrue(Files.notExists(ultimate));
        try (var out = AccessLog.openFile(outer)) {
            out.write("n\n".getBytes());
            out.flush();
        }
        assertTrue(Files.exists(ultimate));
        assertEquals(MODE_0640, Files.getPosixFilePermissions(ultimate));
        assertTrue(Files.readString(ultimate).contains("n"));
    }

    @Test
    void absoluteSymlinkTargetCreatedAs0640() throws Exception {
        assumePosix(dir);
        Path ultimate = dir.resolve("abs-ultimate.log");
        Path link = dir.resolve("abs.link");
        Files.createSymbolicLink(link, ultimate.toAbsolutePath());
        try (var out = AccessLog.openFile(link)) {
            out.write("a\n".getBytes());
            out.flush();
        }
        assertTrue(Files.exists(ultimate));
        assertEquals(MODE_0640, Files.getPosixFilePermissions(ultimate));
        assertTrue(Files.readString(ultimate).contains("a"));
    }

    @Test
    void resolveLogLeafRejectsRootPath() {
        assertThrows(IOException.class, () -> AccessLog.resolveLogLeaf(Path.of("/")));
    }

    @Test
    void resolveLogLeafRejectsSymlinkToRoot() throws Exception {
        assumePosix(dir);
        Path link = dir.resolve("to-root.link");
        Files.createSymbolicLink(link, Path.of("/"));
        assertThrows(IOException.class, () -> AccessLog.resolveLogLeaf(link));
    }

    @Test
    void openLeafRejectsUnresolvedSymlink() throws Exception {
        assumePosix(dir);
        Path link = dir.resolve("dangling-leaf.link");
        Files.createSymbolicLink(link, Path.of("missing.log"));
        assertThrows(IOException.class, () -> AccessLog.openLeaf(link));
    }

    @Test
    void openFileCreatesMissingAncestorsAs0750() throws Exception {
        assumePosix(dir);
        Path log = dir.resolve("sub").resolve("nested").resolve("a.log");
        try (var out = AccessLog.openFile(log)) {
            out.write("x\n".getBytes());
            out.flush();
        }
        var dir0750 = PosixFilePermissions.fromString("rwxr-x---");
        assertEquals(dir0750, Files.getPosixFilePermissions(dir.resolve("sub")));
        assertEquals(dir0750, Files.getPosixFilePermissions(dir.resolve("sub").resolve("nested")));
        assertEquals(MODE_0640, Files.getPosixFilePermissions(log));
        assertTrue(Files.readString(log).contains("x"));
    }

    @Test
    void openFileRejectsParentThatIsAFile() throws Exception {
        assumePosix(dir);
        Path notDir = dir.resolve("not-a-dir");
        Files.writeString(notDir, "x");
        assertThrows(FileAlreadyExistsException.class, () -> AccessLog.openFile(notDir.resolve("x.log")));
    }

    @Test
    void closePreservesCallerInterruptStatus() throws Exception {
        Path path = dir.resolve("close-intr.log");
        var log = AccessLog.start(path.toString(), AccessLogFormat.COMMON);
        log.offer("127.0.0.1", "GET", "/", "HTTP/1.1", 200, 1, 0, null, null);
        for (int i = 0; i < 50 && !Files.exists(path); i++) {
            Thread.sleep(20);
        }
        Thread.currentThread().interrupt();
        log.close();
        assertTrue(Thread.interrupted(), "close must preserve the caller interrupt status");
    }

    @Test
    void symlinkLoopFailsAndReopenKeepsPreviousStream() throws Exception {
        assumePosix(dir);
        Path a = dir.resolve("loop-a.log");
        Path b = dir.resolve("loop-b.log");
        Files.writeString(a, "seed\n");
        Files.setPosixFilePermissions(a, MODE_0640);
        try (var log = AccessLog.start(a.toString(), AccessLogFormat.COMMON)) {
            log.offer("127.0.0.1", "GET", "/ok", "HTTP/1.1", 200, 1, 0, "-", "-");
            for (int i = 0; i < 100 && Files.size(a) <= 5; i++) {
                Thread.sleep(20);
            }
            assertTrue(Files.readString(a).contains("seed"));
            // Replace the configured path with a symlink loop. Reopen must fail closed and keep
            // the previous stream (do not set failed; the force flag must be consumed).
            Files.delete(a);
            Files.createSymbolicLink(a, b.getFileName());
            Files.createSymbolicLink(b, a.getFileName());
            log.requestReopen();
            for (int i = 0; i < 100 && log.reopenForcedPending(); i++) {
                Thread.sleep(20);
            }
            assertFalse(log.reopenForcedPending(), "failed reopen must consume the force flag");
            long dropped = log.dropped();
            log.offer("127.0.0.1", "GET", "/after-loop", "HTTP/1.1", 200, 1, 0, "-", "-");
            // offer() increments dropped synchronously if failed was set.
            assertEquals(dropped, log.dropped(), "failed reopen must not mark the log failed");
            assertThrows(FileSystemException.class, () -> AccessLog.resolveLogLeaf(a));
            assertThrows(FileSystemException.class, () -> AccessLog.openFile(a));
        }
    }
    @Test
    void openFileWritesThroughProcFdPipe() throws Exception {
        assumePosix(dir);
        Assumptions.assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "procfs required");
        Set<String> beforePipes = pipeTargets();
        Path dest = dir.resolve("from-pipe.log");
        Process cat = new ProcessBuilder("cat").redirectOutput(dest.toFile()).start();
        try {
            Path fdLink = newPipeFd(beforePipes);
            Assumptions.assumeTrue(fdLink != null, "could not find the cat pipe write fd");
            try (var out = AccessLog.openFile(fdLink)) {
                out.write("piped-line\n".getBytes());
                out.flush();
            }
        } finally {
            cat.getOutputStream().close();
            if (!cat.waitFor(2, TimeUnit.SECONDS)) {
                cat.destroyForcibly();
            }
        }
        assertTrue(Files.exists(dest) && Files.readString(dest).contains("piped-line"),
                Files.exists(dest) ? Files.readString(dest) : "missing " + dest);
    }

    @Test
    void resolveAndOpenDevStdout() throws Exception {
        Path stdout = Path.of("/dev/stdout");
        Assumptions.assumeTrue(Files.isSymbolicLink(stdout), "/dev/stdout is not a symlink");
        Path leaf = AccessLog.resolveLogLeaf(stdout);
        assertNotNull(leaf);
        try (var out = AccessLog.openFile(stdout)) {
            assertNotNull(out);
        }
    }

    private static Set<String> pipeTargets() throws Exception {
        Set<String> targets = new HashSet<>();
        try (var fds = Files.list(Path.of("/proc/self/fd"))) {
            for (Path fd : fds.toList()) {
                try {
                    String t = Files.readSymbolicLink(fd).toString();
                    if (t.startsWith("pipe:[")) {
                        targets.add(t);
                    }
                } catch (IOException ignored) {
                    // fd already closed
                }
            }
        }
        return targets;
    }

    private static Path newPipeFd(Set<String> beforePipes) throws Exception {
        try (var fds = Files.list(Path.of("/proc/self/fd"))) {
            for (Path fd : fds.toList()) {
                try {
                    Path target = Files.readSymbolicLink(fd);
                    if (AccessLog.isKernelMagicTarget(target) && target.toString().startsWith("pipe:[")
                            && !beforePipes.contains(target.toString())) {
                        return fd.toAbsolutePath();
                    }
                } catch (IOException ignored) {
                    // fd already closed
                }
            }
        }
        return null;
    }

}
