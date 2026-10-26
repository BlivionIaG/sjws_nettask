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
import java.util.concurrent.TimeUnit;
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
     * Same-process {@link ReplyRouter}, or null when this service is used alone. A null check on the reply path;
     * when set, {@link #reply}/{@link #replyAll} complete the caller before the ledger write returns.
     */
    private volatile ReplyRouter localReplies;

    /**
     * Partitions per pool (S1c step 2). Shard 0 is topic {@code tasks.P} (so a 1-shard pool is exactly the old
     * layout); shard {@code i > 0} is {@code tasks.P#i}. Each shard has its own lock, ledger and DLQ, so claim/ack
     * on different shards never contend. Default {@code sjws.swarm.pool.shards} (1).
     */
    private final int shards;
    private final java.util.concurrent.atomic.AtomicLong roundRobin = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Runs the post-durability acks of {@link #reply}/{@link #replyAll}/{@link #streamLast}. Without it the ack (which
     * takes the pool partition's lock) ran on the reply topic's ledger-writer thread, stalling that writer behind the
     * lock. Owned by this service (#181: it used to be static and outlived every close): its one thread starts with the
     * first ack and ends in {@link #close}.
     */
    private final java.util.concurrent.ThreadPoolExecutor acker = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                var th = new Thread(r, ACKER_THREAD);
                th.setDaemon(true);
                return th;
            });
    static final String ACKER_THREAD = "sjws-reply-ack";
    /** How long {@link #close} lets queued acks drain before it drops them. */
    static final long CLOSE_DRAIN_MS = 2_000;

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

    /** S2c: set only when {@code sjws.swarm.stream.enabled} is on; null otherwise (no stream class loaded). */
    private volatile TokenStreams streams;

    /** Wires the S2c stream produce path ({@link SwarmModule}, flag on only). */
    void bindStreams(TokenStreams streams) {
        this.streams = streams;
    }

    TokenStreams streams() {
        return this.streams;
    }

    /** Wires the node's {@link ReplyRouter} so in-process replies wake the caller directly. Idempotent. */
    void bindLocalReplies(ReplyRouter replies) {
        this.localReplies = replies;
    }

    /** Completes a same-process caller when {@code replyTo} is this node's reply topic; no-op otherwise. */
    private void deliverLocal(String replyTo, TaskEnvelope reply) {
        var local = this.localReplies;
        if (local != null && local.topic().equals(replyTo)) {
            local.completeLocal(reply);
        }
    }

    /** Backstop park of a waiting sharded claim (redeliveries do not signal); appends wake it at once. */
    private final long waitSliceNanos;

    /**
     * Per-pool sharding state (sharded pools only; a 1-shard pool never creates one). {@code ready[s]} is a hint:
     * tasks submitted to shard {@code s} and not yet claimed. Claims sweep only shards with {@code ready > 0},
     * plus a full sweep every {@link #FULL_SWEEP_EVERY} sweeps and whenever a wait slice expires, which finds
     * redeliveries (expired leases, nacks past their backoff) and tasks submitted by other processes. A submit
     * on a sharded pool claims from its own shard for the oldest parked claim and unparks it with the tasks in
     * hand (direct handoff, no lock/Condition); cross-shard drain is still the pre-park sweep and full sweep.
     */
    static final class PoolShards {
        final java.util.concurrent.atomic.AtomicLongArray ready;
        /** Parked claims, oldest first; a submit claims for the first one it can take (direct handoff). */
        final java.util.concurrent.ConcurrentLinkedQueue<Waiter> waiters = new java.util.concurrent.ConcurrentLinkedQueue<>();
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
        }

        void taken(int shard, long n) {
            this.ready.getAndUpdate(shard, v -> Math.max(0, v - n));
        }
    }

    /**
     * A sharded claim parked on its pool. A submit that finds it moves it WAITING -> CLAIMING (exclusive), polls
     * its own shard with the waiter's worker/max/visibility, stores the result in {@link #handed} and moves it to
     * DONE before unparking it. The waiter can only leave on its own by WAITING -> CANCELLED; once a submit holds
     * CLAIMING it waits for DONE and returns what it was handed, so a task claimed on its behalf is never dropped.
     */
    static final class Waiter {
        static final int WAITING = 0, CLAIMING = 1, DONE = 2, CANCELLED = 3;
        final Thread thread;
        final String worker;
        final int max;
        final long visibilityMs;
        final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(WAITING);
        /** Written before state DONE (volatile), read after it. */
        List<Task> handed = List.of();
        /** Ledger clock at the submitter's poll for {@link #handed}; written before DONE, read after it. */
        long handedAtMs;

        Waiter(Thread thread, String worker, int max, long visibilityMs) {
            this.thread = thread;
            this.worker = worker;
            this.max = max;
            this.visibilityMs = visibilityMs;
        }

        boolean cancel() {
            return this.state.compareAndSet(WAITING, CANCELLED);
        }
    }

    /** Claims handed to a parked claim by the submitting thread (tests and benches). */
    private final java.util.concurrent.atomic.LongAdder handoffs = new java.util.concurrent.atomic.LongAdder();

    long handoffs() {
        return this.handoffs.sum();
    }

    /** Handoffs whose poll threw (the waiter then sweeps for itself); tests and benches. */
    private final java.util.concurrent.atomic.LongAdder handoffErrors = new java.util.concurrent.atomic.LongAdder();

    long handoffErrors() {
        return this.handoffErrors.sum();
    }

    /** Handed leases re-extended by the woken waiter because the clock moved during its wake (tests, benches). */
    private final java.util.concurrent.atomic.LongAdder handoffRefreshes = new java.util.concurrent.atomic.LongAdder();

    long handoffRefreshes() {
        return this.handoffRefreshes.sum();
    }

    /**
     * Visible for testing only: runs on the submitting thread after a handoff poll leased tasks for a waiter and
     * before the waiter is released (simulates the waiter's schedule gap). Production leaves it at {@link #NO_HOOK}.
     */
    volatile Runnable afterHandoffPoll = NO_HOOK;

    /**
     * Lease refresh on receipt (review of #223): the submitter's poll started the handed leases before the
     * waiter was even runnable, so the lease would otherwise tick through the waiter's schedule gap and a short
     * {@code visibilityMs} could expire (and redeliver) a task the waiter is about to run. Once the waiter is on
     * CPU it re-extends the handed leases to {@code now + visibilityMs} (never past a task's own deadline), so the
     * lease window starts when the worker runs, as on master. Skipped when the ledger clock has not moved since
     * the poll (the common sub-millisecond handoff), so the fast path costs no extra ledger write. A lease that
     * was already fenced (expired and redelivered while the waiter was descheduled) is dropped from the result:
     * the redelivery owns it now.
     */
    private List<Task> refreshHanded(Waiter w) {
        List<Task> ts = w.handed;
        long now = this.topics.clock().getAsLong();
        if (now <= w.handedAtMs) return ts;
        var t0 = ts.get(0);
        var p = partition(t0.pool(), t0.shard());
        int k = ts.size();
        var fullOff = new long[k];
        var fullAtt = new int[k];
        var fullIdx = new int[k];
        var shortOff = new long[k];
        var shortAtt = new int[k];
        var shortIdx = new int[k];
        int nf = 0, ns = 0;
        long shortest = Long.MAX_VALUE;
        for (int i = 0; i < k; i++) {
            var t = ts.get(i);
            long dl = t.envelope().deadlineMs();
            if (dl - now >= w.visibilityMs) {
                fullOff[nf] = t.offset(); fullAtt[nf] = t.attempt(); fullIdx[nf++] = i;
            } else if (dl > now) {
                shortOff[ns] = t.offset(); shortAtt[ns] = t.attempt(); shortIdx[ns++] = i;
                shortest = Math.min(shortest, dl - now);
            } // else past its own deadline: leave the lease as is, the handler sees the deadline
        }
        var out = new ArrayList<>(ts);
        var dropped = new boolean[k];
        if (nf > 0) {
            var r = p.extend(WORKERS, java.util.Arrays.copyOf(fullOff, nf), java.util.Arrays.copyOf(fullAtt, nf), w.visibilityMs);
            for (int j = 0; j < nf; j++) {
                if (r.get(j) == LeaseResult.OK) out.set(fullIdx[j], ts.get(fullIdx[j]).withDeadline(now + w.visibilityMs));
                else dropped[fullIdx[j]] = true;
            }
        }
        if (ns > 0) {
            var r = p.extend(WORKERS, java.util.Arrays.copyOf(shortOff, ns), java.util.Arrays.copyOf(shortAtt, ns), shortest);
            for (int j = 0; j < ns; j++) {
                if (r.get(j) == LeaseResult.OK) out.set(shortIdx[j], ts.get(shortIdx[j]).withDeadline(now + shortest));
                else dropped[shortIdx[j]] = true;
            }
        }
        this.handoffRefreshes.increment();
        var kept = new ArrayList<Task>(k);
        for (int i = 0; i < k; i++) if (!dropped[i]) kept.add(out.get(i));
        return kept;
    }

    /**
     * Direct handoff (S1c): the submitting thread, already running, claims from {@code shard} for the oldest parked
     * claim and unparks it with the tasks in hand. The woken worker then returns at once: no pool-lock reacquire,
     * no sweep and no partition lock after the wake, so a worker descheduled right after its wake holds nothing
     * that the next submit needs. A submit that finds no waiter (the common busy case) does nothing here.
     */
    private void handoff(String pool, PoolShards ps, int shard) {
        if (ps.waiting.get() == 0) return;
        Waiter w;
        while ((w = ps.waiters.poll()) != null) {
            if (w.state.compareAndSet(Waiter.WAITING, Waiter.CLAIMING)) break;
        }
        if (w == null) return;
        List<Task> ts = List.of();
        try {
            var p = partition(pool, shard);
            w.handedAtMs = this.topics.clock().getAsLong();
            this.shardPolls.increment();
            var got = p.poll(WORKERS, w.worker, w.max, w.visibilityMs);
            if (!got.isEmpty()) {
                ps.taken(shard, got.size());
                ts = tasks(pool, shard, p, got);
            }
            if (!ts.isEmpty()) this.afterHandoffPoll.run();
        } catch (RuntimeException e) {
            // The submit itself succeeded; the waiter sweeps for the task (or a later claim takes it).
            this.handoffErrors.increment();
        } finally {
            w.handed = ts;
            w.state.set(Waiter.DONE);
            java.util.concurrent.locks.LockSupport.unpark(w.thread);
        }
        if (!ts.isEmpty()) this.handoffs.increment();
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
     * oldest unresolved task's enqueue time (0 if none). Exact, from each shard's published counts; unlike
     * {@link #readyHints}, which are cheap claim-path hints only and may be stale.
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
        int shard = shardOf(producerId, seq);
        var r = partition(pool, shard).produce(producerId, seq, task.encode());
        if (this.shards > 1) {
            var ps = pool(pool);
            ps.added(shard, 1); // a deduped resubmit over-counts by one: a hint, corrected on claim
            handoff(pool, ps, shard); // after the hint: a claim parking concurrently sees one or the other
        }
        return r;
    }

    /** Where {@link #submitStream} put a task: its shard topic and offset (the cancel key). */
    record Placed(String topic, long offset) { }

    /**
     * S2c PR 5: submits a streaming task like {@link #submit} (anonymous, no dedup) and returns where it landed, so
     * the HTTP leg can track its deadline and cancel it. Streaming must be on.
     */
    Placed submitStream(String pool, TaskEnvelope task) {
        requireStreams();
        int shard = shardOf(0, 0);
        var r = partition(pool, shard).produce(0, 0, task.encode());
        if (this.shards > 1) {
            var ps = pool(pool);
            ps.added(shard, 1);
            handoff(pool, ps, shard);
        }
        return new Placed(topic(pool, shard), r.offset());
    }

    /**
     * S2c PR 5: produces the opaque cancel record of the task at {@code target} on that task's own topic (an ordinary
     * produce, no new messaging entry type; §12 ruling). A claim that reads the record applies it to its node's
     * {@link CancelSet} (creating the entry if that node never saw the task) and acks it; it is never handed out as a task. Streaming must be on.
     */
    void produceCancel(Placed target, long deadlineMs, byte[] correlationId) {
        requireStreams();
        var p = this.topics.partition(target.topic());
        p.subscribe(WORKERS, true);
        p.produce(0, 0, CancelSet.record(target.offset(), deadlineMs, correlationId), Durability.ASYNC);
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
            var w = new Waiter(Thread.currentThread(), worker, max, visibilityMs);
            ps.waiting.incrementAndGet();
            ps.waiters.add(w);
            try {
                // Lost-wakeup check: a submit bumps the hint and then looks for waiters; we enqueue and then look
                // at the hints. Both sides are volatile, so at least one of them sees the other.
                if (ps.anyReady() && w.cancel()) {
                    ps.waiters.remove(w);
                    full = false;
                    continue;
                }
                long until = System.nanoTime() + Math.min(left, this.waitSliceNanos);
                while (true) {
                    int st = w.state.get();
                    if (st == Waiter.DONE || st == Waiter.CANCELLED) break;
                    if (st == Waiter.CLAIMING) { // a submit is polling for us right now: its unpark follows
                        java.util.concurrent.locks.LockSupport.parkNanos(this, 1_000_000);
                        continue;
                    }
                    long r = until - System.nanoTime();
                    if (r <= 0 || Thread.currentThread().isInterrupted()) {
                        if (w.cancel()) ps.waiters.remove(w);
                        continue; // CANCELLED, or a submit won the race: wait for its DONE
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(this, r);
                }
            } finally {
                ps.waiting.decrementAndGet();
            }
            if (w.state.get() == Waiter.DONE && !w.handed.isEmpty()) {
                List<Task> kept;
                try {
                    kept = refreshHanded(w);
                } catch (RuntimeException e) { // closing partition: return the handed leases as polled
                    kept = w.handed;
                }
                if (!kept.isEmpty()) return kept;
            }
            if (Thread.currentThread().isInterrupted()) return List.of();
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
                var st = this.streams; // S2c PR 5: with streaming off, never anything but bad-envelope
                if (st != null && CancelSet.isRecord(d.payload())) {
                    st.cancels().apply(p.topic(), CancelSet.recordTarget(d.payload()), CancelSet.recordDeadline(d.payload()));
                    p.ack(WORKERS, d.offset(), d.attempt());
                    continue;
                }
                p.reject(WORKERS, d.offset(), d.attempt(), "bad-envelope");
                continue;
            }
            if (env.expiredAt(now)) {
                p.reject(WORKERS, d.offset(), d.attempt(), "deadline");
                continue;
            }
            var st = this.streams;
            if (st != null && CancelSet.CANCELLED.equals(st.cancels().reason(p.topic(), d.offset(), now))) {
                p.reject(WORKERS, d.offset(), d.attempt(), "cancelled"); // redelivered after a cancel (§12 Q3)
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
     * reply is durable. Takes ownership of the {@code bodies} arrays (no copy). When a {@link ReplyRouter} is bound
     * and {@code replyTo} is local, each caller is completed as soon as its envelope is built (before the write),
     * so RTT does not wait on the ledger hand-off. A rare produce failure after that still fails the worker's
     * future; the caller already has the reply and dedups by task id on redelivery.
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
            var reply = t.envelope().replyOwned(contentType, bodies.get(i));
            deliverLocal(to, reply); // before produce: RTT must not wait on the ledger hand-off
            durable.add(this.topics.partition(to).produce(producerId, firstSeq + i,
                    reply.encode(), Durability.ASYNC).durable());
        }
        // Different reply topics are different logs, so wait for each topic's futures (normally one topic).
        return afterDurable(CompletableFuture.allOf(durable.toArray(CompletableFuture[]::new)), () -> ackAll(tasks)); // one batched ack per shard (a claim may span shards)
    }

    public CompletableFuture<LeaseResult> reply(Task t, long producerId, long seq, String contentType, byte[] body) {
        var to = t.envelope().replyTo();
        if (to.isEmpty()) {
            throw new IllegalArgumentException("task has no replyTo");
        }
        var reply = t.envelope().reply(contentType, body);
        deliverLocal(to, reply); // before produce: RTT must not wait on the ledger hand-off
        var r = this.topics.partition(to).produce(producerId, seq, reply.encode(), Durability.ASYNC);
        return afterDurable(r.durable(), () -> ack(t));
    }

    /**
     * S2c: writes one non-final chunk of {@code t}'s token stream (§5.2), fenced by attempt: a chunk from an attempt
     * older than one that already produced is {@code STALE_ATTEMPT} and not written. {@code c}'s stream id is the
     * task's correlation id. Chunks use the agent's own {@code (producerId, seq)}. Throws
     * {@link IllegalStateException} when streaming is off.
     */
    public LeaseResult streamChunk(Task t, long producerId, long seq, sjws_nettask.swarm.wire.StreamChunk c) {
        var s = requireStreams();
        if (c.last()) {
            throw new IllegalArgumentException("LAST goes through streamLast");
        }
        return s.admitAndWrite(t, producerId, seq, c).result();
    }

    /**
     * S2c: ends {@code t}'s stream (§4.4 order): writes {@code last} (a {@code LAST} chunk, ASYNC) with
     * {@code (producerId, seq)} and the reply with {@code (producerId, seq + 1)}, and acks the task (fenced) only
     * once both are durable. A stale attempt gets {@code STALE_ATTEMPT} and writes nothing. The reply uses
     * {@code seq + 1} on the reply topic: the caller's next produce must skip it (use {@code seq + 2}).
     */
    public CompletableFuture<LeaseResult> streamLast(Task t, long producerId, long seq,
            sjws_nettask.swarm.wire.StreamChunk last, String contentType, byte[] body) {
        var s = requireStreams();
        if (!last.last()) {
            throw new IllegalArgumentException("streamLast needs a LAST chunk");
        }
        var to = t.envelope().replyTo();
        if (to.isEmpty()) {
            throw new IllegalArgumentException("task has no replyTo");
        }
        var w = s.admitAndWrite(t, producerId, seq, last);
        if (w.result() != LeaseResult.OK) {
            return CompletableFuture.completedFuture(w.result());
        }
        var lastDurable = w.produced().durable();
        var reply = t.envelope().reply(contentType, body);
        deliverLocal(to, reply);
        var replyDurable = this.topics.partition(to).produce(producerId, seq + 1, reply.encode(), Durability.ASYNC).durable();
        return afterDurable(CompletableFuture.allOf(lastDurable, replyDurable), () -> {
            var a = ack(t);
            if (a == LeaseResult.OK) {
                s.settle(t); // tombstone: a zombie older attempt cannot write after LAST
            } else {
                s.retire(t); // this attempt lost its lease: refuse its later chunks
            }
            return a;
        }).whenComplete((a, e) -> {
            if (e != null) {
                s.lastFailed(e);
            }
        });
    }

    private TokenStreams requireStreams() {
        var s = this.streams;
        if (s == null) {
            throw new IllegalStateException("streaming is off (sjws.swarm.stream.enabled)");
        }
        return s;
    }

    /**
     * {@code ack} on the ack executor once {@code durable} completes: as {@code durable.thenApplyAsync(x -> ack.get(),
     * acker)} (failures arrive wrapped in a {@link java.util.concurrent.CompletionException}), except that the returned
     * future also fails, never hangs, when the service closes first (#181).
     */
    private <T> CompletableFuture<T> afterDurable(CompletableFuture<?> durable, java.util.function.Supplier<T> ack) {
        var out = new CompletableFuture<T>();
        durable.whenComplete((x, e) -> {
            if (e != null) {
                out.completeExceptionally(wrap(e));
                return;
            }
            try {
                this.acker.execute(new Ack<>(out, ack));
            } catch (java.util.concurrent.RejectedExecutionException r) {
                out.completeExceptionally(new java.util.concurrent.CompletionException(closedBeforeAck(r)));
            }
        });
        return out;
    }

    private static Throwable wrap(Throwable e) {
        return e instanceof java.util.concurrent.CompletionException ? e : new java.util.concurrent.CompletionException(e);
    }

    private static IllegalStateException closedBeforeAck(Throwable cause) {
        return new IllegalStateException("swarm task service closed before the ack ran; the task is redelivered", cause);
    }

    /** One queued ack: {@link #close} fails the ones it drops. */
    private record Ack<T>(CompletableFuture<T> out, java.util.function.Supplier<T> ack) implements Runnable {
        @Override
        public void run() {
            try {
                this.out.complete(this.ack.get());
            } catch (Throwable e) {
                this.out.completeExceptionally(wrap(e));
            }
        }
    }

    /**
     * Stops the ack executor (#181); idempotent, and safe while replies are in flight. From now on no ack starts: an
     * ack whose durability completes later fails its future, so the task is redelivered once its lease ends instead
     * of being acked late. Acks already queued drain for up to {@link #CLOSE_DRAIN_MS} (the partitions are still
     * open: {@link Swarm#close} closes them after this); past that the thread is interrupted and every ack still
     * queued fails. Returns once the thread has ended, or after at most twice the bound.
     */
    public void close() {
        this.acker.shutdown();
        try {
            if (this.acker.awaitTermination(CLOSE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            dropQueuedAcks();
            if (!this.acker.awaitTermination(CLOSE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
                System.err.println("WARN: swarm: " + ACKER_THREAD + " still running " + 2 * CLOSE_DRAIN_MS + " ms after close");
            }
        } catch (InterruptedException e) {
            dropQueuedAcks();
            Thread.currentThread().interrupt();
        }
    }

    /** Interrupts the ack thread and fails every ack still queued. */
    private void dropQueuedAcks() {
        for (var r : this.acker.shutdownNow()) {
            if (r instanceof Ack<?> a) {
                a.out().completeExceptionally(new java.util.concurrent.CompletionException(closedBeforeAck(null)));
            }
        }
    }

    boolean ackerTerminated() {
        return this.acker.isTerminated();
    }
}
