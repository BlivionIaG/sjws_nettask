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
        this(topics, shards, SHARD_WAIT_SLICE_NANOS);
    }

    TaskService(Topics topics, int shards, long waitSliceNanos) {
        if (shards < 1 || shards > 256) throw new IllegalArgumentException("shards 1..256: " + shards);
        this.topics = topics;
        this.shards = shards;
        this.waitSliceNanos = waitSliceNanos;
    }

    /** Backstop park of a waiting sharded claim (redeliveries do not signal); appends wake it at once. */
    private final long waitSliceNanos;

    /**
     * Per-pool sharding state (sharded pools only; a 1-shard pool never creates one). {@code ready[s]} is a hint:
     * tasks submitted to shard {@code s} and not yet claimed. Claims sweep only shards with {@code ready > 0},
     * plus a full sweep every {@link #FULL_SWEEP_EVERY} sweeps and whenever a wait slice expires, which finds
     * redeliveries (expired leases, nacks past their backoff) and tasks submitted by other processes. A submit
     * signals the pool's waiters, so an append on any shard wakes a claim parked on the pool.
     */
    static final class PoolShards {
        final java.util.concurrent.atomic.AtomicLongArray ready;
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final java.util.concurrent.locks.Condition nonEmpty = this.lock.newCondition();
        final java.util.concurrent.atomic.AtomicInteger waiting = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong sweeps = new java.util.concurrent.atomic.AtomicLong();

        PoolShards(int shards) {
            this.ready = new java.util.concurrent.atomic.AtomicLongArray(shards);
        }

        boolean anyReady() {
            for (int i = 0; i < this.ready.length(); i++) {
                if (this.ready.get(i) > 0) return true;
            }
            return false;
        }

        void added(int shard, long n) {
            this.ready.addAndGet(shard, n);
            if (this.waiting.get() > 0) {
                this.lock.lock();
                try {
                    this.nonEmpty.signalAll();
                } finally {
                    this.lock.unlock();
                }
            }
        }

        void taken(int shard, long n) {
            this.ready.getAndUpdate(shard, v -> Math.max(0, v - n));
        }
    }

    static final int FULL_SWEEP_EVERY = 64;

    private final java.util.concurrent.ConcurrentHashMap<String, PoolShards> pools =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Partition polls done by sharded claims (tests: empty shards are skipped). */
    private final java.util.concurrent.atomic.LongAdder shardPolls = new java.util.concurrent.atomic.LongAdder();

    /**
     * Per-shard ready hints of {@code pool}: tasks submitted to each shard and not yet claimed, as seen by this
     * service. A hint, not a count: it excludes redeliveries and other processes' submits, and is reset to 0 when a
     * poll finds the shard empty. All zeros for a 1-shard pool or a pool not used yet. A snapshot copy.
     */
    public long[] readyHints(String pool) {
        var out = new long[this.shards];
        var ps = this.pools.get(pool);
        if (ps != null) {
            for (int i = 0; i < out.length; i++) out[i] = ps.ready.get(i);
        }
        return out;
    }

    long shardPolls() {
        return this.shardPolls.sum();
    }

    PoolShards pool(String pool) {
        var ps = this.pools.get(pool);
        if (ps != null) return ps;
        var fresh = new PoolShards(this.shards);
        ps = this.pools.putIfAbsent(pool, fresh);
        return ps != null ? ps : fresh;
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

    /** The pool's dead-letter queue ({@code tasks.P.dlq}). */
    public Partition deadLetters(String pool) {
        return partition(pool).deadLetterQueue();
    }

    /** The dead-letter queue of one shard. */
    public Partition deadLetters(String pool, int shard) {
        return partition(pool, shard).deadLetterQueue();
    }

    /** Submits a task; a repeated {@code (producerId, seq)} in the dedup window is dropped (§4.4). */
    public Partition.ProduceResult submit(String pool, long producerId, long seq, TaskEnvelope task) {
        int shard = shardOf(producerId, seq);
        var r = partition(pool, shard).produce(producerId, seq, task.encode());
        if (this.shards > 1) {
            pool(pool).added(shard, 1); // a deduped resubmit over-counts by one: a hint, corrected on claim
        }
        return r;
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
        // Sharded: sweep shards from the worker's home shard (spreads workers over the locks), skipping shards with
        // no ready hint, filling up to max across shards; park on the pool (woken by a submit to any shard).
        var ps = pool(pool);
        int home = Math.floorMod(worker.hashCode(), this.shards);
        long deadline = waitNanos > 0 ? System.nanoTime() + waitNanos : 0;
        boolean full = ps.sweeps.incrementAndGet() % FULL_SWEEP_EVERY == 0;
        while (true) {
            var out = sweep(pool, ps, worker, max, visibilityMs, home, full);
            if (!out.isEmpty()) return out;
            if (!full) {
                full = true; // nothing ready by the hints: one full sweep before parking (redeliveries, other processes)
                continue;
            }
            long left = deadline - System.nanoTime();
            if (waitNanos <= 0 || left <= 0) return List.of();
            ps.waiting.incrementAndGet();
            ps.lock.lock();
            try {
                if (!ps.anyReady()) {
                    ps.nonEmpty.awaitNanos(Math.min(left, this.waitSliceNanos));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                ps.lock.unlock();
                ps.waiting.decrementAndGet();
            }
            full = !ps.anyReady(); // woken by a submit: hinted sweep; slice expired: full sweep
        }
    }

    /** One pass over the shards; {@code full} also polls shards whose ready hint is zero. */
    private List<Task> sweep(String pool, PoolShards ps, String worker, int max, long visibilityMs, int home,
            boolean full) {
        List<Task> out = null;
        for (int i = 0; i < this.shards; i++) {
            int s = (home + i) % this.shards;
            long seen = ps.ready.get(s);
            if (!full && seen <= 0) continue;
            int want = max - (out == null ? 0 : out.size());
            var p = partition(pool, s);
            this.shardPolls.increment();
            var got = p.poll(WORKERS, worker, want, visibilityMs);
            if (got.isEmpty()) {
                this.afterEmptyPoll.run();
                // The hint was stale. Reset only if no submit raced in since we read it: a blind set(s, 0) here lost
                // the hint of a submit landing between the poll and the reset, and a claim then parked a full slice.
                // A failed CAS also keeps the stale part of the hint: at worst one extra empty poll later (the next
                // empty poll or full sweep resets it). Deliberate: do not "fix" this back to a blind set.
                ps.ready.compareAndSet(s, seen, 0);
                continue;
            }
            ps.taken(s, got.size());
            var ts = tasks(pool, s, p, got);
            if (ts.isEmpty()) continue;
            if (out == null) {
                if (ts.size() == max) return ts;
                out = new ArrayList<>(max);
            }
            out.addAll(ts);
            if (out.size() >= max) break;
        }
        return out == null ? List.of() : out;
    }

    static final Runnable NO_HOOK = () -> { };

    /**
     * Visible for testing only: runs between a sharded poll that came back empty and the reset of that shard's
     * ready hint. Never null; production leaves it at {@link #NO_HOOK}.
     */
    volatile Runnable afterEmptyPoll = NO_HOOK;

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
        int shard = tasks.get(0).shard();
        boolean oneShard = true;
        for (var t : tasks) {
            if (!t.pool().equals(pool)) {
                throw new IllegalArgumentException("tasks from one pool");
            }
            oneShard &= t.shard() == shard;
        }
        if (oneShard) {
            return ackShard(pool, shard, tasks);
        }
        // tasks from a multi-shard claim: one batched ack per shard, results put back in input order
        var results = new LeaseResult[tasks.size()];
        var byShard = new java.util.TreeMap<Integer, List<Integer>>();
        for (int i = 0; i < tasks.size(); i++) {
            var idx = byShard.get(tasks.get(i).shard());
            if (idx == null) {
                idx = new ArrayList<>();
                byShard.put(tasks.get(i).shard(), idx);
            }
            idx.add(i);
        }
        for (var e : byShard.entrySet()) {
            var idx = e.getValue();
            var part = new ArrayList<Task>(idx.size());
            for (int i : idx) part.add(tasks.get(i));
            var r = ackShard(pool, e.getKey(), part);
            for (int j = 0; j < idx.size(); j++) results[idx.get(j)] = r.get(j);
        }
        return List.of(results);
    }

    private List<LeaseResult> ackShard(String pool, int shard, List<Task> tasks) {
        var offs = new long[tasks.size()];
        var atts = new int[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            offs[i] = tasks.get(i).offset();
            atts[i] = tasks.get(i).attempt();
        }
        return partition(pool, shard).ack(WORKERS, offs, atts, null).results();
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
            durable.add(this.topics.partition(to).produce(producerId, firstSeq + i,
                    t.envelope().replyOwned(contentType, bodies.get(i)).encode(), Durability.ASYNC).durable());
        }
        // Different reply topics are different logs, so wait for each topic's futures (normally one topic).
        return CompletableFuture.allOf(durable.toArray(CompletableFuture[]::new))
                .thenApplyAsync(x -> ackAll(tasks), ACKER); // one batched ack per shard (a claim may span shards)
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
