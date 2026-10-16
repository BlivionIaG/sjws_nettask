package sjws_nettask.messaging.delivery;

/**
 * Outcome of a fenced lease call ({@code ack} / {@code nack} / {@code reject} / {@code extend} with the delivery's
 * attempt). {@link #CANCELLED} comes only from {@code extend}.
 * The attempt in {@link Delivery#attempt()} is the lease token: only the holder of the current attempt may settle.
 */
public enum LeaseResult {
    /** Applied: the caller held the current lease. */
    OK,
    /** Nothing in flight at this offset for this group (already settled, never delivered, or dead-lettered). */
    NOT_IN_FLIGHT,
    /** In flight, but under a newer attempt: the caller's lease expired and the message was re-leased. Refused. */
    STALE_ATTEMPT,
    /**
     * Returned only by {@code extend}, when the caller still holds the lease but the partition's {@link CancelProbe}
     * reports the work cancelled (#224). The lease is neither extended nor released: the holder should stop and
     * settle it ({@code ack}) before it expires. Appended last so existing ordinals do not change.
     */
    CANCELLED;

    public boolean ok() { return this == OK; }
}
