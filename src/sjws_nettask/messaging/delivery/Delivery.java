package sjws_nettask.messaging.delivery;

/** One message handed to a consumer by {@link Partition#poll}. Ack/nack it by {@code offset}. */
public record Delivery(long offset, int attempt, long deadlineMs, long producerId, long seq, long enqueuedAtMs, byte[] payload,
        String originGroup, long originOffset, int originAttempts, String originReason) {
    public Delivery { payload = payload.clone(); }
    @Override public byte[] payload() { return payload.clone(); }
}
