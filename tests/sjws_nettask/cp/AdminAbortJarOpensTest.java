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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Packaged {@code java -jar} must apply the jar {@code Add-Opens} for
 * {@code jdk.httpserver/sun.net.httpserver} so admin TLS can install a reflective abort handle.
 * Tagged {@code jar} so Maven runs this after {@code package} (surefire execution {@code jar-tests}).
 */
@Tag("jar")
class AdminAbortJarOpensTest {

    static Path jar() throws Exception {
        try (var files = Files.list(Path.of("target"))) {
            var jars = files.filter(p -> p.getFileName().toString().matches("sjws_nettask-[^-]+(-SNAPSHOT)?\\.jar"))
                    .toList();
            Assumptions.assumeTrue(jars.size() == 1, "run `mvn package` first; found " + jars);
            return jars.get(0);
        }
    }

    @Test
    void packagedJarManifestListsHttpserverAddOpens() throws Exception {
        try (var jar = new JarFile(jar().toFile())) {
            var opens = jar.getManifest().getMainAttributes().getValue("Add-Opens");
            assertTrue(opens != null && opens.contains("jdk.httpserver/sun.net.httpserver"), opens);
        }
    }

    @Test
    void javaDashJarSelfCheckInstallsAbortWithoutCliAddOpens() throws Exception {
        var jar = jar().toAbsolutePath();
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // Deliberately omit --add-opens: the packaged jar Add-Opens must be enough under -jar.
        var pb = new ProcessBuilder(java, "-jar", jar.toString(), "--self-check-admin-tls-abort");
        pb.redirectErrorStream(true);
        pb.directory(Path.of(".").toAbsolutePath().toFile());
        var proc = pb.start();
        var output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), output);
        assertEquals(0, proc.exitValue(), output);
        assertTrue(output.contains("admin-tls-abort: ok"), output);
    }
}
