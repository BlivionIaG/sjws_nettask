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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.UpstreamRegistry;
import sjws_nettask.routing.discovery.UpstreamSet;

/** The watch thread debounces endpoint snapshots and leaves the request path alone. */
class EndpointWatchTest {

    @TempDir
    Path dir;

    @Test
    void debouncePublishesTheLatestSnapshotOnTheWatchThread() throws Exception {
        var service = "svc-" + dir.getFileName();
        var source = UpstreamRegistry.fromConfig(new UpstreamSet(service, 0,
                new Endpoint[] {Endpoint.of("10.0.0.1", 8080)}, LbPolicy.ROUND_ROBIN));
        var watch = new EndpointWatch(new EndpointWatch.Registration(List.of(service)), null);
        var thread = new AtomicReference<Thread>();
        source.subscribe(set -> thread.set(Thread.currentThread()));
        var first = frame(service, 1, "round_robin", member("a", "10.0.0.2", 8080, 1, "READY"));
        var second = frame(service, 2, "weighted_random", member("b", "10.0.0.3", 9090, 5, "READY"),
                member("gone", "10.0.0.4", 1, 1, "DEAD"));
        assertTrue(watch.offer(first, 0));
        assertEquals(0, watch.flush(EndpointWatch.DEBOUNCE_NANOS - 1));
        assertTrue(watch.offer(second, 10));
        assertFalse(watch.offer(first, 20));
        assertEquals(0, watch.flush(10 + EndpointWatch.DEBOUNCE_NANOS - 1));
        assertEquals(1, watch.flush(10 + EndpointWatch.DEBOUNCE_NANOS));
        assertSame(Thread.currentThread(), thread.get());
        var current = source.current();
        assertEquals(2, current.version());
        assertEquals(LbPolicy.WEIGHTED_RANDOM, current.policy());
        assertEquals(1, current.size());
        assertEquals("10.0.0.3", current.endpointAt(0).address());
        assertFalse(watch.offer(null, 0));
        assertFalse(watch.offer(frame("other", 1, "round_robin", member("a", "10.0.0.2", 1, 1, "READY")), 0));
    }

    @Test
    void suspectStaysAtWeightZeroAndAnEmptyUpdateKeepsTheLastSet() throws Exception {
        var service = "health-" + dir.getFileName();
        UpstreamRegistry.fromConfig(new UpstreamSet(service, 0,
                new Endpoint[] {Endpoint.of("10.0.0.1", 80)}, null));
        var watch = new EndpointWatch(new EndpointWatch.Registration(List.of(service)), dir);
        assertTrue(watch.offer(frame(service, 1, "round_robin", member("up", "10.0.0.8", 80, 4, "READY"),
                member("slow", "10.0.0.9", 80, 9, "SUSPECT")), 0));
        assertEquals(1, watch.flush(EndpointWatch.DEBOUNCE_NANOS));
        var current = UpstreamRegistry.lookup(service).current();
        assertEquals(2, current.size());
        assertEquals(4, current.endpointAt(0).effectiveWeight());
        assertEquals(0, current.endpointAt(1).effectiveWeight());
        assertEquals(9, current.endpointAt(1).weight());
        assertTrue(watch.offer(frame(service, 2, "round_robin"), EndpointWatch.DEBOUNCE_NANOS));
        assertEquals(0, watch.flush(2 * EndpointWatch.DEBOUNCE_NANOS));
        assertEquals(1, UpstreamRegistry.lookup(service).current().version());
        assertEquals(2, UpstreamRegistry.lookup(service).current().size());
        var text = Files.readString(dir.resolve("config").resolve("endpoints-last-good.json"));
        assertTrue(text.contains("\"version\":1"), text);
        assertTrue(text.contains("SUSPECT"), text);
        assertFalse(text.contains("10.0.0.4"));
    }

