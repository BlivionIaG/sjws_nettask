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
package sjws_nettask.swarm.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwarmTraceTest {

    static final String TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void parsesValidAndNormalizes() {
        assertEquals(TP, SwarmTrace.parse(TP));
        assertEquals(TP, SwarmTrace.parse("  " + TP.toUpperCase().replace("00-4BF", "00-4bf") + " "));
        // future version: first 55 chars, emitted as 00
        assertEquals(TP, SwarmTrace.parse("cc" + TP.substring(2) + "-what-ever"));
        assertEquals(TP, SwarmTrace.parse("cc" + TP.substring(2)));
    }

    @Test
    void rejectsInvalid() {
        for (var bad : new String[] {null, "", "garbage", TP.substring(0, 54), TP + "-x", "cc" + TP.substring(2) + "x",
            "ff" + TP.substring(2), TP.replace('-', '_'), "00_" + TP.substring(3), TP.substring(0, 35) + "_" + TP.substring(36),
            TP.substring(0, 52) + "_" + TP.substring(53), "0g" + TP.substring(2), TP.replace("4bf9", "4bz9"),
            TP.replace("00f0", "0zf0"), TP.substring(0, 53) + "0z", "00-" + "0".repeat(32) + TP.substring(35),
            TP.substring(0, 36) + "0".repeat(16) + TP.substring(52)}) {
            assertEquals("", SwarmTrace.parse(bad), String.valueOf(bad));
        }
    }

    @Test
    void childKeepsTraceAndFlagsWithFreshSpan() {
        for (int i = 0; i < 1000; i++) {
            var c = SwarmTrace.child(TP);
            assertEquals(SwarmTrace.traceId(TP), SwarmTrace.traceId(c));
            assertEquals(55, c.length());
            assertEquals(c, SwarmTrace.parse(c));
            assertTrue(c.endsWith("-01"));
            assertNotEquals(SwarmTrace.spanId(TP), SwarmTrace.spanId(c));
        }
        assertEquals("", SwarmTrace.child(""));
        assertEquals("", SwarmTrace.child(null));
        assertTrue(SwarmTrace.describe().contains(SwarmTrace.HEADER));
    }
}
