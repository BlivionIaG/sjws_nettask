package sjws_nettask.messaging.delivery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.RecordBatch;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * One topic partition with at-least-once delivery to consumer groups (docs/messaging/design.md §1–§3).
 * Every operation validates against {@link DeliveryState}, appends one {@link LedgerEntry} to the
 * partition log via {@link LedgerWriter} and applies it, all under one lock, so log order equals apply
 * order and {@link #open} replays the log into exactly the same state. Dead-lettered messages are first
 * copied to the {@code <topic>.dlq} partition (deduplicated by origin group and offset), then marked in this log,
 * so a crash between the two steps just repeats the copy idempotently. DLQ copies carry producerId 0
 * (no producer dedup) and the origin topic group, offset, attempts and reason.
 *
 * <p>Durability: each operation's record follows the writer default (ASYNC: acked after write, background fsync)
 * unless an override is passed; the returned futures complete per {@link LedgerWriter}.
 *
 * <p>Inspection: {@link #counts()} is exact and lock-free (republished after every entry, O(groups)).
 * {@link #snapshot()} (paged item lists) is lock-free on the fast path and rate-limited: it is rebuilt at most every
 * {@code minRebuildNanos} (with {@code tryLock}, so a busy partition is never stalled), every list is capped at
 * {@code maxItemsPerList} so a rebuild does bounded work under the lock, and a view older than {@code maxStaleNanos}
 * is rebuilt with a blocking lock so staleness is bounded under contention.
 */
public final class Partition implements AutoCloseable {

    public static final String DLQ_SUFFIX = ".dlq";

    /** Outcome of a produce: {@code duplicate} when (producerId, seq) was already appended. */
    public record ProduceResult(long offset, boolean duplicate, CompletableFuture<Long> durable) { }

    private final String topic;
    private final SegmentedLog log;
    private final LedgerWriter writer;
    private final DeliveryState state;
    private final Partition dlq;
    private final LongSupplier clockMs;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<DeliverySnapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<DeliveryCounts> counts = new AtomicReference<>();
    private volatile long lastRebuildNanos;
    private volatile long minRebuildNanos = 50_000_000L;
    private volatile long maxStaleNanos = 1_000_000_000L;
    private volatile int maxItemsPerList = 10_000;

    private Partition(String topic, SegmentedLog log, LedgerWriter writer, DeliveryState state, Partition dlq, LongSupplier clockMs) {
        this.topic = topic;
        this.log = log;
        this.writer = writer;
        this.state = state;
        this.dlq = dlq;
        this.clockMs = clockMs;
    }

    /** Opens {@code dir/<topic>} (and {@code dir/<topic>.dlq}) with the default writer (ASYNC). */
    public static Partition open(Path dir, String topic, RetryPolicy policy, LongSupplier clockMs) throws IOException {
        return open(dir, topic, policy, clockMs, Durability.ASYNC, SegmentedLog.DEFAULT_SEGMENT_BYTES);
    }

    public static Partition open(Path dir, String topic, RetryPolicy policy, LongSupplier clockMs, Durability durability,
            long segmentBytes) throws IOException {
        if (topic.isEmpty() || topic.contains("/") || topic.contains("\\")) throw new IllegalArgumentException("topic");
        boolean isDlq = topic.endsWith(DLQ_SUFFIX);
        Partition dlq = isDlq ? null : open(dir, topic + DLQ_SUFFIX, RetryPolicy.NO_DLQ, clockMs, durability, segmentBytes);
        var log = SegmentedLog.open(dir.resolve(topic), segmentBytes);
        var st = new DeliveryState(isDlq ? RetryPolicy.NO_DLQ : policy);
        try {
            for (RecordBatch b : log.readAll()) {
                long o = b.baseOffset();
                for (byte[] r : b.records()) st.apply(o++, LedgerEntry.decode(r));
            }
        } catch (RuntimeException e) {
            log.close();
            if (dlq != null) dlq.close();
            throw new IOException("ledger replay failed for " + topic, e);
        }
        var p = new Partition(topic, log, new LedgerWriter(log, durability), st, dlq, clockMs);
        p.publish();
        return p;
    }

    public String topic() { return topic; }

    /** The {@code <topic>.dlq} partition, or null for a DLQ topic itself. */
    public Partition deadLetterQueue() { return dlq; }

    // ---- producer ----

    public ProduceResult produce(long producerId, long seq, byte[] payload) { return produce(producerId, seq, payload, null); }

    /**
     * Appends a message. With {@code producerId != 0}, a repeated (producerId, seq) inside the dedup window returns
     * the original offset with {@code duplicate = true} and appends nothing; a seq older than the window is
     * rejected as a duplicate with offset -1.
     */
    public ProduceResult produce(long producerId, long seq, byte[] payload, Durability durability) {
        lock.lock();
        try {
            long dup = state.duplicateOf(producerId, seq);
            if (dup == -2) return new ProduceResult(-1, true, CompletableFuture.completedFuture(-1L));
            if (dup != -1) return new ProduceResult(dup, true, CompletableFuture.completedFuture(dup));
            long o = state.nextOffset;
            var f = append(new LedgerEntry.Enqueue(producerId, seq, clockMs.getAsLong(), "", -1, 0, "", payload), durability);
            return new ProduceResult(o, false, f);
        } finally {
            lock.unlock();
        }
    }

    // ---- consumer groups ----

    /** Creates {@code group} if absent: from the oldest retained message, or from the next new one. Idempotent. */
    public boolean subscribe(String group, boolean fromStart) {
        lock.lock();
        try {
            if (state.groups.containsKey(group)) return false;
            long from = fromStart ? (state.messages.isEmpty() ? state.nextOffset : Math.min(state.messages.firstKey(), state.nextOffset))
                    : state.nextOffset;
            append(new LedgerEntry.Subscribe(group, from), null);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Leases up to {@code max} messages to {@code consumer} for {@code visibilityMs}. Due redeliveries (nacked
     * messages past their backoff, expired leases past theirs) come first, then fresh messages in offset order.
     * A due message that already had {@code maxAttempts} deliveries is dead-lettered instead.
     */
    public List<Delivery> poll(String group, String consumer, int max, long visibilityMs) {
        if (max < 1 || visibilityMs < 1) throw new IllegalArgumentException("max/visibility");
        lock.lock();
        try {
            var g = state.group(group);
            long now = clockMs.getAsLong();
            var out = new ArrayList<Delivery>();
            var policy = state.policy();
            for (long o : state.dueRedeliveries(g, now, Integer.MAX_VALUE)) {
                if (out.size() >= max) break;
                var lease = g.inFlight.get(o);
                int attempts = lease != null ? lease.attempt() : g.retry.get(o).attempts();
                if (attempts >= policy.maxAttempts()) {
                    deadLetter(g.name, o, attempts, lease != null ? "lease-expired:max-attempts" : "nacked:max-attempts");
                    continue;
                }
                out.add(deliver(group, o, consumer, now + visibilityMs, attempts + 1));
            }
            while (out.size() < max) {
                Long o = state.messages.ceilingKey(g.cursor);
                if (o == null) break;
                out.add(deliver(group, o, consumer, now + visibilityMs, 1));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    private Delivery deliver(String group, long o, String consumer, long deadline, int attempt) {
        append(new LedgerEntry.Deliver(group, o, consumer, deadline, attempt), null);
        var m = state.messages.get(o);
        return new Delivery(o, attempt, deadline, m.producerId(), m.seq(), m.timestampMs(), m.payload(), m.originGroup(),
                m.originOffset(), m.originAttempts(), m.originReason());
    }

    /** IN_FLIGHT → ACKED. Returns false if {@code offset} is not in flight for {@code group} (late or duplicate ack). */
    public boolean ack(String group, long offset) { return ack(group, offset, null) != null; }

    /** As {@link #ack(String, long)}, returning the record's durability future, or null when rejected. */
    public CompletableFuture<Long> ack(String group, long offset, Durability durability) {
        lock.lock();
        try {
            if (!state.group(group).inFlight.containsKey(offset)) return null;
            return append(new LedgerEntry.Ack(group, offset), durability);
        } finally {
            lock.unlock();
        }
    }

    /**
     * IN_FLIGHT → ENQUEUED, redeliverable after max(delayMs, backoff(attempt)); dead-lettered instead once the
     * message has used {@code maxAttempts}. Returns false if not in flight.
     */
    public boolean nack(String group, long offset, long delayMs) {
        lock.lock();
        try {
            var g = state.group(group);
            var l = g.inFlight.get(offset);
            if (l == null) return false;
            if (l.attempt() >= state.policy().maxAttempts()) {
                deadLetter(group, offset, l.attempt(), "nacked:max-attempts");
                return true;
            }
            long notBefore = clockMs.getAsLong() + Math.max(Math.max(0, delayMs), state.policy().backoffMs(l.attempt()));
            append(new LedgerEntry.Nack(group, offset, notBefore), null);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Dead-letters an in-flight message immediately (poison message). Returns false if not in flight. */
    public boolean reject(String group, long offset, String reason) {
        lock.lock();
        try {
            var l = state.group(group).inFlight.get(offset);
            if (l == null) return false;
            deadLetter(group, offset, l.attempt(), "rejected:" + reason);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void deadLetter(String group, long offset, int attempts, String reason) {
        var m = state.messages.get(offset);
        if (dlq != null) {
            var f = dlq.enqueueDeadLetter(group, offset, attempts, reason, m);
            try {
                f.get(); // the copy must be in the DLQ log before this log says DEAD_LETTERED
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while dead-lettering", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("dead-letter copy failed", e.getCause());
            }
        }
        append(new LedgerEntry.DeadLetter(group, offset, reason), null);
    }

    private CompletableFuture<Long> enqueueDeadLetter(String group, long offset, int attempts, String reason,
            DeliveryState.Message m) {
        lock.lock();
        try {
            if (state.hasOrigin(group, offset)) return CompletableFuture.completedFuture(-1L);
            return append(new LedgerEntry.Enqueue(0, 0, m.timestampMs(), group, offset, attempts, reason,
                    m.payload()), null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends {@code e} and applies it. Called with the lock held, so the writer queue (and hence log
     * order) matches apply order.
     */
    private CompletableFuture<Long> append(LedgerEntry e, Durability durability) {
        long offset = state.nextOffset;
        CompletableFuture<Long> f;
        try {
            f = writer.append(List.of(LedgerEntry.encode(e)), durability);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ie);
        }
        if (f.isCompletedExceptionally()) {
            f.join(); // rethrows: the writer is fenced; nothing was applied
        }
        state.apply(offset, e);
        publish();
        return f.thenApply(base -> {
            if (base != offset) throw new IllegalStateException("ledger offset drift " + base + " != " + offset);
            return base;
        });
    }

    // ---- inspection ----

    /** Exact counts as of the last applied entry. Lock-free, never stale. */
    public DeliveryCounts counts() { return counts.get(); }

    /**
     * Paged view. Lock-free unless the view is older than {@code maxStaleNanos} while the partition stays busy, in
     * which case this call waits for the lock once to rebuild (bounded by {@code maxItemsPerList} work).
     */
    public DeliverySnapshot snapshot() {
        var s = snapshot.get();
        if (s.version() == state.version) return s;
        long age = System.nanoTime() - lastRebuildNanos;
        if (age >= minRebuildNanos && lock.tryLock()) {
            try { return rebuild(); } finally { lock.unlock(); }
        }
        if (age >= maxStaleNanos) return freshSnapshot();
        return s;
    }

    /** Rebuilds under the lock and returns a view that includes every entry applied so far. */
    public DeliverySnapshot freshSnapshot() {
        lock.lock();
        try { return rebuild(); } finally { lock.unlock(); }
    }

    /** Tuning: rebuild rate limit, staleness bound and per-list cap of {@link #snapshot()} (also applied to the DLQ). */
    public void snapshotPolicy(long minRebuildNanos, long maxStaleNanos, int maxItemsPerList) {
        if (minRebuildNanos < 0 || maxStaleNanos < minRebuildNanos || maxItemsPerList < 1) throw new IllegalArgumentException("snapshot policy");
        this.minRebuildNanos = minRebuildNanos;
        this.maxStaleNanos = maxStaleNanos;
        this.maxItemsPerList = maxItemsPerList;
        if (dlq != null) dlq.snapshotPolicy(minRebuildNanos, maxStaleNanos, maxItemsPerList);
    }

    private boolean eager;

    /** When true, the paged view is rebuilt after every operation (tests, low-rate topics). Default: rate-limited. */
    public void eagerSnapshots(boolean on) { lock.lock(); try { eager = on; publish(); } finally { lock.unlock(); } }

    private void publish() {
        counts.set(state.counts());
        if (eager || snapshot.get() == null) rebuild();
    }

    private DeliverySnapshot rebuild() {
        var s = state.snapshot(maxItemsPerList);
        snapshot.set(s);
        lastRebuildNanos = System.nanoTime();
        return s;
    }

    /** Canonical state dump (tests). */
    String canonicalState() { lock.lock(); try { return state.canonical(); } finally { lock.unlock(); } }

    @Override public void close() throws IOException {
        try {
            writer.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            log.close();
            if (dlq != null) dlq.close();
        }
    }
}
