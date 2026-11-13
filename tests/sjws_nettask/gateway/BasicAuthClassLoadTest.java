package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** basic-auth is off until a document names it; a standalone start must not load the filter class. */
class BasicAuthClassLoadTest {

    private static final String FILTER = "sjws_nettask.gateway.BasicAuthFilter";

    @TempDir
    Path dir;

    @Test
    void standaloneStartDoesNotLoadBasicAuthFilter() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html"));
        var log = this.dir.resolve("classes.log");
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.ha.BenchServer", "--mode=single", "--port=0", "--root=" + root)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            var text = "";
            while (System.nanoTime() < deadline) {
                if (Files.exists(log)) {
                    text = Files.readString(log);
                    if (text.contains("bench port=")) {
                        break;
                    }
                }
                Thread.sleep(50);
            }
            assertTrue(text.contains("bench port="), text);
            assertFalse(text.contains("] " + FILTER + " source:"), FILTER + " loaded when basic-auth is unset");
        } finally {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }
}
