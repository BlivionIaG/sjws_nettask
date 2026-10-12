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
package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.HTTP.Http1Writer;

/**
 * The security verification's repros (V3, V4, V6), inverted: execute-only directories are served
 * again, a file swapped for a FIFO is refused without blocking, and an open that fails after the
 * handler's decision is answered with a proper status instead of a dropped connection.
 */
public class VerificationRegressionTest {

    @TempDir
    Path base;
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        Assumptions.assumeTrue(base.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX only");
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root ignores permissions");
        this.root = Files.createDirectories(this.base.resolve("root")).toRealPath();
    }

    private final java.util.List<Path> changed = new java.util.ArrayList<>();

    @AfterEach
    void restore() throws IOException { // so @TempDir can clean up
        for (var p : this.changed.reversed()) {
            if (Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    void chmod(Path p, String perms) throws IOException {
        this.changed.add(p);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
    }

    /** Handles and writes like the HTTP/1.1 connection does; returns the bytes on the wire. */
    static String http1(StaticFileHandler h, String target) throws IOException {
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, h.handle(new Request("GET", target)), true);
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    static Body.FileBody body(Path file, Path root) throws IOException {
        var a = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        return new Body.FileBody(file, 0, a.size(), Validator.of(a), root);
    }

    /** V3 repro executeOnlyDirectories, inverted: 0111 subdirectory and 0100 root serve their files. */
    @Test
    void executeOnlyDirectoriesServeTheirFiles() throws Exception {
        var xo = Files.createDirectories(this.root.resolve("xonly"));
        Files.writeString(xo.resolve("f.txt"), "in x-only dir");
        Files.writeString(this.root.resolve("top.txt"), "top file");
        var h = new StaticFileHandler(this.root);
        chmod(xo, "--x--x--x");
        var wire = http1(h, "/xonly/f.txt");
        assertTrue(wire.startsWith("HTTP/1.1 200 ") && wire.endsWith("in x-only dir"), wire);
        chmod(xo, "rwx------");
        chmod(this.root, "--x------");
        wire = http1(h, "/top.txt");
        assertTrue(wire.startsWith("HTTP/1.1 200 ") && wire.endsWith("top file"), wire);
    }

    @ParameterizedTest(name = "secure={0}")
    @ValueSource(booleans = {true, false})
    void safeOpenWalksSearchOnlyDirectories(boolean secure) throws Exception {
        var f = Files.writeString(Files.createDirectories(this.root.resolve("a/b")).resolve("f.txt"), "deep");
        var expected = body(f, this.root);
        chmod(this.root.resolve("a"), "--x------");
        try (var ch = SafeOpen.open(expected, secure)) {
            assertEquals(4, ch.size());
        }
        // the fallback still refuses a symlinked component under the search-only directory
        chmod(this.root.resolve("a"), "rwx------");
        var outside = Files.createDirectories(this.base.resolve("outside"));
        Files.writeString(outside.resolve("f.txt"), "SECRET");
        Files.move(this.root.resolve("a/b"), this.root.resolve("a/b.real"));
        Files.createSymbolicLink(this.root.resolve("a/b"), outside);
        chmod(this.root.resolve("a"), "--x------");
        var forged = new Body.FileBody(f, 0, 4, Validator.of(Files.readAttributes(outside.resolve("f.txt"),
                java.nio.file.attribute.BasicFileAttributes.class)), this.root); // identity check alone would pass
        assertThrows(SafeOpen.FileChangedException.class, () -> SafeOpen.open(forged, secure).close());
    }

    /** V3, second half: an open that fails after the handler's 200 is answered (403/404), not dropped. */
    @Test
    void unreadableAfterTheDecisionIsA403() throws Exception {
        var f = Files.writeString(this.root.resolve("f.txt"), "x");
        var h = new StaticFileHandler(this.root);
        var response = h.handle(new Request("GET", "/f.txt"));
        assertEquals(200, response.status());
        chmod(f, "---------");
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, response, true);
        assertTrue(out.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 403 "), out.toString());
        chmod(f, "rw-------");
        Files.delete(f);
        out.reset();
        Http1Writer.write(out, response, true);
        assertTrue(out.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 404 "), out.toString());
    }

    public static void mkfifo(Path p) throws Exception {
        Process mk;
        try {
            mk = new ProcessBuilder("mkfifo", p.toString()).start();
        } catch (IOException e) {
            Assumptions.abort("no mkfifo: " + e);
            return;
        }
        Assumptions.assumeTrue(mk.waitFor(5, TimeUnit.SECONDS) && mk.exitValue() == 0, "mkfifo failed");
    }

    /** Opens the FIFO for writing, which unblocks any reader stuck in open(); a no-op if none. */
    public static void unblock(Path fifo) {
        try {
            new ProcessBuilder("sh", "-c", "exec 3<>'" + fifo + "'").start().waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // best effort
        }
    }

    /** Runs {@code action} on a thread and fails (instead of hanging the build) if it blocks. */
    static String withinSeconds(int seconds, Path fifo, java.util.concurrent.Callable<String> action) throws Exception {
        var result = new AtomicReference<Object>();
        var t = Thread.ofPlatform().daemon().start(() -> {
            try {
                result.set(action.call());
            } catch (Exception e) {
                result.set(e);
            }
        });
        t.join(TimeUnit.SECONDS.toMillis(seconds));
        boolean blocked = t.isAlive();
        if (blocked) {
            unblock(fifo);
            t.join(2000);
        }
        assertFalse(blocked, "blocked on the FIFO");
        if (result.get() instanceof Exception e) {
            throw e;
        }
        return (String) result.get();
    }

    /** V4 repro leafSwappedForFifo, inverted: refused with 404 at once, nothing blocks. */
    @Test
    void leafSwappedForAFifoIsRefusedWithoutBlocking() throws Exception {
        var f = Files.writeString(this.root.resolve("f.txt"), "regular");
        var response = new StaticFileHandler(this.root).handle(new Request("GET", "/f.txt"));
        Files.delete(f);
        mkfifo(f);
        var wire = withinSeconds(3, f, () -> {
            var out = new ByteArrayOutputStream();
            Http1Writer.write(out, response, true);
            return out.toString(StandardCharsets.ISO_8859_1);
        });
        assertTrue(wire.startsWith("HTTP/1.1 404 "), wire);
    }

    @ParameterizedTest(name = "secure={0}")
    @ValueSource(booleans = {true, false})
    void safeOpenRefusesNonRegularFilesBeforeOpening(boolean secure) throws Exception {
        var f = Files.writeString(this.root.resolve("f.txt"), "regular");
        var expected = body(f, this.root);
        Files.delete(f);
        mkfifo(f);
        var e = withinSeconds(3, f, () -> {
            try (var ch = SafeOpen.open(expected, secure)) {
                return "opened " + ch;
            } catch (SafeOpen.FileChangedException x) {
                return "refused " + x.getMessage();
            }
        });
        assertTrue(e.startsWith("refused") && e.contains("not a regular file"), e);
    }

    /** A FIFO already there when the request comes is not served either (and doesn't hang). */
    @Test
    void fifoInTheRootIsNotServed() throws Exception {
        var fifo = this.root.resolve("pipe");
        mkfifo(fifo);
        var wire = withinSeconds(3, fifo, () -> http1(new StaticFileHandler(this.root), "/pipe"));
        assertFalse(wire.startsWith("HTTP/1.1 200 "), wire);
    }

    @Test
    void openFailuresMapToStatuses() {
        assertEquals(403, SafeOpen.status(new java.nio.file.AccessDeniedException("x")));
        assertEquals(404, SafeOpen.status(new java.nio.file.NoSuchFileException("x")));
        assertEquals(404, SafeOpen.status(new SafeOpen.FileChangedException("x")));
        assertEquals(500, SafeOpen.status(new IOException("EIO")));
    }

    /** V6: the weaker fallback is announced at startup. */
    @Test
    void fallbackModeIsAnnounced() {
        assertTrue(SafeOpen.startupWarning(true).isEmpty());
        var w = SafeOpen.startupWarning(false).orElseThrow();
        assertTrue(w.contains("SecureDirectoryStream") && w.contains("untrusted"), w);
    }
}
