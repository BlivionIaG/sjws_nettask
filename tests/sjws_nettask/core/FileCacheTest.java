package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S3 small-file cache: hits/misses/eviction, key canonicalisation, validity, consistent loads. */
class FileCacheTest {

    static final Instant MTIME = Instant.parse("2001-02-03T04:05:06Z");

    @TempDir
    Path tmp;
    Path root;
    Path outside;
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    FileCache cache;
    StaticFileHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("www"));
        outside = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "OUTSIDE-SECRET");
        write("hot.txt", "hot content");
        write("other.txt", "other");
        Files.createDirectory(root.resolve("sub"));
        write("sub/a.txt", "in sub");
        Files.createDirectory(root.resolve("dir"));
        write("dir/index.html", "<p>index</p>");
        cache = new FileCache(1 << 20, 100, FileCache.MAX_FILE_SIZE, Duration.ZERO, nanos::get);
        handler = new StaticFileHandler(root, false, cache);
    }

    private Path write(String name, String content) throws IOException {
        var p = Files.writeString(root.resolve(name), content);
        Files.setLastModifiedTime(p, FileTime.from(MTIME));
        return p;
    }

    private Response get(String target) {
        return handler.handle(new Request("GET", target));
    }

    private static String text(Response r) throws IOException {
        return StaticFileHandlerTest.text(r);
    }

    private String key(String path) {
        return root.resolve(path.substring(1)).normalize() + (path.endsWith("/") ? "/" : "");
    }

    // --- hit / miss ------------------------------------------------------------------------

    @Test
    void secondRequestIsAHitWithIdenticalResponse() throws IOException {
        var first = get("/hot.txt");
        assertEquals(1, cache.loads());
        var second = get("/hot.txt");
        assertEquals(1, cache.hits());
        assertEquals(1, cache.loads(), "no second read");
        assertEquals(200, second.status());
        assertEquals("hot content", text(second));
        assertEquals(11, second.contentLength());
        assertEquals(first.headers().entries(), second.headers().entries());
        var body = assertInstanceOf(Body.Bytes.class, second.body());
        assertTrue(body.data().isReadOnly());
    }

    @Test
    void headAndConditionalHitsUseTheEntry() {
        var etag = get("/hot.txt").headers().get("etag");
        var head = handler.handle(new Request("HEAD", "/hot.txt"));
        assertTrue(head.headOnly());
        assertEquals(11, head.contentLength());
        var notModified = handler.handle(new Request("GET", "/hot.txt",
                new RequestHeaders(etag, null, null, null, null, null)));
        assertEquals(304, notModified.status());
        assertEquals(java.util.List.of("etag", "last-modified"),
                notModified.headers().entries().stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(1, cache.loads());
        assertEquals(2, cache.hits());
    }

    @Test
    void headAnd304MissesDoNotLoad() {
        handler.handle(new Request("HEAD", "/hot.txt"));
        handler.handle(new Request("GET", "/hot.txt", RequestHeaders.ifModifiedSince("Sun, 04 Feb 2001 00:00:00 GMT")));
        assertEquals(0, cache.loads(), "HEAD/304 never open the file, cache or not");
        assertEquals(0, cache.size());
    }

    @Test
    void cachedHeadersAreNotShared() {
        get("/hot.txt");
        get("/hot.txt").headers().add("x-mutated", "1");
        assertNull(get("/hot.txt").headers().get("x-mutated"));
    }

    @Test
    void indexIsCachedUnderTheDirectoryKey() throws IOException {
        assertEquals("<p>index</p>", text(get("/dir/")));
        assertTrue(cache.contains(key("/dir/")));
        assertEquals("<p>index</p>", text(get("/dir/")));
        assertEquals(1, cache.hits());
        assertEquals(301, get("/dir").status(), "redirect is not cached");
        Files.delete(root.resolve("dir/index.html"));
        var listing = get("/dir/");
        assertEquals(200, listing.status());
        assertTrue(text(listing).contains("<h1>/dir/</h1>"), "falls back to the listing");
        assertFalse(cache.contains(key("/dir/")));
    }

    @Test
    void notFoundIsNeverCached() {
        for (int i = 0; i < 100; i++) {
            assertEquals(404, get("/missing" + i).status());
        }
        assertEquals(0, cache.size());
    }

    @Test
    void largeFilesAreStreamedNotCached() throws IOException {
        Files.write(root.resolve("big.bin"), new byte[(int) FileCache.MAX_FILE_SIZE + 1]);
        assertInstanceOf(Body.FileBody.class, get("/big.bin").body());
        Files.write(root.resolve("max.bin"), new byte[(int) FileCache.MAX_FILE_SIZE]);
        get("/max.bin");
        assertInstanceOf(Body.Bytes.class, get("/max.bin").body());
        assertEquals(FileCache.MAX_FILE_SIZE, get("/max.bin").contentLength());
    }

    @Test
    void emptyFileIsCached() throws IOException {
        write("empty.txt", "");
        get("/empty.txt");
        var r = get("/empty.txt");
        assertEquals(0, r.contentLength());
        assertEquals(1, cache.hits());
    }

    @Test
    void disabledCacheKeepsTheOldBehaviour() {
        var off = new StaticFileHandler(root, false, FileCache.disabled());
        assertFalse(off.cache().enabled());
        assertInstanceOf(Body.FileBody.class, off.handle(new Request("GET", "/hot.txt")).body());
        assertInstanceOf(Body.FileBody.class, off.handle(new Request("GET", "/hot.txt")).body());
        assertEquals(0, off.cache().size());
    }

    // --- key canonicalisation and flooding --------------------------------------------------

    @Test
    void spellingsOfOnePathShareOneEntry() throws IOException {
        for (var t : new String[] {"/hot.txt", "/hot.txt?v=1", "/hot.txt?v=2#frag", "/%68ot.txt", "/./hot.txt",
            "/sub/../hot.txt", "/sub/%2E%2E/hot.txt"}) {
            assertEquals("hot content", text(get(t)), t);
        }
        assertEquals(1, cache.size());
        assertEquals(1, cache.loads());
    }

    @Test
    void queryStringFloodingCannotEvict() throws IOException {
        var small = new FileCache(4096, 3, FileCache.MAX_FILE_SIZE, Duration.ZERO, nanos::get);
        var h = new StaticFileHandler(root, false, small);
        h.handle(new Request("GET", "/hot.txt"));
        h.handle(new Request("GET", "/other.txt"));
        for (int i = 0; i < 20_000; i++) {
            nanos.incrementAndGet();
            assertEquals(200, h.handle(new Request("GET", "/other.txt?cachebust=" + i)).status());
            if (i % 10 == 0) {
                assertEquals(404, h.handle(new Request("GET", "/nope-" + i + ".txt")).status());
                assertEquals(404, h.handle(new Request("GET", "/.hidden" + i)).status());
                assertEquals(400, h.handle(new Request("GET", "/x%zz" + i)).status());
            }
        }
        assertEquals(2, small.size());
        assertEquals(0, small.evictions());
        assertEquals(2, small.loads());
        assertTrue(small.contains(key("/hot.txt")), "hot entry survived");
    }

    // --- bounds and eviction ------------------------------------------------------------

    @Test
    void entryCountBoundEvictsLeastRecentlyUsed() throws IOException {
        var c = new FileCache(1 << 20, 3, FileCache.MAX_FILE_SIZE, Duration.ZERO, nanos::get);
        var h = new StaticFileHandler(root, false, c);
        for (int i = 0; i < 5; i++) {
            write("f" + i + ".txt", "file " + i);
        }
        for (int i = 0; i < 3; i++) {
            nanos.addAndGet(10);
            h.handle(new Request("GET", "/f" + i + ".txt"));
        }
        nanos.addAndGet(10);
        h.handle(new Request("GET", "/f0.txt")); // f0 used again: f1 is now the oldest
        nanos.addAndGet(10);
        h.handle(new Request("GET", "/f3.txt"));
        assertEquals(3, c.size());
        assertTrue(c.contains(key("/f0.txt")));
        assertFalse(c.contains(key("/f1.txt")));
        assertTrue(c.contains(key("/f3.txt")));
        assertEquals(1, c.evictions());
    }

    @Test
    void byteBudgetCountsBodiesAndKeys() throws IOException {
        var body = "x".repeat(1000);
        for (int i = 0; i < 10; i++) {
            write("b" + i + ".txt", body);
        }
        long perEntry = 1000 + 4L * key("/b0.txt").length() + FileCache.ENTRY_OVERHEAD;
        var c = new FileCache(3 * perEntry, 100, FileCache.MAX_FILE_SIZE, Duration.ZERO, nanos::get);
        var h = new StaticFileHandler(root, false, c);
        for (int i = 0; i < 10; i++) {
            nanos.addAndGet(10);
            assertEquals(body, text(h.handle(new Request("GET", "/b" + i + ".txt"))));
            assertTrue(c.bytes() <= c.maxBytes(), "over budget: " + c.bytes());
        }
        assertEquals(3, c.size());
        assertEquals(3 * perEntry, c.bytes());
        assertEquals(7, c.evictions());
    }

    @Test
    void fileBiggerThanTheBudgetIsServedButNotKept() throws IOException {
        write("mid.txt", "y".repeat(5000));
        var c = new FileCache(2000, 100, FileCache.MAX_FILE_SIZE, Duration.ZERO, nanos::get);
        var h = new StaticFileHandler(root, false, c);
        assertEquals(5000, h.handle(new Request("GET", "/mid.txt")).contentLength());
        assertEquals(0, c.size());
        assertEquals(0, c.bytes());
    }

    // --- validity: every hit is validated ----------------------------------------------------

    @Test
    void modificationIsVisibleOnTheNextRequest() throws IOException {
        get("/hot.txt");
        Files.writeString(root.resolve("hot.txt"), "changed!"); // new mtime and size
        assertEquals("changed!", text(get("/hot.txt")));
        assertEquals(1, cache.invalidations());
        assertEquals(2, cache.loads());
    }

    @Test
    void sameSizeAndRestoredMtimeIsStillDetected() throws IOException {
        Assumptions.assumeTrue(ctimeAvailable(), "needs ctime (unix attribute view)");
        get("/hot.txt");
        nextTimestampTick();
        Files.writeString(root.resolve("hot.txt"), "HOT CONTENT"); // same size
        Files.setLastModifiedTime(root.resolve("hot.txt"), FileTime.from(MTIME)); // like cp -p / rsync -t
        assertEquals("HOT CONTENT", text(get("/hot.txt")));
    }

    @Test
    void fileSwapByRenameIsDetected() throws IOException {
        get("/hot.txt");
        var prepared = write("prepared.tmp", "HOT CONTENT"); // same size, same mtime, other inode
        Files.move(prepared, root.resolve("hot.txt"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertEquals("HOT CONTENT", text(get("/hot.txt")));
    }

    @Test
    void symlinkSwapToOutsideIsNotServed() throws IOException {
        get("/sub/a.txt");
        Files.delete(root.resolve("sub/a.txt"));
        Files.createSymbolicLink(root.resolve("sub/a.txt"), outside.resolve("a.txt"));
        var r = get("/sub/a.txt");
        assertEquals(404, r.status());
        assertFalse(text(r).contains("OUTSIDE-SECRET"));
        assertFalse(cache.contains(key("/sub/a.txt")));
    }

    @Test
    void parentDirectorySwapToOutsideIsNotServed() throws IOException {
        get("/sub/a.txt");
        Files.move(root.resolve("sub"), tmp.resolve("sub-old"));
        Files.createSymbolicLink(root.resolve("sub"), outside);
        var r = get("/sub/a.txt");
        assertEquals(404, r.status());
        assertFalse(text(r).contains("OUTSIDE-SECRET"));
    }

    @Test
    void retargetedInRootSymlinkServesTheNewTarget() throws IOException {
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("hot.txt"));
        assertEquals("hot content", text(get("/link.txt")));
        assertEquals("hot content", text(get("/link.txt")));
        Files.delete(root.resolve("link.txt"));
        Files.createSymbolicLink(root.resolve("link.txt"), root.resolve("other.txt"));
        assertEquals("other", text(get("/link.txt")));
    }

    @Test
    void deletedFileIs404AndDropped() throws IOException {
        get("/hot.txt");
        Files.delete(root.resolve("hot.txt"));
        assertEquals(404, get("/hot.txt").status());
        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
    }

    @Test
    void permissionChangeIsDetected() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        Assumptions.assumeTrue(ctimeAvailable(), "needs ctime");
        get("/hot.txt");
        nextTimestampTick();
        Files.setPosixFilePermissions(root.resolve("hot.txt"), PosixFilePermissions.fromString("---------"));
        try {
            assertEquals(403, get("/hot.txt").status());
        } finally {
            Files.setPosixFilePermissions(root.resolve("hot.txt"), PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void replacedByADirectoryIsNotServedFromCache() throws IOException {
        get("/hot.txt");
        Files.delete(root.resolve("hot.txt"));
        Files.createDirectory(root.resolve("hot.txt"));
        assertEquals(301, get("/hot.txt").status());
    }

    @Test
    void revalidationWindowUsesTheInjectedClock() throws IOException {
        var c = new FileCache(1 << 20, 100, FileCache.MAX_FILE_SIZE, Duration.ofSeconds(1), nanos::get);
        var h = new StaticFileHandler(root, false, c);
        h.handle(new Request("GET", "/hot.txt"));
        Files.writeString(root.resolve("hot.txt"), "new version");
        nanos.addAndGet(500_000_000L);
        assertEquals("hot content", text(h.handle(new Request("GET", "/hot.txt"))), "within the window: not statted");
        nanos.addAndGet(500_000_000L);
        assertEquals("new version", text(h.handle(new Request("GET", "/hot.txt"))), "window over: revalidated");
    }

    // --- snapshot-consistent loads ----------------------------------------------------------

    @Test
    void changeDuringLoadIsNotCachedAndTheCurrentVersionIsServed() throws IOException {
        cache.afterRead = () -> {
            try {
                Files.writeString(root.resolve("hot.txt"), "hot content, longer now");
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        var r = get("/hot.txt");
        cache.afterRead = () -> {
        };
        assertEquals(200, r.status());
        assertEquals("hot content, longer now", text(r), "served the version that is there now");
        assertEquals(r.contentLength(), text(r).getBytes(StandardCharsets.UTF_8).length);
        assertEquals(0, cache.size());
        assertEquals(1, cache.discarded());
        assertEquals("hot content, longer now", text(get("/hot.txt")));
        assertEquals(1, cache.size(), "the next request caches the stable version");
    }

    @Test
    void sameSizeRewriteDuringLoadIsNotCached() throws IOException {
        Assumptions.assumeTrue(ctimeAvailable(), "needs ctime");
        nextTimestampTick(); // the load's "before" stamp must not share a tick with the rewrite
        cache.afterRead = () -> {
            try {
                nextTimestampTick();
                Files.writeString(root.resolve("hot.txt"), "HOT CONTENT");
                Files.setLastModifiedTime(root.resolve("hot.txt"), FileTime.from(MTIME));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        get("/hot.txt");
        cache.afterRead = () -> {
        };
        assertEquals(0, cache.size());
        assertEquals("HOT CONTENT", text(get("/hot.txt")));
    }

    @Test
    void loadGoesThroughSafeOpen() throws IOException {
        // A FileBody whose path has a symlink component: SafeOpen refuses, a plain read would follow it.
        Assumptions.assumeTrue(SafeOpen.SECURE, "secure mode");
        Files.createSymbolicLink(root.resolve("via"), root.resolve("sub"));
        var real = root.toRealPath();
        var attrs = Files.readAttributes(real.resolve("sub/a.txt"), java.nio.file.attribute.BasicFileAttributes.class);
        var v = Validator.of(attrs);
        var body = new Body.FileBody(real.resolve("via/a.txt"), 0, v.size(), v, real);
        var meta = FileMeta.of(v, "a.txt", new EntityTag.Policy(java.time.InstantSource.system()));
        assertThrows(SafeOpen.FileChangedException.class, () -> cache.load("k", real.resolve("via/a.txt"), body, meta));
        assertEquals(0, cache.size());
    }

    @Test
    void searchOnlyDirectoryStays403() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        var hidden = Files.createDirectory(root.resolve("x"));
        write("x/f.txt", "f");
        Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("--x--x--x"));
        try {
            assertEquals(403, get("/x/f.txt").status());
            assertEquals(0, cache.size());
        } finally {
            Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void loadsAreNotDoneUnderTheLock() throws Exception {
        var inRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        cache.afterRead = () -> {
            if (Thread.currentThread().getName().equals("slow-loader")) {
                inRead.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var slow = Thread.ofVirtual().name("slow-loader").start(() -> get("/hot.txt"));
        assertTrue(inRead.await(5, TimeUnit.SECONDS));
        long start = System.nanoTime();
        assertEquals("other", text(get("/other.txt"))); // miss + load + insert while the other load is parked
        assertEquals("other", text(get("/other.txt"))); // hit
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        release.countDown();
        slow.join(5000);
        assertEquals(2, cache.size());
    }

    @Test
    void concurrentUseKeepsTheAccountingConsistent() throws Exception {
        for (int i = 0; i < 20; i++) {
            write("c" + i + ".txt", "c".repeat(100 + i));
        }
        var c = new FileCache(8 * 1024, 100, FileCache.MAX_FILE_SIZE, Duration.ZERO, System::nanoTime);
        var h = new StaticFileHandler(root, false, c);
        var errors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 16; t++) {
            int seed = t;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 500; i++) {
                        int n = (i * 7 + seed) % 20;
                        var r = h.handle(new Request("GET", "/c" + n + ".txt"));
                        assertEquals(100 + n, r.contentLength());
                        assertEquals("c".repeat(100 + n), text(r));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            }));
        }
        for (var t : threads) {
            t.join();
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(c.bytes() <= c.maxBytes());
        assertTrue(c.size() > 0);
    }

    /**
     * File timestamps (mtime, ctime) come from a coarse kernel clock (4 ms ticks here): two changes
     * within one tick share a ctime. A same-size rewrite that also restores the mtime within one
     * tick of the previous change is therefore invisible to any stat-based check (documented).
     */
    static void nextTimestampTick() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean ctimeAvailable() {
        try {
            return FileCache.Stamp.read(root.resolve("hot.txt")).ctimeNanos() >= 0;
        } catch (IOException e) {
            return false;
        }
    }
}
