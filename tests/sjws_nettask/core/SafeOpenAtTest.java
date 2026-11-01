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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@code openat}/{@code O_NOFOLLOW} path (macOS production; forced on Linux in tests).
 * The injected swap hook freezes the race between components so this fails on the path-based
 * fallback and passes with {@link SafeOpenAt}.
 */
public class SafeOpenAtTest {

    static final String SECRET = "SECRET-OUTSIDE-ROOT-0123456789";

    @TempDir
    Path base;
    Path root;
    Path outside;

    @BeforeEach
    void setUp() throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        this.root = Files.createDirectories(this.base.resolve("root")).toRealPath();
        this.outside = Files.createDirectories(this.base.resolve("outside")).toRealPath();
        Files.writeString(this.outside.resolve("a.txt"), SECRET);
        Files.createDirectories(this.root.resolve("d/e"));
        Files.writeString(this.root.resolve("d/e/a.txt"), "public-inside-root-abcdefghijk");
    }

    @AfterEach
    void clearHook() {
        SafeOpenAt.betweenComponents = null;
    }

    static BasicFileAttributes attrs(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    Body.FileBody body(Path realPath, Path identityOf) throws IOException {
        var a = attrs(identityOf);
        return new Body.FileBody(realPath, 0, a.size(), Validator.of(a), this.root);
    }

    static String read(java.nio.channels.FileChannel ch) throws IOException {
        var buf = ByteBuffer.allocate((int) ch.size());
        ch.read(buf, 0);
        return new String(buf.array(), StandardCharsets.UTF_8);
    }

    @Test
    void openAtServesTheResolvedFile() throws IOException {
        var f = this.root.resolve("d/e/a.txt");
        try (var ch = SafeOpen.open(body(f, f), false, true)) {
            assertEquals("public-inside-root-abcdefghijk", read(ch));
        }
    }

    /**
     * Deterministic stand-in for the parent-symlink race: after the root handle is open and before
     * the next component, swap {@code d} for a symlink to {@code outside}. Path-based open follows
     * it; {@code openat}+{@code O_NOFOLLOW} must refuse.
     */
    @Test
    void injectedParentSwapIsRejectedByOpenAt() throws IOException {
        var f = this.root.resolve("d/e/a.txt");
        var expected = body(f, f);
        var swaps = new AtomicInteger();
        SafeOpenAt.betweenComponents = () -> {
            if (swaps.getAndIncrement() != 0) {
                return;
            }
            try {
                var d = this.root.resolve("d");
                Files.move(d, this.root.resolve("d.real"));
                Files.createSymbolicLink(d, this.outside);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        assertThrows(SafeOpen.FileChangedException.class, () -> SafeOpen.open(expected, false, true).close());
        assertTrue(swaps.get() >= 1);
    }

    /**
     * Live race against the openat path (same shape as {@link SafeOpenTest#parentSymlinkRaceNeverLeaksOverHttp1}).
     * On master, macOS takes the path fallback and that race test fails; with openat it must not leak.
     */
    @Test
    void parentSymlinkRaceNeverLeaksWithOpenAt() throws Exception {
        var d = this.root.resolve("d");
        var dReal = this.root.resolve("d.real");
        Files.writeString(d.resolve("a.txt"), "public-inside-root-abcdefghijk");
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var flipper = Thread.ofPlatform().start(() -> SafeOpenTest.flip(d, dReal, this.outside, stop));
        int leaked = 0;
        int served = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        try {
            while (System.nanoTime() < deadline) {
                var f = this.root.resolve("d/a.txt");
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Body.FileBody file;
                try {
                    file = body(f, f);
                } catch (IOException e) {
                    continue;
                }
                try (var ch = SafeOpen.open(file, false, true)) {
                    served++;
                    if (read(ch).contains("SECRET-OUTSIDE")) {
                        leaked++;
                    }
                } catch (IOException e) {
                    // refused or raced
                }
            }
        } finally {
            stop.set(true);
            flipper.join();
        }
        assertEquals(0, leaked, "served " + served);
        assertTrue(served > 0, "the race must also let legitimate requests through");
    }

    @Test
    void leafSymlinkRejected() throws IOException {
        var f = this.root.resolve("d/e/a.txt");
        var expected = body(f, this.outside.resolve("a.txt"));
        Files.delete(f);
        Files.createSymbolicLink(f, this.outside.resolve("a.txt"));
        assertThrows(SafeOpen.FileChangedException.class, () -> SafeOpen.open(expected, false, true).close());
    }

    @Test
    void sameKeyParsesUnixFileKey() throws IOException {
        var f = this.root.resolve("d/e/a.txt");
        var key = attrs(f).fileKey();
        Assumptions.assumeTrue(key != null);
        var s = key.toString();
        var left = s.indexOf("dev=");
        var mid = s.indexOf(",ino=");
        var right = s.indexOf(')', mid);
        long dev = Long.parseLong(s.substring(left + 4, mid), 16);
        long ino = Long.parseLong(s.substring(mid + 5, right));
        assertTrue(SafeOpenAt.sameKey(key, dev, ino));
        assertTrue(!SafeOpenAt.sameKey(key, dev, ino + 1));
    }
}
