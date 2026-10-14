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
    /** Keyed topics: records are {@code [keyLen:int][key][value]} and sealed segments keep only each key's latest record. */
    private volatile java.util.Set<String> compacted = java.util.Set.of();
    /** Serializes maintenance passes (retention and compaction) so a segment is never compacted and deleted at once. */
    private final java.util.concurrent.locks.ReentrantLock maintenance = new java.util.concurrent.locks.ReentrantLock();
    private volatile ConsumerOffsets offsets = new ConsumerOffsets(this, ConsumerOffsets.DEFAULT_EXPIRY_MS, System::currentTimeMillis);

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
        this.offsets = new ConsumerOffsets(this, ConsumerOffsets.DEFAULT_EXPIRY_MS, clockMs);
    }

    // ---- standby replication (durable-log-plan §3) ----
    private final ReplicaAcks replicaAcks = new ReplicaAcks();
    private volatile byte[] replicaToken;
    private volatile java.util.Set<String> replicatedTopics = java.util.Set.of();
    private volatile java.util.Set<String> standbyTopics = java.util.Set.of();
    private volatile long replicaTimeoutMs = MessagingHttp.ACK_TIMEOUT_MS;

    ReplicaAcks replicaAcks() { return this.replicaAcks; }

    /** Shared secret a standby sends with its fetches; acks without it are ignored. Null disables replication acks. */
    void replicaToken(String token) {
        this.replicaToken = token == null || token.isEmpty() ? null : token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    boolean replicaTokenMatches(String presented) {
        var t = this.replicaToken;
        return t != null && presented != null
                && java.security.MessageDigest.isEqual(t, presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    boolean replicationConfigured() { return this.replicaToken != null; }

    /** Primary: topics whose every produce is {@code replicated} (the header can only strengthen, never weaken). */
    void replicatedTopics(java.util.Set<String> t) { this.replicatedTopics = java.util.Set.copyOf(t); }

    boolean replicated(String topic) { return this.replicatedTopics.contains(topic); }

    /** Standby: topics fed by the primary; client produces to them are refused. */
    void standbyTopics(java.util.Set<String> t) { this.standbyTopics = java.util.Set.copyOf(t); }

    boolean standby(String topic) { return this.standbyTopics.contains(topic); }

    void replicaTimeoutMs(long ms) { this.replicaTimeoutMs = ms; }

    long replicaTimeoutMs() { return this.replicaTimeoutMs; }

    ConsumerOffsets offsets() {
        return this.offsets;
    }

    /** Sets how long a group's commit keeps pinning retention after it was made. */
    void offsetsExpiryMs(long ms) {
        if (ms < 1) throw new IllegalArgumentException("offsets expiry must be positive");
        this.offsets = new ConsumerOffsets(this, ms, this.clockMs);
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
        this.maintenance.lock();
        try {
            return enforceRetentionLocked();
        } finally {
            this.maintenance.unlock();
        }
    }

    private int enforceRetentionLocked() throws IOException {
        int deleted = 0;
        long now = this.clockMs.getAsLong();
        for (var e : this.logs.entrySet()) {
            var l = e.getValue();
            long floor = this.offsets.floor(e.getKey()); // a committed consumer still needs history from here
            l.reads().writeLock().lock();
            try {
                long total = l.log().totalBytes();
                for (var seg : l.log().segments()) {
                    if (seg.active() || seg.nextOffset() > floor || !this.retention.expired(total, seg.lastModifiedMs(), now)) break;
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

    /** Marks {@code topics} as keyed (compacted). */
    void compacted(java.util.Set<String> topics) {
        for (var t : topics) if (!validName(t)) throw new IllegalArgumentException("bad compacted topic: " + t);
        this.compacted = java.util.Set.copyOf(topics);
    }

    boolean hasCompactedTopics() {
        return !this.compacted.isEmpty();
    }

    boolean compacted(String topic) {
        return this.compacted.contains(topic) || ConsumerOffsets.TOPIC.equals(topic);
    }

    static java.util.Set<String> parseTopics(String list) {
        var out = new java.util.HashSet<String>();
        for (var t : list.split(",")) if (!t.isBlank()) out.add(t.trim());
        return out;
    }

    /** The key of a keyed record ({@code [keyLen:int][key][value]}), or null if the record is not keyed. */
    static java.nio.ByteBuffer key(byte[] record) {
        if (record.length < 4) return null;
        int len = ((record[0] & 0xff) << 24) | ((record[1] & 0xff) << 16) | ((record[2] & 0xff) << 8) | (record[3] & 0xff);
        if (len < 0 || len > record.length - 4) return null;
        return java.nio.ByteBuffer.wrap(record, 4, len).slice();
    }

    /** What one compaction pass did. */
    record CompactionReport(int segments, long reclaimedBytes) { }

    /**
     * Compacts every open keyed topic: a sealed segment is rewritten keeping only the batches that hold the latest
     * record of at least one key (batch granularity: a batch with any live record is kept whole). Only records the
     * log has made durable count as "latest" (the log is forced first), so a crash can never lose a key's only value.
     * The copy runs without locks; the swap takes the topic's {@code reads} write lock (in-flight fetches finish first).
     */
    CompactionReport compact() throws IOException {
        if (this.compacted.isEmpty() && !this.logs.containsKey(ConsumerOffsets.TOPIC)) return new CompactionReport(0, 0);
        this.maintenance.lock();
        try {
            int segs = 0;
            long reclaimed = 0;
            for (var e : this.logs.entrySet()) {
                if (!compacted(e.getKey())) continue;
                var l = e.getValue();
                long end = l.writer().snapshot().nextOffset();
                l.log().force(); // everything below `end` is now durable
                var latest = new java.util.HashMap<java.nio.ByteBuffer, Long>();
                l.log().scanFrom(l.log().startOffset(), b -> {
                    for (int i = 0; i < b.records().size(); i++) {
                        long o = b.baseOffset() + i;
                        if (o >= end) return;
                        var k = key(b.records().get(i));
                        if (k != null) latest.put(k, o);
                    }
                });
                for (var seg : l.log().segments()) {
                    if (seg.active() || seg.nextOffset() > end) break;
                    var live = new java.util.HashSet<Long>(); // batch base offsets to keep
                    boolean[] drop = {false};
                    l.log().scanFrom(seg.baseOffset(), b -> {
                        if (b.baseOffset() >= seg.nextOffset()) return;
                        boolean keep = false;
                        for (int i = 0; i < b.records().size() && !keep; i++) {
                            var k = key(b.records().get(i));
                            keep = k == null || latest.get(k) == b.baseOffset() + i;
                        }
                        if (keep) live.add(b.baseOffset());
                        else drop[0] = true;
                    });
                    if (!drop[0]) continue;
                    long bytes = l.log().prepareCompaction(seg.baseOffset(), live::contains);
                    l.reads().writeLock().lock();
                    try {
                        l.log().commitCompaction(seg.baseOffset());
                    } finally {
                        l.reads().writeLock().unlock();
                    }
                    segs++;
                    reclaimed += seg.bytes() - bytes;
                }
            }
            return new CompactionReport(segs, reclaimed);
        } finally {
            this.maintenance.unlock();
        }
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
