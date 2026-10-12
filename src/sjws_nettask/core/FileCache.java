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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Small-file cache used by {@link StaticFileHandler} (so every transport benefits).
 * <ul>
 * <li><b>Key</b>: the decoded, normalized path (query and fragment stripped by the handler), so query
 * strings and percent-encoding variants of one path share one entry; 404s are never cached.</li>
 * <li><b>Bounds</b>: total bytes (body + key + a fixed overhead per entry) and entry count; eviction
 * is CLOCK (second chance, an approximation of least recently used): a hit only sets a flag, without
 * any lock, and an insert into a full cache costs amortized O(1).</li>
 * <li><b>Loads in flight</b> are bounded too: a load reserves its size from a byte budget of
 * {@code min(maxBytes, 16 * maxFileSize)} (4 MiB by default) first; without room the request is served
 * the uncached (streaming) way. Concurrent misses on one key share a single read (single-flight): the
 * others wait for it, without any lock held, and use its entry.</li>
 * <li><b>Validity</b>: by default every hit stats the file (one {@code stat}) and compares file key,
 * mtime in ns, size and, where available, ctime (catches {@code chmod}, {@code cp -p}, renames with
 * preserved mtime). Any difference drops the entry and the request takes the full path again
 * (containment via {@code toRealPath}, {@link SafeOpen}). Unless unlistable directories are allowed,
 * a hit also checks that the root and every directory down to the file are still readable
 * ({@code access(2)}, one per level): {@link SafeOpen} refuses files below search-only directories
 * (403), and a {@code chmod} on a directory leaves the file's own stamp unchanged. A revalidation window can be configured
 * for tests or embedding; the server uses none.</li>
 * <li><b>Loading</b> goes through {@link SafeOpen} (same no-follow guarantees as the writers), reads
 * exactly the expected size, stats again afterwards and caches nothing if anything changed or the
 * byte count differs; the body is then the bytes actually read.</li>
 * <li><b>Locking</b>: hits are lock-free; file I/O is never done under a lock (nor in
 * {@code computeIfAbsent}); a {@link ReentrantLock} (no carrier pinning) guards only the
 * map/accounting update after the load.</li>
 * </ul>
 */
public final class FileCache {

    /** Largest file kept in memory; bigger files are streamed ({@link Body.FileBody}). */
    public static final long MAX_FILE_SIZE = 256 * 1024;
    /** Entry cap, whatever the byte budget. */
    public static final int MAX_ENTRIES = 4096;
    /** Bookkeeping charged per entry on top of body and key. */
    static final int ENTRY_OVERHEAD = 256;

    /** Attributes compared on every hit. ctimeNanos is -1 where the platform has no ctime. */
    record Stamp(Object fileKey, long mtimeNanos, long size, long ctimeNanos, boolean regular) {

        private static final boolean UNIX = probeUnix();
        private static final String UNIX_ATTRIBUTES = "unix:fileKey,lastModifiedTime,size,ctime,isRegularFile";

        static Stamp read(Path path, LinkOption... options) throws IOException {
            if (UNIX) {
                Map<String, Object> m = Files.readAttributes(path, UNIX_ATTRIBUTES, options);
                return new Stamp(m.get("fileKey"), ((FileTime) m.get("lastModifiedTime")).to(TimeUnit.NANOSECONDS),
                        (Long) m.get("size"), ((FileTime) m.get("ctime")).to(TimeUnit.NANOSECONDS),
                        (Boolean) m.get("isRegularFile"));
            }
            var a = Files.readAttributes(path, BasicFileAttributes.class, options);
            return new Stamp(a.fileKey(), a.lastModifiedTime().to(TimeUnit.NANOSECONDS), a.size(), -1,
                    a.isRegularFile());
        }

        boolean matches(Validator v) {
            return this.regular && Objects.equals(this.fileKey, v.fileKey()) && this.mtimeNanos == v.mtimeNanos()
                    && this.size == v.size();
        }

