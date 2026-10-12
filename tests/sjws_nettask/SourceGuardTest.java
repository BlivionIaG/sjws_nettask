package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Static guards from the design review: no carrier pinning (M12) and no Netty in core/HTTP-1.
 */
class SourceGuardTest {

    private static String all(Path dir) throws IOException {
        var sb = new StringBuilder();
        try (var walk = Files.walk(dir)) {
            for (var p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".java")) {
                    sb.append("//FILE ").append(p).append('\n').append(Files.readString(p)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @Test
    void noSynchronizedAnywhere() throws IOException {
        // Virtual threads pin their carrier inside synchronized on JDK 21; use ReentrantLock if needed.
        var offenders = new ArrayList<String>();
        try (var walk = Files.walk(Path.of("src"))) {
            for (var p : (Iterable<Path>) walk::iterator) {
                if (p.toString().endsWith(".java") && Files.readString(p).matches("(?s).*\\bsynchronized\\b.*")) {
                    offenders.add(p.toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "synchronized found in " + offenders);
    }

    @Test
    void computeIfAbsentOnlyWithoutIo() throws IOException {
        var src = all(Path.of("src"));
        int count = src.split("computeIfAbsent\\(", -1).length - 1;
        // The only use: header list creation in Http1Parser (pure, no I/O).
        assertEquals(1, count, "review new computeIfAbsent uses: no file I/O inside (design review M12)");
        assertTrue(Files.readString(Path.of("src/sjws_nettask/http1/Http1Parser.java"))
                .contains("computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), k -> new ArrayList<>())"));
    }

    @Test
    void noNettyInCoreOrHttp1() throws IOException {
        assertFalse(all(Path.of("src/sjws_nettask/core")).contains("io.netty"));
        assertFalse(all(Path.of("src/sjws_nettask/http1")).contains("io.netty"));
    }

    @Test
    void coreDoesNotDependOnTransports() throws IOException {
        var core = all(Path.of("src/sjws_nettask/core"));
        assertFalse(core.contains("import sjws_nettask.http1"), "core must not import the HTTP/1 package");
        assertFalse(all(Path.of("src/sjws_nettask/h3")).contains("sjws_nettask.http1"),
                "h3 must not depend on the HTTP/1 package (shared rules live in core)");
        assertFalse(core.contains("java.net.Socket"), "core must not touch sockets");
        var cluster = all(Path.of("src/sjws_nettask/cluster"));
        assertFalse(cluster.contains("sjws_nettask.ha"), "cluster must not import the HTTP HA package");
        assertFalse(cluster.contains("sjws_nettask.http1"), "cluster must not import HTTP/1");
        assertFalse(cluster.contains("io.netty"), "cluster must not import Netty");
    }
}
