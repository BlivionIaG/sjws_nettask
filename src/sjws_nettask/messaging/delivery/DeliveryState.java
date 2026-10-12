package sjws_nettask.messaging.delivery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic delivery state machine for one partition: {@link #apply} is a pure function of the
 * current state and one {@link LedgerEntry} (no clock, no I/O), so {@code replay(log)} reproduces the
 * state exactly. Mutable and single-threaded: owned by {@link Partition} under its lock. Validation
 * ({@code can*}) is separate from application so the partition logs only valid transitions.
 */
final class DeliveryState {

    record Message(long offset, long producerId, long seq, long timestampMs, byte[] payload, String originGroup,
            long originOffset, int originAttempts, String originReason) { }

    record Lease(String consumer, long deadlineMs, int attempt) { }

    record Retry(long notBeforeMs, int attempts) { }

    record Dead(String reason, int attempts) { }

    /** Ordering key for "becomes eligible for (re)delivery at". */
    record Due(long atMs, long offset) implements Comparable<Due> {
        @Override public int compareTo(Due o) {
            int c = Long.compare(atMs, o.atMs);
            return c != 0 ? c : Long.compare(offset, o.offset);
        }
    }

    static final int DEDUP_WINDOW = 4096;

    final class Group {
        final String name;
        final long startOffset;
        long cursor;          // next never-delivered offset
        long freshCount;      // messages ≥ cursor
        long committedOffset; // every message below is ACKED or DEAD_LETTERED
        long acked, deadLettered, redeliveries;
        final TreeMap<Long, Lease> inFlight = new TreeMap<>();
        final TreeMap<Long, Retry> retry = new TreeMap<>();
        final TreeMap<Long, Dead> dead = new TreeMap<>();
        final TreeSet<Long> resolvedAbove = new TreeSet<>();
        final TreeSet<Due> due = new TreeSet<>();

        Group(String name, long from) {
            this.name = name;
            this.startOffset = from;
            this.cursor = from;
            this.committedOffset = from;
            this.freshCount = messages.tailMap(from, true).size();
        }

        Due leaseDue(long offset, Lease l) { return new Due(l.deadlineMs + policy.backoffMs(l.attempt), offset); }

        boolean resolved(long offset) { return offset < committedOffset || resolvedAbove.contains(offset); }

        void resolve(long offset) {
            resolvedAbove.add(offset);
            while (true) {
                Long k = messages.ceilingKey(committedOffset);
                if (k == null) { committedOffset = Math.max(committedOffset, nextOffset); break; }
                if (!resolvedAbove.remove(k)) { committedOffset = k; break; }
                committedOffset = k + 1;
            }
            releaseIfDone(offset);
        }
    }

    private final RetryPolicy policy;
    final TreeMap<Long, Message> messages = new TreeMap<>();
    final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
    /** producerId → (seq → offset) window, plus the highest seq seen. */
    final Map<Long, LinkedHashMap<Long, Long>> dedup = new HashMap<>();
    final Map<Long, Long> highestSeq = new HashMap<>();
    /** DLQ topics: "group@offset" of every origin message already copied in. */
    final Set<String> origins = new HashSet<>();
    long nextOffset;
    long enqueued;
    /** Read without the lock by {@link Partition#snapshot()} to detect staleness. */
    volatile long version;

    DeliveryState(RetryPolicy policy) { this.policy = policy; }

    RetryPolicy policy() { return policy; }

    // ---- validation (no mutation) ----

    /** Returns the offset of an earlier identical (producerId, seq), -2 for a too-old seq, or -1 if new. */
    long duplicateOf(long producerId, long seq) {
        if (producerId == 0) return -1;
        var w = dedup.get(producerId);
        if (w != null) { Long o = w.get(seq); if (o != null) return o; }
        Long hi = highestSeq.get(producerId);
        if (hi != null && seq <= hi - DEDUP_WINDOW) return -2;
        return -1;
    }

    boolean hasOrigin(String group, long offset) { return origins.contains(group + "@" + offset); }

    // ---- application ----

    void apply(long offset, LedgerEntry e) {
        if (offset != nextOffset) throw new IllegalStateException("gap: expected " + nextOffset + " got " + offset);
        switch (e) {
            case LedgerEntry.Enqueue x -> {
                messages.put(offset, new Message(offset, x.producerId(), x.seq(), x.timestampMs(), x.payloadRef(),
                        x.originGroup(), x.originOffset(), x.originAttempts(), x.originReason()));
                enqueued++;
                for (var g : groups.values()) g.freshCount++;
                if (x.producerId() != 0) {
                    var w = dedup.get(x.producerId());
                    if (w == null) { w = new LinkedHashMap<>(); dedup.put(x.producerId(), w); }
                    w.put(x.seq(), offset);
                    if (w.size() > DEDUP_WINDOW) w.remove(w.keySet().iterator().next());
                    highestSeq.merge(x.producerId(), x.seq(), Math::max);
                }
                if (!x.originGroup().isEmpty()) origins.add(x.originGroup() + "@" + x.originOffset());
            }
            case LedgerEntry.Subscribe x -> {
                if (groups.containsKey(x.group())) throw new IllegalStateException("group exists: " + x.group());
                groups.put(x.group(), new Group(x.group(), x.fromOffset()));
            }
            case LedgerEntry.Deliver x -> {
                var g = group(x.group());
                long o = x.offset();
                var lease = new Lease(x.consumer(), x.deadlineMs(), x.attempt());
                if (x.attempt() == 1) {
                    if (o < g.cursor || !messages.containsKey(o)) throw new IllegalStateException("not fresh: " + o);
                    // Deliveries of fresh messages are in offset order; skip resolved/expired ones in between.
                    g.freshCount -= messages.subMap(g.cursor, true, o, true).size();
                    g.cursor = o + 1;
                } else {
                    var old = g.inFlight.remove(o);
                    if (old != null) g.due.remove(g.leaseDue(o, old));
                    else {
                        var r = Objects.requireNonNull(g.retry.remove(o), "not redeliverable");
                        g.due.remove(new Due(r.notBeforeMs, o));
                    }
                    g.redeliveries++;
                }
                g.inFlight.put(o, lease);
                g.due.add(g.leaseDue(o, lease));
            }
            case LedgerEntry.Ack x -> {
                var g = group(x.group());
                var l = Objects.requireNonNull(g.inFlight.remove(x.offset()), "not in flight");
                g.due.remove(g.leaseDue(x.offset(), l));
                g.acked++;
                g.resolve(x.offset());
            }
            case LedgerEntry.Nack x -> {
                var g = group(x.group());
                var l = Objects.requireNonNull(g.inFlight.remove(x.offset()), "not in flight");
                g.due.remove(g.leaseDue(x.offset(), l));
                g.retry.put(x.offset(), new Retry(x.notBeforeMs(), l.attempt));
                g.due.add(new Due(x.notBeforeMs(), x.offset()));
            }
            case LedgerEntry.DeadLetter x -> {
                var g = group(x.group());
                long o = x.offset();
                int attempts;
                var l = g.inFlight.remove(o);
                if (l != null) { g.due.remove(g.leaseDue(o, l)); attempts = l.attempt; }
                else {
                    var r = g.retry.remove(o);
                    if (r != null) { g.due.remove(new Due(r.notBeforeMs, o)); attempts = r.attempts; }
                    else {
                        if (o < g.cursor || !messages.containsKey(o)) throw new IllegalStateException("not dead-letterable: " + o);
                        attempts = 0; // poison message rejected before delivery: never happens via Partition today
                        g.freshCount--;
                        if (o == g.cursor) g.cursor = o + 1; else throw new IllegalStateException("out-of-order DLQ of fresh " + o);
                    }
                }
                g.dead.put(o, new Dead(x.reason(), attempts));
                g.deadLettered++;
                g.resolve(o);
            }
        }
        nextOffset = offset + 1;
        version++;
    }

    Group group(String name) {
        var g = groups.get(name);
        if (g == null) throw new IllegalStateException("unknown group " + name);
        return g;
    }

    /** Frees a message's payload once every group has resolved it (only when at least one group exists). */
    private void releaseIfDone(long offset) {
        for (var g : groups.values()) if (!g.resolved(offset) || g.dead.containsKey(offset)) return;
        messages.remove(offset);
        for (var g : groups.values()) g.resolvedAbove.remove(offset);
    }

    /** Candidates for delivery at {@code nowMs}: due redeliveries first (by due time), then fresh in offset order. */
    List<Long> dueRedeliveries(Group g, long nowMs, int max) {
        var out = new ArrayList<Long>();
        for (var d : g.due) {
            if (d.atMs > nowMs || out.size() >= max) break;
            out.add(d.offset);
        }
        return out;
    }

    // ---- snapshot ----

    DeliverySnapshot snapshot() {
        var gs = new LinkedHashMap<String, DeliverySnapshot.GroupView>();
        for (var g : groups.values()) {
            var enq = new ArrayList<DeliverySnapshot.Item>();
            for (var r : g.retry.entrySet()) enq.add(item(r.getKey(), MessageState.ENQUEUED, r.getValue().attempts, "", r.getValue().notBeforeMs, ""));
            for (var m : messages.tailMap(g.cursor, true).values()) enq.add(item(m.offset, MessageState.ENQUEUED, 0, "", 0, ""));
            enq.sort(null);
            var inf = new ArrayList<DeliverySnapshot.Item>();
            for (var e : g.inFlight.entrySet()) {
                var l = e.getValue();
                inf.add(item(e.getKey(), MessageState.IN_FLIGHT, l.attempt, l.consumer, l.deadlineMs, ""));
            }
            var dl = new ArrayList<DeliverySnapshot.Item>();
            for (var e : g.dead.entrySet()) dl.add(item(e.getKey(), MessageState.DEAD_LETTERED, e.getValue().attempts, "", 0, e.getValue().reason));
            gs.put(g.name, new DeliverySnapshot.GroupView(g.name, g.startOffset, g.committedOffset,
                    new DeliverySnapshot.Counts(enq.size(), inf.size(), g.acked, g.deadLettered, g.redeliveries),
                    List.copyOf(enq), List.copyOf(inf), List.copyOf(dl)));
        }
        return new DeliverySnapshot(version, nextOffset, enqueued, messages.size(), gs);
    }

    private DeliverySnapshot.Item item(long offset, MessageState s, int attempts, String consumer, long timeMs, String reason) {
        var m = messages.get(offset);
        return new DeliverySnapshot.Item(offset, s, attempts, consumer, timeMs, m == null ? 0 : m.timestampMs,
                m == null ? 0 : m.payload.length, reason);
    }

    /** Canonical, comparable dump of the full state (tests: replay must reproduce it exactly). */
    String canonical() {
        var sb = new StringBuilder();
        sb.append("next=").append(nextOffset).append(" enq=").append(enqueued).append('\n');
        for (var m : messages.values())
            sb.append("m ").append(m.offset).append(' ').append(m.producerId).append(':').append(m.seq).append(' ')
              .append(java.util.Arrays.hashCode(m.payload)).append(' ').append(m.originGroup).append('@').append(m.originOffset).append('\n');
        for (var g : groups.values()) {
            sb.append("g ").append(g.name).append(" start=").append(g.startOffset).append(" cur=").append(g.cursor)
              .append(" fresh=").append(g.freshCount).append(" commit=").append(g.committedOffset)
              .append(" acked=").append(g.acked).append(" dl=").append(g.deadLettered).append(" re=").append(g.redeliveries)
              .append("\n  inflight=").append(g.inFlight).append("\n  retry=").append(g.retry)
              .append("\n  dead=").append(g.dead).append("\n  resolved=").append(g.resolvedAbove).append("\n  due=").append(g.due).append('\n');
        }
        sb.append("dedup=").append(new TreeMap<>(highestSeq)).append(" origins=").append(new TreeSet<>(origins));
        return sb.toString();
    }
}
