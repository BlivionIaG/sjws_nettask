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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import sjws_nettask.messaging.ledger.Durability;

/**
 * Committed consumer offsets (durable-log-plan §2): the next offset a group will fetch from a topic. Stored as keyed
 * records in the internal compacted topic {@value #TOPIC} ({@code key = topic\0group}, {@code value = [offset][atMs]}),
 * so recovery is a replay and compaction keeps one record per (topic, group). A group's oldest live commit is a
 * retention floor for its topic until it is older than {@code expiryMs}.
 */
final class ConsumerOffsets {

    static final String TOPIC = "__offsets";
    static final long DEFAULT_EXPIRY_MS = TimeUnit.DAYS.toMillis(7);
    static final long COMMIT_TIMEOUT_MS = 10_000;

    record Commit(long offset, long atMs) { }

    private final LogTopics topics;
    private final long expiryMs;
    private final LongSupplier clockMs;
    /** topic -> group -> latest commit; published in log order (appends are enqueued under {@link #lock}). */
    private final ConcurrentHashMap<String, Map<String, Commit>> byTopic = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean loaded;

    ConsumerOffsets(LogTopics topics, long expiryMs, LongSupplier clockMs) {
        this.topics = topics;
        this.expiryMs = expiryMs;
        this.clockMs = clockMs;
    }

    static byte[] record(String topic, String group, long offset, long atMs) {
        var k = (topic + "\0" + group).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + k.length + 16).putInt(k.length).put(k).putLong(offset).putLong(atMs).array();
    }

    /** Replays {@value #TOPIC} once (first use after start). */
    private void load() throws IOException {
        if (this.loaded) return;
        this.lock.lock();
        try {
            if (this.loaded) return;
            var l = this.topics.get(TOPIC, false);
            if (l != null) {
                long end = l.writer().snapshot().nextOffset();
                l.log().scanFrom(l.log().startOffset(), b -> {
                    for (int i = 0; i < b.records().size() && b.baseOffset() + i < end; i++) apply(b.records().get(i));
                });
            }
            this.loaded = true;
        } finally {
            this.lock.unlock();
        }
    }

    private void apply(byte[] rec) {
        var k = LogTopics.key(rec);
        if (k == null || rec.length != 4 + k.remaining() + 16) return; // not ours: ignore
        var key = StandardCharsets.UTF_8.decode(k.duplicate()).toString();
        int z = key.indexOf('\0');
        if (z < 0) return;
        var v = ByteBuffer.wrap(rec, 4 + k.remaining(), 16);
        put(key.substring(0, z), key.substring(z + 1), new Commit(v.getLong(), v.getLong()));
    }

    private void put(String topic, String group, Commit c) {
        var m = this.byTopic.get(topic);
        if (m == null) {
            m = new ConcurrentHashMap<>();
            var prev = this.byTopic.putIfAbsent(topic, m);
            if (prev != null) m = prev;
        }
        m.put(group, c);
    }

    /** Commits {@code offset} for {@code group}; returns once the record is durable ({@link Durability#GROUP}). */
    Commit commit(String topic, String group, long offset) throws IOException {
        load();
        var c = new Commit(offset, this.clockMs.getAsLong());
        java.util.concurrent.CompletableFuture<Long> f;
        var l = this.topics.get(TOPIC, true);
        this.lock.lock(); // log order == publication order for one key
        try {
            f = l.writer().append(List.of(record(topic, group, offset, c.atMs())), Durability.GROUP);
            put(topic, group, c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } finally {
            this.lock.unlock();
        }
        try {
            f.get(COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("offset commit not durable: " + e);
        }
        return c;
    }

    /** The group's committed offset, or null. */
    Commit get(String topic, String group) throws IOException {
        load();
        var m = this.byTopic.get(topic);
        return m == null ? null : m.get(group);
    }

    /** Retention floor of {@code topic}: the lowest live (unexpired) commit, or {@link Long#MAX_VALUE}. */
    long floor(String topic) throws IOException {
        load();
        var m = this.byTopic.get(topic);
        if (m == null) return Long.MAX_VALUE;
        long now = this.clockMs.getAsLong(), min = Long.MAX_VALUE;
        for (var c : m.values()) if (now - c.atMs() < this.expiryMs) min = Math.min(min, c.offset());
        return min;
    }

    /** Snapshot for tests and tools. */
    Map<String, Map<String, Commit>> all() throws IOException {
        load();
        var out = new HashMap<String, Map<String, Commit>>();
        this.byTopic.forEach((t, m) -> out.put(t, Map.copyOf(m)));
        return out;
    }
}
