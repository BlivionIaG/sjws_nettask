package sjws_nettask.messaging.ledger;

/**
 * Immutable view of a ledger's committed state, published by {@link LedgerWriter} through an atomic
 * reference after every committed group, so inspectors (LedgerInspector, admin TUI, metrics) read a
 * consistent snapshot with a single volatile load and never touch the writer's mutable state.
 *
 * @param nextOffset        first offset not yet committed (readers must not read at or past it)
 * @param startOffset       first offset still on disk
 * @param committedBatches  batches committed since open
 * @param committedRecords  records committed since open
 * @param committedBytes    frame bytes committed since open
 * @param fsyncs            fsyncs issued since open
 * @param segmentCount      segment files
 * @param durability        the writer's mode
 * @param commitSeq         monotonically increasing per publication (0 = state right after open)
 * @param lastCommitNanos   {@link System#nanoTime()} of the publication
 */
public record LedgerSnapshot(long nextOffset, long startOffset, long committedBatches, long committedRecords,
        long committedBytes, long fsyncs, int segmentCount, Durability durability, long commitSeq, long lastCommitNanos) {
}
