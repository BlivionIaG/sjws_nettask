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

import sjws_nettask.messaging.ledger.ProducerSequenceException;

/**
 * Producer dedup state of one replicated log topic (M2): a deterministic function of the applied consensus entries,
 * so every replica, and a node that replays its log after a restart or wins an election, holds the same table.
 *
 * <p>Key: {@code (origin, producerId)}. {@code origin} is the producer's namespace: 0 for HTTP clients today (messaging
 * has no authenticated principal yet), reserved for the authenticated principal or a forwarding peer's wire later.
 * A producer's seqs apply strictly in order from 1. Its last {@link #WINDOW} batches keep their base offset, record
 * count and a digest of their records (CRC32C over the entry's encoded records region, computed from the committed entry
 * bytes, so every replica gets the same value). A retry with the same count and digest is acknowledged with the original
 * offset and never appended again; the same seq with other records is {@link #SEQ_REUSED}, so a batch that happens to
 * share an id, seq and count with another (two clients picking the same id) is refused, never acked unstored.
 *
 * <p><b>Shared namespace.</b> Every HTTP client of a topic shares origin 0, so producer ids are not scoped to a client
 * yet: two clients that pick the same id interfere (refusals, lost dedup), and the {@link #MAX_PRODUCERS} limit is
 * shared. Scoping {@code origin} to the authenticated principal or forwarding peer, a per-principal cap and a node-wide
 * bound are tracked in #283, which blocks turning producer dedup on by default.
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
    /** 2: each remembered batch also carries its records digest (B2 of the #278 review). */
    static final int FORMAT = 2;

    /** Result of {@link #check}: {@code >= 0} is the original base offset of a duplicate. */
    static final long NEW = -1, OUT_OF_ORDER = -2, SEQ_REUSED = -3, OUT_OF_WINDOW = -4, UNKNOWN_PRODUCER = -5;
    /** Highest seq a batch may carry: {@code highest + 1} never overflows, and the window loops end. */
    static final long MAX_SEQ = Long.MAX_VALUE - 1;

    record Key(int origin, long producerId) { }

    /** One producer: its highest applied seq and a ring of its last batches (index {@code seq % WINDOW}). */
    static final class Producer {
        long highest;
        final long[] base = new long[WINDOW];
        final int[] count = new int[WINDOW];
        final int[] digest = new int[WINDOW];
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
     * Classifies batch {@code seq} of {@code (origin, producerId)} holding {@code count} records with records digest
     * {@code digest}: {@link #NEW} for the next seq (1 for an unknown producer), the original base offset for a
     * remembered duplicate with the same count and digest, {@link #SEQ_REUSED} for one with another count or digest,
     * {@link #OUT_OF_WINDOW} for a seq applied before the window, {@link #UNKNOWN_PRODUCER} for a seq above 1 from a
     * producer the table does not hold (never seen or evicted: an earlier attempt may be in the log, the table cannot
     * tell), and {@link #OUT_OF_ORDER} for anything else (a gap or a seq out of range). Changes nothing.
     */
    long check(int origin, long producerId, long seq, int count, int digest) {
        if (seq < 1 || seq > MAX_SEQ) return OUT_OF_ORDER;
        var p = producers.get(new Key(origin, producerId));
        if (p == null) return seq == 1 ? NEW : UNKNOWN_PRODUCER;
        long highest = p.highest;
        if (seq == highest + 1) return NEW;
        if (seq > highest || seq < 1) return OUT_OF_ORDER;
        if (seq <= highest - WINDOW) return OUT_OF_WINDOW;
        int i = (int) (seq % WINDOW);
        return p.count[i] == count && p.digest[i] == digest ? p.base[i] : SEQ_REUSED;
    }

    /** The seq {@code (origin, producerId)} must send next: 1 when unknown. */
    long expectedSeq(int origin, long producerId) {
        var p = producers.get(new Key(origin, producerId));
        return p == null ? 1 : p.highest + 1;
    }

    /** Records batch {@code seq} (which {@link #check} found {@link #NEW}) appended at {@code base}. */
    void apply(int origin, long producerId, long seq, long base, int count, int digest) {
        if (seq < 1 || seq > MAX_SEQ) throw new IllegalArgumentException("seq " + seq);
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
        p.digest[i] = digest;
        producers.put(key, p);
    }

    /**
     * The refusal for {@link #check} result {@code c} (not {@link #NEW} nor a duplicate), {@code next} being the seq
     * the producer must send next. Built here so that the replica, which runs with the flag off too, never names
     * the exception class (#278 B4: nothing producer-related loads unless a producer entry is applied).
     */
    static RuntimeException refusal(long c, long next, String message) {
        var kind = c == SEQ_REUSED ? ProducerSequenceException.Kind.SEQ_REUSED
                : c == OUT_OF_WINDOW ? ProducerSequenceException.Kind.DUPLICATE_OUT_OF_WINDOW
                : c == UNKNOWN_PRODUCER ? ProducerSequenceException.Kind.UNKNOWN_PRODUCER
                : ProducerSequenceException.Kind.OUT_OF_ORDER;
        return new ProducerSequenceException(kind, next, message + " " + kind + ", next seq " + next);
    }

    /** Not proposed: not every voter has confirmed it applies producer entries. */
    static RuntimeException dedupUnavailable(String message) {
        return new ProducerSequenceException(ProducerSequenceException.Kind.DEDUP_UNAVAILABLE, 0, message);
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
            for (int k = n - 1; k >= 0; k--) { // seqs highest - n + 1 .. highest, counted so it cannot overflow
                int i = (int) ((p.highest - k) % WINDOW);
                out.writeLong(p.base[i]);
                out.writeInt(p.count[i]);
                out.writeInt(p.digest[i]);
            }
        }
    }

    /**
     * Reads a table written by {@link #write}; refuses another format, window or producer limit (a table with another
     * limit would evict differently from its peers), and negative offsets, counts or evictions.
     */
    public static ProducerTable read(DataInput in) throws IOException {
        return read(in, MAX_PRODUCERS);
    }

    /** {@link #read} of a table whose producer limit must be {@code max} (tests use small limits). */
    static ProducerTable read(DataInput in, int expectedMax) throws IOException {
        int format = in.readInt(), window = in.readInt(), max = in.readInt();
        if (format != FORMAT || window != WINDOW || max != expectedMax) {
            throw new IOException("producer table: format " + format + " window " + window + " max " + max
                    + ", this build reads format " + FORMAT + " window " + WINDOW + " max " + expectedMax);
        }
        var t = new ProducerTable(max);
        t.evictions = in.readLong();
        if (t.evictions < 0) throw new IOException("producer table: negative evictions");
        int size = in.readInt();
        if (size < 0 || size > max) throw new IOException("producer table: " + size + " producers over the limit " + max);
        for (int k = 0; k < size; k++) {
            var key = new Key(in.readInt(), in.readLong());
            var p = new Producer();
            p.highest = in.readLong();
            if (key.producerId() == 0 || p.highest < 1 || p.highest > MAX_SEQ) {
                throw new IOException("producer table: bad producer entry");
            }
            int n = (int) Math.min(WINDOW, p.highest);
            for (int back = n - 1; back >= 0; back--) {
                int i = (int) ((p.highest - back) % WINDOW);
                p.base[i] = in.readLong();
                p.count[i] = in.readInt();
                p.digest[i] = in.readInt();
                if (p.base[i] < 0 || p.count[i] < 1) throw new IOException("producer table: bad base or count");
            }
            if (t.producers.put(key, p) != null) throw new IOException("producer table: duplicate producer");
        }
        return t;
    }
}
