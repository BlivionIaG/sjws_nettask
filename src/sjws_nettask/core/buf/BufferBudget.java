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
package sjws_nettask.core.buf;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * One tenant's view of a {@link BufferPool} with its own byte limit (see the package documentation). Thread-safe.
 */
public final class BufferBudget {

    final BufferPool pool;
    private final String name;
    private final long limit;
    final AtomicLong used = new AtomicLong();
    final LongAdder denied = new LongAdder();
    final LongAdder fallbacks = new LongAdder();
    final LongAdder acquired = new LongAdder();

    BufferBudget(BufferPool pool, String name, long limit) {
        this.pool = pool;
        this.name = name;
        this.limit = limit;
    }

    /**
     * A buffer of at least {@code minCapacity} bytes (at most {@link BufferPool#MAX_CLASS}). Never null: when this
     * budget or the global cap is exhausted, or the pool is off, the result is an unpooled heap buffer.
     *
     * @throws IllegalArgumentException for {@code minCapacity} outside 1..{@link BufferPool#MAX_CLASS}; use
     *         {@link #acquireLarge} for bigger transient buffers
     */
    public PooledBuffer acquire(int minCapacity) {
        var b = this.pool.take(this, minCapacity, false);
        return b != null ? b : this.pool.fallback(this, minCapacity);
    }

    /**
     * As {@link #acquire}, but returns {@code null} instead of an unpooled buffer when this budget or the global cap
     * is exhausted, so the caller can shed load. With the pool off it never returns null.
     */
    public PooledBuffer tryAcquire(int minCapacity) {
        return this.pool.take(this, minCapacity, true);
    }

    /**
     * A transient buffer of {@link BufferPool#MAX_CLASS} + 1 .. {@link BufferPool#MAX_LARGE} bytes (rounded up to a
     * power of two) for recovery, compaction or a scan. Release it when that work ends; do not keep it. Never null:
     * over budget, over {@link BufferPool#MAX_LARGE} or with the pool off it is an unpooled heap buffer.
     */
    public PooledBuffer acquireLarge(int minCapacity) {
        var b = this.pool.takeLarge(this, minCapacity);
        return b != null ? b : this.pool.fallback(this, minCapacity);
    }

    public String name() {
        return this.name;
    }

    /** Most bytes this budget may hold at once (pooled buffers, charged at their class size). */
    public long limitBytes() {
        return this.limit;
    }

    /** Bytes currently held through this budget. */
    public long usedBytes() {
        return this.used.get();
    }

    boolean charge(int bytes) {
        for (;;) {
            long u = this.used.get();
            if (u + bytes > this.limit) {
                return false;
            }
            if (this.used.compareAndSet(u, u + bytes)) {
                return true;
            }
        }
    }

    void refund(int bytes) {
        this.used.addAndGet(-bytes);
    }
}
