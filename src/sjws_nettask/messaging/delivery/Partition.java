package sjws_nettask.messaging.delivery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * <p>Step 3: payloads are not kept in memory; they are read back from the log segment at the message's offset
 * (a small map holds payloads only until their batch is written). State snapshots ({@code state-<offset>.snap},
 * no payloads) are written in the background every {@code snapshotEveryEntries} entries once the log is durable up
 * to that offset, and recovery loads the newest valid one and replays only the entries after it, so recovery time
 * is bounded by the snapshot interval, not by the log size. Step 4: {@link #maintain()} deletes and compacts sealed
 * segments below the low watermark (see {@link RetentionPolicy}).
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
    /**
     * Payloads of the most recent messages (bounded by {@link #payloadCacheBytes}); always holds every message the
     * writer has not yet written, so a read never races the log. Older payloads are read back from segments.
     */
    // Ring of (offset, payload) in offset order; guarded by `lock`. No per-message allocation, O(log n) lookup.
    private long[] ringOff = new long[1024];
    private byte[][] ringVal = new byte[1024][];
    private int ringHead, ringCount;
    private long recentBytes, writtenHint;
    private volatile long payloadCacheBytes = 64L << 20;
    private final Path logDir;
    private final ConcurrentSkipListSet<Long> snapshots = new ConcurrentSkipListSet<>();
    private final ExecutorService snapshotter;
    private volatile long snapshotEveryEntries = 100_000;
    private volatile RetentionPolicy retention = RetentionPolicy.DEFAULT;
    private long lastSnapshotTrigger;
    private CompletableFuture<Long> lastSnapshot = CompletableFuture.completedFuture(-1L);
    private final long recoveredFromSnapshot;
    private final long replayedEntries;

    private Partition(String topic, SegmentedLog log, LedgerWriter writer, DeliveryState state, Partition dlq, LongSupplier clockMs,
            Path logDir, java.util.Collection<Long> snaps, long recoveredFromSnapshot, long replayedEntries) {
        this.logDir = logDir;
        this.snapshots.addAll(snaps);
        this.recoveredFromSnapshot = recoveredFromSnapshot;
        this.replayedEntries = replayedEntries;
        this.lastSnapshotTrigger = state.nextOffset;
        this.snapshotter = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ledger-snapshot-" + topic).daemon().factory());
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
        var logDir = dir.resolve(topic);
        var log = SegmentedLog.open(logDir, segmentBytes);
        var policyFor = isDlq ? RetryPolicy.NO_DLQ : policy;
        DeliveryState st = null;
        var snaps = new java.util.TreeSet<Long>();
        long fromSnap = -1;
        long[] replayed = {0};
        try {
            try (var files = Files.list(logDir)) {
                files.map(f -> f.getFileName().toString()).filter(n -> n.matches("state-\\d{20}\\.snap"))
                        .forEach(n -> snaps.add(Long.parseLong(n.substring(6, 26))));
            }
            for (long so : snaps.descendingSet()) {
                if (so > log.nextOffset() || so < log.startOffset()) continue; // not covered by the log we have
                try {
                    st = DeliveryState.deserialize(Files.readAllBytes(logDir.resolve(snapName(so))), policyFor);
                    if (st.nextOffset != so) { st = null; continue; }
                    fromSnap = so;
                    break;
                } catch (IllegalArgumentException bad) {
                    st = null; // corrupt or torn: try an older one
                }
            }
            if (st == null) {
                if (log.startOffset() > 0) throw new IOException("no usable state snapshot and the log starts at " + log.startOffset());
                st = new DeliveryState(policyFor);
            }
            var target = st;
            log.scanFrom(target.nextOffset, b -> {
                long o = b.baseOffset();
                for (byte[] r : b.records()) {
                    if (o >= target.nextOffset) { target.apply(o, LedgerEntry.decode(r)); replayed[0]++; }
                    o++;
                }
            });
        } catch (IOException | RuntimeException e) {
            log.close();
            if (dlq != null) dlq.close();
            if (e instanceof IOException io && io.getMessage() != null && io.getMessage().startsWith("no usable")) throw io;
            throw new IOException("ledger replay failed for " + topic, e);
        }
        // Keep the loaded snapshot and older ones the log still covers (fallbacks); drop unusable newer ones.
        var keep = new java.util.TreeSet<Long>();
        for (long so : snaps) {
            if (fromSnap >= 0 && so <= fromSnap && so >= log.startOffset()) keep.add(so);
            else Files.deleteIfExists(logDir.resolve(snapName(so)));
        }
        var p = new Partition(topic, log, new LedgerWriter(log, durability), st, dlq, clockMs, logDir, keep, fromSnap, replayed[0]);
        p.publish();
        return p;
    }

    static String snapName(long offset) { return String.format("state-%020d.snap", offset); }

    /** Ledger offset of the snapshot recovery started from (-1 = full replay), and how many entries were replayed after it. */
    public long recoveredFromSnapshot() { return recoveredFromSnapshot; }
    public long replayedEntries() { return replayedEntries; }

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
        return new Delivery(o, attempt, deadline, m.producerId(), m.seq(), m.timestampMs(), payloadOf(o), m.originGroup(),
                m.originOffset(), m.originAttempts(), m.originReason());
    }

    /** Matches any attempt: the unfenced overloads (kept for compatibility) settle whichever lease is current. */
    static final int ANY_ATTEMPT = -1;

    /** Lock held. Null when the caller holds the current lease, else why not. */
    private LeaseResult fence(DeliveryState.Lease l, int attempt) {
        if (l == null) return LeaseResult.NOT_IN_FLIGHT;
        if (attempt != ANY_ATTEMPT && attempt != l.attempt()) return LeaseResult.STALE_ATTEMPT;
        return null;
    }

    /**
     * IN_FLIGHT → ACKED. Returns false if {@code offset} is not in flight for {@code group} (late or duplicate ack).
     * Unfenced: prefer {@link #ack(String, long, int)} with the delivery's attempt.
     */
    public boolean ack(String group, long offset) { return ack(group, offset, ANY_ATTEMPT).ok(); }

    /** As {@link #ack(String, long)}, returning the record's durability future, or null when rejected. Unfenced. */
    public CompletableFuture<Long> ack(String group, long offset, Durability durability) {
        return ack(group, offset, ANY_ATTEMPT, durability).durable();
    }

    /** Fenced ack: applied only if {@code attempt} is the current lease ({@link Delivery#attempt()}). */
    public LeaseResult ack(String group, long offset, int attempt) {
        lock.lock();
        try { // no result allocation on the common path
            var r = fence(state.group(group).inFlight.get(offset), attempt);
            if (r != null) return r;
            append(new LedgerEntry.Ack(group, offset), null);
            return LeaseResult.OK;
        } finally {
            lock.unlock();
        }
    }

    /** Fenced ack with a per-record durability override. */
    public AckResult ack(String group, long offset, int attempt, Durability durability) {
        lock.lock();
        try {
            var r = fence(state.group(group).inFlight.get(offset), attempt);
            if (r != null) return new AckResult(r, null);
            return new AckResult(LeaseResult.OK, append(new LedgerEntry.Ack(group, offset), durability));
        } finally {
            lock.unlock();
        }
    }

    /**
     * IN_FLIGHT → ENQUEUED, redeliverable after max(delayMs, backoff(attempt)); dead-lettered instead once the
     * message has used {@code maxAttempts}. Returns false if not in flight. Unfenced.
     */
    public boolean nack(String group, long offset, long delayMs) { return nack(group, offset, ANY_ATTEMPT, delayMs).ok(); }

    /** Fenced nack: applied only if {@code attempt} is the current lease. */
    public LeaseResult nack(String group, long offset, int attempt, long delayMs) {
        lock.lock();
        try {
            var g = state.group(group);
            var l = g.inFlight.get(offset);
            var r = fence(l, attempt);
            if (r != null) return r;
            if (l.attempt() >= state.policy().maxAttempts()) {
                deadLetter(group, offset, l.attempt(), "nacked:max-attempts");
                return LeaseResult.OK;
            }
            long notBefore = clockMs.getAsLong() + Math.max(Math.max(0, delayMs), state.policy().backoffMs(l.attempt()));
            append(new LedgerEntry.Nack(group, offset, notBefore), null);
            return LeaseResult.OK;
        } finally {
            lock.unlock();
        }
    }

    /** Dead-letters an in-flight message immediately (poison message). Returns false if not in flight. Unfenced. */
    public boolean reject(String group, long offset, String reason) { return reject(group, offset, ANY_ATTEMPT, reason).ok(); }

    /** Fenced reject: applied only if {@code attempt} is the current lease. */
    public LeaseResult reject(String group, long offset, int attempt, String reason) {
        lock.lock();
        try {
            var l = state.group(group).inFlight.get(offset);
            var r = fence(l, attempt);
            if (r != null) return r;
            deadLetter(group, offset, l.attempt(), "rejected:" + reason);
            return LeaseResult.OK;
        } finally {
            lock.unlock();
        }
    }

    private void deadLetter(String group, long offset, int attempts, String reason) {
        var m = state.messages.get(offset);
        if (dlq != null) {
            var f = dlq.enqueueDeadLetter(group, offset, attempts, reason, m, payloadOf(offset));
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
            DeliveryState.Message m, byte[] payload) {
        lock.lock();
        try {
            if (state.hasOrigin(group, offset)) return CompletableFuture.completedFuture(-1L);
            return append(new LedgerEntry.Enqueue(0, 0, m.timestampMs(), group, offset, attempts, reason,
                    payload), null);
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
        if (e instanceof LedgerEntry.Enqueue x) cachePayload(offset, x.payloadRef());
        state.apply(offset, e);
        publish();
        // Amortized O(1) per entry: a snapshot costs O(live state), so wait at least twice that many entries.
        long since = state.nextOffset - lastSnapshotTrigger;
        if (since >= snapshotEveryEntries && since >= 2L * state.messages.size()) snapshotLocked();
        return f.thenApply(base -> {
            if (base != offset) throw new IllegalStateException("ledger offset drift " + base + " != " + offset);
            return base;
        });
    }

    /** Lock held. Evicts the oldest cached payloads over budget, but never one the writer has not written yet. */
    private void cachePayload(long offset, byte[] payload) {
        if (ringCount == ringOff.length) growRing();
        int mask = ringOff.length - 1, i = (ringHead + ringCount) & mask;
        ringOff[i] = offset;
        ringVal[i] = payload;
        ringCount++;
        recentBytes += payload.length;
        if (recentBytes > payloadCacheBytes) evictPayloads();
    }

    private void evictPayloads() {
        long budget = payloadCacheBytes;
        int mask = ringOff.length - 1;
        while (recentBytes > budget && ringCount > 0) {
            long ho = ringOff[ringHead];
            if (ho >= writtenHint) {
                writtenHint = writer.snapshot().nextOffset();
                if (ho >= writtenHint) return; // not written yet: keep (temporarily over budget)
            }
            recentBytes -= ringVal[ringHead].length;
            ringVal[ringHead] = null;
            ringHead = (ringHead + 1) & mask;
            ringCount--;
        }
    }

    private void growRing() {
        int n = ringOff.length, mask = n - 1;
        var o = new long[n * 2];
        var v = new byte[n * 2][];
        for (int k = 0; k < ringCount; k++) { o[k] = ringOff[(ringHead + k) & mask]; v[k] = ringVal[(ringHead + k) & mask]; }
        ringOff = o;
        ringVal = v;
        ringHead = 0;
    }

    /** Lock held. Cached payload of {@code offset}, or null. */
    private byte[] cachedPayload(long offset) {
        int n = ringCount;
        if (n == 0) return null;
        int mask = ringOff.length - 1;
        if (offset < ringOff[ringHead] || offset > ringOff[(ringHead + n - 1) & mask]) return null;
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = ringOff[(ringHead + mid) & mask];
            if (v < offset) lo = mid + 1; else if (v > offset) hi = mid - 1; else return ringVal[(ringHead + mid) & mask];
        }
        return null;
    }

    /** Bytes of recent payloads kept in memory (default 64 MiB; 0 = only unwritten ones). */
    public void payloadCacheBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("payload cache");
        payloadCacheBytes = bytes;
        if (dlq != null) dlq.payloadCacheBytes(bytes);
    }

    /** Payload of the message at {@code offset}: from the recent cache, else from its log segment. */
    private byte[] payloadOf(long offset) {
        var b = cachedPayload(offset);
        if (b != null) return b;
        try {
            var rb = log.read(offset);
            if (rb == null) throw new IllegalStateException("payload of " + offset + " missing from the log");
            if (!(LedgerEntry.decode(rb.records().get((int) (offset - rb.baseOffset()))) instanceof LedgerEntry.Enqueue x))
                throw new IllegalStateException("offset " + offset + " is not a message");
            return x.payloadRef();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- state snapshots (step 3) ----

    /**
     * Take a state snapshot every {@code n} applied entries (default 100 000), or every {@code 2 × live messages}
     * entries when that is larger, so snapshot work stays proportional to append work.
     */
    public void snapshotEveryEntries(long n) {
        if (n < 1) throw new IllegalArgumentException("snapshot interval");
        snapshotEveryEntries = n;
        if (dlq != null) dlq.snapshotEveryEntries(n);
    }

    public void retention(RetentionPolicy p) { retention = java.util.Objects.requireNonNull(p); if (dlq != null) dlq.retention(p); }

    /** Serializes the state now (under the lock) and writes it in the background; completes with its offset once durable. */
    public CompletableFuture<Long> snapshotNow() {
        lock.lock();
        try { return snapshotLocked(); } finally { lock.unlock(); }
    }

    private CompletableFuture<Long> snapshotLocked() {
        long off = state.nextOffset;
        lastSnapshotTrigger = off;
        if (snapshots.contains(off)) return CompletableFuture.completedFuture(off);
        // Nothing is serialized under the lock: the snapshotter rebuilds the state at `off` from the newest
        // snapshot file plus the log tail (deterministic replay) and serializes that private copy.
        var prev = lastSnapshot;
        lastSnapshot = prev.handleAsync((v, t) -> {
            writeSnapshot(off, rebuildAt(off));
            return off;
        }, snapshotter);
        return lastSnapshot;
    }

    /**
     * Snapshotter thread. State at {@code off}, rebuilt off-lock: newest snapshot file (or empty) + replay of
     * {@code [base, off)}. Only reads log at or above the newest snapshot, which retention/compaction never touch.
     */
    private byte[] rebuildAt(long off) {
        try {
            // The snapshot is only valid if the log is durable through `off`: wait for the writer, then fsync.
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (writer.snapshot().nextOffset() < off) {
                if (System.nanoTime() > deadline) throw new IOException("log never reached snapshot offset " + off);
                LockSupport.parkNanos(100_000);
            }
            log.force();
            var policy = state.policy();
            DeliveryState st = null;
            Long base = snapshots.floor(off);
            if (base != null) {
                try { st = DeliveryState.deserialize(Files.readAllBytes(logDir.resolve(snapName(base))), policy); }
                catch (IllegalArgumentException bad) { st = null; }
            }
            if (st == null) {
                if (log.startOffset() > 0) throw new IOException("snapshot base unreadable and log starts at " + log.startOffset());
                st = new DeliveryState(policy);
            }
            var target = st;
            long from = target.nextOffset;
            log.scanFrom(from, b -> {
                long o = b.baseOffset();
                for (byte[] r : b.records()) {
                    if (o >= target.nextOffset && o < off) target.apply(o, LedgerEntry.decode(r));
                    o++;
                }
            });
            if (target.nextOffset != off) throw new IOException("rebuilt state at " + target.nextOffset + " != " + off);
            return target.serialize();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void writeSnapshot(long off, byte[] data) {
        try {
            var tmp = logDir.resolve(snapName(off) + ".tmp");
            try (var ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var b = java.nio.ByteBuffer.wrap(data);
                while (b.hasRemaining()) ch.write(b);
                ch.force(true);
            }
            Files.move(tmp, logDir.resolve(snapName(off)), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var d = FileChannel.open(logDir, StandardOpenOption.READ)) { d.force(true); } catch (IOException ignored) { }
            snapshots.add(off);
            while (snapshots.size() > 2) { // keep the two newest: the older one is the fallback if the newest is unreadable
                long old = snapshots.pollFirst();
                Files.deleteIfExists(logDir.resolve(snapName(old)));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ---- retention and compaction (step 4) ----

    /** What one {@link #maintain()} pass did. {@code lowWatermark}: nothing at or above it may be removed. */
    public record MaintenanceReport(long lowWatermark, int deletedSegments, int compactedSegments, long reclaimedBytes,
            long pinnedBytes, MaintenanceReport dlq) { }

    /**
     * Deletes sealed segments wholly below the low watermark (oldest first, per {@link RetentionPolicy#keepResolvedMs})
     * and compacts sealed segments that a state snapshot covers but a few live messages pin. The low watermark is
     * min(oldest retained snapshot, oldest message still needed by any group). Copies run without the partition lock;
     * deletes and swaps take it briefly. Also maintains the DLQ partition.
     */
    public MaintenanceReport maintain() throws IOException {
        var pol = retention;
        long now = System.currentTimeMillis();
        int deleted = 0, compacted = 0;
        long reclaimed = 0, lw;
        long oldestSnap = snapshots.isEmpty() ? 0 : snapshots.first(); // no durable snapshot yet: the whole log is needed
        lock.lock();
        try {
            lw = Math.min(oldestSnap, state.oldestNeededMessageOffset());
            for (var seg : log.segments()) {
                if (seg.active() || seg.nextOffset() > lw
                        || (pol.keepResolvedMs() > 0 && now - seg.lastModifiedMs() < pol.keepResolvedMs())) break;
                log.deleteSegment(seg.baseOffset());
                deleted++;
                reclaimed += seg.bytes();
            }
        } finally {
            lock.unlock();
        }
        if (pol.compactLiveRatio() > 0) {
            for (var seg : log.segments()) {
                if (seg.active() || seg.nextOffset() > oldestSnap) break;
                java.util.Set<Long> keep;
                long liveBytes = 0;
                lock.lock();
                try {
                    keep = new java.util.HashSet<>(state.messages.subMap(seg.baseOffset(), true, seg.nextOffset(), false).keySet());
                    for (long k : keep) liveBytes += state.messages.get(k).size() + RecordBatch.HEADER_BYTES + 12 + 4;
                } finally {
                    lock.unlock();
                }
                if (liveBytes >= seg.bytes() * pol.compactLiveRatio()) continue;
                long newSize = log.prepareCompaction(seg.baseOffset(), keep::contains);
                lock.lock();
                try { log.commitCompaction(seg.baseOffset()); } finally { lock.unlock(); }
                compacted++;
                reclaimed += seg.bytes() - newSize;
            }
        }
        long pinned = 0;
        for (var seg : log.segments()) if (!seg.active()) pinned += seg.bytes();
        return new MaintenanceReport(lw, deleted, compacted, reclaimed, pinned, dlq == null ? null : dlq.maintain());
    }

    /** Bytes currently on disk for this partition's log (excluding the DLQ). */
    public long logBytes() { return log.totalBytes(); }

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
            snapshotter.shutdown();
            snapshotter.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            log.close();
            if (dlq != null) dlq.close();
        }
    }
}
