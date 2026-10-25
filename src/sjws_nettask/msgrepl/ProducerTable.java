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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * Producer dedup state of one replicated log topic (M2): a deterministic function of the applied consensus entries,
 * so every replica, and a node that replays its log after a restart or wins an election, holds the same table.
 *
 * <p>Key: {@code (origin, producerId)}. {@code origin} is the producer's namespace: 0 for HTTP clients today (messaging
 * has no authenticated principal yet), reserved for the authenticated principal or a forwarding peer's wire later.
 * A producer's seqs apply strictly in order from 1. Its last {@link #WINDOW} batches keep their base offset and record
 * count, so a retry of one of them is acknowledged with the original offset and never appended again.
 *
 * <p><b>Eviction.</b> At most {@link #MAX_PRODUCERS} producers per topic, least recently <i>applied</i> first: only
 * {@link #apply} (a batch appended) refreshes a producer, never a lookup, and the order is the apply order, so every
 * replica evicts the same producer at the same entry. An evicted producer is unknown again: its next batch must be seq 1
 * (else {@code OUT_OF_ORDER}, expected 1), which in practice means a new {@code Producer-Id}. Dedup therefore holds for a
 * producer while it is among the last {@link #MAX_PRODUCERS} to write the topic. Both limits are constants of the
 * replicated state machine, not settings: replicas with different limits would decide differently.
 *
 * <p><b>Snapshot seam (#270).</b> Once msg groups snapshot and truncate their consensus log, the table can no longer be
 * rebuilt from entries: a snapshot at index {@code i} must carry {@link #write} of the table as applied through
 * {@code i}, and installing it must {@link #read} it back before applying {@code i + 1}. The encoding is versioned and
 * keeps the LRU order, so a restored table evicts exactly like the original.
 *
 * <p>Not thread-safe: owned by the group's delivery thread.
 */
public final class ProducerTable {

    /** Batches per producer whose offsets are remembered. */
    public static final int WINDOW = 16;
    /** Producers per topic; the least recently applied is evicted beyond it. */
    public static final int MAX_PRODUCERS = 4096;
    static final int FORMAT = 1;

    /** Result of {@link #check}: {@code >= 0} is the original base offset of a duplicate. */
    static final long NEW = -1, OUT_OF_ORDER = -2, SEQ_REUSED = -3, OUT_OF_WINDOW = -4;

    record Key(int origin, long producerId) { }

    /** One producer: its highest applied seq and a ring of its last batches (index {@code seq % WINDOW}). */
    static final class Producer {
        long highest;
        final long[] base = new long[WINDOW];
        final int[] count = new int[WINDOW];
    }

    private final int maxProducers;
    /** Insertion order = apply order (an applied producer is removed and re-put): the eldest is evicted first. */
    private final LinkedHashMap<Key, Producer> producers = new LinkedHashMap<>();
    private long evictions;

    public ProducerTable() {
        this(MAX_PRODUCERS);
    }

    ProducerTable(int maxProducers) {
        if (maxProducers < 1) throw new IllegalArgumentException("maxProducers " + maxProducers);
        this.maxProducers = maxProducers;
    }

    /**
     * Classifies batch {@code seq} of {@code (origin, producerId)} holding {@code count} records: {@link #NEW} for the
     * next seq (1 for an unknown producer), the original base offset for a remembered duplicate with the same count,
     * {@link #SEQ_REUSED} for one with another count, {@link #OUT_OF_WINDOW} for a seq applied before the window, and
     * {@link #OUT_OF_ORDER} for anything else. Changes nothing.
     */
    long check(int origin, long producerId, long seq, int count) {
        var p = producers.get(new Key(origin, producerId));
        long highest = p == null ? 0 : p.highest;
        if (seq == highest + 1) return NEW;
        if (seq > highest || seq < 1) return OUT_OF_ORDER;
        if (seq <= highest - WINDOW) return OUT_OF_WINDOW;
        int i = (int) (seq % WINDOW);
        return p.count[i] == count ? p.base[i] : SEQ_REUSED;
    }

    /** The seq {@code (origin, producerId)} must send next: 1 when unknown. */
    long expectedSeq(int origin, long producerId) {
        var p = producers.get(new Key(origin, producerId));
        return p == null ? 1 : p.highest + 1;
    }

    /** Records batch {@code seq} (which {@link #check} found {@link #NEW}) appended at {@code base}. */
    void apply(int origin, long producerId, long seq, long base, int count) {
        var key = new Key(origin, producerId);
        var p = producers.remove(key);
        if (p == null) {
            if (seq != 1) throw new IllegalStateException("producer " + producerId + " must start at seq 1, not " + seq);
            while (producers.size() >= maxProducers) {
                var eldest = producers.keySet().iterator();
                eldest.next();
                eldest.remove();
                evictions++;
            }
            p = new Producer();
        } else if (seq != p.highest + 1) {
            producers.put(key, p);
            throw new IllegalStateException("producer " + producerId + " expects seq " + (p.highest + 1) + ", not " + seq);
        }
        p.highest = seq;
        int i = (int) (seq % WINDOW);
        p.base[i] = base;
        p.count[i] = count;
        producers.put(key, p);
    }

    int size() {
        return producers.size();
    }

    long evictions() {
        return evictions;
    }

    /** Writes the table (format {@link #FORMAT}), eldest producer first. */
    public void write(DataOutput out) throws IOException {
        out.writeInt(FORMAT);
        out.writeInt(WINDOW);
        out.writeInt(maxProducers);
        out.writeLong(evictions);
        out.writeInt(producers.size());
        for (var e : producers.entrySet()) {
            var p = e.getValue();
            out.writeInt(e.getKey().origin());
            out.writeLong(e.getKey().producerId());
            out.writeLong(p.highest);
            int n = (int) Math.min(WINDOW, p.highest);
            for (long s = p.highest - n + 1; s <= p.highest; s++) {
                int i = (int) (s % WINDOW);
                out.writeLong(p.base[i]);
                out.writeInt(p.count[i]);
            }
        }
    }

    /** Reads a table written by {@link #write}; refuses another format, window or producer limit. */
    public static ProducerTable read(DataInput in) throws IOException {
        int format = in.readInt(), window = in.readInt(), max = in.readInt();
        if (format != FORMAT || window != WINDOW || max < 1) {
            throw new IOException("producer table: format " + format + " window " + window + " max " + max
                    + ", this build reads format " + FORMAT + " window " + WINDOW);
        }
        var t = new ProducerTable(max);
        t.evictions = in.readLong();
        int size = in.readInt();
        if (size < 0 || size > max) throw new IOException("producer table: " + size + " producers over the limit " + max);
        for (int k = 0; k < size; k++) {
            var key = new Key(in.readInt(), in.readLong());
            var p = new Producer();
            p.highest = in.readLong();
            if (key.producerId() == 0 || p.highest < 1) throw new IOException("producer table: bad producer entry");
            int n = (int) Math.min(WINDOW, p.highest);
            for (long s = p.highest - n + 1; s <= p.highest; s++) {
                int i = (int) (s % WINDOW);
                p.base[i] = in.readLong();
                p.count[i] = in.readInt();
            }
            if (t.producers.put(key, p) != null) throw new IOException("producer table: duplicate producer");
        }
        return t;
    }
}
