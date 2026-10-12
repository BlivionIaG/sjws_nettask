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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.ServerConfig;

/** Plain checks of the limit record (no QUIC needed). */
class LimitsTest {

    static final Duration S = Duration.ofSeconds(1);

    /** Builds limits with field {@code bad} set to {@code value} and every other field valid. */
    static Limits with(int bad, int value) {
        IntFunction<Integer> v = i -> i == bad ? value : 4;
        return new Limits(v.apply(0), v.apply(1), v.apply(2), v.apply(3), S, S, bad == 4 ? value : 0, S, S,
                v.apply(5), v.apply(6));
    }

    @ParameterizedTest(name = "field {0}")
    @ValueSource(ints = {0, 1, 2, 3, 5, 6})
    void countsMustBePositive(int field) {
        assertThrows(IllegalArgumentException.class, () -> with(field, 0));
        assertNotNull(with(field, 1));
    }

    @Test
    void minRateMayBeZeroButNotNegative() {
        assertEquals(0, with(4, 0).minBytesPerSecond());
        assertThrows(IllegalArgumentException.class, () -> with(4, -1));
    }

    @Test
    void contentionThresholds() {
        var l = with(-1, 0); // 4 in-flight, 4 files
        assertFalse(l.filesContended(new java.util.concurrent.Semaphore(4)), "none used");
        assertFalse(l.filesContended(new java.util.concurrent.Semaphore(1)), "3 of 4 used: not above 75%");
        assertTrue(l.filesContended(new java.util.concurrent.Semaphore(0)), "4 of 4 used");
        assertFalse(l.inflightContended(new java.util.concurrent.Semaphore(2)), "2 of 4: not above half");
        assertTrue(l.inflightContended(new java.util.concurrent.Semaphore(1)), "3 of 4");
    }

    @Test
    void fromConfigAndCopies() {
        var c = ServerConfig.builder(Path.of("/r")).maxConnections(4000).h3MaxConnectionsPerIp(3).h3MinRate(7).build();
        var l = Limits.of(c);
        assertEquals(4000, l.maxInflight());
        assertEquals(Limits.MAX_OPEN_FILES, l.maxOpenFiles());
        assertEquals(3, l.connectionsPerIp());
        assertEquals(7, l.minBytesPerSecond());
        assertEquals(Limits.HANDSHAKE_TIMEOUT, l.handshakeTimeout());
        var h = l.withHandshake(S, 9);
        assertEquals(S, h.handshakeTimeout());
        assertEquals(9, h.maxHalfOpen());
        assertEquals(l.withConnectionsPerIp(5).maxInflight(), 4000);
        assertEquals(5, l.withConnectionsPerIp(5).connectionsPerIp());
    }
}
