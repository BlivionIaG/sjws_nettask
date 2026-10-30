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

import java.nio.file.Path;
import java.util.function.LongSupplier;
import sjws_nettask.messaging.delivery.RetryPolicy;

/**
 * The S1 services on one node (agent-swarm.md §12 S1): task submit/claim/settle with fixed or kept leases,
 * the DLQ, and request/reply on {@code reply.<id>}.
 *
 * <p>{@link #close} stops every thread the swarm started and waits for each, bounded (#181): the reply poller, the
 * stream shard reader, the lease keepers made by {@link #leaseKeeper}, the stream sweep (for its StreamHttp), and the
 * ack executor, whose queued acks drain first. Then it closes every partition. It is idempotent; a concurrent close
 * waits for the first one to finish.
 */
public final class Swarm implements AutoCloseable {

    private final Topics topics;
    private final TaskService tasks;
    private final ReplyRouter replies;
    private volatile TokenStreams streams;
    private final java.util.concurrent.locks.ReentrantLock lifecycle = new java.util.concurrent.locks.ReentrantLock();
    /** Guarded by {@link #lifecycle}. */
    private boolean closed;
    /**
     * Close hooks of components started on this swarm (keepers, the stream sweep).
     * Concurrent so a keeper can deregister while {@link #close} holds {@link #lifecycle} (or while another
     * register races): a {@code tryLock} skip would leak the closed keeper until swarm close (#181 / Codex P2).
     * Adds still go through {@link #lifecycle} with the {@code closed} check.
     */
    private final java.util.Set<Runnable> onClose = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Swarm(Topics topics, String id, long producerId, long idleCloseMs) {
        this.topics = topics;
        this.tasks = new TaskService(topics);
        this.replies = new ReplyRouter(topics, this.tasks, id, producerId, idleCloseMs);
        this.tasks.bindLocalReplies(this.replies);
    }

    public static Swarm open(Path dir, String id, long producerId, RetryPolicy policy, LongSupplier clockMs,
            long idleCloseMs) {
        return new Swarm(new Topics(dir, policy, clockMs), id, producerId, idleCloseMs);
    }

    /**
     * Another node on the same ledger as {@code other} (its own task service, reply router, streams and CancelSet;
     * shared topics). For multi-node tests; close it with {@code replies().close()} and {@code tasks().close()}, the
     * topics belong to {@code other}.
     */
    static Swarm sharing(Swarm other, String id, long producerId, long idleCloseMs) {
        return new Swarm(other.topics, id, producerId, idleCloseMs);
    }

    public static Swarm open(Path dir, String id, long producerId) {
        return open(dir, id, producerId, RetryPolicy.DEFAULT, System::currentTimeMillis,
                ReplyRouter.DEFAULT_IDLE_CLOSE_MS);
    }

    public TaskService tasks() {
        return this.tasks;
    }

    public ReplyRouter replies() {
        return this.replies;
    }

    public Topics topics() {
        return this.topics;
    }

    /** A lease keeper for one worker, using the batched fenced extend. */
    /**
     * Turns on the S2c stream produce path with {@code config} (call once, at startup, only when
     * {@code sjws.swarm.stream.enabled} is on). The node id names the stream shards {@code stream.<node>#i}.
     */
    public TokenStreams enableStreams(TokenStreams.Config config) {
        var s = new TokenStreams(this.topics, this.replies.topic().substring("reply.".length()), config);
        this.lifecycle.lock();
        try {
            if (!this.closed) {
                this.topics.installProbes(s.cancels()::probe); // S2c PR 5: extend answers CANCELLED for cancelled tasks
                this.tasks.bindStreams(s);
                this.streams = s;
                return s;
            }
        } finally {
            this.lifecycle.unlock();
        }
        s.close(); // raced Swarm.close: do not orphan a reader
        throw new IllegalStateException("swarm closed");
    }

    /** Closed by its caller, or at the latest by {@link #close}. */
    public LeaseKeeper leaseKeeper(long visibilityMs, java.util.function.BiConsumer<Task, sjws_nettask.messaging.delivery.LeaseResult> lost) {
        var k = new LeaseKeeper(this.tasks, visibilityMs, lost);
        Runnable hook = k::close;
        k.onClose(() -> removeOnClose(hook));
        onClose(hook);
        return k;
    }

    /** Runs {@code hook} in {@link #close} (at once if already closed). */
    void onClose(Runnable hook) {
        this.lifecycle.lock();
        try {
            if (!this.closed) {
                this.onClose.add(hook);
                return;
            }
        } finally {
            this.lifecycle.unlock();
        }
        hook.run();
    }

    private void removeOnClose(Runnable hook) {
        this.onClose.remove(hook); // lock-free: must not wait on lifecycle (close holds it while running hooks)
    }

    @Override
    public void close() {
        this.lifecycle.lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.replies.close(); // the poller; pending request futures fail
            var s = this.streams;
            if (s != null) {
                s.close(); // the shard reader
            }
            for (var hook : java.util.List.copyOf(this.onClose)) {
                try {
                    hook.run(); // lease keepers, the stream sweep
                } catch (RuntimeException e) {
                    System.err.println("WARN: swarm: close hook: " + e);
                }
            }
            this.onClose.clear();
            this.tasks.close(); // queued acks drain while the partitions are still open
            this.topics.close();
        } finally {
            this.lifecycle.unlock();
        }
    }
}
