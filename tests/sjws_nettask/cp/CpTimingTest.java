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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import sjws_nettask.cluster.GroupOptions;

class CpTimingTest {

    private static final long MS = 1_000_000L;

    @Test
    void masterDefaultsPass() {
        // 80 ms lease * 1.01 + 10 ms = 90.8 ms < 100 ms (control-plane-hosting.md §6.4).
        CpTiming.check(CpStore.groupOptions(), CpTiming.DEFAULT_DRIFT_RATIO, CpTiming.DEFAULT_MARGIN);
    }

    @Test
    void boundaryIsStrict() {
        CpTiming.check(80 * MS, 100 * MS, 0.0, 19 * MS);
        assertThrows(IllegalStateException.class, () -> CpTiming.check(80 * MS, 100 * MS, 0.0, 20 * MS));
        // 80 * 1.01 + 19.2 = 100.0: equal is refused.
        assertThrows(IllegalStateException.class, () -> CpTiming.check(80 * MS, 100 * MS, 0.01, 19_200_000L));
        CpTiming.check(80 * MS, 100 * MS, 0.01, 19_199_000L);
    }

    @Test
    void failingTimingRefuses() {
        var tooLong = new GroupOptions(1024, Duration.ofMillis(100), Duration.ofMillis(50), 10);
        var e = assertThrows(IllegalStateException.class,
                () -> CpTiming.check(tooLong, CpTiming.DEFAULT_DRIFT_RATIO, CpTiming.DEFAULT_MARGIN));
        assertTrue(e.getMessage().contains("refusing to start"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> CpTiming.check(80 * MS, 100 * MS, -0.1, 0));
        assertThrows(IllegalStateException.class, () -> CpTiming.check(80 * MS, 100 * MS, Double.NaN, 0));
        assertThrows(IllegalStateException.class, () -> CpTiming.check(80 * MS, 100 * MS, 0.0, -1));
    }

    @Test
    void keyRingLoadFailsClosedOnMissingFiles() {
        var cfg = new CpKeyConfig(java.nio.file.Path.of("k"), java.nio.file.Path.of("r"), java.nio.file.Path.of("s"),
                null, false, 0L);
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg));
    }
}
