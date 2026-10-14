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
     * Partitions per pool (S1c step 2). Shard 0 is topic {@code tasks.P} (so a 1-shard pool is exactly the old
     * layout); shard {@code i > 0} is {@code tasks.P#i}. Each shard has its own lock, ledger and DLQ, so claim/ack
     * on different shards never contend. Default {@code sjws.swarm.pool.shards} (1).
     */
    private final int shards;
    private final java.util.concurrent.atomic.AtomicLong roundRobin = new java.util.concurrent.atomic.AtomicLong();

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
        this(topics, Integer.getInteger("sjws.swarm.pool.shards", 1));
    }

    public TaskService(Topics topics, int shards) {
        if (shards < 1 || shards > 256) throw new IllegalArgumentException("shards 1..256: " + shards);
        this.topics = topics;
        this.shards = shards;
    }

    public int shards() {
        return this.shards;
    }

    /** Topic of one shard of {@code pool}. */
    public static String topic(String pool, int shard) {
        return shard == 0 ? topic(pool) : topic(pool) + "#" + shard;
    }

    /**
     * Shard of a submit. Deterministic in {@code (producerId, seq)} so a retried submit lands on the shard that holds
     * its dedup window, and a producer's seqs stay increasing within each shard; anonymous submits go round-robin.
     */
    int shardOf(long producerId, long seq) {
        if (this.shards == 1) return 0;
        if (producerId == 0) return (int) Long.remainderUnsigned(this.roundRobin.getAndIncrement(), this.shards);
        long h = (producerId * 0x9E3779B97F4A7C15L) ^ seq;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) Long.remainderUnsigned(h, this.shards);
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
        return partition(pool, 0);
    }

    /** One shard's partition. */
    Partition partition(String pool, int shard) {
        var p = this.topics.partition(topic(pool, shard));
        p.subscribe(WORKERS, true);
        return p;
    }

    /** Shard 0's dead-letter queue ({@code tasks.P.dlq}); the whole pool's for an unsharded pool. See {@link #deadLetterQueues}. */
    public Partition deadLetters(String pool) {
        return partition(pool).deadLetterQueue();
    }

    /** The dead-letter queue of one shard. */
    public Partition deadLetters(String pool, int shard) {
        return partition(pool, shard).deadLetterQueue();
    }

    /** Every shard's dead-letter queue, shard order ({@link #deadLetters(String)} is shard 0 only). */
    public List<Partition> deadLetterQueues(String pool) {
        var out = new ArrayList<Partition>(this.shards);
        for (int s = 0; s < this.shards; s++) out.add(deadLetters(pool, s));
        return out;
    }

    /** One dead-lettered task and the shard whose DLQ holds it (settle it on {@code deadLetters(pool, shard)}). */
    public record DeadLetter(int shard, sjws_nettask.messaging.delivery.Delivery delivery) { }

    /**
     * Polls the pool's dead letters across all shards for {@code group} (subscribed from the oldest on first use),
     * sweeping from shard 0 until {@code max} are leased.
     */
    public List<DeadLetter> pollDeadLetters(String pool, String group, String consumer, int max, long visibilityMs) {
        var out = new ArrayList<DeadLetter>();
        for (int s = 0; s < this.shards && out.size() < max; s++) {
            var dlq = deadLetters(pool, s);
            dlq.subscribe(group, true);
            for (var d : dlq.poll(group, consumer, max - out.size(), visibilityMs)) out.add(new DeadLetter(s, d));
        }
        return out;
    }

    /**
     * Pool totals over all shards: the worker group's counts summed, the dead letters still in the DLQs, and the
     * oldest unresolved task's enqueue time (0 if none).
     */
    public record PoolCounts(int shards, sjws_nettask.messaging.delivery.DeliverySnapshot.Counts workers, long deadLetterQueueSize,
            long oldestUnresolvedAtMs) { }

    public PoolCounts counts(String pool) {
        long enq = 0, infl = 0, acked = 0, dead = 0, redel = 0, dlqSize = 0, oldest = 0;
        for (int s = 0; s < this.shards; s++) {
            var p = partition(pool, s);
            var g = p.counts().groups().get(WORKERS);
            if (g != null) {
                var c = g.counts();
                enq += c.enqueued();
                infl += c.inFlight();
                acked += c.acked();
                dead += c.deadLettered();
                redel += c.redeliveries();
                if (g.oldestUnresolvedAtMs() > 0 && (oldest == 0 || g.oldestUnresolvedAtMs() < oldest)) oldest = g.oldestUnresolvedAtMs();
            }
            var dlq = p.deadLetterQueue();
            if (dlq != null) dlqSize += dlq.counts().liveMessages();
        }
        return new PoolCounts(this.shards, new sjws_nettask.messaging.delivery.DeliverySnapshot.Counts(enq, infl, acked, dead, redel),
                dlqSize, oldest);
    }

    /** Submits a task; a repeated {@code (producerId, seq)} in the dedup window is dropped (§4.4). */
    public Partition.ProduceResult submit(String pool, long producerId, long seq, TaskEnvelope task) {
        return partition(pool, shardOf(producerId, seq)).produce(producerId, seq, task.encode());
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
        if (this.shards == 1) {
            var p = partition(pool, 0);
            return tasks(pool, 0, p, waitNanos > 0 ? p.poll(WORKERS, worker, max, visibilityMs, waitNanos)
                    : p.poll(WORKERS, worker, max, visibilityMs));
        }
        // Sharded: start at the worker's home shard (spreads workers over the locks), then sweep the others.
        int home = Math.floorMod(worker.hashCode(), this.shards);
        long deadline = waitNanos > 0 ? System.nanoTime() + waitNanos : 0;
        while (true) {
            for (int i = 0; i < this.shards; i++) {
                int s = (home + i) % this.shards;
                var p = partition(pool, s);
                var got = p.poll(WORKERS, worker, max, visibilityMs);
                if (!got.isEmpty()) {
                    var out = tasks(pool, s, p, got);
                    if (!out.isEmpty()) return out;
                }
            }
            long left = deadline - System.nanoTime();
            if (waitNanos <= 0 || left <= 0) return List.of();
            // Park on the home shard (woken by its appends), in slices so tasks landing on other shards are seen.
            var p = partition(pool, home);
            var got = p.poll(WORKERS, worker, max, visibilityMs, Math.min(left, SHARD_WAIT_SLICE_NANOS));
            if (!got.isEmpty()) {
                var out = tasks(pool, home, p, got);
                if (!out.isEmpty()) return out;
            }
        }
    }

    /** Longest park on the home shard of a waiting sharded claim before the other shards are swept again. */
    static final long SHARD_WAIT_SLICE_NANOS = 2_000_000;

    /** Decodes, deadline-checks and lease-shortens one shard's deliveries. */
    private List<Task> tasks(String pool, int shard, Partition p, List<sjws_nettask.messaging.delivery.Delivery> got) {
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
            out.add(new Task(pool, d.offset(), d.attempt(), lease, env, shard));
        }
        if (n > 0) {
            // One EXTEND record for the batch; it uses the shortest remaining deadline for every shortened lease.
            p.extend(WORKERS, java.util.Arrays.copyOf(shortOff, n), java.util.Arrays.copyOf(shortAtt, n), shortest);
            for (int i = 0; i < n; i++) {
                var t = out.get(shortIdx[i]);
                out.set(shortIdx[i], t.withDeadline(now + shortest));
            }
        }
        return out;
    }

    java.util.function.LongSupplier clock() {
        return this.topics.clock();
    }

    /** Batched, fenced heartbeat: one EXTEND record for all {@code offsets} (§4.2). */
    public List<LeaseResult> extend(String pool, long[] offsets, int[] attempts, long visibilityMs) {
        return extend(pool, 0, offsets, attempts, visibilityMs);
    }

    /** As {@link #extend(String, long[], int[], long)}, for leases of one shard. */
    public List<LeaseResult> extend(String pool, int shard, long[] offsets, int[] attempts, long visibilityMs) {
        return partition(pool, shard).extend(WORKERS, offsets, attempts, visibilityMs);
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
        return partition(t.pool(), t.shard()).ack(WORKERS, t.offset(), t.attempt());
    }

    /** Transient failure: redelivered after {@code max(delayMs, backoff)}, dead-lettered at max attempts. */
    public LeaseResult nack(Task t, long delayMs) {
        return partition(t.pool(), t.shard()).nack(WORKERS, t.offset(), t.attempt(), delayMs);
    }

    /** Deterministic failure: straight to the pool's DLQ with {@code rejected:<reason>}. */
    public LeaseResult reject(Task t, String reason) {
        return partition(t.pool(), t.shard()).reject(WORKERS, t.offset(), t.attempt(), reason);
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
        int shard = tasks.get(0).shard();
        var offs = new long[tasks.size()];
        var atts = new int[tasks.size()];
        var durable = new java.util.ArrayList<CompletableFuture<Long>>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            var t = tasks.get(i);
            if (!t.pool().equals(pool) || t.shard() != shard) {
                throw new IllegalArgumentException("tasks from one pool (and shard)");
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
                .thenApplyAsync(x -> partition(pool, shard).ack(WORKERS, offs, atts, null).results(), ACKER);
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
