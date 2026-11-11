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

import java.nio.file.Path;

import sjws_nettask.OptionalModule;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Mounts produce / fetch on plain log topics at {@code /messaging/v1} (docs/design/partition-log.md §2.2). Off by
 * default: loaded by name from {@code OptionalModules} only when {@code sjws.messaging.enabled} is true.
 */
public final class MessagingModule implements OptionalModule, HandlerContributor {

    static final String DIR_PROPERTY = "sjws.messaging.dir";
    static final String DIR_ENV = "SJWS_MESSAGING_DIR";

    static final String SEGMENT_BYTES_PROPERTY = "sjws.messaging.segment.bytes";
    static final String SEGMENT_BYTES_ENV = "SJWS_MESSAGING_SEGMENT_BYTES";
    static final String RETENTION_BYTES_PROPERTY = "sjws.messaging.retention.bytes";
    static final String RETENTION_BYTES_ENV = "SJWS_MESSAGING_RETENTION_BYTES";
    static final String RETENTION_MS_PROPERTY = "sjws.messaging.retention.ms";
    static final String RETENTION_MS_ENV = "SJWS_MESSAGING_RETENTION_MS";
    static final String RETENTION_CHECK_MS_PROPERTY = "sjws.messaging.retention.check.ms";
    static final String RETENTION_CHECK_MS_ENV = "SJWS_MESSAGING_RETENTION_CHECK_MS";

    static final String DURABILITY_PROPERTY = "sjws.messaging.durability";
    static final String DURABILITY_ENV = "SJWS_MESSAGING_DURABILITY";
    static final String TOPIC_DURABILITY_PROPERTY = "sjws.messaging.durability.topics";
    static final String TOPIC_DURABILITY_ENV = "SJWS_MESSAGING_DURABILITY_TOPICS";

    static final String OFFSETS_EXPIRY_MS_PROPERTY = "sjws.messaging.offsets.expiry.ms";
    static final String OFFSETS_EXPIRY_MS_ENV = "SJWS_MESSAGING_OFFSETS_EXPIRY_MS";
    static final String COMPACTION_TOPICS_PROPERTY = "sjws.messaging.compaction.topics";
    static final String COMPACTION_TOPICS_ENV = "SJWS_MESSAGING_COMPACTION_TOPICS";
    /**
     * M2: accept {@code Producer-Id}/{@code Producer-Seq} on replicated topics ({@code true}/{@code false}, default
     * false).
     *
     * <p><b>Producer ids are not scoped to a client yet.</b> Every client of a topic shares one producer id space and one
     * table of 4096 producers: two clients that pick the same id interfere (refusals, lost dedup). Do not enable this
     * for topics written by mutually untrusted clients until principal-scoped producers land
     * (https://github.com/BlivionIaG/sjws_nettask/issues/283, which blocks turning this on by default).
     *
     * <p>Older builds: a leader proposes the first producer entry only once every voter has advertised that it applies
     * them (an older node would skip them and its ledger would diverge); until then such a produce is 503
     * producer-dedup-unavailable. Downgrading a node below M2, or adding an older voter, is unsupported once a producer
     * entry has committed.
     */
    static final String PRODUCER_DEDUP_PROPERTY = "sjws.messaging.producer.dedup";
    static final String PRODUCER_DEDUP_ENV = "SJWS_MESSAGING_PRODUCER_DEDUP";

    private final LogTopics topics;
    private volatile MessagingHooks hooks = MessagingHooks.NONE;
    /** Mounted handler; updated when {@link #hooks(MessagingHooks)} runs after contribute. */
    private volatile MessagingHttp http;
    private volatile boolean producerDedup = flag(setting(PRODUCER_DEDUP_PROPERTY, PRODUCER_DEDUP_ENV, ""));
    /** Null unless a retention limit or a compacted topic is set: no thread when both are off. */
    private java.util.concurrent.ScheduledExecutorService retentionTimer;
    private final long checkMs;
    private boolean closed;
    /** Guards {@link #retentionTimer} and {@link #closed}. */
    private final java.util.concurrent.locks.ReentrantLock timerLock = new java.util.concurrent.locks.ReentrantLock();

