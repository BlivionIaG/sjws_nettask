package sjws_nettask.messaging.delivery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exact per-group counts, published after <em>every</em> applied ledger entry (O(groups) to build), so
 * {@link LedgerInspector#counts} and metrics are always current and lock-free. The paged item lists live in
 * the rate-limited {@link DeliverySnapshot}.
 */
public record DeliveryCounts(long version, long nextOffset, long enqueuedTotal, int liveMessages, Map<String, Group> groups) {

    /**
     * @param committedOffset          first ledger offset not yet resolved (ACKED/DEAD_LETTERED) by the group
     * @param oldestUnresolvedAtMs     enqueue time of the oldest message the group has not resolved (0 if none)
     */
    public record Group(long committedOffset, DeliverySnapshot.Counts counts, long oldestUnresolvedAtMs) { }

    public DeliveryCounts { groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups)); }
}
