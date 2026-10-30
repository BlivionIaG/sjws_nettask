package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.observe.ServerMetrics;

/**
 * #315: install observability follow-ups — abandoned-oversized counter (N2) and ServerMetrics wiring (N5).
 * Each assertion fails when its production fix is removed.
 */
@Timeout(120)
class InstallObservability315Test {

    @TempDir Path dir;
    SnapshotCluster c;

    @AfterEach
    void stop() throws Exception {
        if (c != null) c.close();
    }

    /**
     * #315 N2: a consensus image over {@code max_install_bytes} abandons the install (marker kept) and increments
     * {@code installOversizedAbandoned}. Mutation: drop the bump — this test fails on the counter.
     */
    @Test
    void oversizedConsensusInstallBumpsAbandonedCounter() throws Exception {
        var settings = new Snapshots(20, 5, MsgSnapshot.MIN_CHUNK, 64L << 20, Duration.ofHours(1), 1L);
        c = new SnapshotCluster(dir, settings, 2048, 2048);
        int l = c.leader(), f = 1 - l;
        long end = -1;
        for (int k = 0; k < 30; k++) end = c.produce("pid1", k, "x".repeat(60) + k) + 1;
        c.awaitEnd(f, end);
        c.cut(f);
        end = -1;
        for (int k = 30; k < 80; k++) end = c.produce("pid1", k, "x".repeat(60) + k) + 1;
        assertTrue(c.data[l].snapshot());
        var m = c.data[l].storage.loadSnapshot(SnapshotCluster.GROUP,
                sjws_nettask.cluster.GroupOptions.DEFAULT_MAX_SNAPSHOT_BYTES);
        assertTrue(MsgSnapshot.decode(m.data()).dataBytes() > 1, "fixture must exceed the 1-byte bound");
        long before = c.data[f].replica.installOversizedAbandoned();
        long beforeRuntime = c.data[f].runtime.installOversizedAbandoned.get();
        c.heal(f);
        for (int k = 0; k < 20_000 && c.data[f].replica.installOversizedAbandoned() == before; k++) {
            c.step();
            Thread.sleep(0, 100_000);
        }
        assertTrue(c.data[f].replica.installOversizedAbandoned() > before, "abandoned counter must tick");
        assertTrue(c.data[f].runtime.installOversizedAbandoned.get() > beforeRuntime, "runtime counter must tick");
        assertEquals(0, c.data[f].replica.installs(), "oversized consensus must not install");
        assertTrue(c.data[f].replica.installPending(), "marker stays for operator intervention");
    }

    /**
     * #315 N5: {@link ServerMetrics#setMsgreplInstallCounters} exposes the sibling install counters on scrape text
     * (Observability: suppliers read at scrape time; attaching does not arm the filter). Mutation: drop the prometheus
     * lines — this test fails on the sample values.
     */
    @Test
    void installCountersAppearOnServerMetricsWhenAttached() {
        var pin = new AtomicLong(3);
        var oversized = new AtomicLong(5);
        var metrics = new ServerMetrics();
        var zero = metrics.prometheus();
        assertTrue(zero.contains("sjws_msgrepl_install_pin_exhausted_total 0\n"), zero);
        assertTrue(zero.contains("sjws_msgrepl_install_oversized_abandoned_total 0\n"), zero);
        metrics.setMsgreplInstallCounters(pin::get, oversized::get);
        var text = metrics.prometheus();
        assertTrue(text.contains("sjws_msgrepl_install_pin_exhausted_total 3\n"), text);
        assertTrue(text.contains("sjws_msgrepl_install_oversized_abandoned_total 5\n"), text);
        assertTrue(text.contains("# TYPE sjws_msgrepl_install_pin_exhausted_total counter"), text);
        assertTrue(text.contains("# TYPE sjws_msgrepl_install_oversized_abandoned_total counter"), text);
        pin.set(4);
        oversized.set(6);
        var next = metrics.prometheus();
        assertTrue(next.contains("sjws_msgrepl_install_pin_exhausted_total 4\n"), next);
        assertTrue(next.contains("sjws_msgrepl_install_oversized_abandoned_total 6\n"), next);
    }
}
