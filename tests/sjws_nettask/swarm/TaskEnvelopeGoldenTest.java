package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/** The {@code task-env-*} golden frames (shared with the Python SDK) decode and re-encode as standard UTF-8. */
class TaskEnvelopeGoldenTest {

    @Test
    void goldenEnvelopesAreStandardUtf8() throws Exception {
        var want = java.util.Map.of("task-env-nul", "reply.nul\0x", "task-env-emoji", "reply.\uD83D\uDE00");
        int n = 0;
        for (var line : Files.readAllLines(Path.of("tests/sjws_nettask/swarm/wire/golden-v1.txt"))) {
            var f = line.split(" ");
            if (want.containsKey(f[0])) {
                var env = TaskEnvelope.decode(HexFormat.of().parseHex(f[3]));
                assertEquals(want.get(f[0]), env.replyTo(), f[0]);
                assertEquals("text/plain", env.contentType(), f[0]);
                assertArrayEquals(HexFormat.of().parseHex(f[3]), env.encode(), f[0]);
                n++;
            }
        }
        assertEquals(2, n);
    }
}
