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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Small-file cache used by {@link StaticFileHandler} (so every transport benefits).
 * <ul>
 * <li><b>Key</b>: the decoded, normalized path (query and fragment stripped by the handler), so query
 * strings and percent-encoding variants of one path share one entry; 404s are never cached.</li>
 * <li><b>Bounds</b>: total bytes (body + key + a fixed overhead per entry) and entry count; least
 * recently used entries are evicted.</li>
 * <li><b>Validity</b>: by default every hit stats the file (one {@code stat}) and compares file key,
 * mtime in ns, size and, where available, ctime (catches {@code chmod}, {@code cp -p}, renames with
 * preserved mtime). Any difference drops the entry and the request takes the full path again
 * (containment via {@code toRealPath}, {@link SafeOpen}). A revalidation window can be configured
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
        volatile long lastUsed;
        volatile long validatedAt;

        Entry(String key, Path statPath, Stamp stamp, FileMeta meta, Body.Bytes body, long now) {
            this.key = key;
            this.statPath = statPath;
            this.stamp = stamp;
            this.meta = meta;
            this.body = body;
            this.headers200 = meta.headers200();
            this.headers304 = meta.headers304();
            this.charge = body.length() + 4L * key.length() + ENTRY_OVERHEAD; // key + statPath, UTF-16
            this.lastUsed = now;
            this.validatedAt = now;
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
            return new Response(200, this.headers200.copy(), this.body, head);
        }
    }

    private final long maxBytes;
    private final int maxEntries;
    private final long maxFileSize;
    private final long revalidateNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private long bytes; // guarded by lock
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder discarded = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder invalidations = new LongAdder();
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
            if (!e.stamp.equals(current)) {
                invalidate(e);
                this.misses.increment();
                return null;
            }
            e.validatedAt = now;
        }
        e.lastUsed = now;
        this.hits.increment();
        return e;
    }

    /**
     * Reads {@code file} (through {@link SafeOpen}, outside any lock) and caches it under {@code key}.
     *
     * @param statPath the request's logical path of the file, statted (following links) on later hits
     * @return the entry, or null if the file changed while it was being read (nothing was cached; the
     *         caller serves the file the usual way)
     * @throws IOException from {@link SafeOpen#open}: map it with {@link SafeOpen#status}
     */
    public Entry load(String key, Path statPath, Body.FileBody file, FileMeta meta) throws IOException {
        var before = Stamp.read(file.realPath(), LinkOption.NOFOLLOW_LINKS);
        if (!before.matches(file.expected())) {
            this.discarded.increment();
            return null;
        }
        int size = (int) file.length();
        var data = new byte[size];
        int read = 0;
        boolean grew;
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
        var entry = new Entry(key, statPath, before, meta, Body.Bytes.of(data), this.nanoClock.getAsLong());
        return insert(entry);
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
            while (this.bytes > this.maxBytes || this.entries.size() > this.maxEntries) {
                evictOldest(entry);
            }
            return entry;
        } finally {
            this.lock.unlock();
        }
    }

    /** Called with the lock held. Never evicts {@code keep} unless it is the only entry. */
    private void evictOldest(Entry keep) {
        Entry oldest = null;
        for (var e : this.entries.values()) {
            if (e != keep && (oldest == null || e.lastUsed < oldest.lastUsed)) {
                oldest = e;
            }
        }
        if (oldest == null) {
            oldest = keep;
        }
        this.entries.remove(oldest.key);
        this.bytes -= oldest.charge;
        this.evictions.increment();
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

    boolean contains(String key) {
        return this.entries.containsKey(key);
    }
}
