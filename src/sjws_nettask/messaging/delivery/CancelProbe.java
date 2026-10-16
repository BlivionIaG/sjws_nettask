package sjws_nettask.messaging.delivery;

/**
 * Optional hook a higher layer (swarm) installs on a {@link Partition} so that {@code extend} can tell a lease holder
 * that its work was cancelled (#224). Messaging stores no cancel state: the probe is asked only by {@code extend},
 * after the lease fence passed, and its answer is never written to the ledger or replicated.
 * <p>
 * Implementations must be a plain in-memory read: no locks, no I/O, no allocation. It is called under the
 * partition lock.
 */
@FunctionalInterface
public interface CancelProbe {
    /** True when the message at {@code offset}, leased to {@code group}, has been cancelled by its producer side. */
    boolean isCancelled(String group, long offset);
}
