/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.messaging.stats;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import sjws_nettask.messaging.http.MessagingHooks;
import sjws_nettask.observe.ServerMetrics;

/**
 * {@link MessagingHooks} exporter for produce/fetch latency histograms
 * ({@code sjws_messaging_*_duration_seconds}, docs/design/messaging-metrics.md). Labels from the
 * shipped hooks are {@code topic} and {@code status} (plain log topics have no partition index on
 * the hook). Topic cardinality is capped at 1000; further topics fold into {@code topic="other"}.
 *
 * <p>Optional scrape-time lag text from a {@link MessagingStats} supplier is appended when the
 * supplier is non-null and not {@link MessagingStats#NONE}. Proposed offset/replication gauges from
 * the design doc are <strong>not</strong> emitted here until Messaging Core confirms those names.
 *
 * <p>Name prefix {@code sjws_messaging_*} does not overlap parked swarm stream counters on PR #299
 * ({@code sjws_swarm_stream_*}).
 */
public final class MessagingMetrics implements MessagingHooks {

    static final int TOPIC_CAP = 1000;

    private final ConcurrentHashMap<String, Hist> produce = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Hist> fetch = new ConcurrentHashMap<>();
    /**
     * Distinct topic labels admitted (not topic/status pairs). Cap is {@link #admittedCount}.
     * A topic is published into the map only after a CAS reserves its slot, so peers never observe
     * a name that is later rolled back to {@code other}. Uses AtomicInteger CAS (SourceGuard:
     * no monitor locks on the request path).
     */
    private final ConcurrentHashMap<String, Boolean> admittedTopics = new ConcurrentHashMap<>();
    private final AtomicInteger admittedCount = new AtomicInteger();
    private final LongAdder foldedTopics = new LongAdder();
    private volatile Supplier<MessagingStats> stats = () -> MessagingStats.NONE;

    /** Lag / partition stats source for scrape. Null or {@link MessagingStats#NONE} omits that family. */
    public void stats(Supplier<MessagingStats> stats) {
        this.stats = stats == null ? () -> MessagingStats.NONE : stats;
    }

    @Override
    public void produced(String topic, int status, long bytes, long nanos) {
        hist(this.produce, topic, status).record(nanos);
    }

    @Override
    public void fetched(String topic, int status, long bytes, long nanos) {
        hist(this.fetch, topic, status).record(nanos);
    }

    /** Prometheus text for the latency histograms (and lag gauges when a stats source is attached). */
    public String prometheus() {
        var sb = new StringBuilder(512);
        appendHistogram(sb, "sjws_messaging_produce_duration_seconds",
                "Handler time for POST /messaging/v1 produce (docs/design/messaging-metrics.md).", this.produce);
        appendHistogram(sb, "sjws_messaging_fetch_duration_seconds",
                "Handler time for GET /messaging/v1 fetch before the body send.", this.fetch);
        if (this.foldedTopics.sum() > 0) {
            sb.append("# HELP sjws_messaging_topics_folded_total Topics folded into topic=\"other\" after the cardinality cap.\n");
            sb.append("# TYPE sjws_messaging_topics_folded_total counter\n");
            sb.append("sjws_messaging_topics_folded_total ").append(this.foldedTopics.sum()).append('\n');
        }
        var src = this.stats;
        if (src != null) {
            var s = src.get();
            if (s != null && s != MessagingStats.NONE) {
                sb.append(PartitionStatsRegistry.prometheus(s.snapshot()));
            }
        }
        return sb.toString();
    }

    /** Appends this exporter's text to a {@code /metrics} scrape buffer. */
    public void appendTo(StringBuilder sb) {
        sb.append(prometheus());
    }

    private Hist hist(ConcurrentHashMap<String, Hist> map, String topic, int status) {
        String admitted = admitTopic(topic);
        String k = key(admitted, status);
        Hist existing = map.get(k);
        if (existing != null) {
            return existing;
        }
        Hist created = new Hist(admitted, status);
        Hist raced = map.putIfAbsent(k, created);
        return raced != null ? raced : created;
    }

    /**
     * Returns a topic label that is either already admitted, newly admitted under the cap, or
     * {@code "other"}. Slot reservation ({@link #admittedCount} CAS) happens before the name is
     * published into {@link #admittedTopics}, so concurrent callers never observe a topic that is
     * later rolled back to {@code other}.
     */
    private String admitTopic(String topic) {
        String candidate = topic == null || topic.isEmpty() ? "other" : topic;
        if ("other".equals(candidate)) {
            return candidate;
        }
        while (true) {
            if (this.admittedTopics.containsKey(candidate)) {
                return candidate;
            }
            int n = this.admittedCount.get();
            if (n >= TOPIC_CAP) {
                if (this.admittedTopics.containsKey(candidate)) {
                    return candidate;
                }
                this.foldedTopics.increment();
                return "other";
            }
            if (!this.admittedCount.compareAndSet(n, n + 1)) {
                continue;
            }
            if (this.admittedTopics.putIfAbsent(candidate, Boolean.TRUE) == null) {
                return candidate;
            }
            // Peer admitted the same name first; release the unused slot.
            this.admittedCount.decrementAndGet();
            return candidate;
        }
    }

    private static String key(String topic, int status) {
        return topic + '\0' + status;
    }

    private static void appendHistogram(StringBuilder sb, String name, String help,
            ConcurrentHashMap<String, Hist> map) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" histogram\n");
        var keys = map.keySet().stream().sorted().toList();
        for (var k : keys) {
            var h = map.get(k);
            if (h == null) {
                continue;
            }
            long cumulative = 0;
            for (int i = 0; i < h.buckets.length; i++) {
                cumulative += h.buckets[i].sum();
                sb.append(name).append("_bucket{topic=\"").append(esc(h.topic)).append("\",status=\"")
                        .append(h.status).append("\",le=\"").append(ServerMetrics.LATENCY_LE_LABELS[i])
                        .append("\"} ").append(cumulative).append('\n');
            }
            sb.append(name).append("_sum{topic=\"").append(esc(h.topic)).append("\",status=\"")
                    .append(h.status).append("\"} ")
                    .append(String.format(Locale.ROOT, "%.6f", h.sumNanos.sum() / 1_000_000_000.0)).append('\n');
            sb.append(name).append("_count{topic=\"").append(esc(h.topic)).append("\",status=\"")
                    .append(h.status).append("\"} ").append(cumulative).append('\n');
        }
    }

    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static final class Hist {
        final String topic;
        final int status;
        final LongAdder[] buckets = new LongAdder[ServerMetrics.LATENCY_BOUNDS_NANOS.length];
        final LongAdder sumNanos = new LongAdder();

        Hist(String topic, int status) {
            this.topic = topic;
            this.status = status;
            for (int i = 0; i < this.buckets.length; i++) {
                this.buckets[i] = new LongAdder();
            }
        }

        void record(long nanos) {
            long n = Math.max(0L, nanos);
            this.buckets[ServerMetrics.bucket(n)].increment();
            this.sumNanos.add(n);
        }
    }
}
