package sjws_nettask.messaging.stats;

import java.util.List;

/**
 * Pull-based messaging statistics for the control plane, the admin TUI ("Queues" view) and metrics
 * (docs/messaging/design.md §4; admin design §5/§8). Implementations must be lock-free and cheap to call.
 * When messaging is off, use {@link #NONE}: no registry exists, nothing is computed and nothing is allocated per call.
 */
public interface MessagingStats {

    /** Point-in-time stats of every registered partition. */
    StatsSnapshot snapshot();

    /** Messaging disabled: an empty, shared, immutable snapshot. */
    MessagingStats NONE = () -> StatsSnapshot.EMPTY;

    /**
     * @param takenAtMs  wall-clock time of the snapshot
     * @param partitions one entry per registered partition, including each {@code <topic>.dlq} partition
     */
    record StatsSnapshot(long takenAtMs, List<PartitionStats> partitions) {
        public static final StatsSnapshot EMPTY = new StatsSnapshot(0, List.of());
        public StatsSnapshot { partitions = List.copyOf(partitions); }
    }

    /**
     * @param logEndOffset  next ledger offset (log end)
     * @param enqueuedTotal messages ever appended to this partition
     * @param liveMessages  messages not yet resolved by every group (payload retained)
     * @param deadLetterQueue true for a {@code <topic>.dlq} partition
     */
    record PartitionStats(String topic, int partition, boolean deadLetterQueue, long logEndOffset, long enqueuedTotal,
            int liveMessages, List<GroupStats> groups) {
        public PartitionStats { groups = List.copyOf(groups); }
    }

    /**
     * Per consumer group. Ledger offsets count every ledger record (messages <em>and</em> state records), so
     * {@code lagOffsets} = log end − committed offset is the spec'd offset lag, and {@code lagMessages} =
     * enqueued + in-flight is the exact number of messages the group still has to finish. {@code lagOffsets} is 0
     * when {@code lagMessages} is 0 (other groups' state records keep moving the log end).
     *
     * @param dlqDepth            messages in this partition's {@code <topic>.dlq} not yet consumed by every DLQ group
     *                            (partition-wide: the DLQ is shared by all groups); 0 for a DLQ partition
     * @param oldestPendingAgeMs  age of the oldest message the group has not resolved (0 if none)
     */
    record GroupStats(String group, long committedOffset, long lagOffsets, long lagMessages, long enqueued, long inFlight,
            long acked, long deadLettered, long redeliveries, long dlqDepth, long oldestPendingAgeMs) { }
}
