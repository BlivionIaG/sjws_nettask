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
package sjws_nettask.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceLaunchControlTest {

    @Test
    void hostingStartsOnlyWhenConfigured() {
        assertFalse(ServiceLaunch.controlConfigured(null, k -> null, Map.of()));
        assertTrue(ServiceLaunch.controlConfigured(Map.of(), k -> null, Map.of()));
        assertTrue(ServiceLaunch.controlConfigured(null, k -> k.equals("sjws.cp.nodeId") ? "cp-1" : null, Map.of()));
        assertTrue(ServiceLaunch.controlConfigured(null, k -> null, Map.of("SJWS_CP_NODE_ID", "cp-1")));
    }

    @Test
    void configuredControlFailsClosed() {
        var e = assertThrows(IOException.class, () -> ServiceLaunch.launchControl(Map.of("nodeId", "cp-1"), null));
        assertTrue(e.getMessage().contains("cp."), e.getMessage());
    }
}
