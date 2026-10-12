package sjws_nettask.messaging.ledger;

/** When an append is acknowledged relative to fsync (names as in ARCHITECTURE.md T3 / docs/design/messaging.md §2.2). */
public enum Durability {
    /** Ack after write(2) into the page cache; never fsyncs on the append path. Loses data on power failure. */
    NONE,
    /** Group commit: one write + one fsync for everything queued, then ack the whole group. Default. */
    GROUP,
    /** One fsync per batch. Compliance-style streams only. */
    EACH
}
