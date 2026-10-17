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
    /** Partitions {@link #close(String)} has started closing; only touched under {@link #opening}. */
    private final java.util.Set<Partition> closing = ConcurrentHashMap.newKeySet();
    /** {@code closing.size()} as one volatile read, so a cached hit skips the set when nothing is closing. */
    private volatile int closingCount;
    private volatile boolean closed;

    public Topics(Path dir, RetryPolicy policy, LongSupplier clockMs) {
        this.dir = dir;
        this.policy = policy;
        this.clockMs = clockMs;
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
        if (p != null && usable(p)) {
            return p; // hot path: a lock-free cached hit
        }
        this.opening.lock(); // opening does I/O: not inside computeIfAbsent
        try {
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            // close(String) removes and closes under this lock, so a partition seen here is never closing
            p = this.open.get(topic);
            if (p == null) {
                p = Partition.open(this.dir, topic, this.policy, this.clockMs);
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
        if (p != null && usable(p)) {
            return p; // hot path: a lock-free cached hit
        }
        this.opening.lock();
        try {
            if (this.closed) {
                throw new IllegalStateException("closed");
            }
            // close(String) removes and closes under this lock, so a partition seen here is never closing
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

    public boolean isOpen(String topic) {
        return this.open.containsKey(topic);
    }

    /** False while {@link #close(String)} is closing {@code p}: the caller must take the lock and reopen. */
    private boolean usable(Partition p) {
        return this.closingCount == 0 || !this.closing.contains(p);
    }

    /**
     * Closes {@code topic} and keeps its files; the next {@link #partition} reopens it. Holds {@link #opening}
     * across the remove and the close so a concurrent reopen can't open a second {@link Partition} on the same
     * directory, and marks the partition closing first so a cached hit racing this doesn't hand it out.
     * A caller that got the partition before the close started still holds a closed one and must retry.
     */
    public void close(String topic) {
        this.opening.lock();
        try {
            var p = this.open.get(topic);
            if (p == null) {
                return;
            }
            this.closing.add(p);
            this.closingCount++;
            try {
                this.open.remove(topic, p);
                closeQuietly(p);
            } finally {
                this.closing.remove(p);
                this.closingCount--;
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
