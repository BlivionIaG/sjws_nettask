package sjws_nettask.messaging.delivery;

/**
 * One message handed to a consumer by {@link Partition#poll}. Ack/nack it by {@code offset}.
 * {@link #payload()} returns a fresh copy on every call. The constructor does not copy: the only producer is
 * {@link Partition}, which passes its own never-mutated payload array (one copy per delivery instead of two).
 */
public record Delivery(long offset, int attempt, long deadlineMs, long producerId, long seq, long enqueuedAtMs, byte[] payload,
        String originGroup, long originOffset, int originAttempts, String originReason) {
    public Delivery { java.util.Objects.requireNonNull(payload); }
    @Override public byte[] payload() { return payload.clone(); }
}
