package sjws_nettask.messaging.ledger;

/**
 * Test-only fault injection for {@link LedgerWriter}. Package-private so the hooks stay off the public API.
 * Ctor faults use a {@link ThreadLocal} so they cannot leak across instances or tests (#335 N3).
 * Per-writer materialize / allocateDirect faults are private fields on {@link LedgerWriter}; this class is the
 * only same-package setter surface (#336 Security nit).
 */
final class LedgerWriterFaults {
    private LedgerWriterFaults() {}

    /** Thrown from the constructor after idle acquire when set on the constructing thread. */
    static final ThreadLocal<RuntimeException> CTOR = new ThreadLocal<>();

    static void setMaterializeFault(LedgerWriter w, RuntimeException fault) {
        w.setMaterializeFaultForTests(fault);
    }

    static void setAllocateDirectFault(LedgerWriter w, RuntimeException fault) {
        w.setAllocateDirectFaultForTests(fault);
    }
}
