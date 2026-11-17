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
package sjws_nettask.routing;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Per-upstream {@code max_pending} / {@code max_connections} caps (design §13). Lock-free.
 * {@link #of(int, int)} is {@code null} when both are unset, so the request path is a single
 * null check when the feature is off.
 */
public final class Admission {

    /** A taken slot; {@link #release()} returns it exactly once. */
    public static final class Hold {
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicInteger counter;
        private final CountDownLatch released = new CountDownLatch(1);

        Hold(AtomicInteger counter) {
            this.counter = counter;
        }

        public void release() {
            if (this.open.compareAndSet(true, false)) {
                this.counter.decrementAndGet();
                this.released.countDown();
            }
        }

        /** Wait until {@link #release()} has run, or until {@code timeout}. */
        boolean awaitReleased(long timeout, TimeUnit unit) throws InterruptedException {
            return this.released.await(timeout, unit);
        }
    }

    private final int maxPending;
    private final int maxConnections;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();
    /** Pools that share this cap; used to reclaim a sibling's idle socket (#508). */
    private final CopyOnWriteArrayList<IdleReclaim> reclaimers = new CopyOnWriteArrayList<>();

    /**
     * An upstream pool that may close one idle keep-alive so a sibling can take the slot.
     */
    interface IdleReclaim {
        /**
         * Close one idle socket and wait until that socket's connection hold is released, or until
         * {@code timeout}. @return true if this socket's slot was released
         */
        boolean closeOneIdleAndAwait(Duration timeout) throws InterruptedException;
    }

    private Admission(int maxPending, int maxConnections) {
        this.maxPending = maxPending;
        this.maxConnections = maxConnections;
    }

    /**
     * @param maxPending concurrent outstanding exchanges, or 0 if unset
     * @param maxConnections concurrent sockets (in use, connecting, or idle), or 0 if unset
     * @return the limiter, or {@code null} when both values are 0
     */
    public static Admission of(int maxPending, int maxConnections) {
        if (maxPending < 0 || maxConnections < 0) {
            throw new IllegalArgumentException("max_pending and max_connections must be >= 0");
        }
        if (maxPending == 0 && maxConnections == 0) {
            return null;
        }
        return new Admission(maxPending, maxConnections);
    }

    public boolean limitsPending() {
        return this.maxPending > 0;
    }

    public boolean limitsConnections() {
        return this.maxConnections > 0;
    }

    /** @return a hold, or {@code null} when the pending cap is reached */
    public Hold tryPending() {
        return acquire(this.pending, this.maxPending);
    }

    /** @return a hold, or {@code null} when the connection cap is reached */
    Hold tryConnection() {
        return acquire(this.connections, this.maxConnections);
    }

    private static Hold acquire(AtomicInteger counter, int max) {
        if (max <= 0) {
            throw new IllegalStateException("limit is unset");
        }
        while (true) {
            int n = counter.get();
            if (n >= max) {
                return null;
            }
            if (counter.compareAndSet(n, n + 1)) {
                return new Hold(counter);
            }
        }
    }

    int pending() {
        return this.pending.get();
    }

    int connections() {
        return this.connections.get();
    }

    int maxConnections() {
        return this.maxConnections;
    }

    boolean atConnectionCap() {
        return this.maxConnections > 0 && this.connections.get() >= this.maxConnections;
    }

    void attach(IdleReclaim pool) {
        if (pool != null && this.maxConnections > 0) {
            this.reclaimers.addIfAbsent(pool);
        }
    }

    void detach(IdleReclaim pool) {
        this.reclaimers.remove(pool);
    }

    /**
     * Close one idle keep-alive on a sibling pool and wait until a connection slot is
     * free. No-op when the cap is unset or a slot is already free.
     */
    boolean reclaimIdle(IdleReclaim except, Duration timeout) throws InterruptedException {
        if (!limitsConnections() || this.connections.get() < this.maxConnections) {
            return this.maxConnections <= 0 || this.connections.get() < this.maxConnections;
        }
        Duration wait = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(2) : timeout;
        for (var pool : this.reclaimers) {
            if (pool == except) {
                continue;
            }
            if (pool.closeOneIdleAndAwait(wait) && this.connections.get() < this.maxConnections) {
                return true;
            }
        }
        return this.connections.get() < this.maxConnections;
    }
}