    @Test
    void restoreFeedsASourceThatTheControlPlaneHasNotFed() throws Exception {
        var service = "boot-" + dir.getFileName();
        UpstreamRegistry.fromConfig(new UpstreamSet(service, 0,
                new Endpoint[] {Endpoint.of("10.0.0.1", 80)}, LbPolicy.WEIGHTED_RANDOM));
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config").resolve("endpoints-last-good.json"), """
                {"services":[{"service":"%s","version":4,"policy":"round_robin","endpoints":[{"id":"a","address":"10.9.0.2","port":443,"weight":2,"health":"SUSPECT","zone":"edge"}]}]}
                """.formatted(service));
        var watch = new EndpointWatch(new EndpointWatch.Registration(List.of(service)), dir);
        assertEquals(1, watch.restore());
        var current = UpstreamRegistry.lookup(service).current();
        assertEquals(4, current.version());
        assertEquals(LbPolicy.ROUND_ROBIN, current.policy());
        assertEquals(0, current.endpointAt(0).effectiveWeight());
        assertEquals("edge", current.endpointAt(0).zone());
        assertEquals(0, watch.restore());
        assertEquals(0, new EndpointWatch(new EndpointWatch.Registration(List.of(service)), null).restore());
        assertEquals(0, new EndpointWatch(new EndpointWatch.Registration(List.of(service)), dir.resolve("absent")).restore());
    }

    @Test
    void holdsAFrameUntilTheNodeRegistersTheSource() throws Exception {
        var service = "late-" + dir.getFileName();
        var watch = new EndpointWatch(new EndpointWatch.Registration(List.of(service)), dir);
        assertTrue(watch.offer(frame(service, 3, "least_request", member(null, "10.1.0.2", 443, 1, null)), 0));
        assertEquals(0, watch.flush(EndpointWatch.DEBOUNCE_NANOS));
        UpstreamRegistry.fromConfig(new UpstreamSet(service, 0,
                new Endpoint[] {Endpoint.of("10.0.0.1", 80)}, LbPolicy.ROUND_ROBIN));
        assertEquals(1, watch.flush(EndpointWatch.DEBOUNCE_NANOS));
        var current = UpstreamRegistry.lookup(service).current();
        assertEquals(3, current.version());
        assertEquals(LbPolicy.LEAST_REQUEST, current.policy());
        assertEquals(Endpoint.Health.READY, current.endpointAt(0).health());
        assertEquals("10.1.0.2:443", current.endpointAt(0).id());
        var perms = Files.getPosixFilePermissions(dir.resolve("config").resolve("endpoints-last-good.json"));
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
    }

    @Test
    void deadOnlySnapshotDoesNotReplaceOrPersist() throws Exception {
        var service = "dead-" + dir.getFileName();
        UpstreamRegistry.fromConfig(new UpstreamSet(service, 0, new Endpoint[] {
                new Endpoint("d", "10.0.0.1", 80, 1, Map.of(), Endpoint.Health.DEAD, null)}, null));
        var watch = new EndpointWatch(new EndpointWatch.Registration(List.of(service)), dir);
        assertTrue(watch.offer(frame(service, 1, "nope", member("d", "10.0.0.1", 80, 1, "DEAD")), 0));
        assertEquals(0, watch.flush(EndpointWatch.DEBOUNCE_NANOS));
        assertNull(UpstreamRegistry.lookup(service).current());
        assertFalse(Files.exists(dir.resolve("config").resolve("endpoints-last-good.json")));
    }

    @Test
    void roundTripKeepsEscapesAndSkipsServicesThisNodeDidNotRegister() throws Exception {
        var mine = "q\"s\\" + dir.getFileName();
        var other = "other-" + dir.getFileName();
        UpstreamRegistry.fromConfig(new UpstreamSet(mine, 0, new Endpoint[] {Endpoint.of("10.0.0.1", 80)}, null));
        var watch = new EndpointWatch(new EndpointWatch.Registration(List.of(mine)), dir);
        assertTrue(watch.offer(new EndpointWatch.Frame(mine, 2, "round_robin",
                List.of(new EndpointWatch.Member("id\"1", "10.2.0.1", 9, 3, "READY", "z\"1"))), 0));
        assertEquals(1, watch.flush(EndpointWatch.DEBOUNCE_NANOS));
        var text = Files.readString(dir.resolve("config").resolve("endpoints-last-good.json"));
        var sets = EndpointWatch.read(text);
        assertEquals(1, sets.size());
        assertEquals(mine, sets.get(0).service());
        assertEquals("id\"1", sets.get(0).endpointAt(0).id());
        assertEquals("z\"1", sets.get(0).endpointAt(0).zone());
        Files.writeString(dir.resolve("config").resolve("endpoints-last-good.json"), text.substring(0, text.length() - 2)
                + ",{\"service\":\"" + other + "\",\"version\":1,\"policy\":\"round_robin\",\"endpoints\":[]}"
                + text.substring(text.length() - 2));
        var again = new EndpointWatch(new EndpointWatch.Registration(List.of(mine)), dir);
        assertEquals(0, again.restore());
        assertEquals(List.of(), EndpointWatch.read("{\"services\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> EndpointWatch.read("{\"service\":\"unterminated"));
        var defaults = new EndpointWatch.Frame(mine, 9, null, null).toSet();
        assertEquals(0, defaults.size());
        assertEquals(LbPolicy.WEIGHTED_RANDOM, defaults.policy());
        var noId = EndpointWatch.read(
                "{\"services\":[{\"service\":\"s\",\"version\":1,\"policy\":\"round_robin\",\"endpoints\":[{\"address\":\"10.8.0.1\",\"port\":80,\"weight\":1,\"health\":\"READY\",\"zone\":\"\"}]}]}");
        assertEquals("10.8.0.1:80", noId.get(0).endpointAt(0).id());
        assertNull(noId.get(0).endpointAt(0).zone());
    }

    private static EndpointWatch.Frame frame(String service, long version, String policy, EndpointWatch.Member... members) {
        return new EndpointWatch.Frame(service, version, policy, List.of(members));
    }

    private static EndpointWatch.Member member(String id, String address, int port, int weight, String health) {
        return new EndpointWatch.Member(id, address, port, weight, health, null);
    }
}
