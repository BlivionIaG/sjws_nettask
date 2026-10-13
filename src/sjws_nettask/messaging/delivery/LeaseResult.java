package sjws_nettask.messaging.delivery;

/**
 * Outcome of a fenced settle call ({@code ack} / {@code nack} / {@code reject} with the delivery's attempt).
 * The attempt in {@link Delivery#attempt()} is the lease token: only the holder of the current attempt may settle.
 */
public enum LeaseResult {
    /** Applied: the caller held the current lease. */
    OK,
    /** Nothing in flight at this offset for this group (already settled, never delivered, or dead-lettered). */
    NOT_IN_FLIGHT,
    /** In flight, but under a newer attempt: the caller's lease expired and the message was re-leased. Refused. */
    STALE_ATTEMPT;

    public boolean ok() { return this == OK; }
}
