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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.WeightedHandler.Backend;
import sjws_nettask.routing.WeightedHandler.Match;

class WeightedHandlerTest {

    private static Handler status(int code) {
        return r -> StaticFileHandler.error(code, false);
    }

    private static WeightedHandler of(Match sticky, int... weights) {
        var list = new ArrayList<Backend>();
        for (int i = 0; i < weights.length; i++) {
            list.add(new Backend(status(200 + i), weights[i], null));
        }
        return new WeightedHandler(list, sticky);
    }

    private static RequestHeaders headers(String... nameValue) {
        var map = new java.util.LinkedHashMap<String, List<String>>();
        for (int i = 0; i < nameValue.length; i += 2) {
            map.computeIfAbsent(nameValue[i], k -> new ArrayList<>()).add(nameValue[i + 1]);
        }
        return RequestHeaders.of(map);
    }

    @Test
    void aliasTableIsExact() {
        int[][] cases = {{1}, {1, 1}, {90, 10}, {99, 1}, {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, {5, 0, 5}, {1_000_000, 1, 7},
            {3, 3, 3, 1}};
        for (var w : cases) {
            var h = of(null, w);
            long total = 0;
            for (int x : w) {
                total += x;
            }
            for (int i = 0; i < w.length; i++) {
                assertEquals((double) w[i] / total, h.share(i), 1e-12, java.util.Arrays.toString(w) + " #" + i);
            }
        }
    }

    @Test
    void randomPicksFollowTheWeights() {
        var h = of(null, 70, 20, 10);
        int[] seen = new int[3];
        int n = 300_000;
        for (int i = 0; i < n; i++) {
            seen[h.select(RequestHeaders.NONE)]++;
        }
        assertEquals(0.70, seen[0] / (double) n, 0.01);
        assertEquals(0.20, seen[1] / (double) n, 0.01);
        assertEquals(0.10, seen[2] / (double) n, 0.01);
    }

    @Test
    void pickCoversTheBitSpace() {
        var h = of(null, 1, 3);
        var rnd = new SplittableRandom(7);
        int one = 0;
        for (int i = 0; i < 100_000; i++) {
            one += h.pick(rnd.nextLong());
        }
        assertEquals(0.75, one / 100_000.0, 0.01);
        assertEquals(0, of(null, 1).pick(-1L));
    }

    @Test
    void stickyKeepsAClientAndSpreadsClients() {
        var h = of(new Match(true, "session", null), 50, 50);
        var a = headers("cookie", "theme=dark; session=abc123");
        int first = h.select(a);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, h.select(a));
        }
        int ones = 0;
        for (int i = 0; i < 20_000; i++) {
            ones += h.select(headers("cookie", "session=user" + i));
        }
        assertEquals(0.5, ones / 20_000.0, 0.03);
        var byHeader = of(new Match(false, "X-User", null), 1, 1, 1);
        assertEquals(byHeader.select(headers("x-user", "42")), byHeader.select(headers("x-user", "42")));
        int[] seen = new int[2];
        for (int i = 0; i < 2000; i++) { // no sticky value: random
            seen[h.select(RequestHeaders.NONE)]++;
        }
        assertTrue(seen[0] > 800 && seen[1] > 800);
    }

    @Test
    void pinsWinOverWeights() {
        var stable = status(200);
        var canary = status(299);
        var h = new WeightedHandler(List.of(new Backend(stable, 100, null),
                new Backend(canary, 0, new Match(false, "X-Canary", "1"))), null);
        assertEquals(299, h.handle(new Request("GET", "/", headers("x-canary", "1"))).status());
        assertEquals(299, h.handle(new Request("GET", "/", headers("x-canary", " 1 "))).status());
        assertEquals(200, h.handle(new Request("GET", "/", headers("x-canary", "0"))).status());
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, h.select(RequestHeaders.NONE));
        }
        var cookiePin = new WeightedHandler(List.of(new Backend(stable, 1, null),
                new Backend(canary, 1, new Match(true, "beta", null))), null);
        assertEquals(1, cookiePin.select(headers("cookie", "a=1", "cookie", "beta=yes")));
        assertEquals(2, cookiePin.size());
    }

    @Test
    void cookieParsing() {
        assertEquals("v", WeightedHandler.cookieValue("a=v", "a"));
        assertEquals("v", WeightedHandler.cookieValue("x=1; a=v ", "a"));
        assertEquals("q v", WeightedHandler.cookieValue("a=\"q v\"", "a"));
        assertEquals("", WeightedHandler.cookieValue("a=", "a"));
        assertNull(WeightedHandler.cookieValue("ab=1; b=2", "a"));
        assertNull(WeightedHandler.cookieValue("a", "a"));
        assertNull(WeightedHandler.cookieValue(";;", "a"));
        assertEquals("2", WeightedHandler.cookieValue("x; a=2", "a"));
        assertEquals("\"", WeightedHandler.cookieValue("a=\"", "a"));
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new WeightedHandler(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> of(null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Backend(status(200), -1, null));
        assertThrows(NullPointerException.class, () -> new Backend(null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new Match(false, "", null));
        assertThrows(NullPointerException.class, () -> new Match(false, null, null));
        assertEquals("x-a", new Match(false, "X-A", null).name());
        assertEquals("Sess", new Match(true, "Sess", null).name());
        assertNotEquals(0, WeightedHandler.mix(1));
    }

    @Test
    void handleDelegates() {
        Response r = of(null, 1).handle(new Request("GET", "/"));
        assertEquals(200, r.status());
        assertNotNull(Map.of());
    }
}
