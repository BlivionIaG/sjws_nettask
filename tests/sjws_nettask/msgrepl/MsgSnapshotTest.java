package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The manifest decoder bounds every count and length and refuses anything it could not place safely. */
class MsgSnapshotTest {

    static byte[][] digests(int n) {
        var d = new byte[n][];
        for (int i = 0; i < n; i++) d[i] = new byte[32];
        return d;
    }

    static MsgSnapshot ok() {
        int c = MsgSnapshot.MIN_CHUNK;
        return new MsgSnapshot("t", 9, 2, 30, 0, c, new byte[] {1, 2},
                List.of(new MsgSnapshot.Seg(0, 10, c + 1, -1, digests(2)), new MsgSnapshot.Seg(10, 30, 5, 30, digests(1))));
    }

    @Test
    void roundTrips() {
        var m = ok();
        var back = MsgSnapshot.decode(m.encode());
        assertEquals(m.topic(), back.topic());
        assertEquals(m.index(), back.index());
        assertEquals(m.endOffset(), back.endOffset());
        assertEquals(m.segments(), back.segments());
        assertEquals(m.dataBytes(), back.dataBytes());
    }

    static void refused(MsgSnapshot m, String why) {
        var e = assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(m.encode()));
        assertTrue(e.getMessage().contains(why), e.getMessage());
    }

    @Test
    void refusesWhatItCannotPlace() {
        int c = MsgSnapshot.MIN_CHUNK;
        var seg = List.of(new MsgSnapshot.Seg(0, 30, 5, -1, digests(1)));
        refused(new MsgSnapshot("..", 1, 1, 30, 0, c, null, seg), "topic");
        refused(new MsgSnapshot("a/b", 1, 1, 30, 0, c, null, seg), "topic");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c - 1, null, seg), "chunk");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, MsgSnapshot.MAX_CHUNK + 1, null, seg), "chunk");
        refused(new MsgSnapshot("t", 1, 1, 30, 40, c, null, seg), "snapshot point");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, null, List.of()), "segment count");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, null, List.of(new MsgSnapshot.Seg(0, 10, 5, -1, digests(1)),
                new MsgSnapshot.Seg(11, 30, 5, -1, digests(1)))), "segment 1");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, null, List.of(new MsgSnapshot.Seg(0, 30, c + 1, -1, digests(1)))), "digests");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, null, List.of(new MsgSnapshot.Seg(0, 30, 5, 3, digests(1)))), "marker");
        refused(new MsgSnapshot("t", 1, 1, 31, 0, c, null, seg), "cover");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, null, List.of(new MsgSnapshot.Seg(0, 30, MsgSnapshot.MAX_SEGMENT_BYTES + 1, -1,
                digests(0)))), "segment 0");
    }

    @Test
    void refusesTruncatedOversizedAndTrailingImages() {
        var image = ok().encode();
        for (int cut : new int[] {0, 3, 10, image.length - 1}) {
            assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(Arrays.copyOf(image, cut)));
        }
        var trailing = Arrays.copyOf(image, image.length + 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(trailing)).getMessage().contains("trailing"));
        // Huge counts are refused before anything is allocated.
        var huge = ByteBuffer.wrap(image.clone());
        int countAt = 4 + 1 + 1 + 8 * 4 + 4 + 4 + 2;
        huge.putInt(countAt, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(huge.array()));
        var state = ByteBuffer.wrap(image.clone());
        state.putInt(4 + 1 + 1 + 8 * 4 + 4, MsgSnapshot.MAX_STATE + 1);
        assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(state.array()));
        var magic = image.clone();
        magic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(magic));
    }
}
