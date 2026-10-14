package sjws_nettask.messaging.ledger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * How messaging appends to one partition's log: the seam where a replicated implementation (durable-log-plan §3,
 * one Raft group {@code msg/<partition>} per partition) can later plug in. Today the only
 * implementation is {@link LedgerWriter} (local segmented log), so this adds one interface call and no behaviour.
 *
 * <p>Must stay free of cluster-package types: with HA off no cluster class may load
 * (MessagingClusterIsolationTest).
 */
public interface PartitionAppender {

    /** Appends one batch; waits while the hand-off is at capacity. Completes with the base offset per {@code mode}. */
    CompletableFuture<Long> append(List<byte[]> records, Durability mode) throws InterruptedException;

    /** As {@link #append} but never waits; callers that hold a lock call {@link #awaitCapacity} before taking it. */
    CompletableFuture<Long> appendNoWait(List<byte[]> records, Durability mode);

    /** Blocks while the appender is behind (backpressure), without holding any caller lock. */
    void awaitCapacity() throws InterruptedException;
}
