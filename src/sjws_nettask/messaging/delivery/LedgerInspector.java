package sjws_nettask.messaging.delivery;

import java.util.List;
import java.util.Map;

/**
 * Read-only inspection API (docs/design/messaging.md §4) over {@link DeliverySnapshot}s: counts per state and
 * paged listings of pending (ENQUEUED), IN_FLIGHT and DEAD_LETTERED items. Lock-free: every call reads one
 * immutable snapshot; a single snapshot can be passed around for consistent multi-call views.
 */
public final class LedgerInspector {

    private final Partition partition;

    public LedgerInspector(Partition partition) { this.partition = partition; }

    public DeliverySnapshot snapshot() { return partition.snapshot(); }

    public Map<String, DeliverySnapshot.Counts> counts() {
        var out = new java.util.LinkedHashMap<String, DeliverySnapshot.Counts>();
        snapshot().groups().forEach((k, v) -> out.put(k, v.counts()));
        return out;
    }

    public DeliverySnapshot.Counts counts(String group) { return view(snapshot(), group).counts(); }

    public List<DeliverySnapshot.Item> page(String group, MessageState state, long fromOffset, int limit) {
        return view(snapshot(), group).page(state, fromOffset, limit);
    }

    /** DLQ topic listing (its own groups' views), or null for a DLQ topic. */
    public LedgerInspector deadLetterQueue() {
        var d = partition.deadLetterQueue();
        return d == null ? null : new LedgerInspector(d);
    }

    private static DeliverySnapshot.GroupView view(DeliverySnapshot s, String group) {
        var v = s.groups().get(group);
        if (v == null) throw new IllegalArgumentException("unknown group " + group);
        return v;
    }
}
