package sjws_nettask.messaging.ledger;

/**
 * Cross-package test access to package-private {@link LedgerWriterBudget} / {@link LedgerWriterFaults} hooks (#335 N3).
 * Lives in test sources only — not shipped in the main jar.
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
}
