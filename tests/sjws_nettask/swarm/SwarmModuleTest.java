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
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.StaticFileHandler;

class SwarmModuleTest {

    @Test
    void moduleStartsNothingUntilUsedAndCloses() {
        int threads = Thread.activeCount();
        var module = new SwarmModule();
        assertFalse(module.closed());
        assertTrue(Thread.activeCount() <= threads, "constructing the module starts no thread");
        assertFalse(module.swarm().replies().polling());
        module.close();
        assertTrue(module.closed());
        module.close(); // idempotent
        assertTrue(module.closed());
    }

    @Test
    void mountsSwarmV1AndLeavesOtherPathsAlone() {
        var seen = new AtomicReference<String>();
        Handler fallback = request -> {
            seen.set(request.target());
            return StaticFileHandler.error(404, request.headOnly());
        };
        var module = new SwarmModule();
        var handler = HandlerMounts.install(List.of(module), fallback);
        // no tokens configured in the test JVM: the gate denies everything (fail closed)
        assertEquals(SwarmGate.DENY_ALL, module.gate());
        assertEquals(401, handler.handle(new Request("GET", "/swarm/v1")).status());
        assertEquals(401, handler.handle(new Request("GET", "/swarm/v1/tasks?x=1")).status());
        assertEquals(401, handler.handle(new Request("HEAD", "/swarm/v1/leases/1:ack")).status());
        assertEquals(401, handler.handle(new Request("POST", "/swarm/v1")).status());
        module.close();
        var miss = handler.handle(new Request("GET", "/swarm/v2"));
        assertEquals(404, miss.status());
        assertEquals("/swarm/v2", seen.get());
        assertSame(fallback, HandlerMounts.install(List.of(), fallback));
    }

    @Test
    void settingsReadPropertyThenEnvironmentThenDefault() {
        System.setProperty("sjws.swarm.test.x", " v ");
        try {
            assertEquals("v", SwarmModule.setting("sjws.swarm.test.x", "SJWS_NO_SUCH_ENV", "d"));
        } finally {
            System.clearProperty("sjws.swarm.test.x");
        }
        assertEquals("d", SwarmModule.setting("sjws.swarm.test.x", "SJWS_NO_SUCH_ENV", "d"));
        System.setProperty("sjws.swarm.test.x", " ");
        try {
            assertEquals("d", SwarmModule.setting("sjws.swarm.test.x", "SJWS_NO_SUCH_ENV", "d"));
        } finally {
            System.clearProperty("sjws.swarm.test.x");
        }
    }
}
