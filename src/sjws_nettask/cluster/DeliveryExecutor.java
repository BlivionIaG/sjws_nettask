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
package sjws_nettask.cluster;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One group's commit deliveries (listener callbacks, no-op listeners, append-future completions) on a single
 * dedicated virtual thread, in submission order, never overlapping (docs/design/raft-delivery-executor.md).
 * {@link #submit} never blocks, so the Raft thread and the group lock never wait for a callback. Backpressure
 * is applied by {@link RaftGroup#append}, which refuses new work while {@link #lag()} is at the limit. The
 * thread starts on the first submission, so a group that never delivers (a witness) costs nothing.
 */
final class DeliveryExecutor {

    /** Queued plus running deliveries at which {@code append()} starts refusing. */
    static final int DEFAULT_LAG_LIMIT = 4096;

    /** {@code index}: log index of the committed entry this delivers, or -1 (not a committed entry). */
    /** {@code error}: when non-null, the failure {@code task} delivers, kept as-is if the executor closes first. */
    private record Item(Runnable task, CompletableFuture<?> future, long index, Throwable error) {
    }

    private static final Item STOP = new Item(() -> { }, null, -1, null);

    /**
     * Lag at which a WARN is logged once (re-armed when the queue empties). {@code append()} bounds leader-side
     * growth at the lag limit, but follower commits are never refused, so a slow follower apply is unbounded;
     * this makes it visible.
     */
    static final int LAG_WARN_FACTOR = 4;

    private final String name;
    private final int lagLimit;
    private final LinkedBlockingQueue<Item> queue = new LinkedBlockingQueue<>();
    private final AtomicLong lag = new AtomicLong();
    private final AtomicLong maxLag = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();
    private final AtomicLong lagWarnings = new AtomicLong();
    private boolean lagWarned;
    private final ReentrantLock state = new ReentrantLock();
    private final Condition idle = state.newCondition();
    private Thread thread;
    private boolean closed;

    DeliveryExecutor(String name, int lagLimit) {
        if (lagLimit < 1) {
            throw new IllegalArgumentException("lagLimit");
        }
        this.name = name;
        this.lagLimit = lagLimit;
    }

    /** A callback for the committed entry at {@code index}. */
    void submit(Runnable task, long index) {
        submit(task, null, index);
    }

    /** A delivery that is not tied to a committed entry (for example failing futures on step-down). */
    void submit(Runnable task, CompletableFuture<?> future) {
        submit(task, future, -1);
    }

    /**
     * A delivery; {@code future} (may be null) is the one {@code task} completes, failed instead if the
     * executor closes before running it: with {@link CommittedNotAppliedException} when {@code index >= 0}
     * (the entry is committed), else {@link IllegalStateException}. Never blocks.
     */
    void submit(Runnable task, CompletableFuture<?> future, long index) {
        enqueue(new Item(task, future, index, null));
    }

    /**
     * Fails {@code future} with {@code error} on the delivery thread, in order after earlier deliveries. If the
     * executor closes first it is still failed with {@code error}, not a generic "closed": an outcome-unknown
     * failure must not turn into something that reads as a refusal (#254).
     */
    void fail(CompletableFuture<?> future, Throwable error) {
        enqueue(new Item(() -> future.completeExceptionally(error), future, -1, error));
    }

    private void enqueue(Item item) {
        state.lock();
        try {
            if (closed) {
                abandoned.incrementAndGet();
                abandon(item);
                return;
            }
            long now = lag.incrementAndGet();
            maxLag.accumulateAndGet(now, Math::max);
            if (!lagWarned && now >= (long) lagLimit * LAG_WARN_FACTOR) {
                lagWarned = true;
                lagWarnings.incrementAndGet();
                System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                        "group " + name + ": apply lag " + now + " deliveries (warn at " + (long) lagLimit
                                * LAG_WARN_FACTOR + "); callbacks are not keeping up");
            }
            queue.add(item);
            if (thread == null) {
                thread = Thread.ofVirtual().name("raft-deliver-" + name).start(this::loop);
            }
        } finally {
            state.unlock();
        }
    }

    /** True when {@code append()} should refuse: deliveries queued or running reached the limit. */
    boolean overLimit() {
        return lag.get() >= lagLimit;
    }

    /** Deliveries queued or running now (the apply-lag metric). */
    long lag() {
        return lag.get();
    }

    /** Highest {@link #lag()} seen. */
    long maxLag() {
        return maxLag.get();
    }

    /** Deliveries run so far. */
    long delivered() {
        return delivered.get();
    }

    /** Deliveries dropped by {@link #close} (or submitted after it) without running. */
    long abandoned() {
        return abandoned.get();
    }

    /** Times the lag crossed {@code LAG_WARN_FACTOR x lagLimit}. */
    long lagWarnings() {
        return lagWarnings.get();
    }

    int lagLimit() {
        return lagLimit;
    }

    /** True on this group's delivery thread: inside a commit callback or a future continuation it ran. */
    boolean onExecutorThread() {
        var t = thread;
        return t != null && Thread.currentThread() == t;
    }

    /**
     * Waits until nothing is queued or running. Returns false at once on the executor thread itself (it would
     * wait for itself), or when {@code timeout} passes first.
     */
    boolean awaitIdle(Duration timeout) {
        if (onExecutorThread()) {
            return lag.get() <= 1;
        }
        long nanos = timeout.toNanos();
        state.lock();
        try {
            while (lag.get() > 0) {
                if (nanos <= 0) {
                    return false;
                }
                try {
                    nanos = idle.awaitNanos(nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } finally {
            state.unlock();
        }
    }

    /**
     * Deterministic stop: drains for up to {@code drainBudget}, then abandons what is still queued (each future
     * fails with {@link IllegalStateException}), interrupts a callback still running, and joins the thread for up
     * to {@code drainBudget} more. Once this returns no further delivery starts, so a restarted group (heal)
     * never overlaps this one. Returns true when the thread has stopped. Called on the executor thread itself,
     * it abandons the queue and returns false without joining.
     */
    boolean close(Duration drainBudget) {
        Thread t;
        state.lock();
        try {
            if (closed && thread == null) {
                return true;
            }
            t = thread;
        } finally {
            state.unlock();
        }
        boolean self = t != null && Thread.currentThread() == t;
        if (t != null && !self) {
            awaitIdle(drainBudget);
        }
        state.lock();
        try {
            closed = true;
            long callbacks = 0;
            long futures = 0;
            long first = Long.MAX_VALUE;
            long last = -1;
            for (Item item; (item = queue.poll()) != null;) {
                if (item != STOP) {
                    lag.decrementAndGet();
                    abandoned.incrementAndGet();
                    if (item.future() == null) {
                        callbacks++;
                    } else {
                        futures++;
                    }
                    if (item.index() >= 0) {
                        first = Math.min(first, item.index());
                        last = Math.max(last, item.index());
                    }
                    abandon(item);
                }
            }
            if (callbacks + futures > 0) {
                System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                        "group " + name + ": close abandoned " + callbacks + " commit callbacks and " + futures
                                + " append futures" + (last >= 0 ? " for committed indexes " + first + ".." + last : "")
                                + " after the drain budget; they are applied on reopen by log replay");
            }
            queue.add(STOP);
            idle.signalAll();
        } finally {
            state.unlock();
        }
        if (t == null) {
            return true;
        }
        if (self) {
            return false;
        }
        try {
            t.join(drainBudget.toMillis() / 2 + 1);
            if (t.isAlive()) {
                t.interrupt(); // a callback overran the budget
                t.join(drainBudget.toMillis() / 2 + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !t.isAlive();
    }

    private void loop() {
        while (true) {
            Item item;
            try {
                item = queue.take();
            } catch (InterruptedException e) {
                state.lock();
                try {
                    if (closed) {
                        return;
                    }
                } finally {
                    state.unlock();
                }
                continue;
            }
            if (item == STOP) {
                return;
            }
            try {
                item.task().run();
            } catch (RuntimeException e) {
                System.getLogger(RaftGroup.class.getName()).log(System.Logger.Level.WARNING,
                        "commit callback failed in group " + name, e);
            } finally {
                delivered.incrementAndGet();
                if (lag.decrementAndGet() == 0) {
                    state.lock();
                    try {
                        lagWarned = false;
                        idle.signalAll();
                    } finally {
                        state.unlock();
                    }
                }
                Thread.interrupted(); // an interrupt aimed at one callback must not leak into the next
            }
        }
    }

    private void abandon(Item item) {
        if (item.future() != null) {
            item.future().completeExceptionally(item.error() != null ? item.error()
                    : item.index() >= 0 ? new CommittedNotAppliedException(name, item.index())
                    : new IllegalStateException("group closed before delivery"));
        }
    }
}
