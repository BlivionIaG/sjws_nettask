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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.observe.ServerMetrics;

/** #259: sjws_cp_idempotency_evicted_total moves after an eviction when attached. */
class IdempotencyMetricsTest {

    @TempDir
    Path dir;

    @Test
    void seriesMovesAfterCapEvictionWhenAttached() throws Exception {
        var keys = KeyRing.generate("online-1", 1_700_000_000_000L + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            store.useIdempotencyTableForTest(new IdempotencyTable(100, 2));
            clock.advance(Duration.ofMillis(100));
            store.drive();
            assertTrue(store.leadership().leaderReady());

            var metrics = new ServerMetrics();
            var before = metrics.prometheus();
            assertTrue(before.contains("sjws_cp_idempotency_evicted_total 0\n"), before);

            metrics.setCpIdempotencyEvictions(store::idempotencyEvictions);
            assertEquals(0, store.idempotencyEvictions());

            // Three keyed advances with cap=2 → at least one eviction.
            assertEquals(200, store.rollout(java.util.List.of("a", "b", "c")).status());
            assertEquals(200, store.advance("m1").status());
            assertEquals(200, store.advance("m2").status());
            assertEquals(200, store.advance("m3").status());
            assertTrue(store.idempotencyEvictions() >= 1, "cap eviction");

            var text = metrics.prometheus();
            assertTrue(text.contains("sjws_cp_idempotency_evicted_total " + store.idempotencyEvictions() + "\n"), text);
            assertTrue(text.contains("# TYPE sjws_cp_idempotency_evicted_total counter"), text);
        }
    }

    @Test
    void unattachedSeriesStaysZeroWhenCpIsOff() {
        // No CpStore / CpHost: the series exists at 0 (zero cost when CP is off).
        var metrics = new ServerMetrics();
        assertTrue(metrics.prometheus().contains("sjws_cp_idempotency_evicted_total 0\n"));
    }
}
