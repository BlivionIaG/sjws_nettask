package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** The ASCII fast paths of {@link TaskEnvelope} produce and accept exactly the DataOutput (modified UTF-8) format. */
class TaskEnvelopeFastPathTest {

    static byte[] streamEncode(TaskEnvelope e) throws Exception {
        var buf = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buf)) {
            out.writeByte(TaskEnvelope.VERSION);
            out.write(e.taskId());
            out.write(e.correlationId());
            out.writeLong(e.deadlineMs());
            for (var s : new String[] {e.replyTo(), e.traceparent(), e.tracestate(), e.budgetRef(), e.contentType()}) out.writeUTF(s);
            out.writeInt(e.body().length);
            out.write(e.body());
        }
        return buf.toByteArray();
    }

    static TaskEnvelope env(String text, byte[] body) {
        var id = new byte[16];
        Arrays.fill(id, (byte) 7);
        var corr = new byte[16];
        corr[3] = (byte) 0xF0;
        return new TaskEnvelope(id, corr, text, 1234L, "00-abc-01", "", text, "application/json", body);
    }

    @Test
    void sameBytesAsStreamFormatForAsciiAndNonAscii() throws Exception {
        for (var text : new String[] {"", "reply.topic", "naïve", "nul\0x", "emoji\uD83D\uDE00", "\u007F"}) {
            for (var body : new byte[][] {new byte[0], {1, 2, (byte) 0xFF}, new byte[5000]}) {
                var e = env(text, body);
                var bytes = e.encode();
                assertArrayEquals(streamEncode(e), bytes, text);
                assertEquals(e, TaskEnvelope.decode(bytes), text);
            }
        }
    }

    @Test
    void malformedPayloadsStillRejected() throws Exception {
        var good = env("x", new byte[] {9}).encode();
        for (int cut = 0; cut < good.length - 1; cut++) {
            var bad = Arrays.copyOf(good, cut);
            assertThrows(IllegalArgumentException.class, () -> TaskEnvelope.decode(bad), "cut " + cut);
        }
        var v2 = good.clone();
        v2[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> TaskEnvelope.decode(v2));
        var neg = good.clone();
        neg[good.length - 5] = (byte) 0x80; // body length negative
        assertThrows(IllegalArgumentException.class, () -> TaskEnvelope.decode(neg));
    }
}
