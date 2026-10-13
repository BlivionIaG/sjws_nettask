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
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sjws_nettask.swarm.SwarmModule;

class OptionalModulesTest {

    private static final java.util.function.Function<String, String> NO_PROPS = k -> null;

    @Test
    void offByDefault() {
        assertFalse(OptionalModules.swarmEnabled(NO_PROPS, Map.of()));
        assertSame(OptionalModules.NONE, OptionalModules.start(NO_PROPS, Map.of(), System.err));
        assertTrue(OptionalModules.contributors(OptionalModules.NONE).isEmpty());
        OptionalModules.NONE.close();
    }

    @Test
    void propertyWinsOverEnvironment() {
        assertTrue(OptionalModules.swarmEnabled(k -> " TRUE ", Map.of()));
        assertFalse(OptionalModules.swarmEnabled(k -> "false", Map.of(OptionalModules.SWARM_ENV, "true")));
        assertTrue(OptionalModules.swarmEnabled(NO_PROPS, Map.of(OptionalModules.SWARM_ENV, "true")));
        assertFalse(OptionalModules.swarmEnabled(NO_PROPS, Map.of(OptionalModules.SWARM_ENV, "yes")));
    }

    @Test
    void yamlKeyAppliesOnlyWhenPropertyAndEnvironmentAreUnset() {
        assertFalse(OptionalModules.swarmEnabled(NO_PROPS, Map.of(), null));
        assertFalse(OptionalModules.swarmEnabled(NO_PROPS, Map.of(), false));
        assertTrue(OptionalModules.swarmEnabled(NO_PROPS, Map.of(), true));
        assertFalse(OptionalModules.swarmEnabled(k -> "false", Map.of(), true));
        assertFalse(OptionalModules.swarmEnabled(NO_PROPS, Map.of(OptionalModules.SWARM_ENV, "false"), true));
        assertTrue(OptionalModules.swarmEnabled(NO_PROPS, Map.of(OptionalModules.SWARM_ENV, "true"), false));
        assertSame(OptionalModules.NONE, OptionalModules.start(NO_PROPS, Map.of(), false, System.err));
        var module = OptionalModules.start(NO_PROPS, Map.of(), true, System.err);
        assertInstanceOf(SwarmModule.class, module);
        module.close();
        if (System.getProperty(OptionalModules.SWARM_PROPERTY) == null && System.getenv(OptionalModules.SWARM_ENV) == null) {
            assertSame(OptionalModules.NONE, OptionalModules.start((Boolean) null, System.err));
        }
    }

    @Test
    void enabledLoadsTheSwarmByName() {
        var module = OptionalModules.start(k -> "true", Map.of(), System.err);
        var swarm = assertInstanceOf(SwarmModule.class, module);
        assertEquals(List.of(module), OptionalModules.contributors(module));
        module.close();
        assertTrue(swarm.closed());
    }

    @Test
    void theDefaultEntryReadsTheProcessGate() {
        // Surefire does not set the property; the environment is not expected to either.
        if (System.getProperty(OptionalModules.SWARM_PROPERTY) == null && System.getenv(OptionalModules.SWARM_ENV) == null) {
            assertSame(OptionalModules.NONE, OptionalModules.start(System.err));
        }
    }

    @Test
    void aMissingOrWrongModuleIsLoggedAndSkipped() {
        var buf = new ByteArrayOutputStream();
        var err = new PrintStream(buf, true, StandardCharsets.UTF_8);
        assertSame(OptionalModules.NONE, OptionalModules.load("sjws_nettask.swarm.Nope", err));
        assertSame(OptionalModules.NONE, OptionalModules.load("java.lang.String", err));
        var text = buf.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("sjws_nettask.swarm.Nope") && text.contains("java.lang.String"), text);
    }

    @Test
    void theSwarmIsReferencedOnlyByName() {
        assertTrue(OptionalModules.SWARM_CLASS.equals(SwarmModule.class.getName()));
    }
}
