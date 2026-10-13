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
package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.RateLimitFilter.By;
import sjws_nettask.routing.RateLimitFilter.Limit;

class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final AtomicInteger passed = new AtomicInteger();
    private final RequestFilter.Chain next = r -> {
        this.passed.incrementAndGet();
        return StaticFileHandler.error(200, false);
    };

    private RateLimitFilter filter(Limit limit) {
        return new RateLimitFilter(limit, this.now::get);
    }

    private static Request from(String ip) {
        return new Request("GET", "/", RequestHeaders.NONE, new InetSocketAddress(ip, 1234), false);
    }

    private static Request withHeader(String name, String value) {
        return new Request("GET", "/", RequestHeaders.of(Map.of(name, List.of(value))));
    }

    @Test
    void burstThenRefusedThenRefilled() {
        var f = filter(new Limit(By.PEER, null, 10, 's', 3, 64));
        for (int i = 0; i < 3; i++) {
            assertEquals(200, f.filter(from("10.0.0.1"), this.next).status());
        }
        Response refused = f.filter(from("10.0.0.1"), this.next);
        assertEquals(429, refused.status());
        assertEquals("1", refused.headers().get("retry-after"));
        assertEquals(3, this.passed.get());
        this.now.addAndGet(100_000_000L); // one interval at 10/s
        assertEquals(200, f.filter(from("10.0.0.1"), this.next).status());
        assertEquals(429, f.filter(from("10.0.0.1"), this.next).status());
        this.now.addAndGet(10_000_000_000L); // long idle: back to a full burst, not more
        for (int i = 0; i < 3; i++) {
            assertEquals(200, f.filter(from("10.0.0.1"), this.next).status());
        }
        assertEquals(429, f.filter(from("10.0.0.1"), this.next).status());
    }

    @Test
    void retryAfterRoundsUpToTheNextToken() {
        var f = filter(new Limit(By.ROUTE, null, 1, 'm', 1, 1));
        assertEquals(200, f.filter(from("10.0.0.1"), this.next).status());
        assertEquals("60", f.filter(from("10.0.0.2"), this.next).headers().get("retry-after"));
        this.now.addAndGet(59_500_000_000L);
        assertEquals("1", f.filter(from("10.0.0.2"), this.next).headers().get("retry-after"));
        var head = f.filter(new Request("HEAD", "/"), this.next);
        assertEquals(429, head.status());
        assertTrue(head.headOnly());
    }

    @Test
    void peersAreIndependentUnlessTheyShareAStripe() {
        var f = filter(new Limit(By.PEER, null, 1, 'h', 1, 1 << 16));
        int admitted = 0;
        for (int i = 0; i < 200; i++) {
            if (f.filter(from("10.1." + (i / 250) + "." + (i % 250)), this.next).status() == 200) {
                admitted++;
            }
        }
        assertTrue(admitted >= 199, "admitted " + admitted); // 200 peers in 65536 stripes
        var tiny = filter(new Limit(By.PEER, null, 1, 'h', 1, 1));
        assertEquals(200, tiny.filter(from("10.0.0.1"), this.next).status());
        assertEquals(429, tiny.filter(from("10.0.0.2"), this.next).status()); // one stripe: shared, stricter
    }

    @Test
    void headerKeysAndKeylessRequestsShareOneBucket() {
        var f = filter(new Limit(By.HEADER, "X-Api-Key", 1, 'h', 1, 1024));
        assertEquals("x-api-key", f.limit().header());
        assertEquals(200, f.filter(withHeader("x-api-key", "a"), this.next).status());
        assertEquals(429, f.filter(withHeader("x-api-key", "a"), this.next).status());
        assertEquals(200, f.filter(new Request("GET", "/"), this.next).status());
        assertEquals(429, f.filter(withHeader("x-api-key", ""), this.next).status()); // empty = no key
        var peer = filter(new Limit(By.PEER, null, 1, 'h', 1, 8));
        assertEquals(200, peer.filter(new Request("GET", "/"), this.next).status());
        assertEquals(429, peer.filter(new Request("GET", "/"), this.next).status());
        var unresolved = new Request("GET", "/", RequestHeaders.NONE, InetSocketAddress.createUnresolved("x", 1), false);
        assertEquals(429, peer.filter(unresolved, this.next).status());
    }

    @Test
    void concurrentCallersNeverExceedTheBurst() throws Exception {
        var f = filter(new Limit(By.ROUTE, null, 1, 'h', 500, 1));
        var start = new CountDownLatch(1);
        var threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 1000; i++) {
                    f.filter(from("10.0.0.1"), this.next);
                }
            });
        }
        start.countDown();
        for (var t : threads) {
            t.join();
        }
        assertEquals(500, this.passed.get());
    }

    @Test
    void manyDistinctPeersUseTheFixedTable() {
        var f = filter(new Limit(By.PEER, null, 1000, 's', 1000, 256));
        for (int i = 0; i < 100_000; i++) {
            f.filter(from("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255)), this.next);
        }
        assertTrue(this.passed.get() > 0);
        int[] hits = new int[256];
        for (int i = 0; i < 65_536; i++) {
            hits[f.slot(from("10.2." + (i >> 8) + "." + (i & 255)))]++;
        }
        for (int h : hits) {
            assertTrue(h > 128 && h < 384, "stripe load " + h); // even spread of adjacent addresses
        }
    }

    @Test
    void limitValidation() {
        assertThrows(NullPointerException.class, () -> new Limit(null, null, 1, 's', 1, 1));
        assertThrows(NullPointerException.class, () -> new Limit(By.HEADER, null, 1, 's', 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, "x", 1, 's', 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 1, 'd', 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 0, 's', 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 2_000_000_000L, 's', 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 1, 's', 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 1, 's', 2_000_000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 1, 's', 1, 3));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 1, 's', 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Limit(By.PEER, null, 1, 's', 1, 1 << 21));
        assertEquals(1, new Limit(By.ROUTE, null, 1, 's', 1, 4096).buckets());
        assertEquals(1_000_000L, new Limit(By.PEER, null, 1000, 's', 1, 1).intervalNanos());
        assertEquals(1_000_000_000L, new Limit(By.PEER, null, 60, 'm', 1, 1).intervalNanos());
        assertEquals(1_000_000_000L, new Limit(By.PEER, null, 3600, 'h', 1, 1).intervalNanos());
        assertEquals(1L, new Limit(By.PEER, null, 1_000_000_000L, 's', 1, 1).intervalNanos());
        assertEquals("5/m", new Limit(By.PEER, null, 5, 'm', 1, 1).rate());
        assertNotNull(new RateLimitFilter(new Limit(By.PEER, null, 1, 'h', 1_000_000, 1)));
        assertNotEquals(0, AliasTable.mix(1));
    }

    @Test
    void tableRejectsABadShapeAndReportsDelayWithoutTaking() {
        assertThrows(IllegalArgumentException.class, () -> new GcraTable(0, 1, 1, () -> 1L));
        assertThrows(IllegalArgumentException.class, () -> new GcraTable(1, 0, 1, () -> 1L));
        assertThrows(IllegalArgumentException.class, () -> new GcraTable(1, 1, 3, () -> 1L));
        var table = new GcraTable(1_000_000_000L, 1, 4, () -> 1_000_000_000L);
        assertEquals(0, table.delay(0));
        assertEquals(0, table.admit(table.stripe(1)));
        assertTrue(table.delay(table.stripe(1)) > 0);
        assertEquals(4, table.buckets());
        assertEquals(4, table.keylessSlot());
    }
}
