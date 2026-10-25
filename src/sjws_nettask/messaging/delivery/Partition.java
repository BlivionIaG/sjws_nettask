package sjws_nettask.messaging.delivery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** Every append goes through the seam (today {@link #writer} itself; durable-log-plan §3 replication later). */
    private final sjws_nettask.messaging.ledger.PartitionAppender appender;
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
    /** Retain-from pins by owner (agent-graphs.md §4.3): retention never deletes at or above the lowest. */
    private volatile Map<String, Long> pins = Map.of();
    private final java.util.concurrent.locks.ReentrantLock pinLock = new java.util.concurrent.locks.ReentrantLock();
    static final String PINS_FILE = "retain-from.pins";

    /** Optional, set at open (#224): {@code null} unless swarm installs one; read only by {@code extend}. */
    private final CancelProbe cancelProbe;

    private Partition(String topic, SegmentedLog log, LedgerWriter writer, DeliveryState state, Partition dlq, LongSupplier clockMs,
            Path logDir, java.util.Collection<Long> snaps, long recoveredFromSnapshot, long replayedEntries,
            CancelProbe cancelProbe) {
        this.cancelProbe = cancelProbe;
        this.logDir = logDir;
        this.snapshots.addAll(snaps);
        this.recoveredFromSnapshot = recoveredFromSnapshot;
        this.replayedEntries = replayedEntries;
        this.lastSnapshotTrigger = state.nextOffset;
        this.snapshotter = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("ledger-snapshot-" + topic).daemon().factory());
        this.topic = topic;
        this.log = log;
        this.writer = writer;
        this.appender = writer;
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
        return open(dir, topic, policy, clockMs, durability, segmentBytes, true);
    }

    /**
     * As {@link #open(Path, String, RetryPolicy, LongSupplier, Durability, long)}; {@code deadLetterQueue = false}
     * opens the topic without a {@code <topic>.dlq} partition (no directory, no writer, syncer or snapshotter
     * threads for it). A message that exhausts {@code maxAttempts}, or is rejected, is then dropped in place: its
     * own log records DEAD_LETTERED with the reason (it is counted in {@code deadLettered}, listed by inspection,
     * never delivered again, and its payload becomes reclaimable by retention), but no copy is kept anywhere.
     * An existing {@code <topic>.dlq} directory is left untouched, so reopening with a DLQ later still sees it.
     */
    public static Partition open(Path dir, String topic, RetryPolicy policy, LongSupplier clockMs, Durability durability,
            long segmentBytes, boolean deadLetterQueue) throws IOException {
        return open(dir, topic, policy, clockMs, durability, segmentBytes, deadLetterQueue, null);
    }

    /**
     * Opens {@code dir/<topic>} with no {@code <topic>.dlq} partition, the default writer (ASYNC) and segment size:
     * the open path for short-lived topics such as lazy swarm stream shards ({@code stream.<node>#i}, #224) that
     * should not cost a second writer, syncer and snapshotter. Dead-letter semantics are as in
     * {@link #open(Path, String, RetryPolicy, LongSupplier, Durability, long, boolean)} with {@code false}.
     */
    public static Partition openWithoutDlq(Path dir, String topic, RetryPolicy policy, LongSupplier clockMs)
            throws IOException {
        return open(dir, topic, policy, clockMs, Durability.ASYNC, SegmentedLog.DEFAULT_SEGMENT_BYTES, false, null);
    }

    /**
     * As {@link #open(Path, String, RetryPolicy, LongSupplier, Durability, long, boolean)}, installing
     * {@code cancelProbe} (may be {@code null}: exactly the behaviour without one). The probe is asked only by
     * {@code extend}, after the lease fence, and makes it return {@link LeaseResult#CANCELLED} (#224). It is not
     * installed on this topic's {@code .dlq} partition.
     */
    public static Partition open(Path dir, String topic, RetryPolicy policy, LongSupplier clockMs, Durability durability,
            long segmentBytes, boolean deadLetterQueue, CancelProbe cancelProbe) throws IOException {
        if (topic.isEmpty() || topic.contains("/") || topic.contains("\\")) throw new IllegalArgumentException("topic");
        boolean isDlq = topic.endsWith(DLQ_SUFFIX);
        Partition dlq = isDlq || !deadLetterQueue ? null
                : open(dir, topic + DLQ_SUFFIX, RetryPolicy.NO_DLQ, clockMs, durability, segmentBytes, false);
        var logDir = dir.resolve(topic);
        SegmentedLog log;
        try {
            log = SegmentedLog.open(logDir, segmentBytes); // takes the directory lock (one process per ledger dir)
        } catch (IOException | RuntimeException e) {
            if (dlq != null) dlq.close(); // do not leave the DLQ (and its lock) open behind a failed open
            throw e;
        }
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
        Map<String, Long> pinned;
        try {
            for (long so : snaps) {
                if (fromSnap >= 0 && so <= fromSnap && so >= log.startOffset()) keep.add(so);
                else Files.deleteIfExists(logDir.resolve(snapName(so)));
            }
            pinned = readPins(logDir); // before any thread starts: a corrupt pin file fails the open cleanly
        } catch (IOException | RuntimeException e) {
            log.close(); // releases the directory lock
            if (dlq != null) dlq.close();
            throw e;
        }
        var p = new Partition(topic, log, new LedgerWriter(log, durability), st, dlq, clockMs, logDir, keep, fromSnap, replayed[0],
                cancelProbe);
        p.pins = pinned;
        p.publish();
        return p;
    }

    static String snapName(long offset) { return String.format("state-%020d.snap", offset); }

    /** Ledger offset of the snapshot recovery started from (-1 = full replay), and how many entries were replayed after it. */
    public long recoveredFromSnapshot() { return recoveredFromSnapshot; }
    public long replayedEntries() { return replayedEntries; }

    public String topic() { return topic; }

    /** The {@code <topic>.dlq} partition, or null for a DLQ topic itself or a topic opened without one. */
    public Partition deadLetterQueue() { return dlq; }

    // ---- producer ----

    public ProduceResult produce(long producerId, long seq, byte[] payload) { return produce(producerId, seq, payload, null); }

    /**
     * Appends a message. With {@code producerId != 0}, a repeated (producerId, seq) inside the dedup window returns
     * the original offset with {@code duplicate = true} and appends nothing; a seq older than the window is
     * rejected as a duplicate with offset -1.
     */
    public ProduceResult produce(long producerId, long seq, byte[] payload, Durability durability) {
        // Allocate and encode outside the partition lock so a descheduled lock holder does less work under
        // contention (claim-wakeup #184: reply produce held the lock while writer/syncer threads ran).
        long ts = clockMs.getAsLong();
        var entry = new LedgerEntry.Enqueue(producerId, seq, ts, "", -1, 0, "", payload);
        var enc = List.of(LedgerEntry.encode(entry));
        lockForAppend();
        try {
            long dup = state.duplicateOf(producerId, seq);
            if (dup == -2) return new ProduceResult(-1, true, CompletableFuture.completedFuture(-1L));
            if (dup != -1) return new ProduceResult(dup, true, CompletableFuture.completedFuture(dup));
            long o = state.nextOffset;
            var f = appendEncoded(entry, enc, durability);
            return new ProduceResult(o, false, f);
        } finally {
            lock.unlock();
        }
    }

    /** What a forwarded produce did (#236, {@link #produceFrom}). */
    public enum ForwardOutcome {
        /** Appended at {@code offset}. */
        APPENDED,
        /** Already appended at {@code offset}: a retry. */
        DUPLICATE,
        /** The seq fell out of the 4096-seq window: it may never have been applied, so this is a failure, not success. */
        TOO_OLD,
        /** Not the next seq for this producer: resend from {@code expectedSeq} (go-back-N). */
        OUT_OF_ORDER,
        /** A new producer while its origin already has the maximum live producers here: retry later. */
        BUSY
    }

    /**
     * Result of {@link #produceFrom}.
     *
     * @param offset the new offset (APPENDED) or the original one (DUPLICATE), else -1
     * @param expectedSeq for OUT_OF_ORDER, the seq this partition accepts next from the producer; else 0
     * @param durable APPENDED: completes with {@code offset} once durable per the requested durability; otherwise
     *        already complete
     */
    public record ForwardedResult(ForwardOutcome outcome, long offset, long expectedSeq, CompletableFuture<Long> durable) { }

    /** Default cap on live forwarded producers per origin per partition. */
    public static final int DEFAULT_LIVE_PRODUCERS = 1024;
    /** Default idle time after which a forwarded producer may be evicted to make room for a new one. */
    public static final long DEFAULT_PRODUCER_IDLE_MS = 3_600_000L;

    /**
     * Appends a message forwarded by another node (#236 option (a)). {@code origin} is the authenticated sending
     * node's wire id, never a payload value; dedup is keyed by {@code (origin, producerId)}, apart from local
     * producers. Each forwarded producer's seqs are applied strictly in order starting at 1: a repeat inside the
     * window is DUPLICATE, a seq older than the window is TOO_OLD, and a gap is OUT_OF_ORDER with the expected seq.
     * {@link #produce} is unchanged.
     *
     * @param maxLiveProducers cap on live producers for {@code origin} here; beyond it a new producer gets BUSY unless
     *        one has been idle {@code producerIdleMs} and is evicted
     * @throws java.util.concurrent.CompletionException when the ledger writer is fenced: nothing was applied. Any
     *         other exception may come after the append was handed to the ledger, so the write's fate is unknown.
     */
    public ForwardedResult produceFrom(int origin, long producerId, long seq, byte[] payload, Durability durability,
            int maxLiveProducers, long producerIdleMs) {
        if (origin < 1 || origin > 0xFFFF) throw new IllegalArgumentException("origin " + origin);
        if (producerId == 0 || seq < 1) throw new IllegalArgumentException("forwarded producer and seq");
        if (maxLiveProducers < 1 || producerIdleMs < 0) throw new IllegalArgumentException("producer limits");
        long ts = clockMs.getAsLong();
        var entry = new LedgerEntry.Enqueue(producerId, seq, ts, "", -1, 0, "", payload, origin);
        var enc = List.of(LedgerEntry.encode(entry));
        lockForAppend();
        try {
            long c = state.checkRemote(origin, producerId, seq, ts, maxLiveProducers, producerIdleMs);
            if (c >= 0) return new ForwardedResult(ForwardOutcome.DUPLICATE, c, 0, CompletableFuture.completedFuture(c));
            if (c == DeliveryState.REMOTE_TOO_OLD) return refused(ForwardOutcome.TOO_OLD, 0);
            if (c == DeliveryState.REMOTE_OUT_OF_ORDER) {
                return refused(ForwardOutcome.OUT_OF_ORDER, state.expectedSeq(origin, producerId));
            }
            if (c == DeliveryState.REMOTE_BUSY) return refused(ForwardOutcome.BUSY, 0);
            long o = state.nextOffset;
            var f = appendEncoded(entry, enc, durability);
            return new ForwardedResult(ForwardOutcome.APPENDED, o, 0, f);
        } finally {
            lock.unlock();
        }
    }

    private static ForwardedResult refused(ForwardOutcome outcome, long expected) {
        return new ForwardedResult(outcome, -1, expected, CompletableFuture.completedFuture(-1L));
    }

    // ---- consumer groups ----

    /** Creates {@code group} if absent: from the oldest retained message, or from the next new one. Idempotent. */
    public boolean subscribe(String group, boolean fromStart) {
        // Groups are never removed, so a group seen once stays subscribed: callers that re-subscribe before every
        // claim/ack (TaskService) skip the partition lock entirely.
        if (subscribed.contains(group)) return false;
        lockForAppend();
        try {
            if (state.groups.containsKey(group)) { subscribed.add(group); return false; }
            long from = fromStart ? (state.messages.isEmpty() ? state.nextOffset : Math.min(state.messages.firstKey(), state.nextOffset))
                    : state.nextOffset;
            append(new LedgerEntry.Subscribe(group, from), null);
            subscribed.add(group);
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
        List<Leased> got;
        lockForAppend();
        try {
            got = pollLocked(group, consumer, max, visibilityMs);
        } finally {
            lock.unlock();
        }
        return toDeliveries(got);
    }

    /**
     * Blocking poll: as {@link #poll(String, String, int, long)}, but when nothing is deliverable it parks (no spin)
     * for up to {@code waitNanos} until a message is appended, or until the group's earliest redelivery is due.
     * Returns early with what it got; an empty list means the wait elapsed (or the partition closed / the thread
     * was interrupted, with the interrupt flag kept). Wakeups come from the append path and cost nothing when no
     * caller is waiting.
     */
    public List<Delivery> poll(String group, String consumer, int max, long visibilityMs, long waitNanos) {
        return toDeliveries(pollWaiting(group, consumer, max, visibilityMs, waitNanos));
    }

    private List<Leased> pollWaiting(String group, String consumer, int max, long visibilityMs, long waitNanos) {
        if (max < 1 || visibilityMs < 1 || waitNanos < 0) throw new IllegalArgumentException("max/visibility/wait");
        long deadline = System.nanoTime() + waitNanos;
        lockForAppend();
        try {
            while (true) {
                var out = pollLocked(group, consumer, max, visibilityMs);
                if (!out.isEmpty() || closed) return out;
                long left = deadline - System.nanoTime();
                if (left <= 0) return out;
                var g = state.group(group);
                long firstDue = g.due.isEmpty() ? Long.MAX_VALUE : g.due.first().atMs();
                long wait = left;
                if (firstDue != Long.MAX_VALUE) {
                    long ms = Math.max(1, firstDue - clockMs.getAsLong());
                    wait = Math.min(wait, ms * 1_000_000L);
                }
                var w = waiters.get(group);
                if (w == null) { w = new Waiters(lock.newCondition()); waiters.put(group, w); }
                w.count++;
                if (firstDue < w.sleepUntilDueMs) w.sleepUntilDueMs = firstDue;
                try {
                    w.cond.awaitNanos(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return out;
                } finally {
                    if (--w.count == 0) waiters.remove(group);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Lock held. Waiters of one group: blocked in {@link #poll(String, String, int, long, long)}. */
    private static final class Waiters {
        final java.util.concurrent.locks.Condition cond;
        int count;
        /** Earliest redelivery due time any current waiter planned to wake at (MAX = none). */
        long sleepUntilDueMs = Long.MAX_VALUE;
        Waiters(java.util.concurrent.locks.Condition c) { cond = c; }
    }

    private final java.util.HashMap<String, Waiters> waiters = new java.util.HashMap<>();
    private volatile boolean closed;

    /** Lock held, after {@code e} was applied, only when someone waits. */
    private void wake(LedgerEntry e) {
        switch (e) {
            case LedgerEntry.Enqueue x -> { for (var w : waiters.values()) w.cond.signal(); } // one waiter per group per message
            case LedgerEntry.Deliver x -> dueChanged(x.group(), x.deadlineMs());
            case LedgerEntry.Nack x -> dueChanged(x.group(), x.notBeforeMs());
            case LedgerEntry.Extend x -> dueChanged(x.group(), x.deadlineMs());
            default -> { }
        }
    }

    /** A redelivery may now be due at or after {@code atMs}: wake the group's waiters if they sleep past it. */
    private void dueChanged(String group, long atMs) {
        var w = waiters.get(group);
        if (w != null && atMs < w.sleepUntilDueMs) {
            w.sleepUntilDueMs = atMs;
            w.cond.signalAll();
        }
    }

    /**
     * A lease taken under the lock; the {@link Delivery} (and its payload copy) is built after unlocking. The
     * payload array is the cached or decoded one, never mutated, so reading it off-lock is safe.
     */
    private record Leased(long offset, int attempt, long deadline, DeliveryState.Message m, byte[] payload) { }

    private static List<Delivery> toDeliveries(List<Leased> got) {
        if (got.isEmpty()) return List.of();
        var out = new ArrayList<Delivery>(got.size());
        for (var l : got) {
            var m = l.m();
            out.add(new Delivery(l.offset(), l.attempt(), l.deadline(), m.producerId(), m.seq(), m.timestampMs(),
                    l.payload(), m.originGroup(), m.originOffset(), m.originAttempts(), m.originReason()));
        }
        return out;
    }

    /**
     * Lock held. Counts/snapshot publication runs once at the end of the poll instead of once per lease
     * ({@link #deferPublish}), which is what bounds the lock hold time of a multi-message claim.
     */
    private List<Leased> pollLocked(String group, String consumer, int max, long visibilityMs) {
        var g = state.group(group);
        // Empty poll (a spinning or woken consumer finding nothing): no allocation at all.
        if ((g.due.isEmpty() || g.due.first().atMs() > clockMs.getAsLong())
                && (state.messages.isEmpty() || state.messages.lastKey() < g.cursor)) return List.of();
        long now = clockMs.getAsLong();
        var out = new ArrayList<Leased>(Math.min(max, 16));
        var policy = state.policy();
        long before = state.nextOffset;
        deferPublish = true;
        try {
            // Due redeliveries in due order. Each step removes the head (redelivered or dead-lettered), so this
            // reads only what it uses instead of materialising every due entry per poll.
            while (out.size() < max && !g.due.isEmpty()) {
                var d = g.due.first();
                if (d.atMs() > now) break;
                long o = d.offset();
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
        } finally {
            deferPublish = false;
            if (state.nextOffset != before) afterApply();
        }
        return out;
    }

    private Leased deliver(String group, long o, String consumer, long deadline, int attempt) {
        append(new LedgerEntry.Deliver(group, o, consumer, deadline, attempt), null);
        return new Leased(o, attempt, deadline, state.messages.get(o), payloadOf(o));
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
        lockForAppend();
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
        lockForAppend();
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
        lockForAppend();
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
        lockForAppend();
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

    /**
     * Heartbeat: moves the lease of {@code offset} (held under {@code attempt}) to {@code now + visibilityMs}.
     * Appends one EXTEND record. The new deadline may be earlier than the current one (shortening is allowed).
     * With a {@link CancelProbe} installed and reporting the offset cancelled, returns {@link LeaseResult#CANCELLED}
     * after the fence ({@code NOT_IN_FLIGHT}/{@code STALE_ATTEMPT} win) and writes nothing: the lease keeps its
     * current deadline.
     */
    public LeaseResult extend(String group, long offset, int attempt, long visibilityMs) {
        if (visibilityMs < 1) throw new IllegalArgumentException("visibility");
        lockForAppend();
        try {
            var r = fence(state.group(group).inFlight.get(offset), attempt);
            if (r != null) return r;
            var probe = cancelProbe;
            if (probe != null && probe.isCancelled(group, offset)) return LeaseResult.CANCELLED; // not extended, not released
            append(new LedgerEntry.Extend(group, clockMs.getAsLong() + visibilityMs, new long[] {offset}, new int[] {attempt}), null);
            return LeaseResult.OK;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Batched heartbeat (one worker, many leases): every lease that is still held gets {@code now + visibilityMs},
     * in a single EXTEND record. Returns one result per input, in order; refused leases are not written.
     */
    public List<LeaseResult> extend(String group, long[] offsets, int[] attempts, long visibilityMs) {
        if (visibilityMs < 1 || offsets.length != attempts.length) throw new IllegalArgumentException("extend");
        var out = new ArrayList<LeaseResult>(offsets.length);
        lockForAppend();
        try {
            var g = state.group(group);
            var okOff = new long[offsets.length];
            var okAtt = new int[offsets.length];
            int n = 0;
            var probe = cancelProbe;
            for (int i = 0; i < offsets.length; i++) { // a duplicate offset is written twice; applying it is idempotent
                var r = fence(g.inFlight.get(offsets[i]), attempts[i]);
                if (r == null && probe != null && probe.isCancelled(group, offsets[i])) r = LeaseResult.CANCELLED;
                if (r == null) { okOff[n] = offsets[i]; okAtt[n] = attempts[i]; n++; r = LeaseResult.OK; }
                out.add(r);
            }
            if (n > 0) append(new LedgerEntry.Extend(group, clockMs.getAsLong() + visibilityMs,
                    java.util.Arrays.copyOf(okOff, n), java.util.Arrays.copyOf(okAtt, n)), null);
            return out;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Batched fenced ack (one worker settling many leases): every lease still held under its attempt is acked,
     * all in one ledger write (one writer hand-off, one durability wait). Returns one result per input, in order,
     * and the batch's durability future (null when nothing was acked). A duplicate offset acks once; the
     * second copy reports NOT_IN_FLIGHT, exactly as two single acks would.
     */
    public BatchAckResult ack(String group, long[] offsets, int[] attempts, Durability durability) {
        if (offsets.length != attempts.length) throw new IllegalArgumentException("ack");
        var out = new LeaseResult[offsets.length];
        lockForAppend();
        try {
            var g = state.group(group);
            var entries = new ArrayList<LedgerEntry>(offsets.length);
            var accepted = new long[offsets.length];
            java.util.HashSet<Long> seen = offsets.length > 32 ? new java.util.HashSet<>() : null;
            for (int i = 0; i < offsets.length; i++) {
                var r = fence(g.inFlight.get(offsets[i]), attempts[i]);
                if (r == null && (seen != null ? !seen.add(offsets[i]) : contains(accepted, entries.size(), offsets[i]))) {
                    r = LeaseResult.NOT_IN_FLIGHT; // already acked earlier in this batch
                }
                if (r == null) {
                    accepted[entries.size()] = offsets[i];
                    entries.add(new LedgerEntry.Ack(group, offsets[i]));
                    r = LeaseResult.OK;
                }
                out[i] = r;
            }
            return new BatchAckResult(List.of(out), entries.isEmpty() ? null : appendAll(entries, durability));
        } finally {
            lock.unlock();
        }
    }

    private static boolean contains(long[] a, int n, long v) {
        for (int j = 0; j < n; j++) if (a[j] == v) return true;
        return false;
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
        lockForAppend();
        try {
            if (state.hasOrigin(group, offset)) return CompletableFuture.completedFuture(-1L);
            return append(new LedgerEntry.Enqueue(0, 0, m.timestampMs(), group, offset, attempts, reason,
                    payload), null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes {@link #lock}, first waiting (unlocked) while the writer queue is at capacity. Appends under the lock use
     * {@link LedgerWriter#appendNoWait}, so a lock holder never blocks on the writer (S1c: a blocking queue.put under
     * this lock stalled every produce, poll and ack of the partition). Reentrant calls skip the wait.
     */
    private void lockForAppend() {
        if (!lock.isHeldByCurrentThread()) {
            try {
                appender.awaitCapacity();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", ie);
            }
        }
        lock.lock();
    }

    /**
     * Appends {@code e} and applies it. Called with the lock held, so the writer queue (and hence log
     * order) matches apply order.
     */
    private CompletableFuture<Long> append(LedgerEntry e, Durability durability) {
        return appendEncoded(e, List.of(LedgerEntry.encode(e)), durability);
    }

    /** As {@link #append}, for several entries written as one batch; the future yields the first offset. */
    private CompletableFuture<Long> appendAll(List<LedgerEntry> es, Durability durability) {
        List<byte[]> enc;
        if (es.size() == 1) enc = List.of(LedgerEntry.encode(es.get(0)));
        else { enc = new ArrayList<>(es.size()); for (var e : es) enc.add(LedgerEntry.encode(e)); }
        return appendEncodedAll(es, enc, durability);
    }

    /** Lock held. Single entry with pre-encoded bytes (encode done outside the lock on the produce path). */
    private CompletableFuture<Long> appendEncoded(LedgerEntry e, List<byte[]> enc, Durability durability) {
        return appendEncodedAll(List.of(e), enc, durability);
    }

    /**
     * Lock held. Queue hand-off still happens here so log order matches apply order; produce has already done
     * allocation/encode outside. Wake stays under the lock ({@link java.util.concurrent.locks.Condition} requires it).
     */
    private CompletableFuture<Long> appendEncodedAll(List<LedgerEntry> es, List<byte[]> enc, Durability durability) {
        long offset = state.nextOffset;
        var f = appender.appendNoWait(enc, durability); // never blocks: capacity was awaited before the lock
        if (f.isCompletedExceptionally()) {
            f.join(); // rethrows: the writer is fenced; nothing was applied
        }
        for (int i = 0; i < es.size(); i++) {
            var e = es.get(i);
            if (e instanceof LedgerEntry.Enqueue x) cachePayload(offset + i, x.payloadRef());
            state.apply(offset + i, e);
            if (!waiters.isEmpty()) wake(e);
        }
        if (!deferPublish) afterApply();
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

    /** Lock held. Evicts cached payloads below every group's committed offset (only written ones, as above). */
    private void evictSettled() {
        long below = state.minCommitted();
        int mask = ringOff.length - 1;
        while (ringCount > 0 && ringOff[ringHead] < below) {
            long ho = ringOff[ringHead];
            if (ho >= writtenHint) {
                writtenHint = writer.snapshot().nextOffset();
                if (ho >= writtenHint) return;
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
        lockForAppend();
        try { return snapshotLocked(); } finally { lock.unlock(); }
    }

    private CompletableFuture<Long> snapshotLocked() {
        long off = state.nextOffset;
        lastSnapshotTrigger = off;
        if (snapshots.contains(off)) return CompletableFuture.completedFuture(off);
        // Nothing is serialized under the lock: the snapshotter rebuilds the state at `off` from the newest
        // snapshot file plus the log tail (deterministic replay) and serializes that private copy.
        var prev = lastSnapshot;
        // A small state (the common request/reply case: almost nothing live) is cheaper to serialize here than
        // to rebuild by replaying the whole tail off-lock, which allocates about as much as the live appends did.
        if (state.serializedEntries() <= INLINE_SNAPSHOT_ENTRIES) {
            var data = state.serialize();
            lastSnapshot = prev.handleAsync((v, t) -> {
                awaitDurable(off);
                writeSnapshot(off, data);
                return off;
            }, snapshotter);
            return lastSnapshot;
        }
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
    /** Snapshots of at most this many serialized entries are taken under the lock (bounded, ~100 µs). */
    static final long INLINE_SNAPSHOT_ENTRIES = 16_384;

    /** Snapshotter thread. A snapshot is only valid once the log is durable through {@code off}: wait for the writer, then fsync. */
    private void awaitDurable(long off) {
        try {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (writer.snapshot().nextOffset() < off) {
                if (System.nanoTime() > deadline) throw new IOException("log never reached snapshot offset " + off);
                LockSupport.parkNanos(100_000);
            }
            log.force();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private byte[] rebuildAt(long off) {
        try {
            awaitDurable(off);
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
        lockForAppend();
        try {
            lw = Math.min(Math.min(oldestSnap, state.oldestNeededMessageOffset()), retainFloor());
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
                if (seg.active() || seg.nextOffset() > Math.min(oldestSnap, retainFloor())) break; // never rewrite pinned history
                java.util.Set<Long> keep;
                long liveBytes = 0;
                lockForAppend();
                try {
                    keep = new java.util.HashSet<>(state.messages.subMap(seg.baseOffset(), true, seg.nextOffset(), false).keySet());
                    for (long k : keep) liveBytes += state.messages.get(k).size() + RecordBatch.HEADER_BYTES + 12 + 4;
                } finally {
                    lock.unlock();
                }
                if (liveBytes >= seg.bytes() * pol.compactLiveRatio()) continue;
                long newSize = log.prepareCompaction(seg.baseOffset(), keep::contains);
                lockForAppend();
                try { log.commitCompaction(seg.baseOffset()); } finally { lock.unlock(); }
                compacted++;
                reclaimed += seg.bytes() - newSize;
            }
        }
        long pinned = 0;
        for (var seg : log.segments()) if (!seg.active()) pinned += seg.bytes();
        return new MaintenanceReport(lw, deleted, compacted, reclaimed, pinned, dlq == null ? null : dlq.maintain());
    }

    // ---- retain-from pins (agent-graphs.md §4.3 / §11.3) ----

    /**
     * Pins this partition's history from {@code offset} for {@code owner} (for example a graph runner's checkpoint
     * floor): {@link #maintain()} never deletes or compacts at or above the lowest pin, whatever the delivery state or
     * the {@link RetentionPolicy} says. Setting the same owner again moves its pin (forward or back). Pins are durable:
     * written to {@value #PINS_FILE} (temp file, fsync, atomic rename) before this returns, and reloaded on open, so a
     * restart cannot free history the owner has not re-pinned yet. Below the pins the normal low-watermark rules apply.
     *
     * @throws IllegalArgumentException for a blank owner or a negative offset
     */
    public void retainFrom(String owner, long offset) throws IOException {
        if (owner == null || owner.isBlank() || owner.indexOf('\n') >= 0 || owner.indexOf('=') >= 0) throw new IllegalArgumentException("pin owner");
        if (offset < 0) throw new IllegalArgumentException("pin offset < 0");
        updatePins(m -> m.put(owner, offset));
    }

    /** Drops {@code owner}'s pin, if any. Durable like {@link #retainFrom}. */
    public void releaseRetain(String owner) throws IOException {
        updatePins(m -> m.remove(owner));
    }

    /** The lowest pin, or {@link Long#MAX_VALUE} without pins. */
    public long retainFloor() {
        long f = Long.MAX_VALUE;
        for (long v : this.pins.values()) f = Math.min(f, v);
        return f;
    }

    /** Current pins by owner (immutable). */
    public Map<String, Long> retainPins() {
        return this.pins;
    }

    private void updatePins(java.util.function.Consumer<java.util.Map<String, Long>> change) throws IOException {
        this.pinLock.lock();
        try {
            var next = new java.util.TreeMap<String, Long>(this.pins);
            change.accept(next);
            if (next.equals(this.pins)) return;
            var text = new StringBuilder();
            for (var e : next.entrySet()) text.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            var tmp = this.logDir.resolve(PINS_FILE + ".tmp");
            try (var ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                ch.force(true);
            }
            Files.move(tmp, this.logDir.resolve(PINS_FILE), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            this.pins = Map.copyOf(next); // published only once durable
        } finally {
            this.pinLock.unlock();
        }
    }

    static Map<String, Long> readPins(Path logDir) throws IOException {
        var f = logDir.resolve(PINS_FILE);
        if (!Files.exists(f)) return Map.of();
        var out = new java.util.HashMap<String, Long>();
        for (var line : Files.readAllLines(f, java.nio.charset.StandardCharsets.UTF_8)) {
            int eq = line.lastIndexOf('=');
            if (line.isBlank()) continue;
            if (eq <= 0) throw new IOException("corrupt " + f + ": " + line);
            try {
                out.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1)));
            } catch (NumberFormatException e) {
                throw new IOException("corrupt " + f + ": " + line, e);
            }
        }
        return Map.copyOf(out);
    }

    /** Bytes currently on disk for this partition's log (excluding the DLQ). */
    public long logBytes() { return log.totalBytes(); }

    // ---- inspection ----

    /** Exact counts as of the last applied entry. Lock-free, never stale. */
    /**
     * Exact counts as of the last applied entry. Computed on first read after a change (an append only marks them
     * stale, so the hot path allocates nothing for them); a stale read takes the lock briefly.
     */
    public DeliveryCounts counts() {
        var c = counts.get();
        if (c != null) return c;
        lockForAppend();
        try {
            c = counts.get();
            if (c == null) { c = state.counts(); counts.set(c); }
            return c;
        } finally {
            lock.unlock();
        }
    }

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
        lockForAppend();
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
    public void eagerSnapshots(boolean on) { lockForAppend(); try { eager = on; publish(); } finally { lock.unlock(); } }

    /** Groups known to exist (lock-free hint for {@link #subscribe}); only ever grows, like the groups themselves. */
    private final java.util.Set<String> subscribed = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Lock held. Set while a poll appends several DELIVER records; it publishes once at the end. */
    private boolean deferPublish;

    /** Lock held: after one apply (or a deferred batch of them). */
    private void afterApply() {
        // Payloads every group has settled are dropped from the cache at once instead of aging out of the 64 MiB
        // ring: in request/reply they would otherwise all survive young GCs and be copied into the old generation.
        if (ringCount > 0 && ringOff[ringHead] < state.minCommitted()) evictSettled();
        publish();
        // Amortized O(1) per entry: a snapshot costs O(live state), so wait at least twice that many entries.
        long since = state.nextOffset - lastSnapshotTrigger;
        if (since >= snapshotEveryEntries && since >= 2L * state.messages.size()) snapshotLocked();
    }

    private void publish() {
        counts.setRelease(null); // stale: rebuilt lazily by counts()
        if (eager || snapshot.get() == null) rebuild();
    }

    private DeliverySnapshot rebuild() {
        var s = state.snapshot(maxItemsPerList);
        snapshot.set(s);
        lastRebuildNanos = System.nanoTime();
        return s;
    }

    /** Canonical state dump (tests). */
    String canonicalState() { lockForAppend(); try { return state.canonical(); } finally { lock.unlock(); } }

    @Override public void close() throws IOException {
        lockForAppend();
        try {
            closed = true;
            for (var w : waiters.values()) w.cond.signalAll();
        } finally {
            lock.unlock();
        }
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
