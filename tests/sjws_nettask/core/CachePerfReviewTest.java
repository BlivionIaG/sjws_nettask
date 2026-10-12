package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.http1.Http1Writer;

/**
 * Fixes from the independent perf review (repros ported from PerfReviewCoreTest): SF1 search-only
 * directories on cache hits, SF2 bounded loads in flight and single-flight, N4 CLOCK eviction,
 * N5 cached and uncached ETag weakness agree.
 */
class CachePerfReviewTest {

    static final Instant MTIME = Instant.parse("2001-02-03T04:05:06Z");

    @TempDir
    Path tmp;

    Path mk(Path root, String name, byte[] data) throws IOException {
        var p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
        Files.setLastModifiedTime(p, FileTime.from(MTIME));
        return p;
    }

    static Request get(String target) {
        return new Request("GET", target);
    }

    /** Status as the HTTP/1.1 writer puts it on the wire (opening a FileBody can still turn into 403). */
    static int wire(Response r) throws IOException {
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, r, false);
        return Integer.parseInt(out.toString(StandardCharsets.ISO_8859_1).substring(9, 12));
    }

    static void chmod(Path p, String mode) throws IOException {
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
    }

    static void notRoot() {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root reads anything");
        Assumptions.assumeTrue(SafeOpen.SECURE, "search-only 403 needs secure mode");
    }

    // --- SF1 -------------------------------------------------------------------------------------

    @Test
    void cacheHitHonoursADirectoryThatBecameSearchOnly() throws IOException {
        notRoot();
        var root = Files.createDirectory(tmp.resolve("www"));
        mk(root, "x/y/f.txt", "secret-ish".getBytes());
        var cache = new FileCache(1 << 20);
        var cached = new StaticFileHandler(root, false, cache);
        var plain = new StaticFileHandler(root, false);
        assertEquals(200, wire(cached.handle(get("/x/y/f.txt"))));
        assertEquals(200, wire(cached.handle(get("/x/y/f.txt"))));
        assertEquals(1, cache.hits());
        for (var dir : new String[] {"x", "x/y"}) {
            chmod(root.resolve(dir), "--x--x--x");
            try {
                assertEquals(403, wire(plain.handle(get("/x/y/f.txt"))), dir);
                assertEquals(403, wire(cached.handle(get("/x/y/f.txt"))), "cached must agree: " + dir);
                assertEquals(0, cache.size(), "the entry was dropped");
            } finally {
                chmod(root.resolve(dir), "rwxr-xr-x");
            }
            assertEquals(200, wire(cached.handle(get("/x/y/f.txt"))), "readable again: " + dir);
            assertEquals(1, cache.size());
        }
    }

    @Test
    void cacheHitHonoursASearchOnlyDocumentRoot() throws IOException {
        notRoot();
        var root = Files.createDirectory(tmp.resolve("www"));
        mk(root, "f.txt", "f".getBytes());
        var cached = new StaticFileHandler(root, false, new FileCache(1 << 20));
        assertEquals(200, wire(cached.handle(get("/f.txt"))));
        chmod(root, "--x--x--x");
        try {
            assertEquals(403, wire(cached.handle(get("/f.txt"))));
        } finally {
            chmod(root, "rwxr-xr-x");
        }
    }

    @Test
    void withUnlistableDirsAllowedTheHitNeedsNoDirectoryCheck() throws IOException {
        notRoot();
        var root = Files.createDirectory(tmp.resolve("www"));
        mk(root, "x/f.txt", "f".getBytes());
        var cache = new FileCache(1 << 20);
        var cached = new StaticFileHandler(root, true, cache);
        var plain = new StaticFileHandler(root, true);
        assertEquals(200, wire(cached.handle(get("/x/f.txt"))));
        chmod(root.resolve("x"), "--x--x--x");
        try {
            assertEquals(200, wire(plain.handle(get("/x/f.txt"))));
            assertEquals(200, wire(cached.handle(get("/x/f.txt"))));
            assertEquals(1, cache.hits());
        } finally {
            chmod(root.resolve("x"), "rwxr-xr-x");
        }
    }

    // --- SF2 -------------------------------------------------------------------------------------

    @Test
    void concurrentMissesStayWithinTheLoadBudgetAndFallBackToStreaming() throws Exception {
        var root = Files.createDirectory(tmp.resolve("www"));
        int n = 64;
        int size = 256 * 1024;
        for (int i = 0; i < n; i++) {
            mk(root, "f" + i + ".bin", new byte[size]);
        }
        var cache = new FileCache(1 << 20); // 1 MiB budget: room for 4 loads in flight
        assertEquals(1 << 20, cache.loadingBytesMax());
        var handler = new StaticFileHandler(root, false, cache);
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var maxReserved = new AtomicLong();
        var release = new CountDownLatch(1);
        cache.afterRead = () -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            maxReserved.accumulateAndGet(cache.loadingBytes(), Math::max);
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
        };
        var statuses = new ConcurrentLinkedQueue<String>();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < n; i++) {
            int j = i;
            threads.add(Thread.startVirtualThread(() -> {
                var r = handler.handle(get("/f" + j + ".bin"));
                statuses.add(r.status() + "/" + r.contentLength() + "/" + r.body().getClass().getSimpleName());
            }));
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (statuses.size() < n - 4 && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        release.countDown();
        for (var t : threads) {
            t.join();
        }
        assertEquals(4, maxInFlight.get(), "loads in flight are bounded by the budget");
        assertTrue(maxReserved.get() <= cache.maxBytes(), "reserved " + maxReserved.get());
        assertEquals(n - 4, cache.busy(), "the others were served without loading");
        assertEquals(n, statuses.stream().filter(s -> s.startsWith("200/" + size + "/")).count(), statuses.toString());
        assertTrue(statuses.stream().filter(s -> s.endsWith("FileBody")).count() >= n - 4, "streamed: " + statuses);
        assertEquals(0, cache.loadingBytes(), "every reservation released");
        assertTrue(cache.bytes() <= cache.maxBytes());
    }

    @Test
    void thunderingHerdOnOneFileReadsItOnce() throws Exception {
        var root = Files.createDirectory(tmp.resolve("www"));
        var data = new byte[200 * 1024];
        new java.util.Random(7).nextBytes(data);
        mk(root, "hot.bin", data);
        var cache = new FileCache(64 << 20);
        var handler = new StaticFileHandler(root, false, cache);
        var gate = new CountDownLatch(1);
        var reads = new AtomicInteger();
        cache.afterRead = () -> {
            reads.incrementAndGet();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var bodies = new ConcurrentLinkedQueue<byte[]>();
        var ts = new ArrayList<Thread>();
        for (int i = 0; i < 32; i++) {
            ts.add(Thread.startVirtualThread(() -> {
                try {
                    bodies.add(StaticFileHandlerTest.content(handler.handle(get("/hot.bin")).body()));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));
        }
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.coalesced() < 31 && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        gate.countDown();
        for (var t : ts) {
            t.join();
        }
        assertEquals(1, reads.get(), "one read for 32 concurrent misses");
        assertEquals(1, cache.loads());
        assertEquals(31, cache.coalesced());
        assertEquals(32, bodies.size());
        bodies.forEach(b -> assertArrayEquals(data, b));
    }

    @Test
    void waitersFallBackWhenTheSharedLoadFails() throws Exception {
        var root = Files.createDirectory(tmp.resolve("www"));
        mk(root, "hot.txt", "version one".getBytes());
        var cache = new FileCache(1 << 20);
        var handler = new StaticFileHandler(root, false, cache);
        var inRead = new CountDownLatch(1);
        cache.afterRead = () -> {
            inRead.countDown();
            try {
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (cache.coalesced() < 1 && System.nanoTime() < end) {
                    Thread.sleep(5);
                }
                Files.writeString(root.resolve("hot.txt"), "version two!"); // the shared load is discarded
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        };
        var leader = new String[1];
        var t = Thread.startVirtualThread(() -> leader[0] = text(handler.handle(get("/hot.txt"))));
        assertTrue(inRead.await(5, TimeUnit.SECONDS));
        var follower = new String[1];
        var f = Thread.startVirtualThread(() -> follower[0] = text(handler.handle(get("/hot.txt"))));
        t.join(5000);
        f.join(5000);
        assertEquals(1, cache.coalesced());
        assertEquals("version two!", leader[0]);
        assertEquals("version two!", follower[0], "the waiter served the current version itself");
        assertEquals(1, cache.discarded());
    }

    static String text(Response r) {
        try {
            return new String(StaticFileHandlerTest.content(r.body()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String key(Path root, String name) {
        return root.resolve(name).normalize().toString();
    }

    // --- N4 --------------------------------------------------------------------------------------

    @Test
    void clockGivesRecentlyHitEntriesASecondChance() throws IOException {
        var root = Files.createDirectory(tmp.resolve("www"));
        for (int i = 0; i < 6; i++) {
            mk(root, "f" + i + ".txt", ("file " + i).getBytes());
        }
        var c = new FileCache(1 << 20, 3, FileCache.MAX_FILE_SIZE, Duration.ZERO, System::nanoTime);
        var h = new StaticFileHandler(root, false, c);
        for (int i = 0; i < 3; i++) {
            h.handle(get("/f" + i + ".txt"));
        }
        h.handle(get("/f0.txt"));
        h.handle(get("/f2.txt"));
        h.handle(get("/f3.txt")); // f1 is the only one not hit: evicted
        assertTrue(c.contains(key(root, "f0.txt")) && c.contains(key(root, "f2.txt")) && c.contains(key(root, "f3.txt")));
        assertFalse(c.contains(key(root, "f1.txt")));
        // ring is now f2 (hit), f3, f0 (its chance used): f2 is spared once more, f3 (never hit) goes
        h.handle(get("/f4.txt"));
        assertFalse(c.contains(key(root, "f3.txt")));
        assertTrue(c.contains(key(root, "f0.txt")) && c.contains(key(root, "f2.txt")) && c.contains(key(root, "f4.txt")));
        assertEquals(3, c.size());
        assertEquals(2, c.evictions());
    }

    @Test
    void ringStaysBoundedUnderRepeatedInvalidation() throws IOException {
        var root = Files.createDirectory(tmp.resolve("www"));
        var file = mk(root, "hot.txt", "v".getBytes());
        var c = new FileCache(1 << 20);
        var h = new StaticFileHandler(root, false, c);
        for (int i = 0; i < 2000; i++) {
            Files.writeString(file, "v".repeat(1 + i % 7)); // new size: every request invalidates and reloads
            Files.setLastModifiedTime(file, FileTime.from(MTIME.plusSeconds(i)));
            assertEquals(1 + i % 7, h.handle(get("/hot.txt")).contentLength());
        }
        assertEquals(1, c.size());
        assertTrue(c.ringSize() <= 2 + 64 + 1, "ring " + c.ringSize());
    }

    @Test
    void fullCacheInsertsStayCheapAndWithinBounds() throws IOException {
        var root = Files.createDirectory(tmp.resolve("www"));
        int files = 3000;
        for (int i = 0; i < files; i++) {
            mk(root, "c" + i, new byte[] {(byte) i});
        }
        var c = new FileCache(1 << 30, 1000, FileCache.MAX_FILE_SIZE, Duration.ZERO, System::nanoTime);
        var h = new StaticFileHandler(root, false, c);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < files; i++) {
                if (i % 3 == 0) {
                    h.handle(get("/c" + (i / 3))); // a hot third is hit again and again
                }
                assertEquals(200, h.handle(get("/c" + i)).status());
                assertTrue(c.size() <= 1000);
            }
        }
        assertTrue(c.ringSize() <= 2 * c.size() + 64 + 1, "ring " + c.ringSize());
        assertTrue(c.evictions() > 0);
    }

    // --- N5 --------------------------------------------------------------------------------------

    @Test
    void cachedAndUncachedEtagsAgreeAfterThePolicyForgetsAYoungVersion() throws IOException {
        var root = Files.createDirectory(tmp.resolve("www"));
        mk(root, "young.txt", "young".getBytes());
        var now = new AtomicLong(MTIME.toEpochMilli() + 100); // the version is seen while it is young
        InstantSource clock = () -> Instant.ofEpochMilli(now.get());
        var h = new StaticFileHandler(root, false, clock, new FileCache(1 << 20));
        var first = h.handle(get("/young.txt")).headers().get("etag");
        assertTrue(first.startsWith("W/"), first);
        assertEquals(first, h.handle(get("/young.txt")).headers().get("etag"), "hit, still remembered");
        now.addAndGet(10_000);
        for (int i = 0; i < EntityTag.Policy.MAX_REMEMBERED; i++) { // other young versions fill the memory
            h.etags().weak(new Validator("k" + i, (now.get() - 10) * 1_000_000L, i));
        }
        var v = Validator.of(Files.readAttributes(root.resolve("young.txt"),
                java.nio.file.attribute.BasicFileAttributes.class));
        var uncachedNow = FileMeta.of(v, "young.txt", h.etags()).etag(); // what the uncached path computes
        assertFalse(uncachedNow.startsWith("W/"), "forgotten and old now: strong without the cache");
        var cachedNow = h.handle(get("/young.txt")).headers().get("etag");
        assertEquals(uncachedNow, cachedNow, "the cache follows the policy");
        assertEquals(cachedNow, h.handle(get("/young.txt")).headers().get("etag"), "and keeps it");
    }
}
