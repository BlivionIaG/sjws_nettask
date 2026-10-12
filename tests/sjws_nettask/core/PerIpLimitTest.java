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
package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PerIpLimitTest {

    @Test
    void countsPerAddressAndForgetsAtZero() throws Exception {
        var limit = new PerIpLimit(2);
        var a = InetAddress.getByName("192.0.2.1");
        var b = InetAddress.getByName("2001:db8::1");
        assertTrue(limit.tryAcquire(a));
        assertTrue(limit.tryAcquire(a));
        assertFalse(limit.tryAcquire(a), "third from one address");
        assertTrue(limit.tryAcquire(b), "other addresses are independent");
        assertEquals(2, limit.count(a));
        assertEquals(2, limit.addresses());
        limit.release(a);
        assertTrue(limit.tryAcquire(a));
        limit.release(a);
        limit.release(a);
        limit.release(b);
        assertEquals(0, limit.count(a));
        assertEquals(0, limit.addresses(), "no entry kept for idle addresses");
        limit.release(a); // unmatched: no negative counts
        assertEquals(0, limit.count(a));
        assertThrows(IllegalArgumentException.class, () -> new PerIpLimit(0));
    }

    @Test
    void neverGrantsMoreThanTheLimitConcurrently() throws Exception {
        var limit = new PerIpLimit(5);
        var a = InetAddress.getByName("192.0.2.7");
        var granted = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 64; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                if (limit.tryAcquire(a)) {
                    granted.incrementAndGet();
                }
            }));
        }
        for (var t : threads) {
            t.join();
        }
        assertEquals(5, granted.get());
        assertEquals(5, limit.count(a));
    }
}
