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

    /**
     * V3 + V7: below an execute-only (711) directory, or a 0100 root, the default is 403 (no weaker
     * by-path fallback an attacker could force); with {@code --allow-unlistable-dirs} the files are served.
     */
    @ParameterizedTest(name = "allowUnlistable={0}")
    @ValueSource(booleans = {false, true})
    void executeOnlyDirectories(boolean allowUnlistable) throws Exception {
        Assumptions.assumeTrue(SafeOpen.SECURE, "only secure mode can tell");
        var xo = Files.createDirectories(this.root.resolve("xonly"));
        Files.writeString(xo.resolve("f.txt"), "in x-only dir");
        Files.writeString(this.root.resolve("top.txt"), "top file");
        var h = new StaticFileHandler(this.root, allowUnlistable);
        assertEquals(allowUnlistable, h.allowUnlistableDirs());
        chmod(xo, "--x--x--x");
        var wire = http1(h, "/xonly/f.txt");
        if (allowUnlistable) {
            assertTrue(wire.startsWith("HTTP/1.1 200 ") && wire.endsWith("in x-only dir"), wire);
        } else {
            assertTrue(wire.startsWith("HTTP/1.1 403 ") && !wire.contains("in x-only dir"), wire);
        }
        chmod(xo, "rwx------");
        chmod(this.root, "--x------");
        wire = http1(h, "/top.txt");
        assertTrue(wire.startsWith(allowUnlistable ? "HTTP/1.1 200 " : "HTTP/1.1 403 "), wire);
    }

    @Test
    void searchOnlyDirectoriesAreDeniedByDefault() throws Exception {
        Assumptions.assumeTrue(SafeOpen.SECURE);
        var f = Files.writeString(Files.createDirectories(this.root.resolve("a/b")).resolve("f.txt"), "deep");
        var expected = body(f, this.root);
        assertFalse(expected.allowUnlistable());
        chmod(this.root.resolve("a"), "--x------");
        var e = assertThrows(java.nio.file.AccessDeniedException.class, () -> SafeOpen.open(expected, true).close());
        assertEquals(403, SafeOpen.status(e));
        chmod(this.root.resolve("a"), "rwx------");
        chmod(this.root, "--x------");
        assertThrows(java.nio.file.AccessDeniedException.class, () -> SafeOpen.open(expected, true).close());
    }

    /** With the opt-in, search-only directories are walked by the verified path fallback. */
    @ParameterizedTest(name = "secure={0}")
    @ValueSource(booleans = {true, false})
    void optInWalksSearchOnlyDirectories(boolean secure) throws Exception {
        var f = Files.writeString(Files.createDirectories(this.root.resolve("a/b")).resolve("f.txt"), "deep");
        var a = Files.readAttributes(f, java.nio.file.attribute.BasicFileAttributes.class);
        var expected = new Body.FileBody(f, 0, a.size(), Validator.of(a), this.root, true);
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
                java.nio.file.attribute.BasicFileAttributes.class)), this.root, true); // identity alone would pass
        assertThrows(SafeOpen.FileChangedException.class, () -> SafeOpen.open(forged, secure).close());
    }

    /** Result of {@link #searchOnlyRace}: requests answered 200, answered 403, and leaks of the outside file. */
    public record RaceResult(int tries, int served, int denied, int leaked) {
    }

    /**
     * V7 repro (Verify2SafeOpen.searchOnlyDirForcesFallbackRace): someone with write access in the root
     * makes their own directory {@code -wx} (311) and flips a directory below it to a symlink to outside
     * while {@code request} runs. Builds {@code root/x/d/a.txt} and {@code base/outside/a.txt}.
     *
     * @param request fetches {@code /x/d/a.txt}; returns the status and the body text
     */
    public static RaceResult searchOnlyRace(Path base, Path root, java.time.Duration duration,
            java.util.function.Function<String, java.util.Map.Entry<Integer, String>> request) throws Exception {
        var outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "SECRET-OUTSIDE-ROOT-0123456789");
        var x = Files.createDirectories(root.resolve("x"));
        var d = Files.createDirectories(x.resolve("d"));
        Files.writeString(d.resolve("a.txt"), "public-inside-root-abcdefghijk");
        var dReal = x.resolve("d.real");
        Files.setPosixFilePermissions(x, PosixFilePermissions.fromString("-wx------"));
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var flipper = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                try {
                    Files.move(d, dReal, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    Files.createSymbolicLink(d, outside);
                    Files.delete(d);
                    Files.move(dReal, d, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    // lost a step of the race: keep flipping
                }
            }
        });
        int tries = 0;
        int served = 0;
        int denied = 0;
        int leaked = 0;
        long end = System.nanoTime() + duration.toNanos();
        try {
            while (System.nanoTime() < end) {
                tries++;
                var r = request.apply("/x/d/a.txt");
                served += r.getKey() == 200 ? 1 : 0;
                denied += r.getKey() == 403 ? 1 : 0;
                leaked += r.getValue().contains("SECRET-OUTSIDE") ? 1 : 0;
            }
        } finally {
            stop.set(true);
            flipper.join();
            Files.setPosixFilePermissions(x, PosixFilePermissions.fromString("rwx------"));
        }
        return new RaceResult(tries, served, denied, leaked);
    }

    /** V7 inverted over HTTP/1.1: by default nothing is served below the attacker's 311 directory, nothing leaks. */
    @Test
    void searchOnlyDirectoryCannotForceTheFallback() throws Exception {
        Assumptions.assumeTrue(SafeOpen.SECURE);
        var h = new StaticFileHandler(this.root);
        var result = searchOnlyRace(this.base, this.root, java.time.Duration.ofSeconds(3), target -> {
            try {
                var wire = http1(h, target);
                return java.util.Map.entry(Integer.parseInt(wire.substring(9, 12)), wire);
            } catch (IOException e) {
                return java.util.Map.entry(0, "");
            }
        });
        assertEquals(0, result.leaked(), result.toString());
        assertEquals(0, result.served(), "no 200 below a search-only directory: " + result);
        assertTrue(result.denied() > 0, result.toString());
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
        assertTrue(SafeOpen.unlistableWarning(false).isEmpty(), "V7: off by default, nothing to warn about");
        var u = SafeOpen.unlistableWarning(true).orElseThrow();
        assertTrue(u.contains("--allow-unlistable-dirs") && u.contains("weaker"), u);
    }
}
