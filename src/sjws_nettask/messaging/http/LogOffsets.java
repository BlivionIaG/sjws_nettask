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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import sjws_nettask.messaging.ledger.Durability;

/**
 * Consumer offsets for plain log topics (docs/design/durable-log-plan.md §2). Each commit is one record of the internal
 * compacted topic {@link #TOPIC}, keyed {@code topic\0group} with the §1 key framing, value
 * {@code [offset:long][commitMs:long]}. There is no other file: recovery replays that topic, last record per key wins.
 *
 * <p>The committed offset is the next offset to fetch (Kafka semantics). The oldest committed offset of every group
 * that committed within {@code expiryMs} is the topic's retention and compaction floor ({@link #floor}).
 *
 * <p>Cost when unused: nothing is opened or replayed until the first commit or read, or until maintenance finds an
 * existing {@code __offsets} directory.
 *
 * <p>With HA replication on, {@link #TOPIC} is replicated and holds the commits of the replicated topics only; topics
 * outside {@code ha.topics} stay local, and so do their commits, in the local-only {@link #LOCAL_TOPIC} (same format).
 */
final class LogOffsets {

    static final String TOPIC = "__offsets";
    /** Commits of the topics that stay local on a replicating node (never replicated, never used with HA off). */
    static final String LOCAL_TOPIC = "__offsets_local";
    static final long DEFAULT_EXPIRY_MS = 7L * 24 * 3600 * 1000;
    static final Durability DEFAULT_DURABILITY = Durability.GROUP;

    /** One group's commit; {@code seq} orders updates the same way the log does. */
    record Commit(long offset, long commitMs, long seq) { }

    private final LogTopics topics;
    /** The offsets topic this instance is backed by: {@link #TOPIC} or {@link #LOCAL_TOPIC}. */
    private final String name;
    private final long expiryMs;
    private final java.util.function.LongSupplier clockMs;
    /** topic → group → latest commit. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Commit>> commits = new ConcurrentHashMap<>();
    /** Orders enqueue (= log order) with the sequence used to resolve out-of-order completions. */
    private final ReentrantLock order = new ReentrantLock();
    private long seq;
    private volatile boolean loaded;
    private final ReentrantLock loading = new ReentrantLock();

    LogOffsets(LogTopics topics, long expiryMs, java.util.function.LongSupplier clockMs) {
        this(topics, TOPIC, expiryMs, clockMs);
    }

    LogOffsets(LogTopics topics, String name, long expiryMs, java.util.function.LongSupplier clockMs) {
        if (expiryMs <= 0) throw new IllegalArgumentException("expiryMs must be > 0");
        this.topics = topics;
        this.name = java.util.Objects.requireNonNull(name);
        this.expiryMs = expiryMs;
        this.clockMs = clockMs;
    }

    long expiryMs() {
        return this.expiryMs;
    }

    /** The offsets topic backing this instance. */
    String topic() {
        return this.name;
    }

    static byte[] key(String topic, String group) {
        return (topic + "\0" + group).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] record(String topic, String group, long offset, long commitMs) {
        var k = key(topic, group);
        return ByteBuffer.allocate(4 + k.length + 16).putInt(k.length).put(k).putLong(offset).putLong(commitMs).array();
    }

    /** Opens {@code __offsets} and replays it once; later calls are one volatile read. */
    private LogTopics.Log ensureLoaded(boolean create) throws IOException {
        if (this.loaded) return this.topics.get(this.name, true);
        this.loading.lock();
        try {
            if (this.loaded) return this.topics.get(this.name, true);
            var l = this.topics.get(this.name, create);
            if (l == null) return null;
            long committed = l.writer().snapshot().nextOffset();
            l.log().scanFrom(0, rb -> {
                for (int i = 0; i < rb.count(); i++) {
                    if (rb.baseOffset() + i >= committed) return;
                    var r = rb.records().get(i);
                    apply(r);
                }
            });
            this.loaded = true;
            return l;
        } finally {
            this.loading.unlock();
        }
    }

