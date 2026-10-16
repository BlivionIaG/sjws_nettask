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
 * Entries live until the deadline + {@link #GRACE_MS} and are dropped by {@link #sweep}. At most {@link #MAX_ENTRIES}.
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

    /** Starts tracking a stream task's deadline; false when the set is full even after a sweep. */
    boolean track(String topic, long offset, long deadlineMs) {
        if (this.size.get() >= MAX_ENTRIES) {
            sweep(this.clock.getAsLong());
            if (this.size.get() >= MAX_ENTRIES) {
                return false;
            }
        }
        if (topic(topic).putIfAbsent(offset, new Entry(deadlineMs)) == null) {
            this.size.incrementAndGet();
        }
        return true;
    }

    /** Marks the task cancelled; true the first time. An untracked task is not cancellable here. */
    boolean cancel(String topic, long offset) {
        var m = this.byTopic.get(topic);
        var e = m == null ? null : m.get(offset);
        if (e == null || e.cancelled) {
            return false;
        }
        e.cancelled = true;
        return true;
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
    static final int RECORD_BYTES = 1 + 8 + 16;

    /** The opaque cancel record: {@code [0xC1][targetOffset:i64][correlationId:16]}. */
    static byte[] record(long targetOffset, byte[] correlationId) {
        return java.nio.ByteBuffer.allocate(RECORD_BYTES).put(RECORD_TAG).putLong(targetOffset).put(correlationId, 0, 16)
                .array();
    }

    static boolean isRecord(byte[] payload) {
        return payload.length == RECORD_BYTES && payload[0] == RECORD_TAG;
    }

    static long recordTarget(byte[] payload) {
        return java.nio.ByteBuffer.wrap(payload, 1, 8).getLong();
    }

    int size() {
        return this.size.get();
    }
}
