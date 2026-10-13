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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import sjws_nettask.messaging.delivery.LeaseResult;

/**
 * Keeps a worker's leases alive (agent-swarm.md §4.2). Every {@link #tick()} sends <b>one batched</b>
 * {@code extend(group, offsets[], attempts[], visibilityMs)} per pool, carrying each lease's attempt as its
 * fencing token, so one EXTEND record covers all the worker's leases in that pool (the #91 bench measured
 * −1.4 % batched vs about −9 % unbatched). The single-lease form is never called.
 * <ul>
 * <li>A lease answered {@code NOT_IN_FLIGHT} or {@code STALE_ATTEMPT} is dropped and reported to the listener:
 * the worker lost it and must not settle it.</li>
 * <li>A lease is never extended past its task's deadline: once {@code now + visibilityMs} would pass it, the lease
 * leaves the batch and runs out at its current deadline, which is already no later than the task's.</li>
 * </ul>
 * Heartbeat at {@code visibilityMs / 3} or more often ({@link #start}).
 */
public final class LeaseKeeper implements AutoCloseable {

    private final TaskService tasks;
    private final long visibilityMs;
    private final BiConsumer<Task, LeaseResult> lost;
    private record Key(String pool, long offset) { }

    private final Map<Key, Task> held = new LinkedHashMap<>();
    private final Object lock = new Object();
    private volatile Thread thread;
    private volatile boolean closed;
    private long batches;

    public LeaseKeeper(TaskService tasks, long visibilityMs, BiConsumer<Task, LeaseResult> lost) {
        if (visibilityMs < 1) {
            throw new IllegalArgumentException("visibility");
        }
        this.tasks = tasks;
        this.visibilityMs = visibilityMs;
        this.lost = lost;
    }

    private static Key key(Task t) {
        return new Key(t.pool(), t.offset());
    }

    public void hold(Task t) {
        synchronized (this.lock) {
            this.held.put(key(t), t);
        }
    }

    /** Stops extending {@code t} (call it after a settle). */
    public void release(Task t) {
        synchronized (this.lock) {
            this.held.remove(key(t));
        }
    }

    public int size() {
        synchronized (this.lock) {
            return this.held.size();
        }
    }

    /** Extend calls (one per pool per tick) sent so far. */
    public long batches() {
        synchronized (this.lock) {
            return this.batches;
        }
    }

    /** One heartbeat round: one batched extend per pool. */
    public void tick() {
        long now = this.tasks.clock().getAsLong();
        var byPool = new LinkedHashMap<String, List<Task>>();
        var expiring = new ArrayList<Task>();
        synchronized (this.lock) {
            for (var it = this.held.values().iterator(); it.hasNext();) {
                var t = it.next();
                if (t.envelope().deadlineMs() - now < this.visibilityMs) {
                    it.remove();
                    expiring.add(t);
                } else {
                    byPool.computeIfAbsent(t.pool(), k -> new ArrayList<>()).add(t);
                }
            }
        }
        for (var e : byPool.entrySet()) {
            var list = e.getValue();
            var offs = new long[list.size()];
            var atts = new int[list.size()];
            for (int i = 0; i < offs.length; i++) {
                offs[i] = list.get(i).offset();
                atts[i] = list.get(i).attempt();
            }
            var results = this.tasks.extend(e.getKey(), offs, atts, this.visibilityMs);
            synchronized (this.lock) {
                this.batches++;
            }
            for (int i = 0; i < offs.length; i++) {
                if (!results.get(i).ok()) {
                    release(list.get(i));
                    this.lost.accept(list.get(i), results.get(i));
                }
            }
        }
        for (var t : expiring) {
            this.lost.accept(t, null);
        }
    }

    /** Starts a daemon thread that calls {@link #tick()} every {@code periodMs}. */
    public LeaseKeeper start(long periodMs) {
        if (periodMs < 1 || periodMs > this.visibilityMs) {
            throw new IllegalArgumentException("period");
        }
        this.thread = Thread.ofPlatform().daemon().name("swarm-lease-keeper").start(() -> {
            long next = System.nanoTime();
            while (!this.closed) {
                next += periodMs * 1_000_000L;
                LockSupport.parkNanos(next - System.nanoTime());
                if (!this.closed) {
                    tick();
                }
            }
        });
        return this;
    }

    @Override
    public void close() {
        this.closed = true;
        var t = this.thread;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
