package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.ServerConfig.MetricsExposure;
import sjws_nettask.observe.RequestObserver;

/**
 * #315 N5 / B1: composition-root attach of msgrepl install counters onto the server's ObserverSlot.
 * Suppliers must survive deferred {@link SjwsServer#armMetrics()} and must never mutate
 * {@link RequestObserver#NONE}.
 */
class MsgreplInstallMetricsAttachTest {

    /** Stand-in for {@code HaMessaging}: exposes the #315 getters without loading msgrepl. */
    static final class FakeHa implements OptionalModule {
        final AtomicLong pin = new AtomicLong(2);
        final AtomicLong oversized = new AtomicLong(7);
        boolean explode;

        public long installPinExhausted() {
            if (explode) {
                throw new RuntimeException("boom");
            }
            return pin.get();
        }

        public long installOversizedAbandoned() {
            return oversized.get();
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

    @TempDir
    Path dir;

    @Test
    void findHaMessagingWalksBothAndMatchesGetters() {
        var ha = new FakeHa();
        assertSame(ha, Sjws_nettask.findHaMessaging(ha));
        assertNull(Sjws_nettask.findHaMessaging(new Plain()));
        assertNull(Sjws_nettask.findHaMessaging(OptionalModules.NONE));
        var nested = new OptionalModules.Both(new Plain(), new OptionalModules.Both(ha, new Plain()));
        assertSame(ha, Sjws_nettask.findHaMessaging(nested));
        assertNull(Sjws_nettask.findHaMessaging(new OptionalModules.Both(new Plain(), new Plain())));
    }

    @Test
    void attachPublishesCountersOnArmedMetrics() throws Exception {
        var html = Files.createDirectories(dir.resolve("html"));
        var cfg = ServerConfig.builder(html).metricsExposure(MetricsExposure.ON).build();
        try (var server = SjwsServer.start(cfg)) {
            var ha = new FakeHa();
            Sjws_nettask.attachMsgreplInstallMetrics(new OptionalModules.Both(ha, OptionalModules.NONE), server);
            var text = server.metrics().prometheus();
            assertTrue(text.contains("sjws_msgrepl_install_pin_exhausted_total 2\n"), text);
            assertTrue(text.contains("sjws_msgrepl_install_oversized_abandoned_total 7\n"), text);
            ha.pin.set(9);
            ha.oversized.set(11);
            text = server.metrics().prometheus();
            assertTrue(text.contains("sjws_msgrepl_install_pin_exhausted_total 9\n"), text);
            assertTrue(text.contains("sjws_msgrepl_install_oversized_abandoned_total 11\n"), text);
        }
    }

    @Test
    void attachWithObserveOffSurvivesDeferredArm() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-arm"));
        try (var server = SjwsServer.start(ServerConfig.builder(html).build())) {
            var ha = new FakeHa();
            ha.pin.set(3);
            ha.oversized.set(5);
            assertSame(RequestObserver.NONE.metrics(), server.metrics());
            Sjws_nettask.attachMsgreplInstallMetrics(ha, server);
            // Still on NONE's registry, but NONE must stay at 0 (B1: never mutate it).
            assertSame(RequestObserver.NONE.metrics(), server.metrics());
            var noneText = RequestObserver.NONE.metrics().prometheus();
            assertTrue(noneText.contains("sjws_msgrepl_install_pin_exhausted_total 0\n"), noneText);
            assertTrue(noneText.contains("sjws_msgrepl_install_oversized_abandoned_total 0\n"), noneText);
            assertFalse(noneText.contains("sjws_msgrepl_install_pin_exhausted_total 3\n"), noneText);

            var armed = server.armMetrics();
            assertNotSame(RequestObserver.NONE.metrics(), armed);
            var text = armed.prometheus();
            assertTrue(text.contains("sjws_msgrepl_install_pin_exhausted_total 3\n"), text);
            assertTrue(text.contains("sjws_msgrepl_install_oversized_abandoned_total 5\n"), text);
            ha.pin.set(8);
            ha.oversized.set(13);
            text = server.metrics().prometheus();
            assertTrue(text.contains("sjws_msgrepl_install_pin_exhausted_total 8\n"), text);
            assertTrue(text.contains("sjws_msgrepl_install_oversized_abandoned_total 13\n"), text);

            // NONE stays clean after arm too.
            noneText = RequestObserver.NONE.metrics().prometheus();
            assertTrue(noneText.contains("sjws_msgrepl_install_pin_exhausted_total 0\n"), noneText);
            assertTrue(noneText.contains("sjws_msgrepl_install_oversized_abandoned_total 0\n"), noneText);
        }
    }

    @Test
    void attachDoesNotMutateNone() throws Exception {
        var html = Files.createDirectories(dir.resolve("html-none"));
        var before = RequestObserver.NONE.metrics().prometheus();
        assertTrue(before.contains("sjws_msgrepl_install_pin_exhausted_total 0\n"), before);
        try (var server = SjwsServer.start(ServerConfig.builder(html).build())) {
            var ha = new FakeHa();
            ha.pin.set(99);
            ha.oversized.set(88);
            Sjws_nettask.attachMsgreplInstallMetrics(ha, server);
            var after = RequestObserver.NONE.metrics().prometheus();
            assertEquals(before, after, "attach must not mutate the shared NONE registry");
            assertTrue(after.contains("sjws_msgrepl_install_pin_exhausted_total 0\n"), after);
            assertFalse(after.contains("sjws_msgrepl_install_pin_exhausted_total 99\n"), after);
        }
    }

    @Test
    void attachWithNoHaIsANoOp() throws Exception {
        var html = Files.createDirectories(dir.resolve("html2"));
        try (var server = SjwsServer.start(ServerConfig.builder(html).build())) {
            Sjws_nettask.attachMsgreplInstallMetrics(OptionalModules.NONE, server);
            Sjws_nettask.attachMsgreplInstallMetrics(new OptionalModules.Both(new Plain(), new Plain()), server);
            var armed = server.armMetrics();
            assertTrue(armed.prometheus().contains("sjws_msgrepl_install_pin_exhausted_total 0\n"));
            assertTrue(RequestObserver.NONE.metrics().prometheus()
                    .contains("sjws_msgrepl_install_pin_exhausted_total 0\n"));
        }
    }

    @Test
    void invokeLongReturnsZeroOnReflectiveFailure() throws Exception {
        var ha = new FakeHa();
        Method pin = FakeHa.class.getMethod("installPinExhausted");
        assertEquals(2L, Sjws_nettask.invokeLong(pin, ha));
        ha.explode = true;
        assertEquals(0L, Sjws_nettask.invokeLong(pin, ha));
        assertEquals(0L, Sjws_nettask.invokeLong(pin, new Plain()));
    }
}
