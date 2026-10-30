package sjws_nettask.messaging.ledger;

/**
 * Cross-package test access to package-private {@link LedgerWriterBudget} / {@link LedgerWriterFaults} hooks
 * (#335 N3, #336 fault visibility). Lives in test sources only — not shipped in the main jar.
 */
public final class LedgerBudgetTestHooks {
    private LedgerBudgetTestHooks() {}

    public static void install(LedgerWriterBudget budget) {
        LedgerWriterBudget.install(budget);
    }

    public static void reset() {
        LedgerWriterBudget.resetForTests();
    }

    public static LedgerWriterBudget ofBytes(long budgetBytes) {
        return LedgerWriterBudget.ofBytes(budgetBytes);
    }

    /** Sets a ctor fault on the calling thread (#335 N3 ThreadLocal). */
    public static void setCtorFault(RuntimeException fault) {
        LedgerWriterFaults.CTOR.set(fault);
    }

    public static void clearCtorFault() {
        LedgerWriterFaults.CTOR.remove();
    }

    /** Resets the lazy writer pool so the next open re-resolves {@code sharedWriterThreads}. */
    public static void resetWriterPool() {
        SharedLedgerPool.resetWriterPoolForTests();
    }

    public static final String WRITER_THREADS_PROPERTY = SharedLedgerPool.WRITER_THREADS_PROPERTY;

    public static void setMaterializeFault(LedgerWriter w, RuntimeException fault) {
        LedgerWriterFaults.setMaterializeFault(w, fault);
    }

    public static void setAllocateDirectFault(LedgerWriter w, RuntimeException fault) {
        LedgerWriterFaults.setAllocateDirectFault(w, fault);
    }
}
