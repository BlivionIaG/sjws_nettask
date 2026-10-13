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
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;

/**
 * A buffer from a {@link BufferBudget}, with a reference count. See the package documentation for the ownership
 * rules. Not thread-safe for concurrent use of {@link #buffer()}; {@link #retain()} and {@link #release()} are.
 */
public final class PooledBuffer implements AutoCloseable {

    private static final VarHandle REFS;

    static {
        try {
            REFS = MethodHandles.lookup().findVarHandle(PooledBuffer.class, "refs", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    final ByteBuffer buf;
    /** Null for unpooled buffers (pool off, or a fallback). */
    final BufferPool.Slot slot;
    final BufferBudget budget;
    /** Charged bytes, refunded on the last release (0 when not charged). */
    final int charged;
    /** Non-null when this acquire is tracked for leaks. */
    final Tracker tracker;
    @SuppressWarnings("unused")
    private volatile int refs = 1;

    PooledBuffer(ByteBuffer buf, BufferPool.Slot slot, BufferBudget budget, int charged, Tracker tracker) {
        this.buf = buf;
        this.slot = slot;
        this.budget = budget;
        this.charged = charged;
        this.tracker = tracker;
    }

    /**
     * The buffer: position 0 and limit = capacity at acquire, and the same object until the last release.
     *
     * @throws IllegalStateException after the last release
     */
    public ByteBuffer buffer() {
        if ((int) REFS.getAcquire(this) <= 0) {
            throw new IllegalStateException("buffer used after release");
        }
        return this.buf;
    }

    public int capacity() {
        return this.buf.capacity();
    }

    /** True when it came from the pool (false: pool off, over budget, or too large). */
    public boolean pooled() {
        return this.slot != null;
    }

    /** Adds a reference; each needs its own {@link #release()}. @throws IllegalStateException after the last release */
    public PooledBuffer retain() {
        for (;;) {
            int r = (int) REFS.getVolatile(this);
            if (r <= 0) {
                throw new IllegalStateException("retain after release");
            }
            if (REFS.compareAndSet(this, r, r + 1)) {
                return this;
            }
        }
    }

    /**
     * Drops a reference. The last one returns the buffer to the pool (or lets an unpooled one go).
     *
     * @throws IllegalStateException when released more often than acquired plus retained
     */
    public void release() {
        int r = (int) REFS.getAndAdd(this, -1) - 1;
        if (r > 0) {
            return;
        }
        if (r < 0) {
            REFS.getAndAdd(this, 1);
            throw new IllegalStateException("released more often than acquired/retained");
        }
        if (this.tracker != null) {
            this.tracker.released = true;
            this.tracker.cleanable.clean(); // runs Tracker.run: returns the buffer exactly once
        } else if (this.budget != null) {
            this.budget.pool.recycle(this.buf, this.slot, this.budget, this.charged, false);
        }
    }

    /** Same as {@link #release()}. */
    @Override
    public void close() {
        release();
    }

    /**
     * Cleaner state for a tracked acquire. Does not reference the {@link PooledBuffer}, so an unreleased handle can
     * become phantom-reachable. Runs once: from {@link #release()} (released = true) or from the Cleaner (a leak).
     */
    static final class Tracker implements Runnable {
        final ByteBuffer buf;
        final BufferPool.Slot slot;
        final BufferBudget budget;
        final int charged;
        final Throwable acquiredAt; // debug mode only
        volatile boolean released;
        Cleaner.Cleanable cleanable;

        Tracker(ByteBuffer buf, BufferPool.Slot slot, BufferBudget budget, int charged, Throwable acquiredAt) {
            this.buf = buf;
            this.slot = slot;
            this.budget = budget;
            this.charged = charged;
            this.acquiredAt = acquiredAt;
        }

        @Override
        public void run() {
            this.budget.pool.untrack(this);
            this.budget.pool.recycle(this.buf, this.slot, this.budget, this.charged, !this.released);
        }
    }
}
