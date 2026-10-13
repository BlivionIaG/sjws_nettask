package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HeartbeatCodecTest {

    private static Heartbeat sample(Heartbeat.Kind kind) {
        return new Heartbeat(Heartbeat.VERSION, kind, 2, 65_535, Long.MIN_VALUE, -4L, 7L, 99L, -3, 0x81, Integer.MIN_VALUE,
                65_535, 8L);
    }

    @Test
    void roundTripIsExactlySixtyFourBytes() {
        var raw = new byte[HeartbeatCodec.BYTES];
        for (var kind : Heartbeat.Kind.values()) {
            assertNull(Heartbeat.Kind.fromCode(0));
            var beat = sample(kind);
            HeartbeatCodec.encode(beat, raw);
            assertEquals(HeartbeatCodec.BYTES, raw.length);
            assertEquals(beat, HeartbeatCodec.decode(raw));
            assertEquals(kind, Heartbeat.Kind.fromCode(kind.code()));
        }
        assertNull(Heartbeat.Kind.fromCode(9));
    }

    @Test
    void rejectsAShortBufferABadChecksumAndABadHeader() {
        var raw = new byte[HeartbeatCodec.BYTES];
        HeartbeatCodec.encode(sample(Heartbeat.Kind.ACK), raw);
        assertThrows(IllegalArgumentException.class, () -> HeartbeatCodec.encode(sample(Heartbeat.Kind.ACK), new byte[63]));
        assertNull(HeartbeatCodec.decode(new byte[63]));
        assertNull(HeartbeatCodec.decode(new byte[65]));
        raw[63] ^= 0x01;
        assertNull(HeartbeatCodec.decode(raw));

        HeartbeatCodec.encode(sample(Heartbeat.Kind.HEARTBEAT), raw);
        raw[0] = 0;
        rewriteCrc(raw);
        assertNull(HeartbeatCodec.decode(raw));

        HeartbeatCodec.encode(sample(Heartbeat.Kind.HEARTBEAT), raw);
        raw[2] = 9;
        rewriteCrc(raw);
        assertNull(HeartbeatCodec.decode(raw));

        HeartbeatCodec.encode(sample(Heartbeat.Kind.HEARTBEAT), raw);
        raw[3] = 9;
        rewriteCrc(raw);
        assertNull(HeartbeatCodec.decode(raw));

        HeartbeatCodec.encode(sample(Heartbeat.Kind.HEARTBEAT), raw);
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putShort(4, (short) 0);
        rewriteCrc(raw);
        assertNull(HeartbeatCodec.decode(raw));

        HeartbeatCodec.encode(sample(Heartbeat.Kind.HEARTBEAT), raw);
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putShort(6, (short) 0);
        rewriteCrc(raw);
        assertNull(HeartbeatCodec.decode(raw));
    }

    private static void rewriteCrc(byte[] raw) {
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putInt(60, HeartbeatCodec.checksum(raw));
    }
}
