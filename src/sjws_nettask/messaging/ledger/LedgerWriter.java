package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Single writer for one {@link SegmentedLog}: a dedicated platform thread drains a bounded queue,
 * encodes every pending append as one batch, writes the whole group with one positional write and,
 * depending on {@link Durability}, one fsync (GROUP), one per batch (EACH) or none (NONE). Futures
 * complete with each batch's base offset only after that; then an immutable {@link LedgerSnapshot}
 * is published. A full queue blocks {@link #append} (backpressure).
 */
public final class LedgerWriter implements AutoCloseable {

    public static final int DEFAULT_QUEUE = 65_536;

    private record Pending(List<byte[]> records, long timestampMs, CompletableFuture<Long> done) { }

    private static final Pending POISON = new Pending(List.of(), 0, null);

    private final SegmentedLog log;
    private final Durability durability;
    private final long lingerNanos;
    private final int maxGroupBytes;
    private final BlockingQueue<Pending> queue;
    private final AtomicReference<LedgerSnapshot> snapshot = new AtomicReference<>();
    private final Thread thread;
    private volatile boolean closed;
    private volatile Throwable failure;
    private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
    private long batches, records, bytes, fsyncs, seq;

    public LedgerWriter(SegmentedLog log, Durability durability) { this(log, durability, 0, DEFAULT_QUEUE, 4 << 20); }

    /**
     * @param lingerNanos   extra wait to grow a group after the first pending append (0 = none)
     * @param queueCapacity bounded hand-off queue (backpressure)
     * @param maxGroupBytes soft cap on bytes written per group
     */
    public LedgerWriter(SegmentedLog log, Durability durability, long lingerNanos, int queueCapacity, int maxGroupBytes) {
        this.log = log;
        this.durability = durability;
        this.lingerNanos = lingerNanos;
        this.maxGroupBytes = maxGroupBytes;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        publish();
        this.thread = Thread.ofPlatform().name("ledger-writer-" + log.dir().getFileName()).daemon().unstarted(this::run);
        thread.start();
    }

    /** The latest committed state; one volatile read. */
    public LedgerSnapshot snapshot() { return snapshot.get(); }

    public Durability durability() { return durability; }

    /** Enqueues one batch; completes with its base offset once durable per the mode. Blocks when the queue is full. */
    public CompletableFuture<Long> append(List<byte[]> recs) throws InterruptedException {
        RecordBatch.encodedSize(recs); // validate size eagerly
        if (recs.isEmpty()) throw new IllegalArgumentException("empty batch");
        var f = new CompletableFuture<Long>();
        if (closed || failure != null) return CompletableFuture.failedFuture(failure != null ? failure : new IllegalStateException("closed"));
        queue.put(new Pending(List.copyOf(recs), System.currentTimeMillis(), f));
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
            } catch (InterruptedException e) {
                stop = true;
            }
            int poison = indexOfPoison(group);
            if (poison >= 0) { stop = true; group.subList(poison, group.size()).clear(); }
            if (!group.isEmpty()) commit(group);
            group.clear();
        }
        // Fail anything still queued.
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
                    var one = List.of(p);
                    long base = write(one, RecordBatch.encodedSize(p.records));
                    log.force();
                    fsyncs++;
                    publish();
                    p.done.complete(base);
                }
                return;
            }
            long base = write(g, total);
            if (durability == Durability.GROUP) { log.force(); fsyncs++; }
            publish();
            long o = base;
            for (var p : g) { p.done.complete(o); o += p.records.size(); }
        } catch (IOException | RuntimeException e) {
            // The log's in-memory tail may now disagree with disk: stop accepting writes; reopen recovers.
            failure = e;
            g.forEach(p -> p.done.completeExceptionally(e));
        }
    }

    private long write(List<Pending> g, int total) throws IOException {
        if (buf.capacity() < total) buf = ByteBuffer.allocateDirect(Integer.highestOneBit(total) << 1);
        buf.clear();
        long base = log.nextOffset(), o = base;
        var offs = new long[g.size()];
        var lens = new int[g.size()];
        for (int k = 0; k < g.size(); k++) {
            var p = g.get(k);
            int start = buf.position();
            RecordBatch.encode(o, p.timestampMs, p.records, buf);
            offs[k] = o;
            lens[k] = buf.position() - start;
            o += p.records.size();
            records += p.records.size();
        }
        buf.flip();
        bytes += buf.remaining();
        log.appendFrames(buf, offs, lens, g.size(), o);
        batches += g.size();
        return base;
    }

    private void publish() {
        snapshot.set(new LedgerSnapshot(log.nextOffset(), log.startOffset(), batches, records, bytes, fsyncs,
                log.segmentCount(), durability, seq++, System.nanoTime()));
    }

    /** Stops after committing everything already queued. Does not close the log. */
    @Override public void close() throws InterruptedException {
        if (closed) return;
        closed = true;
        queue.put(POISON);
        thread.join(TimeUnit.SECONDS.toMillis(30));
    }
}
