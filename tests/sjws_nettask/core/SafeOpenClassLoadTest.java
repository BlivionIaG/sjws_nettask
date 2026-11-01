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
package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * On Linux the SecureDirectoryStream path must not load {@link SafeOpenAt} or {@code java.lang.foreign}
 * helpers for file open. Mac loads them on first open; this test is Linux-only.
 */
public class SafeOpenClassLoadTest {

    private static final String OPEN_AT = "sjws_nettask.core.SafeOpenAt";

    @TempDir
    Path dir;

    @Test
    void linuxSecureOpenDoesNotLoadOpenAt() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"));
        Assumptions.assumeTrue(SafeOpen.SECURE);
        var root = Files.createDirectories(this.dir.resolve("root"));
        Files.writeString(root.resolve("a.txt"), "hello-class-load");
        var log = this.dir.resolve("classload.log");
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(java, "-Xlog:class+load=info",
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                SafeOpenClassLoadTest.class.getName(), root.toString()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        var proc = pb.start();
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "subprocess hung");
        var text = Files.readString(log);
        assertTrue(proc.exitValue() == 0, text);
        assertFalse(text.contains(OPEN_AT), "SafeOpenAt loaded on Linux secure open:\n" + text);
        assertTrue(text.contains("SAFEOPEN_CLASSLOAD_OK"), text);
    }

    /** Subprocess entry: open one file through the public API and exit. */
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]).toRealPath();
        var file = root.resolve("a.txt");
        var a = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
        try (var ch = SafeOpen.open(new Body.FileBody(file, 0, a.size(), Validator.of(a), root))) {
            if (ch.size() < 1) {
                throw new IllegalStateException("empty");
            }
        }
        System.out.println("SAFEOPEN_CLASSLOAD_OK");
    }
}
