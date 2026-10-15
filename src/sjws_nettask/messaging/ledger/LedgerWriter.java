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
 * Single writer for one {@link SegmentedLog}. A dedicated platform thread drains a bounded queue
 * (backpressure) and writes each drained group with one positional write; every append is its own
 * batch. When a future completes depends on the append's effective {@link Durability} (per-append
 * override, else the writer default):
 * <ul>
 *   <li>NONE / ASYNC: right after the write (page cache). ASYNC is the default; a background syncer
 *       thread fsyncs off the ack path and advances {@link LedgerSnapshot#durableOffset}.</li>
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

    private record Pending(List<byte[]> records, long timestampMs, Durability mode, CompletableFuture<Long> done) { }

    private record Waiter(long endOffset, long base, CompletableFuture<Long> done) { }

    private static final Pending POISON = new Pending(List.of(), 0, null, null);

    private final SegmentedLog log;
    private final Durability durability;
    private final long lingerNanos;
    private final long fsyncIntervalNanos;
    private final int maxGroupBytes;
    private final BlockingQueue<Pending> queue;
    private final int capacity;
    /** Set by the writer after each drain when the backlog is at the soft capacity; one volatile read on the fast path. */
    private volatile boolean behind;
    /** True while the syncer sleeps out the fsync floor; it wakes on its own timer, so writes need not unpark it. */
    private volatile boolean flooring;
    private final AtomicReference<LedgerSnapshot> snapshot = new AtomicReference<>();
    private final ConcurrentLinkedQueue<Waiter> fsyncWaiters = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private final Thread syncer;
    private volatile boolean closed;
    private volatile boolean stopSyncer;
    private volatile Throwable failure;
    /** Offset after the last write; read by the syncer. */
    private volatile long written;
    private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
    private final ReentrantLock lock = new ReentrantLock();
    // Guarded by lock: counters shared by writer and syncer for snapshot publication.
    private long batches, records, bytes, fsyncs, seq, durable;

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
        this.log = log;
        this.durability = durability;
        this.lingerNanos = lingerNanos;
        this.fsyncIntervalNanos = fsyncIntervalNanos;
        this.maxGroupBytes = maxGroupBytes;
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity");
        this.capacity = queueCapacity;
        // Hard bound 2x the soft one: the slack absorbs appends made under a caller's lock (appendNoWait) by threads that
        // passed awaitCapacity concurrently. ArrayBlockingQueue keeps the one-lock, allocation-free hand-off on the
        // claim path (a LinkedBlockingQueue cost ~0.7 us of claim p50 at 16 workers).
        this.queue = new ArrayBlockingQueue<>((int) Math.min(Integer.MAX_VALUE - 8, 2L * queueCapacity));
        this.written = log.nextOffset();
        this.durable = log.nextOffset(); // recovered data is treated as durable (it survived a restart)
        publish();
        var name = log.dir().getFileName();
        this.syncer = Thread.ofPlatform().name("ledger-syncer-" + name).daemon().unstarted(this::syncLoop);
        this.thread = Thread.ofPlatform().name("ledger-writer-" + name).daemon().unstarted(this::run);
        syncer.start();
        thread.start();
    }

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
        if (!behind) return;
        while (behind && queue.size() >= capacity && !closed && failure == null) {
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
        var f = new CompletableFuture<Long>();
        var p = new Pending(List.copyOf(recs), System.currentTimeMillis(), mode == null ? durability : mode, f);
        if (!queue.offer(p)) {
            // Only past 2x capacity, i.e. the writer stalled while every caller skipped awaitCapacity: block rather than
            // drop or reorder. Not reachable through Partition in practice (callers wait at the soft bound).
            behind = true;
            try { queue.put(p); } catch (InterruptedException e) { Thread.currentThread().interrupt(); f.completeExceptionally(e); }
        }
        return f;
    }

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
                for (var p : g) {
                    long base = write(List.of(p), RecordBatch.encodedSize(p.records));
                    if (p.mode.waitsForFsync()) forceInline(); else publish();
                    p.done.complete(base);
                }
                return;
            }
            long base = write(g, total);
            boolean anyWaits = false;
            for (var p : g) anyWaits |= p.mode.waitsForFsync();
            if (durability == Durability.GROUP && anyWaits) forceInline(); else publish();
            long o = base;
            for (var p : g) {
                long end = o + p.records.size();
                if (!p.mode.waitsForFsync() || durableNow() >= end) p.done.complete(o);
                else fsyncWaiters.add(new Waiter(end, o, p.done));
                o = end;
            }
            // NONE/GROUP/EACH writers only need the syncer for fsync waiters (GROUP overrides); ASYNC unless it is already
            // sleeping out the fsync floor (an unpark per write would turn the floor into a wake-up per write).
            if ((durability == Durability.ASYNC && !flooring) || !fsyncWaiters.isEmpty()) LockSupport.unpark(syncer);
        } catch (IOException | RuntimeException e) {
            // The in-memory tail may disagree with disk: fence the writer; reopening recovers.
            failure = e;
            g.forEach(p -> p.done.completeExceptionally(e));
            failWaiters(e);
        }
    }

    private long durableNow() { lock.lock(); try { return durable; } finally { lock.unlock(); } }

    private void forceInline() throws IOException {
        log.force();
        lock.lock();
        try { fsyncs++; durable = written; } finally { lock.unlock(); }
        publish();
    }

    private long write(List<Pending> g, int total) throws IOException {
        if (buf.capacity() < total) buf = ByteBuffer.allocateDirect(Integer.highestOneBit(total) << 1);
        buf.clear();
        long base = log.nextOffset(), o = base;
        var offs = new long[g.size()];
        var lens = new int[g.size()];
        int recs = 0;
        for (int k = 0; k < g.size(); k++) {
            var p = g.get(k);
            int start = buf.position();
            RecordBatch.encode(o, p.timestampMs, p.records, buf);
            offs[k] = o;
            lens[k] = buf.position() - start;
            o += p.records.size();
            recs += p.records.size();
        }
        buf.flip();
        int n = buf.remaining();
        log.appendFrames(buf, offs, lens, g.size(), o);
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
            boolean dirty = target > durableNow();
            boolean wanted = dirty && (durability == Durability.ASYNC || !fsyncWaiters.isEmpty());
            if (!wanted) {
                if (stopSyncer) return;
                // Event-driven: the writer unparks this thread after every write (and close() does too), and an
                // unpark that comes first leaves a permit, so nothing is missed. An idle partition (a DLQ, say) no
                // longer wakes 1000x/s; the long timeout is only a backstop.
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
            try {
                log.force();
            } catch (IOException | RuntimeException e) {
                failure = e;
                failWaiters(e);
                return;
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
        if (closed) return;
        closed = true;
        queue.put(POISON);
        thread.join(TimeUnit.SECONDS.toMillis(30));
        stopSyncer = true;
        LockSupport.unpark(syncer);
        syncer.join(TimeUnit.SECONDS.toMillis(30));
        if (failure == null && written > durableNow()) {
            try { forceInline(); } catch (IOException e) { failure = e; }
        }
        failWaiters(new IllegalStateException("closed"));
    }
}