    public MessagingModule() {
        this(Path.of(setting(DIR_PROPERTY, DIR_ENV, "messaging-data")),
                number(setting(SEGMENT_BYTES_PROPERTY, SEGMENT_BYTES_ENV, ""), SegmentedLog.DEFAULT_SEGMENT_BYTES),
                new LogRetention(number(setting(RETENTION_BYTES_PROPERTY, RETENTION_BYTES_ENV, ""), 0),
                        number(setting(RETENTION_MS_PROPERTY, RETENTION_MS_ENV, ""), 0)),
                number(setting(RETENTION_CHECK_MS_PROPERTY, RETENTION_CHECK_MS_ENV, ""), 10_000),
                durability(setting(DURABILITY_PROPERTY, DURABILITY_ENV, "")),
                topicDurability(setting(TOPIC_DURABILITY_PROPERTY, TOPIC_DURABILITY_ENV, "")),
                compactionTopics(setting(COMPACTION_TOPICS_PROPERTY, COMPACTION_TOPICS_ENV, "")),
                Math.max(1, number(setting(OFFSETS_EXPIRY_MS_PROPERTY, OFFSETS_EXPIRY_MS_ENV, ""), LogOffsets.DEFAULT_EXPIRY_MS)));
    }

    /** {@code true} or {@code false} (any case, empty = false); anything else is logged and is false. */
    static boolean flag(String v) {
        if (v.isEmpty() || v.equalsIgnoreCase("false")) return false;
        if (v.equalsIgnoreCase("true")) return true;
        System.err.println("WARN: " + PRODUCER_DEDUP_PROPERTY + " must be true or false, got \"" + v + "\"; producer dedup stays off");
        return false;
    }

    /**
     * Topics with key compaction. A bad or reserved entry fails startup ({@link OptionalModule.Refused})
     * naming {@link #COMPACTION_TOPICS_PROPERTY} and the offending entry; the list is never emptied quietly.
     */
    static java.util.Set<String> compactionTopics(String v) {
        try {
            return LogCompaction.parseTopics(v);
        } catch (IllegalArgumentException e) {
            throw new OptionalModule.Refused(COMPACTION_TOPICS_PROPERTY + ": " + e.getMessage());
        }
    }

    /** The module default; a bad value is logged and falls back to the writer default (ASYNC). */
    static Durability durability(String v) {
        if (v.isEmpty()) return LedgerWriter.DEFAULT_DURABILITY;
        try {
            return LogTopics.parseDurability(v);
        } catch (IllegalArgumentException e) {
            System.err.println("WARN: " + e.getMessage());
            return LedgerWriter.DEFAULT_DURABILITY;
        }
    }

    /**
     * Per-topic durability overrides. A bad or reserved entry fails startup ({@link OptionalModule.Refused})
     * naming {@link #TOPIC_DURABILITY_PROPERTY} and the offending entry; other overrides in the same list are
     * not dropped (the whole list is refused).
     */
    static java.util.Map<String, Durability> topicDurability(String v) {
        try {
            return LogTopics.parseTopicDurability(v);
        } catch (IllegalArgumentException e) {
            throw new OptionalModule.Refused(TOPIC_DURABILITY_PROPERTY + ": " + e.getMessage());
        }
    }

    MessagingModule(Path dir) {
        this(dir, SegmentedLog.DEFAULT_SEGMENT_BYTES, LogRetention.NONE, 10_000);
    }

    MessagingModule(Path dir, long segmentBytes, LogRetention retention, long checkMs) {
        this(dir, segmentBytes, retention, checkMs, LedgerWriter.DEFAULT_DURABILITY, java.util.Map.of());
    }

    MessagingModule(Path dir, long segmentBytes, LogRetention retention, long checkMs, Durability durability,
            java.util.Map<String, Durability> topicDurability) {
        this(dir, segmentBytes, retention, checkMs, durability, topicDurability, java.util.Set.of());
    }

