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

    /** Message metadata only: the payload stays in the log segment at {@code offset} (step 3). */
    record Message(long offset, long producerId, long seq, long timestampMs, int size, String originGroup,
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

    record RemoteKey(int origin, long producerId) { }

    /** One forwarded producer: seqs are applied strictly in order 1, 2, 3, ... so the window is contiguous. */
    static final class RemoteProducer {
        final LinkedHashMap<Long, Long> window = new LinkedHashMap<>();
        long highest;
        long lastMs;
    }

    /** {@link #checkRemote} results below zero; {@code >= 0} is the offset of the earlier identical write. */
    static final long REMOTE_NEW = -1, REMOTE_TOO_OLD = -2, REMOTE_OUT_OF_ORDER = -3, REMOTE_BUSY = -4;

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
    /**
     * Forwarded producers (#236): {@code (origin wire, producerId)} → seq window. Kept apart from {@link #dedup} so a
     * peer reusing another node's producerId lands under its own key, and so the local produce path is unchanged.
     * Empty unless something calls {@link Partition#produceFrom}.
     */
    final Map<RemoteKey, RemoteProducer> remote = new HashMap<>();
    /** Live forwarded producers per origin wire, for the per-peer cap. */
    final Map<Integer, Integer> remoteLive = new HashMap<>();
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

    /**
     * Classifies a forwarded {@code (origin, producerId, seq)}: the earlier offset for a seq still in the window, or
     * {@link #REMOTE_NEW} for exactly the next seq (1 for an unknown producer), {@link #REMOTE_TOO_OLD} for one that
     * fell out of the 4096 window, {@link #REMOTE_OUT_OF_ORDER} for anything later than the next seq (the expected
     * seq is {@link #expectedSeq}), and {@link #REMOTE_BUSY} for a new producer while {@code origin} already has
     * {@code maxLive} on this partition and none has been idle {@code idleMs} (an idle one is evicted to make room;
     * eviction is in memory only, so replay may bring it back until the cap is hit again).
     */
    long checkRemote(int origin, long producerId, long seq, long nowMs, int maxLive, long idleMs) {
        var p = remote.get(new RemoteKey(origin, producerId));
        if (p == null) {
            if (seq != 1) return REMOTE_OUT_OF_ORDER;
            if (remoteLive.getOrDefault(origin, 0) >= maxLive && !evictIdle(origin, nowMs, idleMs)) return REMOTE_BUSY;
            return REMOTE_NEW;
        }
        if (seq == p.highest + 1) return REMOTE_NEW;
        if (seq > p.highest) return REMOTE_OUT_OF_ORDER;
        Long o = p.window.get(seq);
        return o != null ? o : REMOTE_TOO_OLD;
    }

    /** The seq a forwarded producer must send next: 1 when unknown. */
    long expectedSeq(int origin, long producerId) {
        var p = remote.get(new RemoteKey(origin, producerId));
        return p == null ? 1 : p.highest + 1;
    }

    private boolean evictIdle(int origin, long nowMs, long idleMs) {
        RemoteKey oldest = null;
        long oldestMs = Long.MAX_VALUE;
        for (var e : remote.entrySet()) {
            if (e.getKey().origin() == origin && e.getValue().lastMs < oldestMs) {
                oldest = e.getKey();
                oldestMs = e.getValue().lastMs;
            }
        }
        if (oldest == null || nowMs - oldestMs < idleMs) return false;
        remote.remove(oldest);
        remoteLive.merge(origin, -1, Integer::sum);
        return true;
    }

    boolean hasOrigin(String group, long offset) { return origins.contains(group + "@" + offset); }

    private void applyRemote(LedgerEntry.Enqueue x, long offset) {
        var key = new RemoteKey(x.origin(), x.producerId());
        var p = remote.get(key);
        if (p == null) {
            p = new RemoteProducer();
            remote.put(key, p);
            remoteLive.merge(x.origin(), 1, Integer::sum);
        }
        p.window.put(x.seq(), offset);
        if (p.window.size() > DEDUP_WINDOW) p.window.remove(p.window.keySet().iterator().next());
        p.highest = Math.max(p.highest, x.seq());
        p.lastMs = Math.max(p.lastMs, x.timestampMs());
    }

    // ---- application ----

    void apply(long offset, LedgerEntry e) {
        if (offset != nextOffset) throw new IllegalStateException("gap: expected " + nextOffset + " got " + offset);
        switch (e) {
            case LedgerEntry.Enqueue x -> {
                messages.put(offset, new Message(offset, x.producerId(), x.seq(), x.timestampMs(), x.payloadRef().length,
                        x.originGroup(), x.originOffset(), x.originAttempts(), x.originReason()));
                enqueued++;
                for (var g : groups.values()) g.freshCount++;
                if (x.origin() != 0) {
                    applyRemote(x, offset);
                } else if (x.producerId() != 0) {
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
            case LedgerEntry.Extend x -> {
                var g = group(x.group());
                for (int i = 0; i < x.offsets().length; i++) {
                    long o = x.offsets()[i];
                    var l = Objects.requireNonNull(g.inFlight.get(o), "extend: not in flight");
                    if (l.attempt != x.attempts()[i]) throw new IllegalStateException("extend: stale attempt at " + o);
                    g.due.remove(g.leaseDue(o, l));
                    var nl = new Lease(l.consumer, x.deadlineMs(), l.attempt);
                    g.inFlight.put(o, nl);
                    g.due.add(g.leaseDue(o, nl));
                }
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

    /** Forgets a message once every group has resolved it (only when at least one group exists); its segment can then be reclaimed. */
    private void releaseIfDone(long offset) {
        for (var g : groups.values()) if (!g.resolved(offset) || g.dead.containsKey(offset)) return;
        messages.remove(offset);
        for (var g : groups.values()) g.resolvedAbove.remove(offset);
    }

    /** Candidates for delivery at {@code nowMs}: due redeliveries first (by due time), then fresh in offset order. */
    // ---- snapshot ----

    DeliverySnapshot.Counts countsOf(Group g) {
        return new DeliverySnapshot.Counts(g.freshCount + g.retry.size(), g.inFlight.size(), g.acked, g.deadLettered, g.redeliveries);
    }

    /** Lowest committed offset over all groups (everything below is settled for every group); MIN_VALUE without groups. */
    long minCommitted() {
        if (groups.isEmpty()) return Long.MIN_VALUE;
        long m = Long.MAX_VALUE;
        for (var g : groups.values()) m = Math.min(m, g.committedOffset);
        return m;
    }

    DeliveryCounts counts() {
        var gs = new LinkedHashMap<String, DeliveryCounts.Group>();
        for (var g : groups.values()) {
            var oldest = messages.ceilingEntry(g.committedOffset);
            gs.put(g.name, new DeliveryCounts.Group(g.committedOffset, countsOf(g), oldest == null ? 0 : oldest.getValue().timestampMs()));
        }
        return new DeliveryCounts(version, nextOffset, enqueued, messages.size(), gs);
    }

    /** Full view with every list capped at {@code cap} items (lowest offsets first): bounded work under the lock. */
    DeliverySnapshot snapshot(int cap) {
        var gs = new LinkedHashMap<String, DeliverySnapshot.GroupView>();
        for (var g : groups.values()) {
            boolean truncated = false;
            var enq = new ArrayList<DeliverySnapshot.Item>();
            var ri = g.retry.entrySet().iterator();
            var fi = messages.tailMap(g.cursor, true).keySet().iterator();
            Map.Entry<Long, Retry> r = ri.hasNext() ? ri.next() : null;
            Long f = fi.hasNext() ? fi.next() : null;
            while (r != null || f != null) {
                if (enq.size() >= cap) { truncated = true; break; }
                if (f == null || (r != null && r.getKey() < f)) {
                    enq.add(item(r.getKey(), MessageState.ENQUEUED, r.getValue().attempts, "", r.getValue().notBeforeMs, ""));
                    r = ri.hasNext() ? ri.next() : null;
                } else {
                    enq.add(item(f, MessageState.ENQUEUED, 0, "", 0, ""));
                    f = fi.hasNext() ? fi.next() : null;
                }
            }
            var inf = new ArrayList<DeliverySnapshot.Item>();
            for (var e : g.inFlight.entrySet()) {
                if (inf.size() >= cap) { truncated = true; break; }
                var l = e.getValue();
                inf.add(item(e.getKey(), MessageState.IN_FLIGHT, l.attempt, l.consumer, l.deadlineMs, ""));
            }
            var dl = new ArrayList<DeliverySnapshot.Item>();
            for (var e : g.dead.entrySet()) {
                if (dl.size() >= cap) { truncated = true; break; }
                dl.add(item(e.getKey(), MessageState.DEAD_LETTERED, e.getValue().attempts, "", 0, e.getValue().reason));
            }
            gs.put(g.name, new DeliverySnapshot.GroupView(g.name, g.startOffset, g.committedOffset, countsOf(g),
                    List.copyOf(enq), List.copyOf(inf), List.copyOf(dl), truncated));
        }
        return new DeliverySnapshot(version, nextOffset, enqueued, messages.size(), gs);
    }

    private DeliverySnapshot.Item item(long offset, MessageState s, int attempts, String consumer, long timeMs, String reason) {
        var m = messages.get(offset);
        return new DeliverySnapshot.Item(offset, s, attempts, consumer, timeMs, m == null ? 0 : m.timestampMs,
                m == null ? 0 : m.size, reason);
    }

    /** Canonical, comparable dump of the full state (tests: replay must reproduce it exactly). */
    String canonical() {
        var sb = new StringBuilder();
        sb.append("next=").append(nextOffset).append(" enq=").append(enqueued).append('\n');
        for (var m : messages.values())
            sb.append("m ").append(m.offset).append(' ').append(m.producerId).append(':').append(m.seq).append(' ')
              .append(m.size).append(' ').append(m.originGroup).append('@').append(m.originOffset).append('\n');
        for (var g : groups.values()) {
            sb.append("g ").append(g.name).append(" start=").append(g.startOffset).append(" cur=").append(g.cursor)
              .append(" fresh=").append(g.freshCount).append(" commit=").append(g.committedOffset)
              .append(" acked=").append(g.acked).append(" dl=").append(g.deadLettered).append(" re=").append(g.redeliveries)
              .append("\n  inflight=").append(g.inFlight).append("\n  retry=").append(g.retry)
              .append("\n  dead=").append(g.dead).append("\n  resolved=").append(g.resolvedAbove).append("\n  due=").append(g.due).append('\n');
        }
        sb.append("dedup=").append(new TreeMap<>(highestSeq)).append(" origins=").append(new TreeSet<>(origins));
        if (!remote.isEmpty()) {
            var keys = new java.util.ArrayList<>(remote.keySet());
            keys.sort(java.util.Comparator.comparingInt(RemoteKey::origin).thenComparingLong(RemoteKey::producerId));
            sb.append(" remote=");
            for (var k : keys) {
                var p = remote.get(k);
                sb.append(k.origin()).append('/').append(k.producerId()).append(':').append(p.highest).append('@')
                  .append(p.lastMs).append(p.window.keySet()).append(' ');
            }
        }
        return sb.toString();
    }

    // ---- on-disk snapshot (step 3) ----

    static final int SNAP_MAGIC = 0x534a5353; // "SJSS"
    static final int SNAP_VERSION = 1;
    /** Written only when {@link #remote} is non-empty, so a partition never used for forwarding stays version 1. */
    static final int SNAP_VERSION_REMOTE = 2;

    /** Serializes the full state (no payloads) as of {@link #nextOffset}, with a trailing CRC32C. */
    /** Entries {@link #serialize} writes (messages, per-group lists, dedup windows, origins): its cost, O(groups + producers). */
    long serializedEntries() {
        long n = messages.size() + origins.size();
        for (var g : groups.values()) n += g.inFlight.size() + g.retry.size() + g.dead.size() + g.resolvedAbove.size() + g.due.size();
        for (var w : dedup.values()) n += w.size();
        for (var p : remote.values()) n += p.window.size();
        return n;
    }

    byte[] serialize() {
        try {
            var bo = new java.io.ByteArrayOutputStream(64 + messages.size() * 48);
            var o = new java.io.DataOutputStream(bo);
            o.writeInt(SNAP_MAGIC); o.writeInt(remote.isEmpty() ? SNAP_VERSION : SNAP_VERSION_REMOTE);
            o.writeLong(nextOffset); o.writeLong(enqueued); o.writeLong(version);
            o.writeInt(messages.size());
            for (var m : messages.values()) {
                o.writeLong(m.offset); o.writeLong(m.producerId); o.writeLong(m.seq); o.writeLong(m.timestampMs); o.writeInt(m.size);
                o.writeUTF(m.originGroup); o.writeLong(m.originOffset); o.writeInt(m.originAttempts); o.writeUTF(m.originReason);
            }
            o.writeInt(groups.size());
            for (var g : groups.values()) {
                o.writeUTF(g.name); o.writeLong(g.startOffset); o.writeLong(g.cursor); o.writeLong(g.freshCount);
                o.writeLong(g.committedOffset); o.writeLong(g.acked); o.writeLong(g.deadLettered); o.writeLong(g.redeliveries);
                o.writeInt(g.inFlight.size());
                for (var e : g.inFlight.entrySet()) { o.writeLong(e.getKey()); o.writeUTF(e.getValue().consumer()); o.writeLong(e.getValue().deadlineMs()); o.writeInt(e.getValue().attempt()); }
                o.writeInt(g.retry.size());
                for (var e : g.retry.entrySet()) { o.writeLong(e.getKey()); o.writeLong(e.getValue().notBeforeMs()); o.writeInt(e.getValue().attempts()); }
                o.writeInt(g.dead.size());
                for (var e : g.dead.entrySet()) { o.writeLong(e.getKey()); o.writeUTF(e.getValue().reason()); o.writeInt(e.getValue().attempts()); }
                o.writeInt(g.resolvedAbove.size());
                for (long x : g.resolvedAbove) o.writeLong(x);
                o.writeInt(g.due.size());
                for (var d : g.due) { o.writeLong(d.atMs()); o.writeLong(d.offset()); }
            }
            o.writeInt(dedup.size());
            for (var e : new TreeMap<>(dedup).entrySet()) {
                o.writeLong(e.getKey()); o.writeLong(highestSeq.get(e.getKey()));
                o.writeInt(e.getValue().size());
                for (var w : e.getValue().entrySet()) { o.writeLong(w.getKey()); o.writeLong(w.getValue()); }
            }
            o.writeInt(origins.size());
            for (var x : new TreeSet<>(origins)) o.writeUTF(x);
            if (!remote.isEmpty()) {
                o.writeInt(remote.size());
                var keys = new java.util.ArrayList<>(remote.keySet());
                keys.sort(java.util.Comparator.comparingInt(RemoteKey::origin).thenComparingLong(RemoteKey::producerId));
                for (var k : keys) {
                    var p = remote.get(k);
                    o.writeShort(k.origin()); o.writeLong(k.producerId()); o.writeLong(p.highest); o.writeLong(p.lastMs);
                    o.writeInt(p.window.size());
                    for (var w : p.window.entrySet()) { o.writeLong(w.getKey()); o.writeLong(w.getValue()); }
                }
            }
            o.flush();
            var body = bo.toByteArray();
            var c = new java.util.zip.CRC32C();
            c.update(body);
            return java.nio.ByteBuffer.allocate(body.length + 4).put(body).putInt((int) c.getValue()).array();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e); // ByteArrayOutputStream does not throw
        }
    }

    /** Inverse of {@link #serialize}; throws IllegalArgumentException on a bad CRC, magic or version. */
    static DeliveryState deserialize(byte[] raw, RetryPolicy policy) {
        if (raw.length < 12) throw new IllegalArgumentException("short snapshot");
        var c = new java.util.zip.CRC32C();
        c.update(raw, 0, raw.length - 4);
        if ((int) c.getValue() != java.nio.ByteBuffer.wrap(raw, raw.length - 4, 4).getInt()) throw new IllegalArgumentException("snapshot crc");
        try {
            var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw, 0, raw.length - 4));
            if (in.readInt() != SNAP_MAGIC) throw new IllegalArgumentException("snapshot format");
            int version = in.readInt();
            if (version != SNAP_VERSION && version != SNAP_VERSION_REMOTE) throw new IllegalArgumentException("snapshot format");
            var st = new DeliveryState(policy);
            st.nextOffset = in.readLong(); st.enqueued = in.readLong(); st.version = in.readLong();
            for (int i = in.readInt(); i > 0; i--) {
                var m = new Message(in.readLong(), in.readLong(), in.readLong(), in.readLong(), in.readInt(), in.readUTF(), in.readLong(), in.readInt(), in.readUTF());
                st.messages.put(m.offset, m);
            }
            for (int i = in.readInt(); i > 0; i--) {
                String name = in.readUTF();
                long start = in.readLong();
                var g = st.new Group(name, start);
                g.cursor = in.readLong(); g.freshCount = in.readLong(); g.committedOffset = in.readLong();
                g.acked = in.readLong(); g.deadLettered = in.readLong(); g.redeliveries = in.readLong();
                for (int k = in.readInt(); k > 0; k--) g.inFlight.put(in.readLong(), new Lease(in.readUTF(), in.readLong(), in.readInt()));
                for (int k = in.readInt(); k > 0; k--) g.retry.put(in.readLong(), new Retry(in.readLong(), in.readInt()));
                for (int k = in.readInt(); k > 0; k--) g.dead.put(in.readLong(), new Dead(in.readUTF(), in.readInt()));
                for (int k = in.readInt(); k > 0; k--) g.resolvedAbove.add(in.readLong());
                for (int k = in.readInt(); k > 0; k--) g.due.add(new Due(in.readLong(), in.readLong()));
                st.groups.put(name, g);
            }
            for (int i = in.readInt(); i > 0; i--) {
                long pid = in.readLong();
                st.highestSeq.put(pid, in.readLong());
                var w = new LinkedHashMap<Long, Long>();
                for (int k = in.readInt(); k > 0; k--) w.put(in.readLong(), in.readLong());
                st.dedup.put(pid, w);
            }
            for (int i = in.readInt(); i > 0; i--) st.origins.add(in.readUTF());
            if (version == SNAP_VERSION_REMOTE) {
                for (int i = in.readInt(); i > 0; i--) {
                    int origin = in.readUnsignedShort();
                    var p = new RemoteProducer();
                    long pid = in.readLong();
                    p.highest = in.readLong(); p.lastMs = in.readLong();
                    for (int k = in.readInt(); k > 0; k--) p.window.put(in.readLong(), in.readLong());
                    if (origin == 0 || st.remote.put(new RemoteKey(origin, pid), p) != null) throw new IllegalArgumentException("snapshot remote producer");
                    st.remoteLive.merge(origin, 1, Integer::sum);
                }
            }
            if (in.available() != 0) throw new IllegalArgumentException("trailing snapshot bytes");
            return st;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("truncated snapshot", e);
        }
    }

    /** Lowest ledger offset whose ENQUEUE record is still needed (oldest retained message), or {@link #nextOffset}. */
    long oldestNeededMessageOffset() { return messages.isEmpty() ? nextOffset : messages.firstKey(); }
}
