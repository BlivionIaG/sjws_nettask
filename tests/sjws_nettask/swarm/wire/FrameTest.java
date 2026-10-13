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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class FrameTest {

    static final Path GOLDEN = Path.of("tests/sjws_nettask/swarm/wire/golden-v1.txt");

    @Test
    void goldenFramesEncodeAndDecodeExactly() throws Exception {
        int n = 0;
        var hex = HexFormat.of();
        for (var line : Files.readAllLines(GOLDEN)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var f = line.split(" ");
            var payload = f[3].equals("-") ? new byte[0] : hex.parseHex(f[3]);
            var frame = new Frame(Frame.Type.valueOf(f[1]), Integer.parseInt(f[2]), payload);
            assertEquals(f[4], hex.formatHex(frame.encode()), f[0]);
            assertEquals(frame, Frame.decode(ByteBuffer.wrap(hex.parseHex(f[4]))), f[0]);
            n++;
        }
        assertTrue(n >= 9, "golden frames: " + n);
    }

    @Test
    void typeCodesAreStable() {
        String[] order = {"TASK", "CLAIM", "LEASE", "EXTEND", "RESULT", "TOKEN", "ERROR", "CANCEL", "CREDIT"};
        for (int i = 0; i < order.length; i++) {
            assertEquals(order[i], Frame.Type.of(i + 1).name());
            assertEquals(i + 1, Frame.Type.valueOf(order[i]).code());
        }
        assertThrows(IllegalArgumentException.class, () -> Frame.Type.of(0));
        assertThrows(IllegalArgumentException.class, () -> Frame.Type.of(10));
    }

    @Test
    void decodeStreamsPartialAndBackToBackFrames() {
        var a = new Frame(Frame.Type.TOKEN, 0, new byte[] {1, 2, 3}).encode();
        var b = new Frame(Frame.Type.TOKEN, Frame.LAST, new byte[0]).encode();
        var buf = ByteBuffer.allocate(a.length + b.length).put(a).put(b).flip();
        assertNull(Frame.decode(ByteBuffer.wrap(a, 0, 5)), "short header");
        assertNull(Frame.decode(ByteBuffer.wrap(a, 0, 8)), "short payload");
        assertArrayEquals(new byte[] {1, 2, 3}, Frame.decode(buf).payload());
        assertEquals(Frame.LAST, Frame.decode(buf).flags());
        assertEquals(0, buf.remaining());
        var big = new Frame(Frame.Type.RESULT, 0, new byte[Frame.MAX_PAYLOAD]);
        assertEquals(Frame.MAX_PAYLOAD, Frame.decode(ByteBuffer.wrap(big.encode())).payload().length);
    }

    @Test
    void rejectsBadFrames() {
        assertThrows(IllegalArgumentException.class, () -> Frame.decode(ByteBuffer.wrap(new byte[] {2, 1, 0, 0, 0, 0})));
        assertThrows(IllegalArgumentException.class, () -> Frame.decode(ByteBuffer.wrap(new byte[] {1, 99, 0, 0, 0, 0})));
        assertThrows(IllegalArgumentException.class, () -> new Frame(null, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame(Frame.Type.TASK, -1, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame(Frame.Type.TASK, 256, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new Frame(Frame.Type.TASK, 0, new byte[Frame.MAX_PAYLOAD + 1]));
    }

    @Test
    void valueSemantics() {
        var p = new byte[] {7};
        var f = new Frame(Frame.Type.CLAIM, 1, p);
        p[0] = 8;
        assertEquals(7, f.payload()[0], "defensive copy in");
        f.payload()[0] = 9;
        assertEquals(7, f.payload()[0], "defensive copy out");
        var same = new Frame(Frame.Type.CLAIM, 1, new byte[] {7});
        assertEquals(f, same);
        assertEquals(f.hashCode(), same.hashCode());
        assertNotEquals(f, new Frame(Frame.Type.LEASE, 1, new byte[] {7}));
        assertNotEquals(f, new Frame(Frame.Type.CLAIM, 0, new byte[] {7}));
        assertNotEquals(f, new Frame(Frame.Type.CLAIM, 1, new byte[] {6}));
        assertNotEquals(f, "x");
        assertEquals("Frame[CLAIM, flags=1, 1B]", f.toString());
    }
}
