package sjws_nettask.messaging.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
