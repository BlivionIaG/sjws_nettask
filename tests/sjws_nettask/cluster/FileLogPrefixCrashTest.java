package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** A prefix or suffix truncate leaves the whole old segment or the whole new one, whatever the crash point. */
class FileLogPrefixCrashTest {

    private static final GroupId GROUP = new GroupId("msg/0");

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static FileLogStorage filled(Path root) throws Exception {
        var log = new FileLogStorage(root);
        for (int i = 1; i <= 5; i++) {
            log.append(GROUP, i, 1, b("e" + i));
        }
        log.force(GROUP);
        return log;
    }

    private static FileLogStorage crashingAt(Path root, String stage) throws Exception {
        var log = filled(root);
        log.crashHook = at -> {
            if (at.equals(stage)) {
                throw new IllegalStateException("crash at " + at);
            }
        };
        assertThrows(IllegalStateException.class, () -> log.truncatePrefix(GROUP, 3));
        log.close();
        return new FileLogStorage(root);
    }

    @Test
    void crashBeforeTheRenameKeepsTheWholeOldSegment() throws Exception {
        var root = Files.createTempDirectory("prefix-crash-temp");
        var log = crashingAt(root, "msg_0.log:rewrite-temp");
        assertEquals(5L, log.lastIndex(GROUP));
        for (int i = 1; i <= 5; i++) {
            assertArrayEquals(b("e" + i), log.read(GROUP, i));
        }
        assertFalse(Files.exists(root.resolve("msg_0.log.rewrite")), "stale temp is dropped on open");
        log.close();
    }

    @Test
    void crashAfterTheRenameKeepsTheWholeNewSegment() throws Exception {
        var root = Files.createTempDirectory("prefix-crash-renamed");
        var log = crashingAt(root, "msg_0.log:rewrite-renamed");
        assertNull(log.read(GROUP, 3));
        assertArrayEquals(b("e4"), log.read(GROUP, 4));
        assertArrayEquals(b("e5"), log.read(GROUP, 5));
        assertEquals(5L, log.lastIndex(GROUP));
        log.close();
    }

    @Test
    void appendsAfterARewriteLandInTheNewSegmentAndSurviveRestart() throws Exception {
        var root = Files.createTempDirectory("prefix-rewrite-append");
        var log = filled(root);
        log.truncatePrefix(GROUP, 2);
        log.truncateSuffix(GROUP, 5);
        log.append(GROUP, 5, 2, b("new5"));
        log.force(GROUP);
        log.close();
        var reopened = new FileLogStorage(root);
        assertNull(reopened.read(GROUP, 2));
        assertArrayEquals(b("e3"), reopened.read(GROUP, 3));
        assertArrayEquals(b("new5"), reopened.read(GROUP, 5));
        assertEquals(2L, reopened.termAt(GROUP, 5));
        assertFalse(Files.exists(root.resolve("msg_0.log.rewrite")));
        assertTrue(reopened.survivesRestart());
        assertFalse(new MemoryLogStorage().survivesRestart());
        reopened.close();
    }
}
