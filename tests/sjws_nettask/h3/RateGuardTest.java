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
    void judgedOncePerWindowOnTheConnectionAverage() {
        var g = new RateGuard(10 * S, 1000);
        g.begin(0);
        assertTrue(g.ok(9 * S), "no verdict inside the first window");
        g.sent(6_000);
        g.begin(9 * S); // a second response on the same connection: both count
        g.sent(4_000);
        assertTrue(g.ok(10 * S), "exactly the minimum, summed over the connection");
        g.sent(9_999);
        assertTrue(g.ok(15 * S), "the verdict holds until the next window ends");
        assertFalse(g.ok(20 * S), "one byte short over the second window");
        assertFalse(g.ok(21 * S), "and stays slow for the whole next window");
        g.sent(20_000);
        assertTrue(g.ok(30 * S), "recovered");
    }

    @Test
    void manySlowStreamsAddUp() {
        // 20 parallel downloads at 200 B/s each on one connection = 4000 B/s >= 2048
        var g = new RateGuard(10 * S, 2048);
        for (int i = 0; i < 20; i++) {
            g.begin(0);
        }
        for (int t = 1; t <= 30; t++) {
            for (int i = 0; i < 20; i++) {
                g.sent(200);
            }
            assertTrue(g.ok(t * S), "second " + t);
        }
    }

    @Test
    void idleTimeIsNotCounted() {
        var g = new RateGuard(10 * S, 1000);
        g.begin(0);
        g.sent(10_000);
        assertTrue(g.ok(10 * S));
        g.end();
        g.begin(100 * S); // 90 s idle, then a new response
        assertTrue(g.ok(105 * S));
        g.sent(10_000);
        assertTrue(g.ok(110 * S));
    }

    @Test
    void dripReaderIsCaughtAlthoughEveryChunkProgresses() {
        // one 64 KiB chunk just before every 30 s write timeout = 2.2 KB/s < 4 KiB/s
        var g = new RateGuard(30 * S, 4 * 1024);
        g.begin(0);
        boolean cut = false;
        for (int i = 1; i <= 10 && !cut; i++) {
            g.sent(64 * 1024);
            cut = !g.ok(i * 29 * S);
        }
        assertTrue(cut);
    }

    @Test
    void zeroDisablesTheCheck() {
        var g = new RateGuard(1, 0);
        g.begin(0);
        assertTrue(g.ok(Long.MAX_VALUE / 2));
    }
}
