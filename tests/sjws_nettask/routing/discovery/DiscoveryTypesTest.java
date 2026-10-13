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
package sjws_nettask.routing.discovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.routing.discovery.Endpoint.Health;

class DiscoveryTypesTest {

    @AfterEach
    void clear() {
        UpstreamRegistry.clear();
    }

    private static Endpoint ep(String a, int port, Health h) {
        return new Endpoint(null, a, port, 1, null, h, null);
    }

    private static UpstreamSet set(long version, Endpoint... e) {
        return new UpstreamSet("api", version, e, null);
    }

    @Test
    void endpointValidationAndAuthority() {
        var e = new Endpoint("", "::1", 8080, 3, Map.of("k", "v"), Health.SUSPECT, "z1");
        assertEquals("[::1]:8080", e.authority());
        assertEquals("[::1]:8080", e.id());
        assertEquals(0, e.effectiveWeight());
        assertEquals(3, e.weight());
        assertEquals("10.0.0.1:80", Endpoint.of("10.0.0.1", 80).authority());
        assertEquals(1, Endpoint.of("h", 1).effectiveWeight());
        assertEquals("[::1]:1", Endpoint.of("[::1]", 1).authority());
        assertEquals("x", new Endpoint("x", "h", 1, 1, null, Health.READY, null).id());
        assertTrue(Endpoint.of("h", 1).labels().isEmpty());
        assertThrows(NullPointerException.class, () -> new Endpoint(null, null, 1, 1, null, Health.READY, null));
        assertThrows(NullPointerException.class, () -> new Endpoint(null, "h", 1, 1, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.of(" ", 1));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.of("h", 0));
        assertThrows(IllegalArgumentException.class, () -> Endpoint.of("h", 65536));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint(null, "h", 1, -1, null, Health.READY, null));
        assertThrows(IllegalArgumentException.class, () -> new Endpoint(null, "h", 1, 2_000_000, null, Health.READY, null));
    }

    @Test
    void policyNames() {
        assertEquals(LbPolicy.ROUND_ROBIN, LbPolicy.parse(" Round_Robin "));
        assertEquals(LbPolicy.LEAST_REQUEST, LbPolicy.parse("least_request"));
        assertNull(LbPolicy.parse("ring_hash"));
        assertNull(LbPolicy.parse(null));
        assertEquals(LbPolicy.WEIGHTED_RANDOM, LbPolicy.parseOrDefault("ring_hash"));
        assertEquals(LbPolicy.ROUND_ROBIN, LbPolicy.parseOrDefault("round_robin"));
        assertEquals("weighted_random", LbPolicy.WEIGHTED_RANDOM.wireName());
    }

    @Test
    void setIsAnImmutableSnapshot() {
        var arr = new Endpoint[] {ep("a", 1, Health.READY), ep("b", 1, Health.DEAD)};
        var s = new UpstreamSet("api", 3, arr, null);
        arr[0] = ep("z", 1, Health.READY);
        assertEquals("a", s.endpointAt(0).address());
        s.ready()[0] = null;
        assertEquals("a", s.endpointAt(0).address());
        assertEquals(LbPolicy.WEIGHTED_RANDOM, s.policy());
        var live = s.withoutDead();
        assertEquals(1, live.size());
        assertSame(live, live.withoutDead());
        assertEquals(s, new UpstreamSet("api", 3, new Endpoint[] {ep("a", 1, Health.READY), ep("b", 1, Health.DEAD)},
                LbPolicy.WEIGHTED_RANDOM));
        assertNotEquals(s, live);
        assertNotEquals(s, "x");
        assertEquals(s.hashCode(), new UpstreamSet("api", 3, s.ready(), null).hashCode());
        assertTrue(s.toString().contains("v3"));
        assertThrows(NullPointerException.class, () -> new UpstreamSet(null, 1, arr, null));
        assertThrows(NullPointerException.class, () -> new UpstreamSet("a", 1, null, null));
        assertThrows(NullPointerException.class, () -> new UpstreamSet("a", 1, new Endpoint[] {null}, null));
    }

    @Test
    void sourceKeepsVersionOrderAndTheLastNonEmptySet() throws Exception {
        var src = new StaticUpstreamSource("api");
        assertNull(src.current());
        var seen = new ArrayList<UpstreamSet>();
        var sub = src.subscribe(seen::add);
        src.subscribe(s -> {
            throw new IllegalStateException("listener bug");
        });
        assertFalse(src.update(set(1, ep("a", 1, Health.DEAD)))); // empty after DEAD: never populated, still null
        assertNull(src.current());
        assertTrue(src.update(set(2, ep("a", 1, Health.READY), ep("b", 1, Health.DEAD))));
        assertEquals(1, src.current().size());
        assertFalse(src.update(set(2, ep("c", 1, Health.READY)))); // equal version
        assertFalse(src.update(set(1, ep("c", 1, Health.READY)))); // stale
        assertFalse(src.update(set(5))); // empty: keep the last non-empty set
        assertEquals("a", src.current().endpointAt(0).address());
        assertFalse(src.update(set(4, ep("old", 1, Health.READY)))); // version 5 was seen
        assertTrue(src.update(set(6, ep("d", 1, Health.SUSPECT))));
        assertEquals(2, seen.size());
        assertTrue(src.controlPlaneFed());
        sub.close();
        assertTrue(src.update(set(7, ep("e", 1, Health.READY))));
        assertEquals(2, seen.size());
        assertThrows(IllegalArgumentException.class, () -> src.update(new UpstreamSet("other", 9, new Endpoint[0], null)));
        assertThrows(NullPointerException.class, () -> src.subscribe(null));
        assertThrows(NullPointerException.class, () -> src.update(null));
        assertEquals("api", src.service());
    }

    @Test
    void configReloadsYieldToTheControlPlane() {
        var src = new StaticUpstreamSource(set(0, ep("a", 1, Health.READY)));
        assertFalse(src.controlPlaneFed());
        assertTrue(src.reloadFromConfig(set(0, ep("b", 1, Health.READY))));
        assertEquals("b", src.current().endpointAt(0).address());
        assertFalse(src.reloadFromConfig(set(0, ep("b", 1, Health.READY)))); // unchanged
        assertFalse(src.reloadFromConfig(set(0, ep("b", 1, Health.DEAD)))); // would be empty
        assertFalse(src.reloadFromConfig(new UpstreamSet("other", 0, new Endpoint[] {ep("x", 1, Health.READY)}, null)));
        assertTrue(src.update(set(1, ep("cp", 1, Health.READY))));
        assertFalse(src.reloadFromConfig(set(0, ep("c", 1, Health.READY))));
        assertEquals("cp", src.current().endpointAt(0).address());
        assertThrows(NullPointerException.class, () -> src.reloadFromConfig(null));
    }

    @Test
    void registryReusesSourcesAcrossCompiles() {
        assertNull(UpstreamRegistry.lookup("api"));
        var first = UpstreamRegistry.fromConfig(set(0, ep("a", 1, Health.READY)));
        assertSame(first, UpstreamRegistry.lookup("api"));
        var again = UpstreamRegistry.fromConfig(set(0, ep("b", 1, Health.READY)));
        assertSame(first, again);
        assertEquals("b", first.current().endpointAt(0).address());
        assertThrows(NullPointerException.class, () -> UpstreamRegistry.fromConfig(null));
        List<String> none = List.of();
        assertTrue(none.isEmpty());
    }
}
