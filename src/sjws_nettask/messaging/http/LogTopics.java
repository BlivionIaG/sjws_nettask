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
    record Log(SegmentedLog log, LedgerWriter writer, java.util.concurrent.locks.ReentrantReadWriteLock reads, boolean topicCompacted,
            sjws_nettask.messaging.ledger.Replica replica) {
        Log(SegmentedLog log, LedgerWriter writer, java.util.concurrent.locks.ReentrantReadWriteLock reads) {
            this(log, writer, reads, false, null);
        }

        Log(SegmentedLog log, LedgerWriter writer, java.util.concurrent.locks.ReentrantReadWriteLock reads, boolean topicCompacted) {
            this(log, writer, reads, topicCompacted, null);
        }

        /** The append seam for this topic: the writer itself, or its replica when HA replication is installed. */
        sjws_nettask.messaging.ledger.PartitionAppender appender() {
            var r = replica;
            return r == null ? writer : r;
        }
    }

    /** Set once by the HA bootstrap ({@link #replication}); null with HA off. */
    private volatile sjws_nettask.messaging.ledger.PartitionReplicator replicator;

    /**
     * Installs replication (msg-replication-sketch phase A) before any topic is open; opens every replicated topic and
     * {@code __offsets} now so this node receives their entries as a follower too.
     */
    void replication(sjws_nettask.messaging.ledger.PartitionReplicator r) throws IOException {
        this.opening.lock();
        try {
            if (this.replicator != null || !this.logs.isEmpty()) throw new IllegalStateException("replication must be installed first, once");
            this.replicator = java.util.Objects.requireNonNull(r);
        } finally {
            this.opening.unlock();
        }
        for (var t : r.topics()) get(t, true);
        get(LogOffsets.TOPIC, true);
    }

    sjws_nettask.messaging.ledger.PartitionReplicator replicator() {
        return this.replicator;
    }

    private final Path dir;
    private final Durability durability;
    /** Per-topic writer default overriding {@link #durability} (docs/design/partition-log.md R8, gap G3). */
    private final java.util.Map<String, Durability> topicDurability;
    private final long segmentBytes;
    private final LogRetention retention;
    /** Topics with key compaction (docs/design/durable-log-plan.md §1); empty = compaction off. */
    private final java.util.Set<String> compacted;
    /** Serializes maintenance (retention, compaction) so a segment is never deleted while a compaction pass reads it. */
    private final java.util.concurrent.locks.ReentrantLock maintenance = new java.util.concurrent.locks.ReentrantLock();
    private final java.util.function.LongSupplier clockMs;
    private final ConcurrentHashMap<String, Log> logs = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock opening = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean closed;
    /** Consumer offsets (durable-log-plan §2); lazily backed by the {@code __offsets} topic. */
    private final LogOffsets offsets;
    /**
     * With replication on: the commits of topics outside {@code ha.topics}, which stay local, in the local-only
     * {@code __offsets_local} topic. Never opened with HA off or when no local topic commits.
     */
    private final LogOffsets localOffsets;

    LogTopics(Path dir, Durability durability) {
        this(dir, durability, SegmentedLog.DEFAULT_SEGMENT_BYTES, LogRetention.NONE, System::currentTimeMillis);
    }

    LogTopics(Path dir, Durability durability, long segmentBytes, LogRetention retention, java.util.function.LongSupplier clockMs) {
        this(dir, durability, java.util.Map.of(), segmentBytes, retention, clockMs);
    }

    LogTopics(Path dir, Durability durability, java.util.Map<String, Durability> topicDurability, long segmentBytes,
            LogRetention retention, java.util.function.LongSupplier clockMs) {
        this(dir, durability, topicDurability, segmentBytes, retention, java.util.Set.of(), clockMs);
    }

    LogTopics(Path dir, Durability durability, java.util.Map<String, Durability> topicDurability, long segmentBytes,
            LogRetention retention, java.util.Set<String> compacted, java.util.function.LongSupplier clockMs) {
        this(dir, durability, topicDurability, segmentBytes, retention, compacted, LogOffsets.DEFAULT_EXPIRY_MS, clockMs);
    }

    LogTopics(Path dir, Durability durability, java.util.Map<String, Durability> topicDurability, long segmentBytes,
            LogRetention retention, java.util.Set<String> compacted, long offsetsExpiryMs, java.util.function.LongSupplier clockMs) {
        this.offsets = new LogOffsets(this, offsetsExpiryMs, clockMs);
        this.localOffsets = new LogOffsets(this, LogOffsets.LOCAL_TOPIC, offsetsExpiryMs, clockMs);
        this.compacted = java.util.Set.copyOf(compacted);
        this.dir = dir;
        this.durability = java.util.Objects.requireNonNull(durability);
        this.topicDurability = java.util.Map.copyOf(topicDurability);
        this.segmentBytes = segmentBytes;
        this.retention = java.util.Objects.requireNonNull(retention);
        this.clockMs = clockMs;
    }

    /** The writer default for {@code topic}: its override, else the module default. */
    Durability durability(String topic) {
        var d = this.topicDurability.get(topic);
        if (d != null) return d;
        return offsetsTopic(topic) ? LogOffsets.DEFAULT_DURABILITY : this.durability;
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
            var entry = part.trim();
            var topic = c < 0 ? "" : part.substring(0, c).trim();
            // User-topic pattern + no __* prefix; bad mode is the same shape of error so the config key wrapper can name the entry.
            if (!validUserTopic(topic)) throw new IllegalArgumentException("invalid or reserved topic entry \"" + entry + "\"");
            try {
                out.put(topic, parseDurability(part.substring(c + 1)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid topic durability entry \"" + entry + "\": " + e.getMessage(), e);
            }
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
            loadOffsetsIfPresent();
            return enforceRetentionLocked();
        } finally {
            this.maintenance.unlock();
        }
    }

    boolean compactionEnabled() {
        return !this.compacted.isEmpty();
    }

    private volatile Runnable offsetsListener;
    private volatile boolean offsetsUsed;

    /** Called once, on the first offset commit (the module starts its maintenance timer). */
    void onOffsetsUse(Runnable r) {
        this.offsetsListener = r;
    }

    void offsetsUsed() {
        if (this.offsetsUsed) return;
        this.offsetsUsed = true;
        var r = this.offsetsListener;
        if (r != null) r.run();
    }

    LogOffsets offsets() {
        return this.offsets;
    }

    /**
     * The offsets that hold {@code topic}'s commits: {@code __offsets}, except for a topic that stays local on a
     * replicating node (outside {@code ha.topics}), whose commits stay local too ({@code __offsets_local}).
     */
    LogOffsets offsets(String topic) {
        return local(topic) ? this.localOffsets : this.offsets;
    }

    /** Replication is on and {@code topic} is not replicated: it keeps the plain local path. */
    private boolean local(String topic) {
        var rep = this.replicator;
        return rep != null && !LogOffsets.TOPIC.equals(topic) && !rep.topics().contains(topic);
    }

    static boolean offsetsTopic(String topic) {
        return LogOffsets.TOPIC.equals(topic) || LogOffsets.LOCAL_TOPIC.equals(topic);
    }

    /** Whether {@code topic} is key-compacted: listed, or an internal offsets topic. */
    boolean isCompacted(String topic) {
        return this.compacted.contains(topic) || offsetsTopic(topic);
    }

    /** Whether there is any maintenance work: retention, a compacted topic, or consumer offsets on disk or in use. */
    boolean maintenanceNeeded() {
        return !this.retention.unlimited() || !this.compacted.isEmpty() || this.logs.containsKey(LogOffsets.TOPIC)
                || java.nio.file.Files.isDirectory(this.dir.resolve(LogOffsets.TOPIC))
                || this.logs.containsKey(LogOffsets.LOCAL_TOPIC);
    }

    /**
     * One key-compaction pass over every open compacted topic ({@link LogCompaction}). Copies are written without
     * blocking fetches or produces; only the swap of each rewritten segment takes the topic's {@code reads} write lock,
     * so an in-flight fetch finishes on the old file first.
     */
    LogCompaction.Result compact() throws IOException {
        if (this.compacted.isEmpty() && !this.logs.containsKey(LogOffsets.TOPIC)
                && !this.logs.containsKey(LogOffsets.LOCAL_TOPIC)) return LogCompaction.Result.NONE;
        var total = LogCompaction.Result.NONE;
        this.maintenance.lock();
        try {
            applyPendingDrops();
            loadOffsetsIfPresent();
            for (var e : this.logs.entrySet()) {
                var l = e.getValue();
                if (!l.topicCompacted()) continue;
                var prepared = LogCompaction.prepare(l.log(), offsets(e.getKey()).floor(e.getKey()));
                for (var p : prepared) {
                    l.reads().writeLock().lock();
                    try {
                        l.log().commitCompaction(p.baseOffset());
                    } finally {
                        l.reads().writeLock().unlock();
                    }
                    total = total.plus(new LogCompaction.Result(1, p.bytesBefore(), p.bytesAfter(), p.batchesDropped()));
                }
            }
        } finally {
            this.maintenance.unlock();
        }
        return total;
    }

    /** Both offsets stores; the local one only exists on a replicating node. */
    private void loadOffsetsIfPresent() throws IOException {
        this.offsets.loadIfPresent();
        if (this.replicator != null) this.localOffsets.loadIfPresent();
    }

    private int enforceRetentionLocked() throws IOException {
        int deleted = 0;
        long now = this.clockMs.getAsLong();
        applyPendingDrops();
        for (var e : this.logs.entrySet()) {
            if (offsetsTopic(e.getKey())) continue; // bounded by compaction; retention would lose commits
            var l = e.getValue();
            long floor = offsets(e.getKey()).floor(e.getKey()); // a committed-but-unfetched group pins its history (§2)
            // #270: replicated retention when the replica supports it; otherwise keep local retention
            // (Replica.advanceLogStart default / RaftReplica without snapshot runtime).
            if (l.replica() != null && l.replica().replicatedRetention()) {
                proposeRetention(l, floor, now);
                continue;
            }
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

    /**
     * HA on only (so {@code ReplicaHooks} never loads with HA off): the replica of {@code topic} with its #270 hooks.
     * Retention is replicated: {@link #dropBelow} runs on every replica when the leader's decision applies.
     */
    private sjws_nettask.messaging.ledger.Replica openReplica(sjws_nettask.messaging.ledger.PartitionReplicator rep, String topic,
            SegmentedLog log, LedgerWriter writer, java.util.concurrent.locks.ReentrantReadWriteLock reads) throws IOException {
        java.util.function.Consumer<java.util.List<byte[]>> applied = LogOffsets.TOPIC.equals(topic) ? this.offsets::applied : recs -> { };
        Runnable reloaded = LogOffsets.TOPIC.equals(topic) ? this.offsets::reload : () -> { };
        var hooks = new sjws_nettask.messaging.ledger.ReplicaHooks(applied, x -> dropBelow(topic, log, reads, x), reads.writeLock(),
                reloaded, null);
        return rep.open(topic, writer, durability(topic), hooks);
    }

    /** Replicated retention cuts not yet applied because a maintenance pass held the lock: topic to offset. */
    private final ConcurrentHashMap<String, Long> pendingDrops = new ConcurrentHashMap<>();

    /**
     * A replicated "advance log start to {@code x}" (#270): deletes whole sealed segments ending at or below it, under
     * the reads write lock. Runs on the replica's apply thread; when a maintenance pass holds the lock it is left for
     * that pass (or the next cut) instead of waiting behind it.
     */
    private void dropBelow(String topic, SegmentedLog log, java.util.concurrent.locks.ReentrantReadWriteLock reads, long x) {
        this.pendingDrops.merge(topic, x, Math::max);
        if (!this.maintenance.tryLock()) return;
        try {
            applyPendingDrops();
        } finally {
            this.maintenance.unlock();
        }
    }

    /** Under {@link #maintenance}: applies every pending replicated cut. */
    private void applyPendingDrops() {
        for (var topic : java.util.List.copyOf(this.pendingDrops.keySet())) {
            Long x = this.pendingDrops.remove(topic);
            if (x == null) continue;
            var l = this.logs.get(topic);
            if (l == null) { // the replica is still opening (a cut recovered on restart): the next pass applies it
                this.pendingDrops.merge(topic, x, Math::max);
                continue;
            }
            l.reads().writeLock().lock();
            try {
                for (var seg : l.log().segments()) {
                    if (seg.active() || seg.nextOffset() > x) break;
                    l.log().deleteSegment(seg.baseOffset());
                }
            } catch (IOException e) {
                System.err.println("WARN: replicated retention of " + topic + " below " + x + ": " + e);
            } finally {
                l.reads().writeLock().unlock();
            }
        }
    }

    /** Leader of a replicated topic: the cut local retention would make, proposed as a replicated decision. */
    private void proposeRetention(Log l, long floor, long now) throws IOException {
        var rep = l.replica();
        if (!rep.leaderReady()) return;
        long total = l.log().totalBytes(), cut = -1;
        for (var seg : l.log().segments()) {
            if (seg.active() || seg.nextOffset() > floor || !this.retention.expired(total, seg.lastModifiedMs(), now)) break;
            cut = seg.nextOffset();
            total -= seg.bytes();
        }
        if (cut > rep.logStartOffset()) {
            rep.advanceLogStart(cut).exceptionally(err -> null); // a lost proposal is retried by the next pass
        }
    }

    static boolean validName(String topic) {
        return NAME.matcher(topic).matches() && !topic.equals(".") && !topic.equals("..");
    }

    /** A client-facing topic name: {@link #validName} and not an internal {@code __}-prefixed name (#217). */
    static boolean validUserTopic(String topic) {
        return validName(topic) && !topic.startsWith("__");
    }

    /** The topic's log, opening (and creating) it when {@code create}; null when absent and not created. */
    Log get(String topic, boolean create) throws IOException {
        if (this.closed) throw new IllegalStateException("closed");
        // Open/create of a user topic must not invent an internal __ name (HTTP already refuses; this is the root).
        if (create && topic.startsWith("__") && !LogOffsets.TOPIC.equals(topic) && !LogOffsets.LOCAL_TOPIC.equals(topic)) {
            throw new IllegalArgumentException("topics starting with __ are internal: " + topic);
        }
        var l = this.logs.get(topic);
        if (l != null || (!create && !java.nio.file.Files.isDirectory(this.dir.resolve(topic)))) return l;
        this.opening.lock(); // opens do file I/O, so not inside computeIfAbsent (design review M12)
        try {
            if (this.closed) throw new IllegalStateException("closed");
            l = this.logs.get(topic);
            if (l == null) {
                var rep = this.replicator;
                var log = SegmentedLog.open(this.dir.resolve(topic), this.segmentBytes);
                LedgerWriter writer;
                try {
                    writer = new LedgerWriter(log, durability(topic));
                } catch (RuntimeException e) {
                    try {
                        log.close();
                    } catch (IOException closing) {
                        e.addSuppressed(closing);
                    }
                    throw e;
                }
                var reads = new java.util.concurrent.locks.ReentrantReadWriteLock();
                sjws_nettask.messaging.ledger.Replica replica = null;
                // A topic outside ha.topics (and __offsets_local) keeps the plain local path: no replica, no 503.
                if (rep != null && !local(topic)) {
                    try {
                        replica = openReplica(rep, topic, log, writer, reads);
                    } catch (IOException | RuntimeException e) {
                        // Refused (HA startup checks): leave nothing open behind the refusal.
                        try {
                            writer.close();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            log.close();
                        } catch (IOException closing) {
                            e.addSuppressed(closing);
                        }
                        throw e;
                    }
                }
                l = new Log(log, writer, reads, isCompacted(topic), replica);
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
                if (l.replica() != null) l.replica().close();
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
