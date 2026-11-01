package sjws_nettask.observe;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.messaging.stats.MessagingMetrics;

final class MessagingMetricsExpositionTest {

    @Test
    void attachOnNoneSlotDoesNotMutateNoneExposition() {
        var slot = new ObserverSlot(RequestObserver.NONE);
        var metrics = new MessagingMetrics();
        slot.attachMessagingMetrics(metrics);
        metrics.produced("x", 200, 1, 1000);
        assertFalse(RequestObserver.NONE.exposition().contains("sjws_messaging_produce"));
        assertFalse(RequestObserver.NONE.exposition().contains("topic=\"x\""));
    }

    @Test
    void armedObserverIncludesMessagingFamilies() throws Exception {
        var metrics = new MessagingMetrics();
        metrics.produced("orders", 200, 1, 1_000_000L);
        var root = Files.createTempDirectory("mm-exp");
        var obs = RequestObserver.open(ServerConfig.builder(root).metricsExposure(MetricsExposure.ON).build());
        try {
            obs.attachMessagingMetrics(metrics);
            var text = obs.exposition();
            assertTrue(text.contains("sjws_messaging_produce_duration_seconds"), text);
            assertTrue(text.contains("topic=\"orders\""), text);
        } finally {
            obs.close();
        }
    }

    @Test
    void slotArmCopiesAttachedMessagingMetrics() throws Exception {
        var slot = new ObserverSlot(RequestObserver.NONE);
        var metrics = new MessagingMetrics();
        slot.attachMessagingMetrics(metrics);
        metrics.produced("late", 201, 1, 500_000L);
        var root = Files.createTempDirectory("mm-arm");
        var armed = slot.arm(ServerConfig.builder(root).metricsExposure(MetricsExposure.ON).build());
        try {
            var text = armed.exposition();
            assertTrue(text.contains("topic=\"late\""), text);
        } finally {
            armed.close();
        }
    }
}
