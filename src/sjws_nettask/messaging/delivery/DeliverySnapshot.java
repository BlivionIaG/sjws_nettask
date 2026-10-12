package sjws_nettask.messaging.delivery;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of a partition's delivery state for {@link LedgerInspector}, admin TUI and metrics.
 * Published through an atomic reference, so readers never take the partition lock.
 *
 * @param version        number of ledger entries applied (monotonic)
 * @param nextOffset     next ledger offset
 * @param enqueuedTotal  messages ever enqueued since the log began
 * @param liveMessages   messages whose payload is still retained (not resolved by every group)
 */
public record DeliverySnapshot(long version, long nextOffset, long enqueuedTotal, int liveMessages, Map<String, GroupView> groups) {

    public DeliverySnapshot { groups = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(groups)); }

    public record Counts(long enqueued, long inFlight, long acked, long deadLettered, long redeliveries) { }

    /**
     * One pending/in-flight/dead item. {@code timeMs} is the lease deadline (IN_FLIGHT), the not-before time of a
     * retry (ENQUEUED after a nack, 0 for fresh messages), or 0.
     */
    public record Item(long offset, MessageState state, int attempts, String consumer, long timeMs, long enqueuedAtMs,
            int size, String reason) implements Comparable<Item> {
        @Override public int compareTo(Item o) { return Long.compare(offset, o.offset); }
    }

    /**
     * Lists are by offset and capped at the partition's {@code maxItemsPerList}; {@code truncated} is true when any
     * list was cut (counts are always exact).
     */
    public record GroupView(String group, long startOffset, long committedOffset, Counts counts, List<Item> enqueued,
            List<Item> inFlight, List<Item> deadLettered, boolean truncated) {

        /** Items in {@code state} with offset ≥ {@code fromOffset}, at most {@code limit}, by offset. ACKED is not listed. */
        public List<Item> page(MessageState state, long fromOffset, int limit) {
            List<Item> l = switch (state) {
                case ENQUEUED -> enqueued;
                case IN_FLIGHT -> inFlight;
                case DEAD_LETTERED -> deadLettered;
                case ACKED -> List.of();
            };
            int lo = 0, hi = l.size();
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (l.get(mid).offset() < fromOffset) lo = mid + 1; else hi = mid; }
            return l.subList(lo, Math.min(l.size(), lo + Math.max(0, limit)));
        }
    }
}
