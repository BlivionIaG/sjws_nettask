package sjws_nettask.messaging.delivery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * One ledger record in a partition log (docs/messaging/design.md §2.1). Messages and every per-group
 * state transition share the log, so replaying it in order rebuilds the delivery state exactly.
 * Encoding: {@code [type:byte]} then fields; strings are {@code [len:short][utf8]}.
 */
public sealed interface LedgerEntry {

    byte ENQUEUE_T = 1, SUBSCRIBE_T = 2, DELIVER_T = 3, ACK_T = 4, NACK_T = 5, DLQ_T = 6, EXTEND_T = 7;

    /** A message. {@code producerId == 0} disables dedup. {@code originGroup} is non-empty only in a DLQ topic. */
    record Enqueue(long producerId, long seq, long timestampMs, String originGroup, long originOffset, int originAttempts,
            String originReason, byte[] payload) implements LedgerEntry {
        public Enqueue { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
        byte[] payloadRef() { return payload; }
    }

    /** Creates a consumer group starting at {@code fromOffset}. */
    record Subscribe(String group, long fromOffset) implements LedgerEntry { }

    /** ENQUEUED → IN_FLIGHT: lease until {@code deadlineMs}; {@code attempt} is 1-based. */
    record Deliver(String group, long offset, String consumer, long deadlineMs, int attempt) implements LedgerEntry { }

    /** IN_FLIGHT → ACKED. */
    record Ack(String group, long offset) implements LedgerEntry { }

    /** IN_FLIGHT → ENQUEUED, eligible again at {@code notBeforeMs}. */
    record Nack(String group, long offset, long notBeforeMs) implements LedgerEntry { }

    /** → DEAD_LETTERED (already copied to {@code <topic>.dlq}). */
    record DeadLetter(String group, long offset, String reason) implements LedgerEntry { }

    /**
     * Lease extension (heartbeat): each {@code offsets[i]} in flight under {@code attempts[i]} gets the new absolute
     * {@code deadlineMs}. One record for a single or a batched extend; only accepted leases are written.
     * Type 7 is new: a build without it rejects a log that contains one (unknown type), it never misreads it.
     */
    record Extend(String group, long deadlineMs, long[] offsets, int[] attempts) implements LedgerEntry {
        public Extend {
            if (offsets.length != attempts.length || offsets.length == 0) throw new IllegalArgumentException("extend batch");
        }
        @Override public boolean equals(Object o) {
            return o instanceof Extend e && e.group.equals(group) && e.deadlineMs == deadlineMs
                    && java.util.Arrays.equals(e.offsets, offsets) && java.util.Arrays.equals(e.attempts, attempts);
        }
        @Override public int hashCode() { return java.util.Objects.hash(group, deadlineMs, java.util.Arrays.hashCode(offsets)); }
    }

    static byte[] encode(LedgerEntry e) {
        var b = ByteBuffer.allocate(size(e));
        switch (e) {
            case Enqueue x -> {
                b.put(ENQUEUE_T).putLong(x.producerId).putLong(x.seq).putLong(x.timestampMs);
                str(b, x.originGroup);
                b.putLong(x.originOffset).putInt(x.originAttempts);
                str(b, x.originReason);
                b.putInt(x.payload.length).put(x.payload);
            }
            case Subscribe x -> { b.put(SUBSCRIBE_T); str(b, x.group); b.putLong(x.fromOffset); }
            case Deliver x -> {
                b.put(DELIVER_T); str(b, x.group); b.putLong(x.offset); str(b, x.consumer);
                b.putLong(x.deadlineMs).putInt(x.attempt);
            }
            case Ack x -> { b.put(ACK_T); str(b, x.group); b.putLong(x.offset); }
            case Nack x -> { b.put(NACK_T); str(b, x.group); b.putLong(x.offset).putLong(x.notBeforeMs); }
            case DeadLetter x -> { b.put(DLQ_T); str(b, x.group); b.putLong(x.offset); str(b, x.reason); }
            case Extend x -> {
                b.put(EXTEND_T); str(b, x.group); b.putLong(x.deadlineMs).putInt(x.offsets.length);
                for (int i = 0; i < x.offsets.length; i++) b.putLong(x.offsets[i]).putInt(x.attempts[i]);
            }
        }
        return b.array();
    }

    private static int size(LedgerEntry e) {
        return 1 + switch (e) {
            case Enqueue x -> 24 + ss(x.originGroup) + 12 + ss(x.originReason) + 4 + x.payload.length;
            case Subscribe x -> ss(x.group) + 8;
            case Deliver x -> ss(x.group) + 8 + ss(x.consumer) + 12;
            case Ack x -> ss(x.group) + 8;
            case Nack x -> ss(x.group) + 16;
            case DeadLetter x -> ss(x.group) + 8 + ss(x.reason);
            case Extend x -> ss(x.group) + 8 + 4 + 12 * x.offsets.length;
        };
    }

    static LedgerEntry decode(byte[] raw) {
        var b = ByteBuffer.wrap(raw);
        try {
            LedgerEntry e = switch (b.get()) {
                case ENQUEUE_T -> {
                    long pid = b.getLong(), seq = b.getLong(), ts = b.getLong();
                    String og = str(b);
                    long oo = b.getLong();
                    int oa = b.getInt();
                    String or = str(b);
                    var p = new byte[b.getInt()];
                    b.get(p);
                    yield new Enqueue(pid, seq, ts, og, oo, oa, or, p);
                }
                case SUBSCRIBE_T -> new Subscribe(str(b), b.getLong());
                case DELIVER_T -> new Deliver(str(b), b.getLong(), str(b), b.getLong(), b.getInt());
                case ACK_T -> new Ack(str(b), b.getLong());
                case NACK_T -> new Nack(str(b), b.getLong(), b.getLong());
                case DLQ_T -> new DeadLetter(str(b), b.getLong(), str(b));
                case EXTEND_T -> {
                    String g = str(b);
                    long deadline = b.getLong();
                    int n = b.getInt();
                    if (n < 1 || n > b.remaining() / 12) throw new IllegalArgumentException("extend count " + n);
                    var offs = new long[n];
                    var atts = new int[n];
                    for (int i = 0; i < n; i++) { offs[i] = b.getLong(); atts[i] = b.getInt(); }
                    yield new Extend(g, deadline, offs, atts);
                }
                default -> throw new IllegalArgumentException("unknown ledger entry type");
            };
            if (b.hasRemaining()) throw new IllegalArgumentException("trailing bytes in ledger entry");
            return e;
        } catch (java.nio.BufferUnderflowException | NegativeArraySizeException ex) {
            throw new IllegalArgumentException("truncated ledger entry", ex);
        }
    }

    private static int ss(String s) {
        int n = s.getBytes(StandardCharsets.UTF_8).length;
        if (n > Short.MAX_VALUE) throw new IllegalArgumentException("string too long");
        return 2 + n;
    }

    private static void str(ByteBuffer b, String s) {
        var x = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) x.length).put(x);
    }

    private static String str(ByteBuffer b) {
        var x = new byte[b.getShort()];
        b.get(x);
        return new String(x, StandardCharsets.UTF_8);
    }
}