        private static boolean probeUnix() {
            try {
                Files.readAttributes(Path.of(System.getProperty("java.io.tmpdir")), UNIX_ATTRIBUTES);
                return true;
            } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
                return false;
            }
        }
    }

    /** One cached file version. Immutable apart from the LRU/validation timestamps. */
    public static final class Entry {

        final String key;
        final Path statPath;
        final Stamp stamp;
        final FileMeta meta;
        final Body.Bytes body;
        final ResponseHeaders headers200;
        final ResponseHeaders headers304;
        final long charge;
        /** Directories that must stay readable (root first), empty when unlistable ones are allowed. */
        final Path[] readableDirs;
        /** CLOCK reference bit: set by hits (no lock), cleared by the eviction hand. */
        volatile boolean referenced;
        volatile long validatedAt;

        Entry(String key, Path statPath, Stamp stamp, FileMeta meta, Body.Bytes body, long now) {
            this(key, statPath, stamp, meta, body, now, NO_DIRS);
        }

        Entry(String key, Path statPath, Stamp stamp, FileMeta meta, Body.Bytes body, long now, Path[] readableDirs) {
            this.key = key;
            this.statPath = statPath;
            this.readableDirs = readableDirs;
            this.stamp = stamp;
            this.meta = meta;
            this.body = body;
            this.headers200 = meta.headers200();
            this.headers304 = meta.headers304();
            this.charge = body.length() + 4L * key.length() + ENTRY_OVERHEAD; // key + statPath, UTF-16
            this.validatedAt = now;
        }

        /** @return whether this version has a weak entity tag */
        boolean weak() {
            return this.meta.etag().startsWith("W/");
        }

        public FileMeta meta() {
            return this.meta;
        }

        public Body.Bytes body() {
            return this.body;
        }

        /** @return the response for this version (fresh header objects: callers may add to them) */
        Response response(boolean head, RequestHeaders conditionals) {
            if (this.meta.notModified(conditionals)) {
                return new Response(304, this.headers304.copy(), Body.Empty.INSTANCE, head);
            }
            if (!head && conditionals.range() != null) { // only GET has range semantics
                switch (ByteRanges.decide(conditionals, this.meta)) {
                    case ByteRanges.Partial p -> {
                        var slice = this.body.data().duplicate().position((int) p.offset())
                                .limit((int) (p.offset() + p.length()));
                        return new Response(206, this.headers200.copy().add("content-range", p.contentRange()),
                                new Body.Bytes(slice), false);
                    }
                    case ByteRanges.Unsatisfiable u -> {
                        return StaticFileHandler.rangeNotSatisfiable(u);
                    }
                    case ByteRanges.Full f -> {
                        // whole file below
                    }
                }
            }
            return new Response(200, this.headers200.copy(), this.body, head);
        }
    }

    private final long maxBytes;
    private final int maxEntries;
    private final long maxFileSize;
    private final long revalidateNanos;
    private final LongSupplier nanoClock;
    private static final Path[] NO_DIRS = {};
    /** Loads in flight reserve their size here first (bounds the transient heap of concurrent misses). */
    static final long LOAD_FILES_IN_FLIGHT = 16;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Entry>> loading = new ConcurrentHashMap<>();
    private final Semaphore loadBytes;
    private final int loadBytesMax;
    private final ReentrantLock lock = new ReentrantLock();
    private long bytes; // guarded by lock
    /** CLOCK ring in insertion order (guarded by lock); may hold stale entries, skipped when reached. */
    private final ArrayDeque<Entry> clock = new ArrayDeque<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder discarded = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder invalidations = new LongAdder();
    private final LongAdder busy = new LongAdder();
    private final LongAdder coalesced = new LongAdder();
    /** Test seam: runs after a load read the bytes and before it re-checks the attributes. */
    volatile Runnable afterRead = () -> {
    };

    /** @return a cache that holds nothing */
    public static FileCache disabled() {
        return new FileCache(0);
    }

    /** @param maxBytes total budget; 0 disables the cache */
    public FileCache(long maxBytes) {
        this(maxBytes, MAX_ENTRIES, MAX_FILE_SIZE, Duration.ZERO, System::nanoTime);
    }

    /**
     * @param revalidateAfter ZERO (the default) stats on every hit; a positive window skips the stat
     *        for that long after the last successful validation (served content may then be stale)
     * @param nanoClock monotonic clock in ns ({@link System#nanoTime}); tests inject a fake one
     */
    public FileCache(long maxBytes, int maxEntries, long maxFileSize, Duration revalidateAfter, LongSupplier nanoClock) {
        if (maxBytes < 0 || maxEntries < 1 || maxFileSize < 0 || revalidateAfter.isNegative()) {
            throw new IllegalArgumentException("invalid cache bounds");
        }
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.maxFileSize = maxFileSize;
        this.revalidateNanos = revalidateAfter.toNanos();
        this.nanoClock = nanoClock;
        this.loadBytesMax = (int) Math.min(Integer.MAX_VALUE,
                Math.min(maxBytes, LOAD_FILES_IN_FLIGHT * Math.max(maxFileSize, 1)));
        this.loadBytes = new Semaphore(this.loadBytesMax);
    }

    public boolean enabled() {
        return this.maxBytes > 0;
    }

    /** @return whether a file of this size may be cached */
    public boolean admits(long size) {
        return enabled() && size <= this.maxFileSize && size + ENTRY_OVERHEAD <= this.maxBytes;
    }

    /**
     * @return the entry for {@code key} if present and still valid (else null; a stale entry is dropped)
     */
    public Entry lookup(String key) {
        var e = this.entries.get(key);
        if (e == null) {
            this.misses.increment();
            return null;
        }
        long now = this.nanoClock.getAsLong();
        if (this.revalidateNanos == 0 || now - e.validatedAt >= this.revalidateNanos) {
            Stamp current;
            try {
                current = Stamp.read(e.statPath); // follows links, as the request's own resolution would
            } catch (IOException | RuntimeException x) {
                current = null; // gone, unreadable parent, ...
            }
            if (!e.stamp.equals(current) || !readable(e.readableDirs)) {
                invalidate(e);
                this.misses.increment();
                return null;
            }
            e.validatedAt = now;
        }
        if (!e.referenced) {
            e.referenced = true; // plain flag, no lock: the next eviction pass gives it a second chance
        }
        this.hits.increment();
        return e;
    }

    private static boolean readable(Path[] dirs) {
        for (var d : dirs) {
            if (!Files.isReadable(d)) { // access(2): a search-only directory now refuses SafeOpen (403)
                return false;
            }
        }
        return true;
    }

    /** The real directories from {@code root} down to the file's parent, which SafeOpen opens. */
    private static Path[] dirsToCheck(Body.FileBody file) {
        if (file.allowUnlistable()) {
            return NO_DIRS;
        }
        var root = file.root();
        var parent = file.realPath().getParent();
        if (parent == null || !parent.startsWith(root)) {
            return NO_DIRS; // SafeOpen refuses such a body anyway
        }
        var rel = root.relativize(parent);
        int depth = rel.toString().isEmpty() ? 0 : rel.getNameCount();
        var dirs = new Path[depth + 1];
        var d = root;
        dirs[0] = d;
        for (int i = 0; i < depth; i++) {
            d = d.resolve(rel.getName(i));
            dirs[i + 1] = d;
        }
        return dirs;
    }

    /** Drops {@code e} (if still current), e.g. because its entity tag no longer agrees with the policy. */
    void drop(Entry e) {
        invalidate(e);
    }

    /**
     * Reads {@code file} (through {@link SafeOpen}, outside any lock) and caches it under {@code key}.
     *
     * @param statPath the request's logical path of the file, statted (following links) on later hits
     * @return the entry, or null if nothing was cached: the file changed while it was being read, the
     *         budget for loads in flight is used up, or the concurrent load this one waited for failed
     *         (the caller serves the file the usual way, streaming it)
     * @throws IOException from {@link SafeOpen#open}: map it with {@link SafeOpen#status}
     */
    public Entry load(String key, Path statPath, Body.FileBody file, FileMeta meta) throws IOException {
        var mine = new CompletableFuture<Entry>();
        var running = this.loading.putIfAbsent(key, mine);
        if (running != null) { // single-flight: wait for that read (no lock held), then use its entry
            this.coalesced.increment();
            try {
                return running.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException e) {
                return null; // not thrown: the leader completes normally
            }
        }
        Entry result = null;
        try {
            result = loadOnce(key, statPath, file, meta);
            return result;
        } finally {
            this.loading.remove(key, mine);
            mine.complete(result); // waiters fall back to the uncached path on null
        }
    }

    private Entry loadOnce(String key, Path statPath, Body.FileBody file, FileMeta meta) throws IOException {
        var before = Stamp.read(file.realPath(), LinkOption.NOFOLLOW_LINKS);
        if (!before.matches(file.expected())) {
            this.discarded.increment();
            return null;
        }
        var cached = this.entries.get(key); // a load of this version finished since our lookup missed
        if (cached != null && cached.stamp.equals(before) && cached.meta.etag().equals(meta.etag())) {
            return cached;
        }
        int size = (int) file.length();
        if (!this.loadBytes.tryAcquire(size)) {
            this.busy.increment();
            return null;
        }
        byte[] data;
        int read = 0;
        boolean grew;
        try {
            data = new byte[size];
            try (var channel = SafeOpen.open(file)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    int n = channel.read(buffer, buffer.position());
                    if (n < 0) {
                        break;
                    }
                }
                read = buffer.position();
                grew = channel.read(ByteBuffer.allocate(1), size) > 0;
            }
            this.afterRead.run();
            var after = Stamp.read(file.realPath(), LinkOption.NOFOLLOW_LINKS);
            if (read != size || grew || !after.equals(before)) {
                this.discarded.increment();
                return null;
            }
            this.loads.increment();
            var entry = new Entry(key, statPath, before, meta, Body.Bytes.of(data), this.nanoClock.getAsLong(),
                    dirsToCheck(file));
            return insert(entry);
        } finally {
            this.loadBytes.release(size); // the bytes now belong to the entry (counted in the budget) or are garbage
        }
    }

    private Entry insert(Entry entry) {
        if (entry.charge > this.maxBytes) {
            return entry; // served, not kept
        }
        this.lock.lock();
        try {
            var old = this.entries.get(entry.key);
            if (old != null && old.stamp.equals(entry.stamp)) {
                return old; // a concurrent load of the same version won
            }
            if (old != null) {
                this.entries.remove(entry.key);
                this.bytes -= old.charge;
            }
            this.entries.put(entry.key, entry);
            this.bytes += entry.charge;
            this.clock.addLast(entry);
            while (this.bytes > this.maxBytes || this.entries.size() > this.maxEntries) {
                evictOne(entry);
            }
            if (this.clock.size() > 2 * this.entries.size() + 64) {
                // stale nodes of replaced/invalidated entries: compact (O(n) once per ~n inserts)
                this.clock.removeIf(e -> this.entries.get(e.key) != e);
            }
            return entry;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * CLOCK step, called with the lock held: entries hit since the hand last passed get a second
     * chance; each hit buys at most one requeue, so eviction is amortized O(1). Never evicts
     * {@code keep} (the entry being inserted) unless it is the only entry.
     */
    private void evictOne(Entry keep) {
        while (true) {
            var e = this.clock.pollFirst();
            if (e == null) {
                return; // unreachable while over bounds (every live entry is in the ring)
            }
            if (this.entries.get(e.key) != e) {
                continue; // stale node
            }
            if (e == keep && this.entries.size() > 1) {
                this.clock.addLast(e);
                continue;
            }
            if (e.referenced && e != keep) {
                e.referenced = false;
                this.clock.addLast(e);
                continue;
            }
            this.entries.remove(e.key);
            this.bytes -= e.charge;
            this.evictions.increment();
            return;
        }
    }

    private void invalidate(Entry e) {
        this.lock.lock();
        try {
            if (this.entries.remove(e.key, e)) {
                this.bytes -= e.charge;
                this.invalidations.increment();
            }
        } finally {
            this.lock.unlock();
        }
    }

    // --- statistics (tests, diagnostics) -------------------------------------------------------

    public int size() {
        return this.entries.size();
    }

    public long bytes() {
        this.lock.lock();
        try {
            return this.bytes;
        } finally {
            this.lock.unlock();
        }
    }

    public long maxBytes() {
        return this.maxBytes;
    }

    public long hits() {
        return this.hits.sum();
    }

    public long misses() {
        return this.misses.sum();
    }

    public long loads() {
        return this.loads.sum();
    }

    public long discarded() {
        return this.discarded.sum();
    }

    public long evictions() {
        return this.evictions.sum();
    }

    public long invalidations() {
        return this.invalidations.sum();
    }

    /** @return misses served uncached because the budget for loads in flight was used up */
    public long busy() {
        return this.busy.sum();
    }

    /** @return misses that waited for a concurrent load of the same key instead of reading */
    public long coalesced() {
        return this.coalesced.sum();
    }

    /** @return bytes currently reserved by loads in flight */
    public long loadingBytes() {
        return this.loadBytesMax - this.loadBytes.availablePermits();
    }

    /** @return the budget for loads in flight, in bytes */
    public long loadingBytesMax() {
        return this.loadBytesMax;
    }

    /** @return nodes in the CLOCK ring, stale ones included (tests: it must stay bounded) */
    int ringSize() {
        this.lock.lock();
        try {
            return this.clock.size();
        } finally {
            this.lock.unlock();
        }
    }

    boolean contains(String key) {
        return this.entries.containsKey(key);
    }
}
