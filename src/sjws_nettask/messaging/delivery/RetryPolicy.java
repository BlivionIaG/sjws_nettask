package sjws_nettask.messaging.delivery;

/**
 * Redelivery policy: a message is dead-lettered once it has been delivered {@code maxAttempts} times
 * and then nacked or its lease expired. Backoff before attempt n+1 = min(base·2^(n-1), max).
 */
public record RetryPolicy(int maxAttempts, long backoffBaseMs, long backoffMaxMs) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(5, 100, 30_000);
    /** Used for DLQ topics: never dead-letter again. */
    public static final RetryPolicy NO_DLQ = new RetryPolicy(Integer.MAX_VALUE, 100, 30_000);

    public RetryPolicy {
        if (maxAttempts < 1 || backoffBaseMs < 0 || backoffMaxMs < backoffBaseMs) throw new IllegalArgumentException("retry policy");
    }

    public long backoffMs(int attempt) {
        if (attempt < 1 || backoffBaseMs == 0) return 0;
        int shift = Math.min(attempt - 1, 40);
        long v = backoffBaseMs << shift;
        return v < 0 || v > backoffMaxMs ? backoffMaxMs : v;
    }
}
