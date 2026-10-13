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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwarmModuleTest {

    @Test
    void s0ModuleStartsNothingAndCloses() {
        int threads = Thread.activeCount();
        var module = new SwarmModule();
        assertFalse(module.closed());
        assertTrue(Thread.activeCount() <= threads, "S0 starts no thread");
        module.close();
        assertTrue(module.closed());
        module.close(); // idempotent
        assertTrue(module.closed());
    }
}
