package sjws_nettask.messaging.ledger;

/**
 * Test-only fault injection for {@link LedgerWriter}. Package-private so the hooks stay off the public API.
 * Ctor faults use a {@link ThreadLocal} so they cannot leak across instances or tests (#335 N3); materialize /
 * allocateDirect faults live as per-writer instance fields on {@link LedgerWriter}.
 */
final class LedgerWriterFaults {
    private LedgerWriterFaults() {}

    /** Thrown from the constructor after idle acquire when set on the constructing thread. */
    static final ThreadLocal<RuntimeException> CTOR = new ThreadLocal<>();
}
