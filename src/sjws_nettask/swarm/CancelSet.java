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
package sjws_nettask.swarm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import sjws_nettask.messaging.delivery.CancelProbe;

/**
 * S2c PR 5 cancel state (agent-swarm.md §5.2, §12 Q3): which stream tasks were cancelled, and their deadlines, keyed by
 * (task topic, offset). Swarm memory only: messaging stores no cancel state; it asks the {@link #probe} installed on
 * each task partition, which {@code extend} calls after the lease fence. A task is cancelled ({@code reason}
 * {@value #CANCELLED}) once {@link #cancel} ran, and past its deadline ({@value #DEADLINE}) once the clock passes it.
 * Entries live until the deadline + {@link #GRACE_MS} and are dropped by {@link #sweep}. At most {@link #MAX_ENTRIES}
 * locally submitted streams ({@link #reserve}; a full set refuses the stream with 503). A cancel record from the
 * ledger always takes effect ({@link #apply} creates the entry on any node).
 * Exists only when {@code sjws.swarm.stream.enabled} is on.
 */
public final class CancelSet {

    static final String CANCELLED = "cancelled";
    static final String DEADLINE = "deadline";
    static final long GRACE_MS = 60_000;
    static final int MAX_ENTRIES = 65_536;

    /** One tracked task; {@code cancelled} flips once. */
    static final class Entry {
        final long deadlineMs;
        volatile boolean cancelled;

        Entry(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }
    }

    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Entry>> byTopic = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();

    CancelSet(LongSupplier clock) {
        this.clock = clock;
    }

    private ConcurrentHashMap<Long, Entry> topic(String topic) {
        var m = this.byTopic.get(topic);
        if (m == null) {
            var fresh = new ConcurrentHashMap<Long, Entry>();
            m = this.byTopic.putIfAbsent(topic, fresh);
            m = m == null ? fresh : m;
        }
        return m;
    }

    /**
     * Reserves room for one {@link #trackReserved} (sweeping once when full); false when the set is full even after
     * the sweep. The caller must then refuse the stream (503) <b>before</b> submitting it: a stream task is never
     * left without cancel and deadline state (#234 review). Refusals are counted in {@link #refused}.
     */
    boolean reserve() {
        for (int attempt = 0; attempt < 2; attempt++) {
            for (int n = this.size.get(); n < MAX_ENTRIES; n = this.size.get()) {
                if (this.size.compareAndSet(n, n + 1)) {
                    return true;
                }
            }
            if (attempt == 0) {
                sweep(this.clock.getAsLong());
            }
        }
        this.refused.incrementAndGet();
        return false;
    }

    /** Gives back a {@link #reserve} that was not used (the submit failed). */
    void unreserve() {
        this.size.decrementAndGet();
    }

    /** Tracks a stream task's deadline in a slot taken by {@link #reserve}. */
    void trackReserved(String topic, long offset, long deadlineMs) {
        if (topic(topic).putIfAbsent(offset, new Entry(deadlineMs)) != null) {
            this.size.decrementAndGet(); // already present (a record applied first): the slot is not needed
        }
    }

    /** {@link #reserve} plus {@link #trackReserved}; false (and counted) when full. */
    boolean track(String topic, long offset, long deadlineMs) {
        if (!reserve()) {
            return false;
        }
        trackReserved(topic, offset, deadlineMs);
        return true;
    }

    /** Marks a task this node tracks as cancelled; true the first time. Remote cancels go through {@link #apply}. */
    boolean cancel(String topic, long offset) {
        var m = this.byTopic.get(topic);
        var e = m == null ? null : m.get(offset);
        if (e == null || e.cancelled) {
            return false;
        }
        e.cancelled = true;
        return true;
    }

    /**
     * Applies a cancel record read from the ledger (#234 review blocking 1): the task may have been submitted on
     * another node, so a missing entry is <b>created</b> cancelled, living until the record's deadline + grace. The cap
     * does not refuse it (dropping it would silently lose the cancel); an entry past the cap is counted in
     * {@link #overCap} and bounded by the sweep. True if this made the task cancelled.
     */
    boolean apply(String topic, long offset, long deadlineMs) {
        var m = topic(topic);
        var e = m.get(offset);
        if (e == null) {
            var fresh = new Entry(deadlineMs);
            fresh.cancelled = true;
            e = m.putIfAbsent(offset, fresh);
            if (e == null) {
                if (this.size.incrementAndGet() > MAX_ENTRIES) {
                    this.overCap.incrementAndGet();
                }
                return true;
            }
        }
        if (e.cancelled) {
            return false;
        }
        e.cancelled = true;
        return true;
    }

    private final java.util.concurrent.atomic.AtomicLong refused = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong overCap = new java.util.concurrent.atomic.AtomicLong();

    /** Streams refused because the set was full (each answered 503). */
    long refused() {
        return this.refused.get();
    }

    /** Remote cancel records applied while the set was at its cap (kept anyway). */
    long overCap() {
        return this.overCap.get();
    }

    /** {@value #CANCELLED}, {@value #DEADLINE} or null (not tracked, or running within its deadline). */
    String reason(String topic, long offset, long now) {
        var m = this.byTopic.get(topic);
        return m == null ? null : reason(m.get(offset), now);
    }

    private static String reason(Entry e, long now) {
        if (e == null) {
            return null;
        }
        if (e.cancelled) {
            return CANCELLED;
        }
        return now > e.deadlineMs ? DEADLINE : null;
    }

    /** The probe for one task partition: a map read and a clock read, no lock, no I/O (the offset key is boxed). */
    public CancelProbe probe(String topic) {
        var m = topic(topic);
        return (group, offset) -> reason(m.get(offset), this.clock.getAsLong()) != null;
    }

    /** Drops entries past their deadline + grace; returns how many. */
    int sweep(long now) {
        int n = 0;
        for (var m : this.byTopic.values()) {
            var it = m.values().iterator();
            while (it.hasNext()) {
                if (now - GRACE_MS > it.next().deadlineMs) {
                    it.remove();
                    n++;
                }
            }
        }
        this.size.addAndGet(-n);
        return n;
    }

    /** First byte of a cancel record; never a {@code TaskEnvelope} version. */
    static final byte RECORD_TAG = (byte) 0xC1;
    static final int RECORD_BYTES = 1 + 8 + 8 + 16;

    /** The opaque cancel record: {@code [0xC1][targetOffset:i64][deadlineMs:i64][correlationId:16]}. */
    static byte[] record(long targetOffset, long deadlineMs, byte[] correlationId) {
        return java.nio.ByteBuffer.allocate(RECORD_BYTES).put(RECORD_TAG).putLong(targetOffset).putLong(deadlineMs)
                .put(correlationId, 0, 16).array();
    }

    static boolean isRecord(byte[] payload) {
        return payload.length == RECORD_BYTES && payload[0] == RECORD_TAG;
    }

    static long recordTarget(byte[] payload) {
        return java.nio.ByteBuffer.wrap(payload, 1, 8).getLong();
    }

    static long recordDeadline(byte[] payload) {
        return java.nio.ByteBuffer.wrap(payload, 9, 8).getLong();
    }

    int size() {
        return this.size.get();
    }
}
