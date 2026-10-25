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
package sjws_nettask.swarm.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** S2c PR 6: the CREDIT v1 payload codec against the shared goldens (agent-swarm.md §11.2). */
class CreditTest {

    static final Path GOLDEN = Path.of("tests/sjws_nettask/swarm/wire/golden-credit-v1.txt");
    static final HexFormat HEX = HexFormat.of();

    @Test
    void goldenVectorsRoundTripThroughCreditFrames() throws Exception {
        int n = 0;
        for (var line : Files.readAllLines(GOLDEN)) {
            if (line.isBlank() || line.startsWith("#") || line.split(" ")[1].equals("!")) {
                continue;
            }
            var f = line.split(" ");
            var id = ByteBuffer.wrap(HEX.parseHex(f[1]));
            var want = new Credit(id.getLong(), id.getLong(), Long.parseLong(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]));
            var c = Credit.decode(HEX.parseHex(f[5]));
            assertEquals(want, c, f[0]);
            assertEquals(f[5], HEX.formatHex(c.encode()), f[0]);
            assertEquals(Credit.SIZE, c.encode().length, f[0]);
            assertEquals(f[6], HEX.formatHex(c.toFrame().encode()), f[0]);
            var fr = Frame.decode(ByteBuffer.wrap(HEX.parseHex(f[6])));
            assertEquals(Frame.Type.CREDIT, fr.type(), f[0]);
            assertEquals(0, fr.flags(), f[0]);
            assertEquals(c, Credit.decode(fr.payload()), f[0]);
            n++;
        }
        assertEquals(5, n);
    }

    @Test
    void u32MaxDecodesUnsigned() {
        var c = Credit.decode(HEX.parseHex("01" + "ff".repeat(28)));
        assertEquals(0xffff_ffffL, c.attempt());
        assertEquals(0xffff_ffffL, c.chunks());
        assertEquals(0xffff_ffffL, c.bytes());
    }

    @Test
    void negativeVectorsAreRejectedWithTheirReasonToken() throws Exception {
        int n = 0;
        for (var line : Files.readAllLines(GOLDEN)) {
            if (line.isBlank() || line.startsWith("#") || !line.split(" ")[1].equals("!")) {
                continue;
            }
            var f = line.split(" ");
            var fr = Frame.decode(ByteBuffer.wrap(HEX.parseHex(f[4])));
            assertEquals(Frame.Type.CREDIT, fr.type(), f[0]);
            var raw = f[3].equals("-") ? new byte[0] : HEX.parseHex(f[3]);
            assertEquals(f[3].equals("-") ? "" : f[3], HEX.formatHex(fr.payload()), f[0]);
            var e = assertThrows(IllegalArgumentException.class, () -> Credit.decode(raw), f[0]);
            assertTrue(e.getMessage().startsWith(f[2]), f[0] + ": " + e.getMessage());
            n++;
        }
        assertEquals(5, n);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Credit.decode(null)).getMessage().startsWith("length"));
    }

    @Test
    void nonZeroFrameFlagsAreIgnored() {
        var c = new Credit(1, 2, 3, 4, 5);
        var fr = Frame.decode(ByteBuffer.wrap(new Frame(Frame.Type.CREDIT, 0x5a, c.encode()).encode()));
        assertEquals(c, Credit.decode(fr.payload()));
    }

    @Test
    void outOfRangeFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Credit(0, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Credit(0, 0, 1L << 32, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Credit(0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Credit(0, 0, 0, 1L << 32, 0));
        assertThrows(IllegalArgumentException.class, () -> new Credit(0, 0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Credit(0, 0, 0, 0, 1L << 32));
    }
}
