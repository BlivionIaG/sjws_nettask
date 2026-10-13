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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.ledger.Durability;

/**
 * Tasks on messaging (agent-swarm.md §4.1): pool {@code P} is topic {@code tasks.P}, its workers are the
 * consumer group {@value #WORKERS}, and a worker is the consumer id. Every settle is fenced by the
 * delivery attempt (#87); the unfenced overloads are never called.
 */
public final class TaskService {

    public static final String WORKERS = "workers";
    public static final String TOPIC_PREFIX = "tasks.";

    private final Topics topics;

    /**
     * Runs the post-durability acks of {@link #reply}/{@link #replyAll}. Without it the ack (which takes the
     * pool partition's lock) ran on the reply topic's ledger-writer thread, stalling that writer behind the lock.
     */
    private static final java.util.concurrent.Executor ACKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        var th = new Thread(r, "sjws-reply-ack");
        th.setDaemon(true);
        return th;
    });

    public TaskService(Topics topics) {
        this.topics = topics;
    }

    public static String topic(String pool) {
        var t = TOPIC_NAMES.get(pool); // claim/submit hot path: no string concat per call
        if (t == null) {
            t = TOPIC_PREFIX + pool;
            if (TOPIC_NAMES.size() < 4096) TOPIC_NAMES.putIfAbsent(pool, t);
        }
        return t;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, String> TOPIC_NAMES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The pool's partition; the worker group reads from the oldest retained task. */
    Partition partition(String pool) {
        var p = this.topics.partition(topic(pool));
        p.subscribe(WORKERS, true);
        return p;
    }

    /** The pool's dead-letter queue ({@code tasks.P.dlq}). */
    public Partition deadLetters(String pool) {
        return partition(pool).deadLetterQueue();
    }

    /** Submits a task; a repeated {@code (producerId, seq)} in the dedup window is dropped (§4.4). */
    public Partition.ProduceResult submit(String pool, long producerId, long seq, TaskEnvelope task) {
        return partition(pool).produce(producerId, seq, task.encode());
    }

    /**
     * Claims up to {@code max} tasks with a fixed lease of {@code visibilityMs} (§5.1 deadlines):
     * <ul>
     * <li>a task whose deadline already passed is rejected with reason {@code deadline} and not returned;</li>
     * <li>a lease that would outlive the task's deadline is shortened to it, in one batched extend.</li>
     * </ul>
     * A payload that is not an envelope is rejected with reason {@code bad-envelope}.
     */
    public List<Task> claim(String pool, String worker, int max, long visibilityMs) {
        return claim(pool, worker, max, visibilityMs, 0);
    }

    /** As {@link #claim(String, String, int, long)}, waiting up to {@code waitNanos} for a task (#99). */
    public List<Task> claim(String pool, String worker, int max, long visibilityMs, long waitNanos) {
        var p = partition(pool);
        var got = waitNanos > 0 ? p.poll(WORKERS, worker, max, visibilityMs, waitNanos)
                : p.poll(WORKERS, worker, max, visibilityMs);
        if (got.isEmpty()) {
            return List.of();
        }
        long now = this.topics.clock().getAsLong();
        var out = new ArrayList<Task>(got.size());
        var shortOff = new long[got.size()];
        var shortAtt = new int[got.size()];
        var shortIdx = new int[got.size()];
        int n = 0;
        long shortest = Long.MAX_VALUE;
        for (var d : got) {
            TaskEnvelope env;
            try {
                env = TaskEnvelope.decode(d.payload());
            } catch (IllegalArgumentException e) {
                p.reject(WORKERS, d.offset(), d.attempt(), "bad-envelope");
                continue;
            }
            if (env.expiredAt(now)) {
                p.reject(WORKERS, d.offset(), d.attempt(), "deadline");
                continue;
            }
            long lease = d.deadlineMs();
            if (env.deadlineMs() < lease) {
                shortOff[n] = d.offset();
                shortAtt[n] = d.attempt();
                shortIdx[n] = out.size();
                n++;
                shortest = Math.min(shortest, env.deadlineMs() - now);
                lease = env.deadlineMs();
            }
            out.add(new Task(pool, d.offset(), d.attempt(), lease, env));
        }
        if (n > 0) {
            // One EXTEND record for the batch; it uses the shortest remaining deadline for every shortened lease.
            p.extend(WORKERS, java.util.Arrays.copyOf(shortOff, n), java.util.Arrays.copyOf(shortAtt, n), shortest);
            for (int i = 0; i < n; i++) {
                var t = out.get(shortIdx[i]);
                out.set(shortIdx[i], new Task(t.pool(), t.offset(), t.attempt(), now + shortest, t.envelope()));
            }
        }
        return out;
    }

    java.util.function.LongSupplier clock() {
        return this.topics.clock();
    }

    /** Batched, fenced heartbeat: one EXTEND record for all {@code offsets} (§4.2). */
    public List<LeaseResult> extend(String pool, long[] offsets, int[] attempts, long visibilityMs) {
        return partition(pool).extend(WORKERS, offsets, attempts, visibilityMs);
    }

    /**
     * Acks every task in {@code tasks} (all from one pool) with <b>one batched fenced ack</b> (#100): one lock
     * acquisition and one ledger record instead of one per task. Results are in input order; a stale or settled
     * task gets {@code STALE_ATTEMPT} / {@code NOT_IN_FLIGHT} without affecting the others.
     */
    public List<LeaseResult> ackAll(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        var pool = tasks.get(0).pool();
        var offs = new long[tasks.size()];
        var atts = new int[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            var t = tasks.get(i);
            if (!t.pool().equals(pool)) {
                throw new IllegalArgumentException("tasks from one pool");
            }
            offs[i] = t.offset();
            atts[i] = t.attempt();
        }
        return partition(pool).ack(WORKERS, offs, atts, null).results();
    }

    public LeaseResult ack(Task t) {
        return partition(t.pool()).ack(WORKERS, t.offset(), t.attempt());
    }

    /** Transient failure: redelivered after {@code max(delayMs, backoff)}, dead-lettered at max attempts. */
    public LeaseResult nack(Task t, long delayMs) {
        return partition(t.pool()).nack(WORKERS, t.offset(), t.attempt(), delayMs);
    }

    /** Deterministic failure: straight to the pool's DLQ with {@code rejected:<reason>}. */
    public LeaseResult reject(Task t, String reason) {
        return partition(t.pool()).reject(WORKERS, t.offset(), t.attempt(), reason);
    }

    /**
     * Writes the reply to the task's {@code replyTo} topic with {@link Durability#ASYNC}, then acks the task once
     * the reply is durable (§4.4): a crash before that redelivers the task, and the caller dedups by taskId.
     * The future holds the ack's result. {@code (producerId, seq)} dedups a reply retried after a crash.
     */
    /**
     * Replies to every task in {@code tasks} ({@code bodies[i]} for {@code tasks.get(i)}, all from one pool), each
     * with {@code (producerId, firstSeq + i)}, then acks them with <b>one batched fenced ack</b> (#100) once the last
     * reply is durable. Takes ownership of the {@code bodies} arrays (no copy).
     */
    public CompletableFuture<List<LeaseResult>> replyAll(List<Task> tasks, long producerId, long firstSeq,
            String contentType, List<byte[]> bodies) {
        if (tasks.size() != bodies.size()) {
            throw new IllegalArgumentException("one body per task");
        }
        if (tasks.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        var pool = tasks.get(0).pool();
        var offs = new long[tasks.size()];
        var atts = new int[tasks.size()];
        var durable = new java.util.ArrayList<CompletableFuture<Long>>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            var t = tasks.get(i);
            if (!t.pool().equals(pool)) {
                throw new IllegalArgumentException("tasks from one pool");
            }
            var to = t.envelope().replyTo();
            if (to.isEmpty()) {
                throw new IllegalArgumentException("task has no replyTo");
            }
            offs[i] = t.offset();
            atts[i] = t.attempt();
            durable.add(this.topics.partition(to).produce(producerId, firstSeq + i,
                    t.envelope().replyOwned(contentType, bodies.get(i)).encode(), Durability.ASYNC).durable());
        }
        // Different reply topics are different logs, so wait for each topic's futures (normally one topic).
        return CompletableFuture.allOf(durable.toArray(CompletableFuture[]::new))
                .thenApplyAsync(x -> partition(pool).ack(WORKERS, offs, atts, null).results(), ACKER);
    }

    public CompletableFuture<LeaseResult> reply(Task t, long producerId, long seq, String contentType, byte[] body) {
        var to = t.envelope().replyTo();
        if (to.isEmpty()) {
            throw new IllegalArgumentException("task has no replyTo");
        }
        var reply = t.envelope().reply(contentType, body);
        var r = this.topics.partition(to).produce(producerId, seq, reply.encode(), Durability.ASYNC);
        return r.durable().thenApplyAsync(off -> ack(t), ACKER);
    }
}
