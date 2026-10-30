package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        refused(new MsgSnapshot("..", 1, 1, 30, 0, c, new byte[0], seg), "topic");
        refused(new MsgSnapshot("a/b", 1, 1, 30, 0, c, new byte[0], seg), "topic");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c - 1, new byte[0], seg), "chunk");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, MsgSnapshot.MAX_CHUNK + 1, new byte[0], seg), "chunk");
        refused(new MsgSnapshot("t", 1, 1, 30, 40, c, new byte[0], seg), "snapshot point");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, new byte[0], List.of()), "segment count");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, new byte[0], List.of(new MsgSnapshot.Seg(0, 10, 5, -1, digests(1)),
                new MsgSnapshot.Seg(11, 30, 5, -1, digests(1)))), "segment 1");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, new byte[0], List.of(new MsgSnapshot.Seg(0, 30, c + 1, -1, digests(1)))), "digests");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, new byte[0], List.of(new MsgSnapshot.Seg(0, 30, 5, 3, digests(1)))), "marker");
        refused(new MsgSnapshot("t", 1, 1, 31, 0, c, new byte[0], seg), "cover");
        refused(new MsgSnapshot("t", 1, 1, 30, 0, c, new byte[0], List.of(new MsgSnapshot.Seg(0, 30, MsgSnapshot.MAX_SEGMENT_BYTES + 1, -1,
                digests(0)))), "segment 0");
    }

    static MsgSnapshot withSections(List<MsgSnapshot.Section> sections) {
        var o = ok();
        return new MsgSnapshot(o.topic(), o.index(), o.term(), o.endOffset(), o.logStart(), o.chunkBytes(), sections, o.segments());
    }

    /** #278 hook: typed, versioned sections; unknown optional ones are skipped, unknown required ones refused. */
    @Test
    void sectionsAreExtensible() {
        var producers = new byte[] {9, 8, 7};
        var back = MsgSnapshot.decode(withSections(List.of(new MsgSnapshot.Section(MsgSnapshot.APP_STATE, 0, new byte[] {1}),
                new MsgSnapshot.Section(MsgSnapshot.PRODUCERS, MsgSnapshot.REQUIRED, producers))).encode());
        assertArrayEquals(producers, back.section(MsgSnapshot.PRODUCERS));
        assertArrayEquals(new byte[] {1}, back.state());
        var future = MsgSnapshot.decode(withSections(List.of(new MsgSnapshot.Section(77, 0, new byte[] {5}),
                new MsgSnapshot.Section(MsgSnapshot.APP_STATE, 0, new byte[] {2}))).encode());
        assertEquals(1, future.sections().size(), "an unknown optional section is skipped");
        assertArrayEquals(new byte[] {2}, future.state());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(withSections(List.of(
                new MsgSnapshot.Section(77, MsgSnapshot.REQUIRED, new byte[] {5}))).encode())).getMessage().contains("required"));
        assertThrows(IllegalArgumentException.class, () -> withSections(List.of(new MsgSnapshot.Section(1, 0, null),
                new MsgSnapshot.Section(1, 0, null))));
        assertEquals(0, MsgSnapshot.decode(withSections(List.of()).encode()).state().length);
        assertEquals(null, MsgSnapshot.decode(withSections(List.of()).encode()).section(MsgSnapshot.PRODUCERS));
    }

    @Test
    void refusesTruncatedOversizedAndTrailingImages() {
        var image = ok().encode();
        for (int cut : new int[] {0, 3, 10, image.length - 1}) {
            assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(Arrays.copyOf(image, cut)));
        }
        var trailing = Arrays.copyOf(image, image.length + 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(trailing)).getMessage().contains("trailing"));
        // Huge counts are refused before anything is allocated. Layout of ok(): header 43 bytes, section count at 43,
        // its one section (id u16, flags u8, len int) at 47, segment count at 56.
        var sections = ByteBuffer.wrap(image.clone());
        sections.putInt(43, MsgSnapshot.MAX_SECTIONS + 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(sections.array())).getMessage().contains("section count"));
        var state = ByteBuffer.wrap(image.clone());
        state.putInt(50, MsgSnapshot.MAX_STATE + 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(state.array())).getMessage().contains("state length"));
        var huge = ByteBuffer.wrap(image.clone());
        huge.putInt(56, Integer.MAX_VALUE);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(huge.array())).getMessage().contains("segment count"));
        var version = image.clone();
        version[4] = 2;
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(version)).getMessage().contains("version"));
        var magic = image.clone();
        magic[0] = 'X';
        assertThrows(IllegalArgumentException.class, () -> MsgSnapshot.decode(magic));
    }
}
