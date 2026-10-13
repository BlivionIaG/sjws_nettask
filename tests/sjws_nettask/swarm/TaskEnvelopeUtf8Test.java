package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Wire text fields are standard UTF-8 (not Java's modified UTF-8): NUL is one byte, a surrogate pair is four. */
class TaskEnvelopeUtf8Test {

    @Test
    void textFieldsAreStandardUtf8() {
        var id = new byte[16];
        for (var text : new String[] {"nul\0x", "emoji\uD83D\uDE00", "naïve", ""}) {
            var e = new TaskEnvelope(id, id, text, 1L, "", "", "", "", new byte[] {1});
            var bytes = e.encode();
            var want = text.getBytes(StandardCharsets.UTF_8);
            assertEquals(want.length, ((bytes[41] & 0xFF) << 8) | (bytes[42] & 0xFF), text);
            var got = new byte[want.length];
            System.arraycopy(bytes, 43, got, 0, want.length);
            assertArrayEquals(want, got, text);
            assertEquals(e, TaskEnvelope.decode(bytes), text);
        }
    }
}