    MessagingModule(Path dir, long segmentBytes, LogRetention retention, long checkMs, Durability durability,
            java.util.Map<String, Durability> topicDurability, java.util.Set<String> compactionTopics) {
        this(dir, segmentBytes, retention, checkMs, durability, topicDurability, compactionTopics, LogOffsets.DEFAULT_EXPIRY_MS);
    }

    MessagingModule(Path dir, long segmentBytes, LogRetention retention, long checkMs, Durability durability,
            java.util.Map<String, Durability> topicDurability, java.util.Set<String> compactionTopics, long offsetsExpiryMs) {
        this.topics = new LogTopics(dir, durability, topicDurability, segmentBytes, retention, compactionTopics,
                offsetsExpiryMs, System::currentTimeMillis);
        this.checkMs = checkMs;
        // No thread while there is no maintenance work; the first offset commit starts it (__offsets is compacted).
        this.topics.onOffsetsUse(this::ensureTimer);
        if (this.topics.maintenanceNeeded()) ensureTimer();
    }

    private void ensureTimer() {
        this.timerLock.lock();
        try {
            if (this.retentionTimer != null || this.closed) return;
            this.retentionTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> Thread.ofPlatform().name("messaging-retention").daemon().unstarted(r));
            this.retentionTimer.scheduleWithFixedDelay(this::retain, this.checkMs, this.checkMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            this.timerLock.unlock();
        }
    }

    void retain() {
        try {
            this.topics.enforceRetention();
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("WARN: messaging retention: " + e);
        }
        try {
            this.topics.compact();
        } catch (java.io.IOException | RuntimeException e) {
            System.err.println("WARN: messaging compaction: " + e);
        }
    }

    LogTopics topics() {
        return this.topics;
    }

    boolean retentionScheduled() {
        this.timerLock.lock();
        try {
            return this.retentionTimer != null;
        } finally {
            this.timerLock.unlock();
        }
    }

    static long number(String v, long dflt) {
        if (v.isEmpty()) return dflt;
        try {
            long n = Long.parseLong(v);
            return n < 0 ? dflt : n;
        } catch (NumberFormatException e) {
            System.err.println("WARN: messaging setting not a number: " + v);
            return dflt;
        }
    }

    static String setting(String property, String env, String dflt) {
        var v = System.getProperty(property);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    /**
     * HA on (msg-replication-sketch phase A): replicates the given topics and {@code __offsets} through {@code r}. The
     * HA bootstrap calls this once, before the server mounts the module; with HA off it is never called and messaging
     * appends straight to the local writer.
     */
    public void replication(sjws_nettask.messaging.ledger.PartitionReplicator r) throws java.io.IOException {
        this.topics.replication(r);
    }

    /** Overrides {@link #PRODUCER_DEDUP_PROPERTY}; call before the server mounts the module. */
    public void producerDedup(boolean on) {
        this.producerDedup = on;
    }

    /**
     * Installs the metrics hooks (see {@link MessagingHooks}). Safe before or after
     * {@link #contribute}: when the HTTP handler is already mounted, it is updated too so a
     * deferred arm (metrics off at start, armed later) actually records.
     */
    public void hooks(MessagingHooks h) {
        this.hooks = java.util.Objects.requireNonNull(h);
        var http = this.http;
        if (http != null) {
            http.hooks(h);
        }
    }

    @Override
    public void contribute(HandlerMounts mounts) {
        var h = new MessagingHttp(this.topics);
        h.hooks(this.hooks);
        h.producerDedup(this.producerDedup);
        this.http = h;
        mounts.mount("/messaging/v1", h);
    }

    @Override
    public void close() {
        this.timerLock.lock();
        try {
            this.closed = true;
            if (this.retentionTimer != null) this.retentionTimer.shutdownNow();
        } finally {
            this.timerLock.unlock();
        }
        this.topics.close();
    }
}
