package sjws_nettask.messaging.ledger;

/**
 * When an append is acknowledged relative to fsync (docs/design/messaging.md §2.2, ARCHITECTURE.md T3).
 * Usable as a writer default and as a per-append override.
 */
public enum Durability {
    /** Ack after write(2); no fsync unless a later append asks for one or the writer closes. Loses data on power failure. */
    NONE,
    /**
     * Default. Ack after write(2) into the page cache; a background syncer fsyncs continuously (at most
     * every {@code fsyncInterval}) and publishes {@link LedgerSnapshot#durableOffset}. Process crashes lose
     * nothing; a power or kernel failure loses at most the records written since the last completed fsync.
     */
    ASYNC,
    /** Group commit: ack after the fsync covering the record (one fsync per drained group). */
    GROUP,
    /** One fsync per batch (writer default only; as a per-append override it behaves like GROUP). */
    EACH;

    boolean waitsForFsync() { return this == GROUP || this == EACH; }
}
