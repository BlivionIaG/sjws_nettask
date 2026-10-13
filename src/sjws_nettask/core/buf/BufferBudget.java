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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * One tenant's view of a {@link BufferPool} with its own byte limit (see the package documentation). Thread-safe.
 */
public final class BufferBudget {

    final BufferPool pool;
    private final String name;
    private final long limit;
    /** Bytes taken from {@link #limit}: held buffers plus credit parked in {@link #cells}. Touched only on lease/return. */
    final AtomicLong used = new AtomicLong();
    /**
     * Per-thread-stripe credit (bytes already counted in {@link #used}), one padded cell per stripe so that a charge or
     * refund is a CAS on a cell the thread usually owns alone, not on one shared counter. Indexed by thread id.
     */
    private final long[] cells;
    private final int mask;
    private final long batch;
    private static final int STRIDE = 16; // 128 bytes per cell: no false sharing between stripes
    private static final VarHandle CELL = MethodHandles.arrayElementVarHandle(long[].class);
    final LongAdder denied = new LongAdder();
    final LongAdder fallbacks = new LongAdder();
    final LongAdder acquired = new LongAdder();

    BufferBudget(BufferPool pool, String name, long limit) {
        this.pool = pool;
        this.name = name;
        this.limit = limit;
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int n = Integer.highestOneBit(cpus * 2 - 1) << 1;
        this.mask = n - 1;
        this.cells = new long[(n + 1) * STRIDE];
        this.batch = Math.min(256 << 10, limit / (4L * n)); // at most a quarter of the limit parked as credit
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

    /** Bytes currently held through this budget (exact when no charge or release is in flight). */
    public long usedBytes() {
        long u = this.used.get();
        for (int i = STRIDE; i < this.cells.length; i += STRIDE) {
            u -= (long) CELL.getVolatile(this.cells, i);
        }
        return Math.max(0, u);
    }

    private int cell() {
        return (((int) Thread.currentThread().threadId() & this.mask) + 1) * STRIDE;
    }

    boolean charge(int bytes) {
        int i = cell();
        for (;;) {
            long c = (long) CELL.getVolatile(this.cells, i);
            if (c < bytes) {
                break;
            }
            if (CELL.compareAndSet(this.cells, i, c, c - bytes)) {
                return true;
            }
        }
        long want = Math.max(bytes, this.batch);
        if (want > bytes && lease(want)) {
            CELL.getAndAdd(this.cells, i, want - bytes);
            return true;
        }
        if (lease(bytes)) {
            return true;
        }
        reclaim(); // other stripes' idle credit counts against the limit: take it back, then decide exactly
        return lease(bytes);
    }

    private boolean lease(long bytes) {
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

    private void reclaim() {
        for (int i = STRIDE; i < this.cells.length; i += STRIDE) {
            long c = (long) CELL.getAndSet(this.cells, i, 0L);
            if (c > 0) {
                this.used.addAndGet(-c);
            }
        }
    }

    void refund(int bytes) {
        int i = cell();
        long c = (long) CELL.getAndAdd(this.cells, i, (long) bytes) + bytes;
        if (c > 2 * this.batch) {
            long excess = c - this.batch;
            if (CELL.compareAndSet(this.cells, i, c, c - excess)) {
                this.used.addAndGet(-excess);
            }
        }
    }
}
