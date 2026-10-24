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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;

/**
 * The swarm's partitions on one node, one per topic, opened on first use under one directory. A topic is
 * one {@link Partition} on master (agent-swarm.md §4.0).
 */
public final class Topics implements AutoCloseable {

    private final Path dir;
    private final RetryPolicy policy;
    private final LongSupplier clockMs;
    private final ConcurrentHashMap<String, Partition> open = new ConcurrentHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock opening = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean closed;

    public Topics(Path dir, RetryPolicy policy, LongSupplier clockMs) {
        this.dir = dir;
        this.policy = policy;
        this.clockMs = clockMs;
    }

    /**
     * S2c PR 5: the {@code CancelProbe} for each task topic ({@code tasks.*}), or null (the default, and always with
     * {@code sjws.swarm.stream.enabled} off: task partitions then open exactly as before, without a probe).
     */
    private volatile java.util.function.Function<String, sjws_nettask.messaging.delivery.CancelProbe> probes;

    /** Installs the cancel probes; call at startup, before any task partition opens. */
    void installProbes(java.util.function.Function<String, sjws_nettask.messaging.delivery.CancelProbe> probes) {
        this.probes = probes;
    }

    public LongSupplier clock() {
        return this.clockMs;
    }

    /** The open partition for {@code topic}, opening it (with its {@code .dlq}) if needed. */
    public Partition partition(String topic) {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        var p = this.open.get(topic);
        if (p != null) {
            return p; // hot path: a lock-free cached hit
        }
        this.opening.lock(); // opening does I/O: not inside computeIfAbsent
        try {
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            // close(String) removes and closes under this lock, so a partition seen here is never closing or closed
            p = this.open.get(topic);
            if (p == null) {
                var pr = this.probes;
                p = pr == null || !topic.startsWith(TaskService.TOPIC_PREFIX)
                        || topic.endsWith(Partition.DLQ_SUFFIX)
                        ? Partition.open(this.dir, topic, this.policy, this.clockMs)
                        : Partition.open(this.dir, topic, this.policy, this.clockMs,
                                sjws_nettask.messaging.ledger.Durability.ASYNC,
                                sjws_nettask.messaging.ledger.SegmentedLog.DEFAULT_SEGMENT_BYTES, true, pr.apply(topic));
                this.open.put(topic, p);
            }
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            this.opening.unlock();
        }
    }

    /**
     * The open partition for {@code topic}, opening it <b>without</b> a {@code .dlq} if needed
     * ({@code Partition.openWithoutDlq}, #225): the lazy stream shards {@code stream.<node>#i} (S2c, #224).
     */
    public Partition partitionWithoutDlq(String topic) {
        if (this.closed) {
            throw new IllegalStateException("closed");
        }
        var p = this.open.get(topic);
        if (p != null) {
            return p; // hot path: a lock-free cached hit
        }
        this.opening.lock();
        try {
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            // close(String) removes and closes under this lock, so a partition seen here is never closing or closed
            p = this.open.get(topic);
            if (p == null) {
                p = Partition.openWithoutDlq(this.dir, topic, this.policy, this.clockMs);
                this.open.put(topic, p);
            }
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            this.opening.unlock();
        }
    }

    public boolean closed() {
        return this.closed;
    }

    public boolean isOpen(String topic) {
        return this.open.containsKey(topic);
    }

    /**
     * Closes {@code topic} and keeps its files; the next {@link #partition} reopens it. Holds {@link #opening}
     * across the remove and the close, so a concurrent reopen (which only opens under {@link #opening}) can't open a
     * second {@link Partition} on the same directory while this one is still closing. A caller that got the
     * partition from the lock-free cached hit before the remove still holds one that is (or is about to be) closed:
     * its produce fails with "closed" and it must retry; the retry misses, waits on {@link #opening} and gets a
     * fresh one.
     */
    public void close(String topic) {
        this.opening.lock();
        try {
            var p = this.open.get(topic);
            if (p != null) {
                this.open.remove(topic, p); // removed first: from here on a lookup misses and blocks on opening
                closeQuietly(p);
            }
        } finally {
            this.opening.unlock();
        }
    }

    @Override
    public void close() {
        this.closed = true;
        for (var name : new ArrayList<>(this.open.keySet())) {
            close(name);
        }
    }

    static void closeQuietly(Partition p) {
        try {
            p.close();
        } catch (IOException e) {
            System.err.println("WARN: swarm: closing " + p.topic() + ": " + e);
        }
    }
}