    /** One {@code __offsets} record into the map (replay and replicated apply); malformed records are skipped. */
    private void apply(byte[] r) {
        var k = LogCompaction.key(r);
        if (k == null || k.remaining() + 4 + 16 != r.length) return;
        int klen = k.remaining();
        var ks = StandardCharsets.UTF_8.decode(k).toString(); // consumes k
        int z = ks.indexOf('\0');
        if (z <= 0) return;
        var v = ByteBuffer.wrap(r, 4 + klen, 16);
        groups(ks.substring(0, z)).put(ks.substring(z + 1), new Commit(v.getLong(), v.getLong(), 0));
    }

    /**
     * Replicated mode: a batch applied to {@code __offsets}, in log order, on every node. Before the first load the
     * replay picks it up from the log; the loading lock orders this against that replay.
     */
    void applied(List<byte[]> records) {
        this.loading.lock();
        try {
            if (!this.loaded) return;
            for (var r : records) apply(r);
        } finally {
            this.loading.unlock();
        }
    }

    /** Loads existing offsets if {@code __offsets} exists (maintenance); never creates it. */
    void loadIfPresent() throws IOException {
        if (!this.loaded) ensureLoaded(false);
    }

    /**
     * Commits {@code offset} for {@code group} on {@code topic}; completes once the record holds per {@code durability}
     * (null = the {@code __offsets} topic default, {@link #DEFAULT_DURABILITY} unless overridden). The caller validated
     * the offset against the topic's log.
     */
    CompletableFuture<Long> commit(String topic, String group, long offset, Durability durability) throws IOException, InterruptedException {
        var l = ensureLoaded(true);
        this.topics.offsetsUsed();
        long now = this.clockMs.getAsLong();
        var rec = List.of(record(topic, group, offset, now));
        l.appender().awaitCapacity(); // outside the order lock: appendNoWait below never blocks
        CompletableFuture<Long> f;
        long s;
        this.order.lock();
        try {
            s = ++this.seq;
            f = l.appender().appendNoWait(rec, durability);
        } finally {
            this.order.unlock();
        }
        if (l.replica() != null) return f; // the map follows the applied log (applied), on leader and followers alike
        var c = new Commit(offset, now, s);
        return f.thenApply(base -> {
            groups(topic).merge(group, c, (old, nu) -> nu.seq() >= old.seq() ? nu : old);
            return base;
        });
    }

    private ConcurrentHashMap<String, Commit> groups(String topic) {
        var m = this.commits.get(topic);
        if (m != null) return m;
        var fresh = new ConcurrentHashMap<String, Commit>();
        m = this.commits.putIfAbsent(topic, fresh);
        return m != null ? m : fresh;
    }

    /** The committed offset of {@code group} on {@code topic}, or -1 when it never committed. */
    long committed(String topic, String group) throws IOException {
        if (ensureLoaded(false) == null) return -1;
        var m = this.commits.get(topic);
        var c = m == null ? null : m.get(group);
        return c == null ? -1 : c.offset();
    }

    /**
     * Retention/compaction floor of {@code topic}: the smallest committed offset among groups that committed within
     * {@code expiryMs}, or {@link Long#MAX_VALUE} when none pins it. Only meaningful after {@link #loadIfPresent}.
     */
    long floor(String topic) {
        var m = this.commits.get(topic);
        if (m == null || m.isEmpty()) return Long.MAX_VALUE;
        long now = this.clockMs.getAsLong(), min = Long.MAX_VALUE;
        for (var c : m.values()) if (now - c.commitMs() < this.expiryMs) min = Math.min(min, c.offset());
        return min;
    }

    /** Snapshot of every commit (tests, tools). */
    Map<String, Map<String, Commit>> all() {
        var out = new java.util.HashMap<String, Map<String, Commit>>();
        this.commits.forEach((t, m) -> out.put(t, Map.copyOf(m)));
        return out;
    }
}
