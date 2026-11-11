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

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActiveHealthTest {

    private static final Duration S = Duration.ofSeconds(1);

    @Test
    void defaultsAndValidation() {
        var d = ActiveHealth.DEFAULT;
        assertEquals("/", d.path());
        assertEquals("HEAD", d.method());
        assertEquals(Duration.ofSeconds(10), d.interval());
        assertEquals(Duration.ofSeconds(2), d.ejectInterval());
        assertEquals(Duration.ofSeconds(1), d.timeout());
        assertEquals("GET", new ActiveHealth("/", "get", S, S, S).method());
        assertThrows(IllegalArgumentException.class, () -> new ActiveHealth("health", "GET", S, S, S));
        assertThrows(IllegalArgumentException.class, () -> new ActiveHealth("/x y", "GET", S, S, S));
        assertThrows(IllegalArgumentException.class, () -> new ActiveHealth("/", "POST", S, S, S));
        assertThrows(IllegalArgumentException.class, () -> new ActiveHealth("/", "GET", Duration.ZERO, S, S));
        assertThrows(IllegalArgumentException.class, () -> new ActiveHealth("/", "GET", S, Duration.ZERO, S));
        assertThrows(NullPointerException.class, () -> new ActiveHealth(null, "GET", S, S, S));
    }

    @Test
    void twoXxIsSuccess() {
        assertTrue(ActiveHealth.success(200));
        assertTrue(ActiveHealth.success(204));
        assertTrue(ActiveHealth.success(299));
        assertFalse(ActiveHealth.success(199));
        assertFalse(ActiveHealth.success(300));
        assertFalse(ActiveHealth.success(404));
        assertFalse(ActiveHealth.success(500));
    }

    @Test
    void tickIsBounded() {
        var slow = new ActiveHealth("/", "HEAD", Duration.ofSeconds(10), Duration.ofSeconds(2), S);
        assertEquals(50, EndpointBalancer.tickMillis(slow));
        var fast = new ActiveHealth("/", "HEAD", Duration.ofMillis(20), Duration.ofMillis(20), S);
        assertEquals(10, EndpointBalancer.tickMillis(fast));
    }
}
