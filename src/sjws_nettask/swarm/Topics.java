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
            return p;
        }
        this.opening.lock(); // opening does I/O: not inside computeIfAbsent
        try {
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

    public boolean isOpen(String topic) {
        return this.open.containsKey(topic);
    }

    /** Closes {@code topic} and keeps its files; the next {@link #partition} reopens it. */
    public void close(String topic) {
        var p = this.open.remove(topic);
        if (p != null) {
            closeQuietly(p);
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
