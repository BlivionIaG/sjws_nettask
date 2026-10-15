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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A prefix or suffix truncate leaves either the whole old segment or the whole new one, at every
 * crash point of the rewrite: temp written (not fsynced), temp fsynced, renamed, directory fsynced.
 */
class FileLogRewriteCrashTest {

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

    private static void truncate(FileLogStorage log, String op) throws Exception {
        if (op.equals("prefix")) {
            log.truncatePrefix(GROUP, 3);
        } else {
            log.truncateSuffix(GROUP, 3);
        }
    }

    private static void assertOld(FileLogStorage log) throws Exception {
        assertEquals(5L, log.lastIndex(GROUP));
        for (int i = 1; i <= 5; i++) {
            assertArrayEquals(b("e" + i), log.read(GROUP, i));
        }
    }

    private static void assertNew(FileLogStorage log, String op) throws Exception {
        if (op.equals("prefix")) {
            for (int i = 1; i <= 3; i++) {
                assertNull(log.read(GROUP, i));
            }
            assertArrayEquals(b("e4"), log.read(GROUP, 4));
            assertArrayEquals(b("e5"), log.read(GROUP, 5));
            assertEquals(5L, log.lastIndex(GROUP));
        } else {
            assertArrayEquals(b("e1"), log.read(GROUP, 1));
            assertArrayEquals(b("e2"), log.read(GROUP, 2));
            assertNull(log.read(GROUP, 3));
            assertEquals(2L, log.lastIndex(GROUP));
        }
    }

    @ParameterizedTest(name = "{0} crash at {1}")
    @CsvSource({
        "prefix, rewrite-written, old",
        "prefix, rewrite-temp, old",
        "prefix, rewrite-renamed, new",
        "prefix, rewrite-dir-synced, new",
        "suffix, rewrite-written, old",
        "suffix, rewrite-temp, old",
        "suffix, rewrite-renamed, new",
        "suffix, rewrite-dir-synced, new",
    })
    void crashAtEveryStepLeavesAWholeSegment(String op, String stage, String expect) throws Exception {
        var root = Files.createTempDirectory("rewrite-crash");
        var log = filled(root);
        log.crashHook = at -> {
            if (at.equals("msg_0.log:" + stage)) {
                throw new IllegalStateException("crash at " + at);
            }
        };
        assertThrows(IllegalStateException.class, () -> truncate(log, op));
        // The live instance matches the disk too: nothing is appended to a replaced segment.
        log.crashHook = null;
        if (expect.equals("old")) {
            assertOld(log);
        } else {
            assertNew(log, op);
        }
        log.close();
        var reopened = new FileLogStorage(root);
        if (expect.equals("old")) {
            assertOld(reopened);
        } else {
            assertNew(reopened, op);
        }
        assertFalse(Files.exists(root.resolve("msg_0.log.rewrite")), "a stale temp is dropped on open");
        reopened.close();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"prefix", "suffix"})
    void appendAfterAFailedDirSyncLandsInTheLiveSegment(String op) throws Exception {
        var root = Files.createTempDirectory("rewrite-after-fail");
        var log = filled(root);
        log.crashHook = at -> {
            if (at.equals("msg_0.log:rewrite-renamed")) {
                throw new IllegalStateException("crash at " + at);
            }
        };
        assertThrows(IllegalStateException.class, () -> truncate(log, op));
        log.crashHook = null;
        long next = log.lastIndex(GROUP) + 1;
        log.append(GROUP, next, 2, b("after"));
        log.force(GROUP);
        log.close();
        var reopened = new FileLogStorage(root);
        assertArrayEquals(b("after"), reopened.read(GROUP, next));
        reopened.close();
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
