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
package sjws_nettask.msgrepl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Snapshot pins of one node (#270). A pin is {@code <root>/<topic>/<index>/}: a hard link to every segment file the
 * snapshot at {@code index} covers, plus its {@code manifest} ({@link MsgSnapshot}). Hard links keep exactly the bytes
 * the manifest describes while the live log moves on: retention unlinks the live name, compaction renames a new file
 * over it, and the pinned inode stays as it was, at no copying cost. {@link SnapshotServer} serves installs from
 * pins; the newest {@link #KEEP} pins per topic are kept, plus any a transfer is still reading.
 *
 * <p>Digests: one SHA-256 per {@code chunkBytes} chunk, cached per inode and chunk, so a sealed segment (or the full
 * chunks of the active one) is hashed once, not at every snapshot.
 */
final class SnapshotStore {

    static final int KEEP = 2;
    static final String MANIFEST = "manifest";

    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();
    /** topic to index to pin; guarded by {@link #lock}. */
    private final Map<String, TreeMap<Long, Pin>> pins = new HashMap<>();
    /** inode key to chunk digests; only touched by the topic's snapshotter (one thread per replica at a time). */
    private final Map<Object, Map<Long, byte[]>> digests = new ConcurrentHashMap<>();

    /** One pinned snapshot; {@code readers} counts transfers using it. */
    static final class Pin {
        final String topic;
        final long index;
        final Path dir;
        final byte[] manifest;
        int readers;

        Pin(String topic, long index, Path dir, byte[] manifest) {
            this.topic = topic;
            this.index = index;
            this.dir = dir;
            this.manifest = manifest;
        }
    }

    SnapshotStore(Path root) throws IOException {
        this.root = Files.createDirectories(root);
        try (var topics = Files.list(root)) {
            for (var t : (Iterable<Path>) topics::iterator) {
                var topic = t.getFileName().toString();
                if (!Files.isDirectory(t) || !MsgSnapshot.TOPIC.matcher(topic).matches()) continue;
                try (var list = Files.list(t)) {
                    for (var p : (Iterable<Path>) list::iterator) {
                        var name = p.getFileName().toString();
                        if (name.endsWith(".tmp")) { // a snapshot cut short by a crash: never recorded
                            deleteTree(p);
                        } else if (name.chars().allMatch(Character::isDigit) && Files.isRegularFile(p.resolve(MANIFEST))) {
                            var m = Files.readAllBytes(p.resolve(MANIFEST));
                            topicPins(topic).put(Long.parseLong(name), new Pin(topic, Long.parseLong(name), p, m));
                        }
                    }
                }
            }
        }
    }

    Path root() {
        return root;
    }

    /** The newest pin of {@code topic} at or above {@code minIndex}, counted as read until {@link #release}; or null. */
    Pin acquire(String topic, long minIndex) {
        lock.lock();
        try {
            var t = pins.get(topic);
            if (t == null) return null;
            var e = t.lastEntry();
            if (e == null || e.getKey() < minIndex) return null;
            e.getValue().readers++;
            return e.getValue();
        } finally {
            lock.unlock();
        }
    }

    void release(Pin pin) {
        lock.lock();
        try {
            pin.readers--;
        } finally {
            lock.unlock();
        }
        prune(pin.topic);
    }

    /** Index of the newest pin of {@code topic}, or -1. */
    long newest(String topic) {
        lock.lock();
        try {
            var t = pins.get(topic);
            return t == null || t.isEmpty() ? -1 : t.lastKey();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Pins the snapshot at {@code index}: hard-links every segment of {@code log} from the one holding
     * {@code logStart} up to {@code endOffset} (cut there), hashes the chunks, writes the manifest, and publishes the pin
     * only once all of it is durable. Null when the log no longer holds what the snapshot needs (a segment was deleted
     * meanwhile): the caller tries again later.
     */
    MsgSnapshot pin(String topic, SegmentedLog log, long index, long term, long endOffset, long logStart, List<MsgSnapshot.Section> sections,
            int chunkBytes) throws IOException {
        var dir = root.resolve(topic);
        Files.createDirectories(dir);
        var tmp = dir.resolve(index + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        boolean done = false;
        try {
            var segs = new ArrayList<MsgSnapshot.Seg>();
            var files = log.segmentFiles();
            for (var f : files) {
                if (f.baseOffset() >= endOffset || f.nextOffset() <= logStart) continue; // only [logStart, endOffset)
                var link = tmp.resolve(name(f.baseOffset()));
                try {
                    Files.createLink(link, f.file());
                } catch (NoSuchFileException gone) {
                    return null; // retention removed it since the list was read
                }
                long markerNext = -1;
                try {
                    var m = Files.readAllBytes(f.marker());
                    if (m.length == 8) markerNext = ByteBuffer.wrap(m).getLong();
                } catch (NoSuchFileException none) {
                    // not compacted
                }
                long bytes;
                long next = Math.min(f.nextOffset(), endOffset);
                if (endOffset >= f.nextOffset() && !f.active()) {
                    bytes = Files.size(link); // a sealed file wholly inside the snapshot: whichever version was linked
                } else if (f.active()) {
                    bytes = log.positionOf(f.baseOffset(), endOffset); // the active file is never swapped: same inode
                    if (bytes < 0) bytes = cutPosition(link, endOffset);
                } else {
                    bytes = cutPosition(link, endOffset); // sealed (maybe compacted) holding the cut: scan the link itself
                }
                if (bytes < 0) throw new IOException("no frame boundary at " + endOffset + " in segment " + f.baseOffset());
                // Cut inside a compacted segment: keep a marker bounded to the snapshot end so recover()
                // still advances across compaction gaps (empty prefix → nextOffset = marker). Clearing it
                // made an empty cut reopen at base despite the manifest end (#288 Codex P1).
                if (markerNext >= 0 && next < f.nextOffset()) markerNext = next;
                segs.add(new MsgSnapshot.Seg(f.baseOffset(), next, bytes, markerNext, hash(link, bytes, chunkBytes)));
            }
            if (segs.isEmpty()) {
                // No record at or past the start yet: an empty file starting at endOffset carries the start offset.
                Files.createFile(tmp.resolve(name(endOffset)));
                segs.add(new MsgSnapshot.Seg(endOffset, endOffset, 0, -1, new byte[0][]));
            }
            long start = Math.min(logStart, endOffset);
            // Retention may have deleted a segment after snapshotAt captured logStart; refuse an incomplete
            // pin that decode would reject (#288 Codex P1).
            if (segs.getFirst().base() > start || segs.getLast().next() != endOffset) {
                return null;
            }
            var snap = new MsgSnapshot(topic, index, term, endOffset, start, chunkBytes, sections, segs);
            var manifest = snap.encode();
            try (var ch = FileChannel.open(tmp.resolve(MANIFEST), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var b = ByteBuffer.wrap(manifest);
                while (b.hasRemaining()) ch.write(b);
                ch.force(true);
            }
            fsyncDir(tmp);
            var target = dir.resolve(Long.toString(index));
            deleteTree(target);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            fsyncDir(dir);
            done = true;
            lock.lock();
            try {
                topicPins(topic).put(index, new Pin(topic, index, target, manifest));
            } finally {
                lock.unlock();
            }
            prune(topic);
            return snap;
        } finally {
            if (!done) deleteTree(tmp);
        }
    }

    /** Under {@link #lock}: the pins of {@code topic}, created empty (no computeIfAbsent: SourceGuardTest). */
    private TreeMap<Long, Pin> topicPins(String topic) {
        var m = pins.get(topic);
        if (m == null) {
            m = new TreeMap<>();
            pins.put(topic, m);
        }
        return m;
    }

    /** Drops pins beyond the newest {@link #KEEP} that no transfer reads, and digests of inodes no pin holds. */
    void prune(String topic) {
        var drop = new ArrayList<Pin>();
        lock.lock();
        try {
            var t = pins.get(topic);
            if (t == null) return;
            int seen = 0;
            for (var it = t.descendingMap().values().iterator(); it.hasNext();) {
                var p = it.next();
                if (++seen > KEEP && p.readers == 0) {
                    drop.add(p);
                    it.remove();
                }
            }
        } finally {
            lock.unlock();
        }
        for (var p : drop) {
            try {
                deleteTree(p.dir);
            } catch (IOException e) {
                System.err.println("WARN: msgrepl: dropping snapshot pin " + p.dir + ": " + e);
            }
        }
        if (!drop.isEmpty()) {
            var live = new java.util.HashSet<Object>();
            lock.lock();
            try {
                for (var t : pins.values()) for (var p : t.values()) {
                    try (var list = Files.list(p.dir)) {
                        for (var f : (Iterable<Path>) list::iterator) live.add(fileKey(f));
                    } catch (IOException ignored) {
                        // a pin being deleted
                    }
                }
            } finally {
                lock.unlock();
            }
            digests.keySet().retainAll(live);
        }
    }

    static String name(long base) {
        return String.format("%020d.log", base);
    }

    private static Object fileKey(Path p) throws IOException {
        var k = Files.readAttributes(p, BasicFileAttributes.class).fileKey();
        return k != null ? k : p.toAbsolutePath().toString();
    }

    /**
     * SHA-256 per chunk of the first {@code bytes} bytes. A full chunk of an inode never changes (sealed files are
     * immutable, the active one only grows), so it is cached by inode; the last partial chunk is hashed each time.
     */
    byte[][] hash(Path file, long bytes, int chunkBytes) throws IOException {
        int n = MsgSnapshot.chunks(bytes, chunkBytes);
        var out = new byte[n][];
        var inode = fileKey(file);
        var cache = digests.get(inode);
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            var raced = digests.putIfAbsent(inode, cache);
            if (raced != null) cache = raced;
        }
        ByteBuffer buf = null;
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            for (int i = 0; i < n; i++) {
                long at = (long) i * chunkBytes;
                int len = (int) Math.min(chunkBytes, bytes - at);
                long key = ((long) i << 1) | (len == chunkBytes ? 0 : 1);
                byte[] d = len == chunkBytes ? cache.get(key) : null;
                if (d == null) {
                    if (buf == null) buf = ByteBuffer.allocate(chunkBytes);
                    d = digest(ch, at, len, buf);
                    if (len == chunkBytes) cache.put(key, d);
                }
                out[i] = d;
            }
        }
        return out;
    }

    static byte[] digest(FileChannel ch, long at, int len, ByteBuffer buf) throws IOException {
        buf.clear().limit(len);
        while (buf.hasRemaining()) {
            if (ch.read(buf, at + buf.position()) < 0) throw new IOException("file shorter than its snapshot length");
        }
        buf.flip();
        var md = sha256();
        md.update(buf);
        return md.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Position of the first frame whose base offset is at or past {@code offset}, or the file size. Header reads only. */
    static long cutPosition(Path file, long offset) throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size(), pos = 0;
            var head = ByteBuffer.allocate(20);
            while (pos + 20 <= size) {
                head.clear();
                while (head.hasRemaining()) if (ch.read(head, pos + head.position()) <= 0) return -1;
                int len = head.getInt(0);
                if (len < sjws_nettask.messaging.ledger.RecordBatch.HEADER_BYTES - 4 || pos + 4 + len > size) return -1;
                if (head.getLong(8) >= offset) return pos;
                pos += 4 + len;
            }
            return pos == size ? pos : -1;
        }
    }

    static void fsyncDir(Path dir) {
        try (var d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException ignored) {
            // not every file system allows a directory fsync
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (var list = Files.list(p)) {
                for (var c : (Iterable<Path>) list::iterator) deleteTree(c);
            }
        }
        Files.deleteIfExists(p);
    }

    /**
     * Publishes {@code m} as a pin whose segment files are hard-linked from {@code staged} (the verified install
     * tree). Uses the consensus manifest bytes as-is so a recovered replica can seed peers after failover without
     * rehashing (#288 Codex P1). Null when {@code staged} is missing a required file.
     */
    MsgSnapshot pinInstalled(MsgSnapshot m, Path staged) throws IOException {
        var dir = root.resolve(m.topic());
        Files.createDirectories(dir);
        var tmp = dir.resolve(m.index() + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        boolean done = false;
        try {
            for (var seg : m.segments()) {
                var src = staged.resolve(name(seg.base()));
                var link = tmp.resolve(name(seg.base()));
                try {
                    Files.createLink(link, src);
                } catch (NoSuchFileException gone) {
                    return null;
                } catch (UnsupportedOperationException | IOException e) {
                    Files.copy(src, link);
                }
                if (seg.markerNext() >= 0) {
                    var marker = staged.resolve(String.format("%020d.compacted", seg.base()));
                    var dest = tmp.resolve(marker.getFileName());
                    try {
                        Files.createLink(dest, marker);
                    } catch (NoSuchFileException none) {
                        Files.write(dest, java.nio.ByteBuffer.allocate(8).putLong(seg.markerNext()).array());
                    } catch (UnsupportedOperationException | IOException e) {
                        if (Files.exists(marker)) Files.copy(marker, dest);
                        else Files.write(dest, java.nio.ByteBuffer.allocate(8).putLong(seg.markerNext()).array());
                    }
                }
            }
            var manifest = m.encode();
            try (var ch = FileChannel.open(tmp.resolve(MANIFEST), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var b = java.nio.ByteBuffer.wrap(manifest);
                while (b.hasRemaining()) ch.write(b);
                ch.force(true);
            }
            fsyncDir(tmp);
            var target = dir.resolve(Long.toString(m.index()));
            deleteTree(target);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            fsyncDir(dir);
            done = true;
            lock.lock();
            try {
                topicPins(m.topic()).put(m.index(), new Pin(m.topic(), m.index(), target, manifest));
            } finally {
                lock.unlock();
            }
            prune(m.topic());
            return m;
        } finally {
            if (!done) deleteTree(tmp);
        }
    }

        /** Pins currently held for {@code topic} (tests). */
    List<Long> pinned(String topic) {
        lock.lock();
        try {
            var t = pins.get(topic);
            return t == null ? List.of() : List.copyOf(t.keySet());
        } finally {
            lock.unlock();
        }
    }

    /** Test-only: publishes an already-written pin directory as the pin at {@code index}. */
    void publishForTest(String topic, long index, Path dir, byte[] manifest) {
        lock.lock();
        try {
            var t = pins.get(topic);
            if (t == null) {
                t = new java.util.TreeMap<>();
                pins.put(topic, t);
            }
            t.put(index, new Pin(topic, index, dir, manifest));
        } finally {
            lock.unlock();
        }
    }

    /** Test-only: drops every pin of {@code topic} above {@code keepIndex}. */
    void dropAboveForTest(String topic, long keepIndex) {
        java.util.ArrayList<Pin> doomed = new java.util.ArrayList<>();
        lock.lock();
        try {
            var t = pins.get(topic);
            if (t == null) return;
            var drop = new java.util.ArrayList<Long>();
            for (var k : t.keySet()) if (k > keepIndex) drop.add(k);
            for (var k : drop) {
                var pin = t.remove(k);
                if (pin != null) doomed.add(pin);
            }
        } finally {
            lock.unlock();
        }
        for (var pin : doomed) {
            try {
                deleteTree(pin.dir);
            } catch (IOException ignored) {
            }
        }
        prune(topic);
    }


}
