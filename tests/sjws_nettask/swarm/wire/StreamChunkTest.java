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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class StreamChunkTest {

    static final Path GOLDEN = Path.of("tests/sjws_nettask/swarm/wire/golden-chunks-v1.txt");

    @Test
    void goldenChunksRoundTripThroughTokenFrames() throws Exception {
        var hex = HexFormat.of();
        int n = 0;
        for (var line : Files.readAllLines(GOLDEN)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var f = line.split(" ");
            var c = StreamChunk.decode(hex.parseHex(f[2]));
            assertEquals(Integer.parseInt(f[1]), c.flags(), f[0]);
            assertEquals(f[2], hex.formatHex(c.encode()), f[0]);
            assertEquals(f[3], hex.formatHex(c.toFrame().encode()), f[0]);
            var fr = Frame.decode(ByteBuffer.wrap(hex.parseHex(f[3])));
            assertEquals(Frame.Type.TOKEN, fr.type());
            assertEquals(c, StreamChunk.decode(fr.payload()), f[0]);
            n++;
        }
        assertEquals(4, n);
    }

    @Test
    void utf8BytesAreCarriedVerbatim() throws Exception {
        var s = "\0\uD83D\uDE00";
        var c = new StreamChunk(-1, -1, 3, Frame.LAST | Frame.ERROR, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertArrayEquals(new byte[] {0, (byte) 0xf0, (byte) 0x9f, (byte) 0x98, (byte) 0x80}, c.bytes());
        assertEquals(5, c.size());
        assertTrue(c.last());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk(0, 0, -1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk(0, 0, StreamChunk.MAX_SEQ + 1, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk(0, 0, 0, 0x10, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk(0, 0, 0, Frame.CANCELLED, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk(0, 0, 0, Frame.ERROR, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new StreamChunk(0, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new StreamChunk(0, 0, 0, 0, new byte[Frame.MAX_PAYLOAD - StreamChunk.HEADER + 1]));
        assertThrows(IllegalArgumentException.class, () -> StreamChunk.decode(new byte[21]));
        var bad = new StreamChunk(1, 2, 3, 0, new byte[] {9}).encode();
        bad[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> StreamChunk.decode(bad));
    }

    @Test
    void defensiveCopiesAndValueSemantics() {
        var b = new byte[] {1, 2};
        var c = new StreamChunk(1, 2, 3, StreamChunk.FIRST, b);
        b[0] = 9;
        assertEquals(1, c.bytes()[0]);
        c.bytes()[0] = 9;
        assertEquals(1, c.bytes()[0]);
        var d = new StreamChunk(1, 2, 3, StreamChunk.FIRST, new byte[] {1, 2});
        assertEquals(c, d);
        assertEquals(c.hashCode(), d.hashCode());
        assertNotEquals(c, new StreamChunk(1, 3, 3, StreamChunk.FIRST, new byte[] {1, 2}));
        assertNotEquals(c, new StreamChunk(2, 2, 3, StreamChunk.FIRST, new byte[] {1, 2}));
        assertNotEquals(c, new StreamChunk(1, 2, 4, StreamChunk.FIRST, new byte[] {1, 2}));
        assertNotEquals(c, new StreamChunk(1, 2, 3, 0, new byte[] {1, 2}));
        assertNotEquals(c, new StreamChunk(1, 2, 3, StreamChunk.FIRST, new byte[] {1}));
        assertNotEquals(c, "x");
        assertFalse(c.last());
        assertEquals(0, c.toFrame().flags());
        assertTrue(c.toString().contains("seq=3"));
        var owned = new byte[] {5};
        var o = StreamChunk.owned(1, 2, 0, 0, owned);
        assertEquals(5, o.view().get(0));
        assertTrue(o.view().isReadOnly());
        assertEquals(StreamChunk.MAX_BYTES, Frame.MAX_PAYLOAD - StreamChunk.HEADER);
    }
}
