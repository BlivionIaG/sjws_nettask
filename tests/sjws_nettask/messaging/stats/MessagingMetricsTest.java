package sjws_nettask.messaging.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

final class MessagingMetricsTest {

    @Test
    void produceAndFetchHistogramsUseDecidedNamesAndReuseLatencyBounds() {
        var m = new MessagingMetrics();
        m.produced("orders", 200, 16, 1_500_000L); // 1.5 ms
        m.fetched("orders", 200, 32, 3_000_000L);
        var text = m.prometheus();
        assertTrue(text.contains("# TYPE sjws_messaging_produce_duration_seconds histogram"), text);
        assertTrue(text.contains("# TYPE sjws_messaging_fetch_duration_seconds histogram"), text);
        assertTrue(text.contains("sjws_messaging_produce_duration_seconds_bucket{topic=\"orders\",status=\"200\",le=\"0.002\"}"), text);
        assertTrue(text.contains("sjws_messaging_fetch_duration_seconds_count{topic=\"orders\",status=\"200\"} 1"), text);
        assertFalse(text.contains("sjws_swarm_stream_"), text);
        // Proposed offset gauges are not emitted until Messaging Core confirms names.
        assertFalse(text.contains("sjws_messaging_log_end_offset"), text);
        assertFalse(text.contains("sjws_messaging_replication_leader"), text);
    }

    @Test
    void emptyExporterEmitsHelpLinesOnlyForHistograms() {
        var text = new MessagingMetrics().prometheus();
        assertTrue(text.contains("sjws_messaging_produce_duration_seconds"), text);
        assertFalse(text.contains("sjws_messaging_lag_offsets"), text);
    }

    @Test
    void nullAndEmptyTopicFoldToOther() {
        var m = new MessagingMetrics();
        m.produced(null, 400, 0, 100);
        m.fetched("", 404, 0, 200);
        var text = m.prometheus();
        assertTrue(text.contains("topic=\"other\",status=\"400\""), text);
        assertTrue(text.contains("topic=\"other\",status=\"404\""), text);
    }

    @Test
    void negativeNanosClampToZeroAndAppendToWorks() {
        var m = new MessagingMetrics();
        m.produced("t", 200, 1, -5);
        var sb = new StringBuilder();
        m.appendTo(sb);
        assertTrue(sb.toString().contains("sjws_messaging_produce_duration_seconds_sum{topic=\"t\",status=\"200\"} 0.000000"),
                sb.toString());
    }

    @Test
    void escapeTopicLabelSpecialChars() {
        var m = new MessagingMetrics();
        m.produced("a\"b\\c\nd", 200, 1, 1_000);
        var text = m.prometheus();
        assertTrue(text.contains("topic=\"a\\\"b\\\\c\\nd\""), text);
    }

    @Test
    void statsNullAndNoneOmitLagFamily() {
        var m = new MessagingMetrics();
        m.stats(null);
        assertFalse(m.prometheus().contains("sjws_messaging_lag_offsets"));
        m.stats(() -> MessagingStats.NONE);
        assertFalse(m.prometheus().contains("sjws_messaging_lag_offsets"));
        m.stats(() -> null);
        assertFalse(m.prometheus().contains("sjws_messaging_lag_offsets"));
    }

    @Test
    void statsSnapshotAppendsLagGauges() {
        var m = new MessagingMetrics();
        var snap = new MessagingStats.StatsSnapshot(1L, List.of(
                new MessagingStats.PartitionStats("orders", 0, false, 10, 10, 1,
                        List.of(new MessagingStats.GroupStats("g1", 5, 5, 1, 1, 0, 0, 0, 0, 0, 0)))));
        m.stats(() -> () -> snap);
        var text = m.prometheus();
        assertTrue(text.contains("sjws_messaging_lag_offsets"), text);
        assertTrue(text.contains("topic=\"orders\""), text);
    }

    @Test
    void topicCardinalityCapFoldsIntoOther() {
        var m = new MessagingMetrics();
        // Cap is on distinct topics, not topic/status pairs.
        for (int i = 0; i < MessagingMetrics.TOPIC_CAP; i++) {
            m.produced("t" + i, 200, 1, 1000);
        }
        // New status on an admitted topic keeps its label.
        m.produced("t0", 500, 1, 1000);
        var text = m.prometheus();
        assertTrue(text.contains("topic=\"t0\",status=\"500\""), text);
        assertFalse(text.contains("sjws_messaging_topics_folded_total"), text);
        // A new topic beyond the cap folds.
        m.produced("overflow-topic", 200, 1, 1000);
        text = m.prometheus();
        assertTrue(text.contains("sjws_messaging_topics_folded_total"), text);
        assertTrue(text.contains("topic=\"other\""), text);
        m.produced("t0", 200, 1, 2000);
        assertTrue(m.prometheus().contains(
                "sjws_messaging_produce_duration_seconds_count{topic=\"t0\",status=\"200\"} 2"),
                m.prometheus());
    }

    @Test
    void concurrentAdmissionDoesNotExposeThenRollback() throws Exception {
        var m = new MessagingMetrics();
        for (int i = 0; i < MessagingMetrics.TOPIC_CAP - 1; i++) {
            m.produced("seed" + i, 200, 1, 100);
        }
        int threads = 32;
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            futures.add(pool.submit(() -> {
                barrier.await();
                m.produced("race-" + id, 200, 1, 1000);
                return null;
            }));
        }
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();
        var text = m.prometheus();
        int named = 0;
        for (int i = 0; i < threads; i++) {
            if (text.contains("topic=\"race-" + i + "\"")) {
                named++;
            }
        }
        // One winner keeps its label; the rest fold to other — never a published-then-removed name.
        assertTrue(named <= 1, "named=" + named);
        assertTrue(named == 1 || text.contains("topic=\"other\""), text);
        if (text.contains("sjws_messaging_topics_folded_total")) {
            assertTrue(text.contains("topic=\"other\""), text);
        }
    }


    @Test
    void sameTopicLastSlotStaysAttributed() throws Exception {
        var m = new MessagingMetrics();
        for (int i = 0; i < MessagingMetrics.TOPIC_CAP - 1; i++) {
            m.produced("seed" + i, 200, 1, 100);
        }
        int threads = 32;
        var barrier = new java.util.concurrent.CyclicBarrier(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                m.produced("last-slot", 200, 1, 1000);
                return null;
            }));
        }
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();
        var text = m.prometheus();
        assertTrue(text.contains("topic=\"last-slot\""), text);
        // All samples for the same name must share the admitted label — no split to other.
        assertFalse(text.contains("topic=\"other\""), text);
        assertFalse(text.contains("sjws_messaging_topics_folded_total"), text);
    }

}
