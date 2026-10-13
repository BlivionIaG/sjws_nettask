package sjws_nettask.messaging.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import sjws_nettask.messaging.delivery.DeliveryCounts;
import sjws_nettask.messaging.delivery.Partition;

/**
 * {@link MessagingStats} over registered {@link Partition}s. Snapshots read only each partition's
 * {@link Partition#counts()} (one volatile load, exact as of the last applied ledger entry), so they never take a
 * partition lock and never block producers or consumers. Registration is copy-on-write (rare: open/close).
 * Created only when messaging is enabled; otherwise callers hold {@link MessagingStats#NONE}.
 */
public final class PartitionStatsRegistry implements MessagingStats {

    private record Entry(String topic, int partition, Partition p) { }

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final LongSupplier clockMs;

    public PartitionStatsRegistry() { this(System::currentTimeMillis); }

    public PartitionStatsRegistry(LongSupplier clockMs) { this.clockMs = clockMs; }

    /** Registers {@code p} (and its DLQ) as partition {@code index} of its topic. Re-registering replaces. */
    public void register(int index, Partition p) {
        if (index < 0) throw new IllegalArgumentException("partition index");
        unregister(p.topic(), index);
        entries.add(new Entry(p.topic(), index, p));
    }

    public boolean unregister(String topic, int index) {
        return entries.removeIf(e -> e.topic.equals(topic) && e.partition == index);
    }

    @Override
    public StatsSnapshot snapshot() {
        long now = clockMs.getAsLong();
        var out = new ArrayList<PartitionStats>(entries.size() * 2);
        for (var e : entries) {
            var dlq = e.p.deadLetterQueue();
            DeliveryCounts dc = dlq == null ? null : dlq.counts();
            long dlqDepth = dc == null ? 0 : dc.liveMessages();
            out.add(stats(e.topic, e.partition, false, e.p.counts(), dlqDepth, now));
            if (dc != null) out.add(stats(dlq.topic(), e.partition, true, dc, 0, now));
        }
        return new StatsSnapshot(now, out);
    }

    private static PartitionStats stats(String topic, int index, boolean isDlq, DeliveryCounts c, long dlqDepth, long now) {
        var groups = new ArrayList<GroupStats>(c.groups().size());
        c.groups().forEach((name, g) -> {
            var k = g.counts();
            long age = g.oldestUnresolvedAtMs() == 0 ? 0 : Math.max(0, now - g.oldestUnresolvedAtMs());
            long lagMessages = k.enqueued() + k.inFlight();
            // A caught-up group is at the log end even though other groups' state records keep advancing it.
            long lagOffsets = lagMessages == 0 ? 0 : Math.max(0, c.nextOffset() - g.committedOffset());
            groups.add(new GroupStats(name, g.committedOffset(), lagOffsets, lagMessages, k.enqueued(), k.inFlight(), k.acked(), k.deadLettered(), k.redeliveries(),
                    dlqDepth, age));
        });
        return new PartitionStats(topic, index, isDlq, c.nextOffset(), c.enqueuedTotal(), c.liveMessages(), groups);
    }

    /** Prometheus text exposition (gauges), labels {@code topic, partition, group}. */
    public static String prometheus(StatsSnapshot s) {
        var sb = new StringBuilder();
        String[][] metrics = {
            {"sjws_messaging_lag_offsets", "log end minus committed offset"},
            {"sjws_messaging_lag_messages", "messages not yet acked or dead-lettered"},
            {"sjws_messaging_inflight", "messages leased to consumers"},
            {"sjws_messaging_dlq_depth", "messages waiting in the topic's DLQ"},
            {"sjws_messaging_oldest_pending_age_ms", "age of the oldest unresolved message"},
        };
        for (int m = 0; m < metrics.length; m++) {
            sb.append("# HELP ").append(metrics[m][0]).append(' ').append(metrics[m][1]).append('\n');
            sb.append("# TYPE ").append(metrics[m][0]).append(" gauge\n");
            for (var p : s.partitions()) for (var g : p.groups()) {
                long v = switch (m) {
                    case 0 -> g.lagOffsets();
                    case 1 -> g.lagMessages();
                    case 2 -> g.inFlight();
                    case 3 -> g.dlqDepth();
                    default -> g.oldestPendingAgeMs();
                };
                sb.append(metrics[m][0]).append("{topic=\"").append(esc(p.topic())).append("\",partition=\"").append(p.partition())
                  .append("\",group=\"").append(esc(g.group())).append("\"} ").append(v).append('\n');
            }
        }
        return sb.toString();
    }

    private static String esc(String v) { return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }

    List<String> registeredTopics() { return entries.stream().map(Entry::topic).toList(); }
}
