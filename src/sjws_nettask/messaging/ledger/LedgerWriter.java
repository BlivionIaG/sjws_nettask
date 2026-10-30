package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single writer for one {@link SegmentedLog}. Appends go through a bounded queue (backpressure); a
 * dedicated writer thread drains groups with one positional write each ({@code take} wake). The queue
 * and the 1 MiB direct write buffer are allocated on the first append (#333), so an idle partition
 * pins only the writer object and its thread bookkeeping; once materialized {@code ready} publishes a plain queue and the hand-off stays an
 * {@link ArrayBlockingQueue} with no volatile queue load (same produce path as before). Construction and materialization charge
 * {@link LedgerWriterBudget}. By default the background syncer is shared via {@link SharedLedgerPool}
 * (CPU-sized); set {@link SharedLedgerPool#PROPERTY} to {@code 0} for a dedicated syncer per writer.
 * When a future completes depends on the append's effective {@link Durability} (per-append override,
 * else the writer default):
 * <ul>
 *   <li>NONE / ASYNC: right after the write (page cache). ASYNC is the default; a background syncer
 *       fsyncs off the ack path only for appends whose effective mode is {@link Durability#ASYNC}
 *       (or stronger waiters), advancing {@link LedgerSnapshot#durableOffset}. NONE appends do not
 *       schedule a background force; an ASYNC force still covers all earlier bytes in the file.</li>
 *   <li>GROUP: after the fsync covering it. Inline (one fsync per group) when GROUP is the writer
 *       default, otherwise by the syncer.</li>
 *   <li>EACH (writer default): one fsync per batch.</li>
 * </ul>
 * After every commit, and after every background fsync, an immutable {@link LedgerSnapshot} is
 * published through an atomic reference.
 */
public final class LedgerWriter implements PartitionAppender, AutoCloseable {

    public static final int DEFAULT_QUEUE = 65_536;
    public static final Durability DEFAULT_DURABILITY = Durability.ASYNC;
    /**
     * Default floor between background ASYNC fsyncs. 0 used to mean back to back, which on a busy topic is a
     * continuous fsync loop (up to 13% system CPU in the S1c request/reply run) for no ack-latency benefit: ASYNC acks
     * after write(2). 1 ms coalesces those into at most 1000 fsyncs/s and adds at most 1 ms to the power-loss window.
     */
    public static final long DEFAULT_FSYNC_INTERVAL_NANOS = 1_000_000L;

    /** {@link #sharedSyncSlice}: no background work. */
    static final int SYNC_IDLE = 0;
    /** {@link #sharedSyncSlice}: forced (or more work remains). */
    static final int SYNC_BUSY = 1;
    /** {@link #sharedSyncSlice}: due but inside the per-member floor; shard must wait (do not park here). */
    static final int SYNC_FLOORED = 2;

    private record Pending(List<byte[]> records, long timestampMs, Durability mode, CompletableFuture<Long> done) { }

    private record Waiter(long endOffset, long base, CompletableFuture<Long> done) { }

    private static final Pending POISON = new Pending(List.of(), 0, null, null);

    private final SegmentedLog log;
    private final Durability durability;
    private final long lingerNanos;
    private final long fsyncIntervalNanos;
    private final int maxGroupBytes;
    /**
     * Soft-bound queue; null until the first append materializes it (#333). Plain after {@link #ready}:
     * the volatile ready store publishes this write so the append hot path needs no volatile queue load.
     */
    private BlockingQueue<Pending> queue;
    /**
     * True once {@link #queue} and the writer thread are live. Acquire-load on every append; after the first
     * materialize the branch is predicted taken and the path matches a final-queue writer (no budget, no lock).
     */
    private volatile boolean ready;
    private final int capacity;
    /** Set by the writer after each drain when the backlog is at the soft capacity; one volatile read on the fast path. */
    private volatile boolean behind;
    /** True while the syncer sleeps out the fsync floor; it wakes on its own timer, so writes need not unpark it. */
    private volatile boolean flooring;
    private final AtomicReference<LedgerSnapshot> snapshot = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Waiter> fsyncWaiters = new ConcurrentLinkedQueue<>();
    /** Dedicated writer thread; started on first materialize, not in the constructor. */
    private final Thread thread;
    private final ReentrantLock materializeLock = new ReentrantLock();
    /**
     * Test-only (per writer): when non-null, thrown after acquireMaterialize and before the queue is published.
     * Instance field so faults cannot leak across writers or tests (#335 N3).
     */
    volatile RuntimeException materializeFaultForTests;
    /**
     * Test-only (per writer): when non-null, thrown after acquireExtra and before {@code allocateDirect}
     * so N2 can prove the extra charge rolls back (#335 N2/N3).
     */
    volatile RuntimeException allocateDirectFaultForTests;
    /** Dedicated syncer, or null when using {@link SharedLedgerPool}. */
    private final Thread syncer;
    /** Syncer shard index, or -1 when this writer has a dedicated syncer. */
    private final int sharedShard;
    private final SharedLedgerPool sharedPool;
    private final LedgerWriterBudget budget;
    /** Bytes charged on this writer (idle + optional materialize + direct growth); released in {@link #close}. */
    private long budgetCharged;
    /** Direct-buffer bytes already covered by {@link #budgetCharged} (materialize includes {@link LedgerWriterBudget#DIRECT_BUF_BYTES}). */
    private int directAccounted;
    private volatile boolean closed;
    private volatile boolean stopSyncer;
    /** True while {@link #pauseSync} holds the syncer off {@code log.force} (snapshot install). */
    private volatile boolean syncPaused;
    /** Non-zero while a syncer thread is inside {@code log.force()}; install waits for zero. */
    private final java.util.concurrent.atomic.AtomicInteger syncInForce = new java.util.concurrent.atomic.AtomicInteger();
    private volatile Throwable failure;
    /** Offset after the last write; read by the syncer. */
    private volatile long written;
    /** Direct write buffer; null until the first group write (#333). */
    private ByteBuffer buf;
    private final ReentrantLock lock = new ReentrantLock();
    // Guarded by lock: counters shared by writer and syncer for snapshot publication.
    private long batches, records, bytes, fsyncs, seq;
    /**
     * Durable offset (every record below this has been fsynced). Volatile so {@link #sharedSyncSlice} can read it without {@link #lock} (#329); updates still happen under
     * the lock together with the counters that {@link #publish} snapshots.
     *
     * <p><b>Publish → durable happens-before.</b> After recovery, the only stores to this field are in
     * {@link #forceInline}, {@link #sharedSyncSlice}, and the dedicated {@code syncLoop}. Each path:
     * (1) captures {@code watermark}/{@code target = written} (the prefix about to be synced);
     * (2) returns from {@code log.force()} so that prefix is on stable storage;
     * (3) stores {@code durable = watermark} (this volatile write);
     * (4) calls {@link #publish()} and completes any {@code fsyncWaiters} with {@code endOffset <= watermark}
     *     (GROUP/EACH acks). Program order on the syncer/force thread is therefore
     *     {@code data written ⊇ fsynced ⊇ durable store ⊇ ack completion}. A volatile read of
     *     {@code durable} (via {@link #durableNow} or {@link LedgerSnapshot#durableOffset} after publish)
     *     synchronizes-with that store, so an observer that sees {@code durable >= N} knows records
     *     {@code [start, N)} were fsynced before the observation, and no fsync-waiting ack for those
     *     records completed before the store either. {@link #afterLogForce} runs between (2) and (3) on {@link #forceInline} and {@link #sharedSyncSlice}
     *     so tests can prove (3) never covers records appended after the force returned.
     */
    private volatile long durable;
    /**
     * Exclusive high-water of offsets that need a background force because an {@link Durability#ASYNC}
     * append committed (or a syncer-served GROUP/EACH waiter is pending via {@link #fsyncWaiters}).
     * NONE appends leave this unchanged so they never schedule a syncer force. A force still uses
     * {@code written} as the watermark, so earlier NONE bytes in the file are covered when ASYNC is due.
     */
    private volatile long asyncForceThrough;

    /** ASYNC writer with defaults. */
    public LedgerWriter(SegmentedLog log) { this(log, DEFAULT_DURABILITY); }

    public LedgerWriter(SegmentedLog log, Durability durability) { this(log, durability, 0, DEFAULT_QUEUE, 4 << 20, DEFAULT_FSYNC_INTERVAL_NANOS); }

    public LedgerWriter(SegmentedLog log, Durability durability, long lingerNanos, int queueCapacity, int maxGroupBytes) {
        this(log, durability, lingerNanos, queueCapacity, maxGroupBytes, DEFAULT_FSYNC_INTERVAL_NANOS);
    }

    /**
     * @param lingerNanos        extra wait to grow a group after the first pending append (0 = none)
     * @param queueCapacity      soft hand-off bound (backpressure in {@link #append} / {@link #awaitCapacity}); the
     *                           queue holds 2x this, so {@link #appendNoWait} after awaitCapacity does not block
     * @param maxGroupBytes      soft cap on bytes written per group
     * @param fsyncIntervalNanos minimum spacing of background fsyncs in ASYNC mode (0 = back to back);
     *                           bounds the power-loss window together with one fsync duration
     */
    public LedgerWriter(SegmentedLog log, Durability durability, long lingerNanos, int queueCapacity, int maxGroupBytes,
            long fsyncIntervalNanos) {
        this(log, durability, lingerNanos, queueCapacity, maxGroupBytes, fsyncIntervalNanos, SharedLedgerPool.sharedOrNull());
    }

    /**
     * Package/test constructor: register on {@code pool} when non-null (dedicated syncer when null).
     * Production callers use {@link #LedgerWriter(SegmentedLog, Durability, long, int, int, long)}.
     */
    LedgerWriter(SegmentedLog log, Durability durability, long lingerNanos, int queueCapacity, int maxGroupBytes,
            long fsyncIntervalNanos, SharedLedgerPool pool) {
        this.log = log;
        this.durability = durability;
        this.lingerNanos = lingerNanos;
        this.fsyncIntervalNanos = fsyncIntervalNanos;
        this.maxGroupBytes = maxGroupBytes;
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity");
        this.capacity = queueCapacity;
        // Queue and direct buffer are allocated on first append (#333): idle writers pin KBs, not ~1.5 MiB.
        // Hard bound stays 2x the soft one once materialized (appendNoWait slack under a caller's lock).
        this.budget = LedgerWriterBudget.shared();
        this.budgetCharged = this.budget.acquireIdle();
        try {
            var ctorFault = LedgerWriterFaults.CTOR.get();
            if (ctorFault != null) throw ctorFault;
            this.written = log.nextOffset();
            this.durable = log.nextOffset(); // recovered data is treated as durable (it survived a restart)
            this.asyncForceThrough = this.durable;
            publish();
            var name = log.dir().getFileName();
            this.thread = Thread.ofPlatform().name("ledger-writer-" + name).daemon().unstarted(this::run);
            if (pool != null) {
                this.sharedPool = pool;
                this.syncer = null;
                this.sharedShard = pool.register(this);
            } else {
                this.sharedPool = null;
                this.sharedShard = -1;
                this.syncer = Thread.ofPlatform().name("ledger-syncer-" + name).daemon().unstarted(this::syncLoop);
                syncer.start();
            }
            // Writer thread starts in materialize() with the queue.
        } catch (Throwable t) {
            long c = this.budgetCharged;
            this.budgetCharged = 0;
            this.budget.release(c);
            throw t;
        }
    }

    /**
     * #270: after {@link SegmentedLog#replaceWith} (no append in flight): the log end and durable offset restart at the
     * replaced log's end, which is durable (the caller fsynced every staged file before the swap).
     */
    public void reload() {
        if (ready && !queue.isEmpty()) throw new IllegalStateException("reload with appends queued");
        lock.lock();
        try {
            written = log.nextOffset();
            durable = written;
            asyncForceThrough = written;
        } finally {
            lock.unlock();
        }
        publish();
    }

    /**
     * Stops background {@code log.force()} until {@link #resumeSync()}. Call only with no append in flight
     * (after draining the last local future). Dedicated and shared syncers both honor this; snapshot install
     * uses it around {@link SegmentedLog#replaceWith} so a ClosedChannelException from closing segment
     * channels cannot permanently fail the writer (#288 Codex P1).
     */
    public void pauseSync() {
        syncPaused = true;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (syncInForce.get() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
        }
    }

    /** Allows background syncing again after {@link #pauseSync}. */
    public void resumeSync() {
        syncPaused = false;
        if (sharedPool != null) sharedPool.unparkSyncer(sharedShard);
        else if (syncer != null) java.util.concurrent.locks.LockSupport.unpark(syncer);
    }

    /** The log this writer appends to (#270: the snapshot export and install read and replace its segments). */
    public SegmentedLog log() { return log; }

    /** The latest published state; one volatile read. */
    public LedgerSnapshot snapshot() { return snapshot.get(); }

    public Durability durability() { return durability; }

    /** Appends with the writer's default durability. */
    public CompletableFuture<Long> append(List<byte[]> recs) throws InterruptedException { return append(recs, null); }

    /**
     * Enqueues one batch; completes with its base offset once durable per {@code mode} (null = writer
     * default). Blocks while the queue holds {@code queueCapacity} appends (see {@link #awaitCapacity}).
     */
    @Override
    public CompletableFuture<Long> append(List<byte[]> recs, Durability mode) throws InterruptedException {
        awaitCapacity();
        return appendNoWait(recs, mode);
    }

    /**
     * Blocks while the queue holds {@code queueCapacity} or more appends (writer behind). Callers that must append
     * under their own lock (Partition) call this <em>before</em> taking it and then use {@link #appendNoWait}, so a
     * slow writer throttles producers without ever stalling a lock holder. The bound is soft: it can be overshot by
     * the appends of threads that passed this check concurrently.
     */
    @Override
    public void awaitCapacity() throws InterruptedException {
        if (!behind || !ready) return;
        var q = queue;
        while (behind && q.size() >= capacity && !closed && failure == null) {
            if (Thread.interrupted()) throw new InterruptedException();
            LockSupport.parkNanos(this, 50_000);
        }
    }

    /** As {@link #append} but never blocks: no capacity wait (see {@link #awaitCapacity}). Safe under a caller's lock. */
    @Override
    public CompletableFuture<Long> appendNoWait(List<byte[]> recs, Durability mode) {
        if (recs.isEmpty()) throw new IllegalArgumentException("empty batch");
        RecordBatch.encodedSize(recs); // validate size eagerly
        if (closed || failure != null) return CompletableFuture.failedFuture(failure != null ? failure : new IllegalStateException("closed"));
        if (!ready) materialize(); // one-time; may throw LedgerBudgetExhaustedException
        var q = queue; // plain after ready (volatile ready acquire published the write)
        var f = new CompletableFuture<Long>();
        var p = new Pending(List.copyOf(recs), System.currentTimeMillis(), mode == null ? durability : mode, f);
        if (!q.offer(p)) {
            // Only past 2x capacity, i.e. the writer stalled while every caller skipped awaitCapacity: block rather than
            // drop or reorder. Not reachable through Partition in practice (callers wait at the soft bound).
            behind = true;
            try { q.put(p); } catch (InterruptedException e) { Thread.currentThread().interrupt(); f.completeExceptionally(e); }
        }
        return f;
    }

    /**
     * Ensures the bounded queue and writer thread exist. Idle writers skip this. After the first call, {@link #ready}
     * is true and {@link #appendNoWait} uses a plain queue read (same ArrayBlockingQueue hand-off as a final field).
     */
    private void materialize() {
        if (ready) return;
        materializeLock.lock();
        try {
            if (ready) return;
            // Under the same lock close() uses: refuse after close so we never start a thread or take a charge.
            if (closed) throw new IllegalStateException("closed");
            long extra = budget.acquireMaterialize(capacity);
            try {
                var fault = materializeFaultForTests;
                if (fault != null) throw fault;
                // Hard bound 2x the soft one: ArrayBlockingQueue keeps the one-lock, allocation-free hand-off
                // (a LinkedBlockingQueue cost ~0.7 us of claim p50 at 16 workers).
                var q = new ArrayBlockingQueue<Pending>((int) Math.min(Integer.MAX_VALUE - 8, 2L * capacity));
                queue = q;
                thread.start();
                budgetCharged += extra;
                directAccounted = LedgerWriterBudget.DIRECT_BUF_BYTES; // materialize cost covers the 1 MiB baseline
                ready = true; // publish queue + started thread to appenders
            } catch (Throwable t) {
                budget.releaseBytes(extra);
                queue = null;
                throw t;
            }
        } finally {
            materializeLock.unlock();
        }
    }

    /** Test/ops: whether the queue and writer thread have been allocated. */
    public boolean materialized() {
        return ready;
    }

    private void unparkSyncer() {
        if (sharedPool != null) sharedPool.unparkSyncer(sharedShard);
        else LockSupport.unpark(syncer);
    }

    /**
     * One shared-syncer slice. Does <em>not</em> park for the fsync floor: a floored member returns
     * {@link #SYNC_FLOORED} so the shard can force siblings and sleep once at shard level.
     * When {@link #stopSyncer} the floor is ignored so {@link #close} can drain.
     */
    int sharedSyncSlice() {
        if (syncPaused) return SYNC_IDLE;
        long d0 = durable;
        if (stopSyncer && (!ready || queue.isEmpty()) && fsyncWaiters.isEmpty() && asyncForceThrough <= d0 && written <= d0) {
            return SYNC_IDLE;
        }
        Waiter w0;
        while ((w0 = fsyncWaiters.peek()) != null && w0.endOffset <= d0) { fsyncWaiters.poll(); w0.done.complete(w0.base); }
        long target = written;
        boolean wanted = asyncForceThrough > durable || !fsyncWaiters.isEmpty();
        if (!wanted) {
            flooring = false;
            return SYNC_IDLE;
        }
        // Per-member floor without parking the shard (HOL fix). Mutation: parkNanos here again makes
        // SyncerDirtinessTest.shardFloorDoesNotBlockSiblingDueForce fail.
        long wait = lastSharedFsync + fsyncIntervalNanos - System.nanoTime();
        if (wait > 0 && fsyncWaiters.isEmpty() && !stopSyncer) {
            flooring = true; // writers skip unpark storms while we are inside the floor window
            return SYNC_FLOORED;
        }
        flooring = false;
        if (syncPaused) return SYNC_IDLE;
        syncInForce.incrementAndGet();
        try {
            if (syncPaused) return SYNC_IDLE;
            try {
                log.force();
            } catch (IOException | RuntimeException e) {
                if (syncPaused) return SYNC_IDLE; // replaceWith closed channels under pause; not a writer failure
                failure = e;
                failWaiters(e);
                return SYNC_IDLE;
            }
        } finally {
            syncInForce.decrementAndGet();
        }
        var hook = afterLogForce;
        if (hook != null) hook.run();
        lastSharedFsync = System.nanoTime();
        lock.lock();
        try { fsyncs++; if (target > durable) durable = target; } finally { lock.unlock(); }
        publish();
        Waiter w;
        while ((w = fsyncWaiters.peek()) != null && w.endOffset <= target) { fsyncWaiters.poll(); w.done.complete(w.base); }
        return asyncForceThrough > durable || !fsyncWaiters.isEmpty() ? SYNC_BUSY : SYNC_IDLE;
    }

    /** Remaining floor nanos after {@link #SYNC_FLOORED}; 0 when eligible now. */
    long sharedSyncFloorWaitNanos() {
        long wait = lastSharedFsync + fsyncIntervalNanos - System.nanoTime();
        return Math.max(0L, wait);
    }

    /** Last fsync time for {@link #sharedSyncSlice} (dedicated syncLoop keeps its own local). */
    private volatile long lastSharedFsync;

    /** Test hook: exclusive high-water awaiting a background ASYNC force. */
    long asyncForceThroughForTests() { return asyncForceThrough; }

    private void run() {
        var group = new ArrayList<Pending>();
        boolean stop = false;
        while (!stop) {
            try {
                group.add(queue.take());
                if (lingerNanos > 0) {
                    long deadline = System.nanoTime() + lingerNanos;
                    while (System.nanoTime() < deadline && queue.size() < 1024) LockSupport.parkNanos(Math.min(20_000, lingerNanos));
                }
                queue.drainTo(group);
                behind = queue.size() >= capacity;
            } catch (InterruptedException e) {
                stop = true;
            }
            int poison = indexOfPoison(group);
            if (poison >= 0) { stop = true; group.subList(poison, group.size()).clear(); }
            if (!group.isEmpty()) commit(group);
            group.clear();
        }
        Pending p;
        while ((p = queue.poll()) != null) if (p.done != null) p.done.completeExceptionally(new IllegalStateException("closed"));
    }

    private static int indexOfPoison(List<Pending> g) {
        for (int i = 0; i < g.size(); i++) if (g.get(i) == POISON) return i;
        return -1;
    }

    private void commit(List<Pending> group) {
        int i = 0;
        while (i < group.size()) {
            int j = i, total = 0;
            while (j < group.size()) {
                int sz = RecordBatch.encodedSize(group.get(j).records);
                if (j > i && total + sz > maxGroupBytes) break;
                total += sz;
                j++;
            }
            writeGroup(group.subList(i, j), total);
            i = j;
        }
    }

    private void writeGroup(List<Pending> g, int total) {
        if (failure != null) { g.forEach(p -> p.done.completeExceptionally(failure)); return; }
        try {
            if (durability == Durability.EACH) {
                boolean anyAsync = false;
                for (var p : g) {
                    long base = write(List.of(p), RecordBatch.encodedSize(p.records));
                    if (p.mode.waitsForFsync()) forceInline(); else publish();
                    if (p.mode == Durability.ASYNC) {
                        anyAsync = true;
                        noteAsyncForceThrough(written);
                    }
                    p.done.complete(base);
                }
                // Mutation: unpark on every EACH group (including NONE-only) makes noneOnlyAppendsIssueNoBackgroundFsyncs fail.
                if ((anyAsync && !flooring) || !fsyncWaiters.isEmpty()) unparkSyncer();
                return;
            }
            long base = write(g, total);
            boolean anyWaits = false;
            boolean anyAsync = false;
            for (var p : g) {
                anyWaits |= p.mode.waitsForFsync();
                anyAsync |= p.mode == Durability.ASYNC;
            }
            if (durability == Durability.GROUP && anyWaits) forceInline(); else publish();
            if (anyAsync) noteAsyncForceThrough(written);
            long o = base;
            for (var p : g) {
                long end = o + p.records.size();
                if (!p.mode.waitsForFsync() || durableNow() >= end) p.done.complete(o);
                else fsyncWaiters.add(new Waiter(end, o, p.done));
                o = end;
            }
            // Schedule the syncer only for ASYNC dirtiness or fsync waiters — never for NONE-only groups.
            // Skip unpark while flooring so the floor is not turned into a wake-up per write.
            // Mutation: unpark when durability==ASYNC regardless of append mode makes noneOnlyAppendsIssueNoBackgroundFsyncs fail.
            if ((anyAsync && !flooring) || !fsyncWaiters.isEmpty()) unparkSyncer();
        } catch (LedgerBudgetExhaustedException e) {
            // Disk was never touched (acquireExtra / growth refusal before allocateDirect+appendFrames). Fail only
            // this batch — same as a materialize refusal on the caller thread — and do not fence the writer so a
            // later small append can still succeed (#335 Security B1). Mutation: setting failure=e here again makes
            // growthRefusalDoesNotFenceWriter fail.
            g.forEach(p -> p.done.completeExceptionally(e));
        } catch (IOException | RuntimeException e) {
            // The in-memory tail may disagree with disk: fence the writer; reopening recovers. This group's writes
            // and the waiters are failed before the fence is raised, so an append refused by the fence cannot be
            // reported ahead of them. That covers only this failing group, not the other fencing paths or writes
            // still queued behind it; the forwarding sender's rule (#251 B2: a refusal of seq N makes every earlier
            // pending seq outcome-unknown) is the guarantee, this ordering only narrows the window.
            g.forEach(p -> p.done.completeExceptionally(e));
            failWaiters(e);
            failure = e;
        }
    }

    /** Volatile read of {@link #durable}; kept as a method so call sites stay obvious in JFR. */
    private long durableNow() { return durable; }

    /** Publishes that an ASYNC append needs a background force through {@code through} (exclusive). */
    private void noteAsyncForceThrough(long through) {
        if (through > asyncForceThrough) asyncForceThrough = through;
    }

    /**
     * Test-only: runs after {@code log.force()} returns and before durable is published. Lets a test append
     * between the fsync and the watermark publish to prove the durable offset never covers unsynced records
     * (#288 Codex P1).
     */
    volatile Runnable afterLogForce;

    /**
     * Fsyncs the log and advances {@link LedgerSnapshot#durableOffset} to the watermark captured <em>before</em>
     * that fsync (#270 / #288 MC-N1, Codex P1). Prefer this over {@link SegmentedLog#force} when a snapshot must
     * observe the durable point: reading {@code written} after {@code log.force()} returns can include records the
     * writer appended in between, which were never part of that fsync.
     */
    public void force() throws IOException {
        forceInline();
    }

    private void forceInline() throws IOException {
        long watermark = written; // capture before fsync: only this prefix is covered
        log.force();
        var hook = afterLogForce;
        if (hook != null) hook.run();
        lock.lock();
        try {
            fsyncs++;
            if (watermark > durable) durable = watermark;
        } finally {
            lock.unlock();
        }
        publish();
    }

    private long write(List<Pending> g, int total) throws IOException {
        var b = buf;
        if (b == null || b.capacity() < total) {
            int cap = b == null ? Math.max(LedgerWriterBudget.DIRECT_BUF_BYTES, Integer.highestOneBit(Math.max(1, total)) << 1)
                    : Integer.highestOneBit(total) << 1;
            // Materialize already charged DIRECT_BUF_BYTES; any larger (or grown) buffer must acquire the delta (#335 B3).
            long need = (long) cap - directAccounted;
            int prevAccounted = directAccounted;
            long got = 0;
            if (need > 0) {
                got = budget.acquireExtra(need, "LedgerWriter direct buffer growth");
                budgetCharged += got;
                directAccounted = cap;
            }
            try {
                var fault = allocateDirectFaultForTests;
                if (fault != null) throw fault;
                b = ByteBuffer.allocateDirect(cap);
            } catch (Throwable t) {
                // #335 N2: release the extra charge if allocateDirect (or a test fault) fails after acquireExtra.
                if (got > 0) {
                    budget.releaseBytes(got);
                    budgetCharged -= got;
                    directAccounted = prevAccounted;
                }
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error err) throw err;
                throw new IOException(t);
            }
            buf = b;
        }
        b.clear();
        long base = log.nextOffset(), o = base;
        var offs = new long[g.size()];
        var lens = new int[g.size()];
        int recs = 0;
        for (int k = 0; k < g.size(); k++) {
            var p = g.get(k);
            int start = b.position();
            RecordBatch.encode(o, p.timestampMs, p.records, b);
            offs[k] = o;
            lens[k] = b.position() - start;
            o += p.records.size();
            recs += p.records.size();
        }
        b.flip();
        int n = b.remaining();
        log.appendFrames(b, offs, lens, g.size(), o);
        lock.lock();
        try { records += recs; bytes += n; batches += g.size(); } finally { lock.unlock(); }
        written = o;
        return base;
    }

    /** Background fsync: ASYNC writers keep durableOffset close to nextOffset; any writer serves GROUP overrides. */
    /** Backstop for the syncer's idle park; every real wake-up is an explicit unpark. */
    static final long IDLE_PARK_NANOS = 100_000_000L;

    private void syncLoop() {
        long lastFsync = 0;
        while (true) {
            // A waiter can be queued after the fsync that already covers it (writer checked durable just
            // before this thread advanced it): complete those here, or they would wait forever.
            long d0 = durableNow();
            Waiter w0;
            while ((w0 = fsyncWaiters.peek()) != null && w0.endOffset <= d0) { fsyncWaiters.poll(); w0.done.complete(w0.base); }
            long target = written;
            boolean wanted = asyncForceThrough > durableNow() || !fsyncWaiters.isEmpty();
            if (syncPaused) {
                if (stopSyncer) return;
                LockSupport.parkNanos(this, 50_000);
                continue;
            }
            if (!wanted) {
                if (stopSyncer) return;
                // Event-driven: the writer unparks this thread after ASYNC writes / waiters (and close() does too).
                // NONE-only appends never wake the syncer. An idle partition no longer wakes 1000x/s.
                LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                continue;
            }
            long wait = lastFsync + fsyncIntervalNanos - System.nanoTime();
            if (wait > 0 && fsyncWaiters.isEmpty() && !stopSyncer) {
                flooring = true;
                LockSupport.parkNanos(wait);
                flooring = false;
                continue;
            }
            if (syncPaused) continue;
            syncInForce.incrementAndGet();
            try {
                if (syncPaused) continue;
                try {
                    log.force();
                } catch (IOException | RuntimeException e) {
                    if (syncPaused) continue; // replaceWith closed channels under pause; not a writer failure
                    failure = e;
                    failWaiters(e);
                    return;
                }
            } finally {
                syncInForce.decrementAndGet();
            }
            lastFsync = System.nanoTime();
            lock.lock();
            try { fsyncs++; if (target > durable) durable = target; } finally { lock.unlock(); }
            publish();
            Waiter w;
            while ((w = fsyncWaiters.peek()) != null && w.endOffset <= target) { fsyncWaiters.poll(); w.done.complete(w.base); }
        }
    }

    private void failWaiters(Throwable e) {
        Waiter w;
        while ((w = fsyncWaiters.poll()) != null) w.done.completeExceptionally(e);
    }

    private void publish() {
        lock.lock();
        try {
        snapshot.set(new LedgerSnapshot(log.nextOffset(), Math.min(durable, written), log.startOffset(), batches, records,
                bytes, fsyncs, log.segmentCount(), durability, seq++, System.nanoTime()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops after committing everything already queued, then fsyncs whatever ASYNC/NONE left unsynced
     * (a clean shutdown loses nothing). Does not close the log.
     */
    @Override public void close() throws InterruptedException {
        // Set closed and snapshot the queue under materializeLock so a concurrent first-append
        // materialize cannot start the writer thread (and charge the budget) after we skip poison.
        BlockingQueue<Pending> toPoison = null;
        materializeLock.lock();
        try {
            if (closed) return;
            closed = true;
            if (ready) toPoison = queue;
        } finally {
            materializeLock.unlock();
        }
        if (toPoison != null) {
            toPoison.put(POISON);
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }
        stopSyncer = true;
        syncPaused = false; // close must drain even if pauseSync was left on
        if (sharedPool != null) {
            sharedPool.unregister(this, sharedShard);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && (written > durableNow() || !fsyncWaiters.isEmpty())) {
                // stopSyncer bypasses the floor inside sharedSyncSlice so close can drain.
                if (sharedSyncSlice() == SYNC_IDLE) break;
            }
        } else if (syncer != null) {
            LockSupport.unpark(syncer);
            syncer.join(TimeUnit.SECONDS.toMillis(30));
        }
        if (failure == null && written > durableNow()) {
            try { forceInline(); } catch (IOException e) { failure = e; }
        }
        failWaiters(new IllegalStateException("closed"));
        long charged = budgetCharged;
        budgetCharged = 0;
        budget.release(charged);
    }
}
