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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RateGuardTest {

    static final long S = 1_000_000_000L;

    @Test
    void judgedOncePerWindowOnTheAverage() {
        var g = new RateGuard(10 * S, 1000, 0);
        assertTrue(g.ok(0, 9 * S), "no verdict inside the first window");
        assertTrue(g.ok(10_000, 10 * S), "exactly the minimum");
        assertTrue(g.ok(10_000, 15 * S), "new window started at 10 s");
        assertFalse(g.ok(19_999, 20 * S), "one byte short over the second window");
    }

    @Test
    void dripReaderIsCaughtAlthoughEveryChunkProgresses() {
        // one 64 KiB chunk taken just before every 30 s write timeout = 2.2 KB/s < 8 KiB/s
        var g = new RateGuard(30 * S, 8 * 1024, 0);
        long sent = 0;
        boolean cut = false;
        for (int i = 1; i <= 10 && !cut; i++) {
            sent += 64 * 1024;
            cut = !g.ok(sent, i * 29 * S);
        }
        assertTrue(cut);
    }

    @Test
    void zeroDisablesTheCheck() {
        var g = new RateGuard(1, 0, 0);
        assertTrue(g.ok(0, Long.MAX_VALUE / 2));
    }
}
