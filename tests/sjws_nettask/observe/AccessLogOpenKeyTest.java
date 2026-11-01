package sjws_nettask.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.ServerConfig.AccessLogFormat;

/** #406 / #428: openedKey must describe the inode of the stream we keep. */
final class AccessLogOpenKeyTest {

    @TempDir
    Path dir;

    @Test
    void openFileWithKeyMatchesPathKeyWhenStable() throws Exception {
        Path path = dir.resolve("access.log");
        var opened = AccessLog.openFileWithKey(path);
        try {
            Object pathKey = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            Assumptions.assumeTrue(pathKey != null, "FS without file keys");
            assertEquals(pathKey, opened.key);
            opened.stream.write("x\n".getBytes());
            opened.stream.flush();
            assertTrue(Files.size(path) > 0);
        } finally {
            opened.stream.close();
        }
    }

    @Test
    void startRecordsKeyMatchingOpenedFile() throws Exception {
        Path path = dir.resolve("started.log");
        try (var log = AccessLog.start(path.toString(), AccessLogFormat.COMMON)) {
            log.offer("127.0.0.1", "GET", "/", "HTTP/1.1", 200, 1, 0, null, null);
            // drain
            for (int i = 0; i < 50 && Files.notExists(path); i++) {
                Thread.sleep(20);
            }
            assertNotNull(log);
            assertTrue(Files.exists(path));
        }
    }
}
