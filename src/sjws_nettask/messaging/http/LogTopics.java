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
package sjws_nettask.messaging.http;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Plain log topics (docs/design/partition-log.md R1, R9): one {@link SegmentedLog} plus its single
 * {@link LedgerWriter} per topic, under {@code dir/<topic>}, opened on first produce. No delivery groups.
 */
final class LogTopics implements AutoCloseable {

    static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /**
     * {@code reads} guards segment deletion against fetches: fetches share the read lock (segment readers publish their
     * chunk cache and sequential hint as immutable volatile records, so concurrent readers are safe and at worst lose a
     * hint); retention takes the write lock.
     */
    record Log(SegmentedLog log, LedgerWriter writer, java.util.concurrent.locks.ReentrantReadWriteLock reads) { }

    private final Path dir;
    private final Durability durability;
    /** Per-topic writer default overriding {@link #durability} (docs/design/partition-log.md R8, gap G3). */
    private final java.util.Map<String, Durability> topicDurability;
    private final long segmentBytes;
    private final LogRetention retention;
    private final java.util.function.LongSupplier clockMs;
    private final ConcurrentHashMap<String, Log> logs = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock opening = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean closed;

    LogTopics(Path dir, Durability durability) {
        this(dir, durability, SegmentedLog.DEFAULT_SEGMENT_BYTES, LogRetention.NONE, System::currentTimeMillis);
    }

    LogTopics(Path dir, Durability durability, long segmentBytes, LogRetention retention, java.util.function.LongSupplier clockMs) {
        this(dir, durability, java.util.Map.of(), segmentBytes, retention, clockMs);
    }

    LogTopics(Path dir, Durability durability, java.util.Map<String, Durability> topicDurability, long segmentBytes,
            LogRetention retention, java.util.function.LongSupplier clockMs) {
        this.dir = dir;
        this.durability = java.util.Objects.requireNonNull(durability);
        this.topicDurability = java.util.Map.copyOf(topicDurability);
        this.segmentBytes = segmentBytes;
        this.retention = java.util.Objects.requireNonNull(retention);
        this.clockMs = clockMs;
    }

    /** The writer default for {@code topic}: its override, else the module default. */
    Durability durability(String topic) {
        return this.topicDurability.getOrDefault(topic, this.durability);
    }

    /**
     * Parses {@code none|async|group|each} (case-insensitive), and {@code topic:mode,topic:mode} lists for the
     * per-topic overrides. Throws {@link IllegalArgumentException} naming the bad entry.
     */
    static Durability parseDurability(String v) {
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> Durability.NONE;
            case "async" -> Durability.ASYNC;
            case "group" -> Durability.GROUP;
            case "each" -> Durability.EACH;
            default -> throw new IllegalArgumentException("durability must be none, async, group or each: " + v);
        };
    }

    static java.util.Map<String, Durability> parseTopicDurability(String list) {
        var out = new java.util.HashMap<String, Durability>();
        for (var part : list.split(",")) {
            if (part.isBlank()) continue;
            int c = part.lastIndexOf(':');
            var topic = c < 0 ? "" : part.substring(0, c).trim();
            if (!validName(topic)) throw new IllegalArgumentException("bad topic in durability override: " + part.trim());
            out.put(topic, parseDurability(part.substring(c + 1)));
        }
        return out;
    }

    LogRetention retention() {
        return this.retention;
    }

    /**
     * Applies {@link LogRetention} to every open topic; returns the number of segments deleted. Deletion holds the
     * topic's {@code reads} write lock, so a concurrent fetch never reads a segment being removed. The writer only touches
     * the active segment, which is never deleted.
     */
    int enforceRetention() throws IOException {
        if (this.retention.unlimited()) return 0;
        int deleted = 0;
        long now = this.clockMs.getAsLong();
        for (var l : this.logs.values()) {
            l.reads().writeLock().lock();
            try {
                long total = l.log().totalBytes();
                for (var seg : l.log().segments()) {
                    if (seg.active() || !this.retention.expired(total, seg.lastModifiedMs(), now)) break;
                    l.log().deleteSegment(seg.baseOffset());
                    total -= seg.bytes();
                    deleted++;
                }
            } finally {
                l.reads().writeLock().unlock();
            }
        }
        return deleted;
    }

    static boolean validName(String topic) {
        return NAME.matcher(topic).matches() && !topic.equals(".") && !topic.equals("..");
    }

    /** The topic's log, opening (and creating) it when {@code create}; null when absent and not created. */
    Log get(String topic, boolean create) throws IOException {
        if (this.closed) throw new IllegalStateException("closed");
        var l = this.logs.get(topic);
        if (l != null || (!create && !java.nio.file.Files.isDirectory(this.dir.resolve(topic)))) return l;
        this.opening.lock(); // opens do file I/O, so not inside computeIfAbsent (design review M12)
        try {
            if (this.closed) throw new IllegalStateException("closed");
            l = this.logs.get(topic);
            if (l == null) {
                var log = SegmentedLog.open(this.dir.resolve(topic), this.segmentBytes);
                l = new Log(log, new LedgerWriter(log, durability(topic)), new java.util.concurrent.locks.ReentrantReadWriteLock());
                this.logs.put(topic, l);
            }
            return l;
        } finally {
            this.opening.unlock();
        }
    }

    @Override
    public void close() {
        this.opening.lock();
        try {
            this.closed = true;
        } finally {
            this.opening.unlock();
        }
        for (var l : this.logs.values()) {
            try {
                l.writer().close();
                l.log().close();
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                System.err.println("WARN: closing log: " + e);
            }
        }
        this.logs.clear();
    }
}
