package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.messaging.http.MessagingHooks;

/** #400 composition-root attach of MessagingMetrics onto ObserverSlot / /metrics. */
class MessagingMetricsAttachTest {

    static final class FakeMessaging implements OptionalModule {
        final AtomicReference<MessagingHooks> hooks = new AtomicReference<>();

        public void hooks(MessagingHooks h) {
            hooks.set(h);
        }

        @Override
        public void close() {
        }
    }

    static final class Plain implements OptionalModule {
        @Override
        public void close() {
        }
    }

    /** hooks() present but invoke fails — prepare/install must catch ReflectiveOperationException. */
    static final class BrokenMessaging implements OptionalModule {
        public void hooks(MessagingHooks h) {
            throw new RuntimeException("broken hooks");
        }

        @Override
        public void close() {
        }
    }

    @TempDir
    Path dir;

    @Test
    void findMessagingHttpWalksBoth() {
        var msg = new FakeMessaging();
        assertSame(msg, Sjws_nettask.findMessagingHttp(msg));
        assertNull(Sjws_nettask.findMessagingHttp(new Plain()));
        assertNull(Sjws_nettask.findMessagingHttp(OptionalModules.NONE));
        var nested = new OptionalModules.Both(new Plain(), new OptionalModules.Both(msg, new Plain()));
        assertSame(msg, Sjws_nettask.findMessagingHttp(nested));
        assertNull(Sjws_nettask.findMessagingHttp(new OptionalModules.Both(new Plain(), new Plain())));
    }

    @Test
    void attachPublishesHistogramsOnMetricsScrape() throws Exception {
        var html = Files.createDirectories(dir.resolve("html"));
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.ON).build();
        try (var server = SjwsServer.start(cfg)) {
            var msg = new FakeMessaging();
            Sjws_nettask.attachMessagingMetricsExporter(new OptionalModules.Both(msg, OptionalModules.NONE), server);
            assertTrue(msg.hooks.get() instanceof sjws_nettask.messaging.stats.MessagingMetrics);
            msg.hooks.get().produced("orders", 200, 8, 1_000_000L);
            var text = HttpTestSupport.get(server.tcpPort(), "/metrics", 2000).bodyText();
            assertTrue(text.contains("sjws_messaging_produce_duration_seconds"), text);
            assertTrue(text.contains("topic=\"orders\""), text);
        }
    }

    @Test
    void prepareHooksBeforeServerStartThenAttach() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-order"));
        var msg = new FakeMessaging();
        var metrics = Sjws_nettask.prepareMessagingMetrics(msg);
        assertTrue(metrics != null);
        assertTrue(msg.hooks.get() instanceof sjws_nettask.messaging.stats.MessagingMetrics);
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.ON).build();
        try (var server = SjwsServer.start(cfg)) {
            Sjws_nettask.attachMessagingMetricsExporter(metrics, server);
            msg.hooks.get().produced("orders", 200, 8, 1_000_000L);
            var text = HttpTestSupport.get(server.tcpPort(), "/metrics", 2000).bodyText();
            assertTrue(text.contains("topic=\"orders\""), text);
        }
    }

    @Test
    void attachWithoutMessagingIsNoOp() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-none"));
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.ON).build();
        try (var server = SjwsServer.start(cfg)) {
            Sjws_nettask.attachMessagingMetricsExporter(new Plain(), server);
            var text = HttpTestSupport.get(server.tcpPort(), "/metrics", 2000).bodyText();
            assertFalse(text.contains("sjws_messaging_produce_duration_seconds"), text);
        }
    }

    @Test
    void metricsOffLeavesHooksNoneUntilArm() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-off"));
        var msg = new FakeMessaging();
        var metrics = Sjws_nettask.prepareMessagingMetrics(msg, false);
        assertTrue(metrics != null);
        assertNull(msg.hooks.get(), "hooks stay unset while metrics are off");
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.OFF).build();
        try (var server = SjwsServer.start(cfg)) {
            Sjws_nettask.attachMessagingMetricsExporter(metrics, server);
            Sjws_nettask.deferMessagingHooksIfNeeded(msg, metrics, false, server);
            assertNull(msg.hooks.get());
            server.armMetrics();
            assertTrue(msg.hooks.get() instanceof sjws_nettask.messaging.stats.MessagingMetrics);
        }
    }


    @Test
    void deferNoOpWhenHooksAlreadyInstalled() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-on"));
        var msg = new FakeMessaging();
        var metrics = Sjws_nettask.prepareMessagingMetrics(msg, true);
        assertTrue(msg.hooks.get() instanceof sjws_nettask.messaging.stats.MessagingMetrics);
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.ON).build();
        try (var server = SjwsServer.start(cfg)) {
            Sjws_nettask.attachMessagingMetricsExporter(metrics, server);
            Sjws_nettask.deferMessagingHooksIfNeeded(msg, metrics, true, server);
            // arm must not NPE / reinstall when nothing was deferred
            server.armMetrics();
            assertSame(metrics, msg.hooks.get());
        }
    }

    @Test
    void prepareWithoutMessagingReturnsNull() {
        assertNull(Sjws_nettask.prepareMessagingMetrics(new Plain(), false));
        assertNull(Sjws_nettask.prepareMessagingMetrics(OptionalModules.NONE, true));
    }

    @Test
    void installHooksNoOpOnPlainModule() {
        var metrics = new sjws_nettask.messaging.stats.MessagingMetrics();
        Sjws_nettask.installMessagingHooks(new Plain(), metrics);
        Sjws_nettask.installMessagingHooks(null, metrics);
        Sjws_nettask.installMessagingHooks(new Plain(), null);
        Sjws_nettask.deferMessagingHooksIfNeeded(new Plain(), null, false, null);
    }

    @Test
    void attachNullExporterIsNoOp() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-null"));
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.ON).build();
        try (var server = SjwsServer.start(cfg)) {
            Sjws_nettask.attachMessagingMetricsExporter((sjws_nettask.messaging.stats.MessagingMetrics) null, server);
            server.armMetrics();
        }
    }


    @Test
    void prepareReturnsNullWhenHooksInvokeFails() {
        assertNull(Sjws_nettask.prepareMessagingMetrics(new BrokenMessaging(), true));
        // installHooks=false skips invoke — still returns a metrics instance
        assertTrue(Sjws_nettask.prepareMessagingMetrics(new BrokenMessaging(), false) != null);
        var metrics = new sjws_nettask.messaging.stats.MessagingMetrics();
        Sjws_nettask.installMessagingHooks(new BrokenMessaging(), metrics);
    }

}
