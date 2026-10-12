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
package sjws_nettask.observe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded multi-producer, single-consumer queue of access-log lines.
 * <p>
 * Producers claim a slot with a compare-and-set and publish it with a release store. A full queue
 * returns immediately ({@code false}); a producer never parks and never takes a monitor. The
 * algorithm is Vyukov's bounded MPMC queue specialised to one consumer. A producer that cannot
 * publish within a few retries drops the line instead of spinning on the request thread.
 */
final class LogQueue {

    private static final VarHandle SEQ = MethodHandles.arrayElementVarHandle(long[].class);
    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(byte[][].class);
    private static final int SPINS = 128;

    private final byte[][] slots;
    private final long[] sequence;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private volatile long tail;

    LogQueue(int capacity) {
        if (capacity < 2 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("capacity must be a power of two, >= 2");
        }
        this.slots = new byte[capacity][];
        this.sequence = new long[capacity];
        this.mask = capacity - 1;
        for (int i = 0; i < capacity; i++) {
            this.sequence[i] = i;
        }
    }

    int capacity() {
        return this.slots.length;
    }

    /** @return false when the queue is full or a slot could not be published quickly */
    boolean offer(byte[] line) {
        for (int spin = 0; spin < SPINS; spin++) {
            long index = this.head.get();
            int slot = (int) (index & this.mask);
            long seq = (long) SEQ.getAcquire(this.sequence, slot);
            long dif = seq - index;
            if (dif == 0) {
                if (this.head.compareAndSet(index, index + 1)) {
                    SLOTS.setRelease(this.slots, slot, line);
                    SEQ.setRelease(this.sequence, slot, index + 1);
                    return true;
                }
            } else if (dif < 0) {
                return false;
            }
        }
        return false;
    }

    /** @return the next line, or null when the consumer has caught up */
    byte[] poll() {
        int slot = (int) (this.tail & this.mask);
        long expected = this.tail + 1;
        if ((long) SEQ.getAcquire(this.sequence, slot) - expected < 0) {
            return null;
        }
        byte[] line = (byte[]) SLOTS.getAcquire(this.slots, slot);
        SLOTS.setRelease(this.slots, slot, null);
        SEQ.setRelease(this.sequence, slot, this.tail + this.slots.length);
        this.tail = this.tail + 1;
        return line;
    }

    /** Racy size hint used to skip formatting when the buffer is already full. */
    boolean isFull() {
        return this.head.get() - this.tail >= this.slots.length;
    }
}
