/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;

/** Standalone and control processes must not load the data-role readiness wrapper. */
@Timeout(30)
class DataReadyHandlerClassLoadTest {

    @TempDir
    Path dir;

    @Test
    void standaloneAndControlDoNotLoadDataReadyHandler() throws Exception {
        assertNotLoaded("standalone");
        assertNotLoaded("control");
    }

    private void assertNotLoaded(String mode) throws Exception {
        var root = Files.createDirectory(dir.resolve(mode + "-html"));
        Files.writeString(root.resolve("index.html"), "ok");
        var log = dir.resolve(mode + "-classes.log");
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp",
                System.getProperty("java.class.path"), DataReadyHandlerClassLoadTest.class.getName(),
                root.toString(), mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String text;
        try {
            boolean done = process.waitFor(25, TimeUnit.SECONDS);
            text = Files.exists(log) ? Files.readString(log) : "";
            assertTrue(done, mode + " subprocess hung: " + excerpt(text));
        } finally {
            process.destroyForcibly();
        }
        assertTrue(text.contains("started " + mode), excerpt(text));
        assertFalse(text.contains("sjws_nettask.DataReadyHandler "),
                "DataReadyHandler loaded by a " + mode + " start: " + excerpt(text));
    }

    private static String excerpt(String text) {
        return text.lines().filter(line -> !line.contains("class,load")).reduce("", (a, b) -> a + "\n" + b);
    }

    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        var mode = args[1];
        var yaml = root.resolve("sjws.yaml");
        Files.writeString(yaml, "node:\n  mode: " + mode + "\nserver:\n  port: 0\n  root: "
                + root.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(loaded, null, !"control".equals(mode))) {
            System.out.println("started " + mode);
        }
    }
}
