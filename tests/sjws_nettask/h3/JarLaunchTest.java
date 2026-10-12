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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.tls.TlsMaterial;

/**
 * The README command, literally: {@code java -jar target/sjws_nettask-<version>.jar <port> <root> --h3}.
 * Tagged "jar" so Maven runs it after {@code package} (surefire execution {@code jar-tests}).
 */
@Tag("jar")
@Tag("h3")
class JarLaunchTest {

    @TempDir
    Path base;

    static Path jar() throws Exception {
        try (var files = Files.list(Path.of("target"))) {
            var jars = files.filter(p -> p.getFileName().toString().matches("sjws_nettask-[^-]+(-SNAPSHOT)?\\.jar"))
                    .toList();
            Assumptions.assumeTrue(jars.size() == 1, "run `mvn package` first; found " + jars);
            return jars.get(0);
        }
    }

    @Test
    void manifestPutsNettyAndTheNativesOnTheClassPath() throws Exception {
        try (var jar = new JarFile(jar().toFile())) {
            var classPath = jar.getManifest().getMainAttributes().getValue("Class-Path");
            assertEquals("sjws_nettask.Sjws_nettask", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            for (var entry : classPath.split(" ")) {
                assertTrue(Files.isRegularFile(Path.of("target").resolve(entry)), "missing " + entry);
            }
            assertTrue(classPath.contains("lib/netty-codec-http3-"), classPath);
            assertTrue(classPath.contains("linux-x86_64"), classPath);
        }
    }

    @Test
    void javaDashJarWithH3ServesHttp3() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(), () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
        var root = H3Fixture.webRoot(this.base);
        var tls = TlsMaterial.selfSigned("localhost");
        var cert = Files.writeString(this.base.resolve("cert.pem"),
                SjwsServerHttp3Test.pem("CERTIFICATE", tls.certificate().getEncoded()));
        var key = Files.writeString(this.base.resolve("key.pem"),
                SjwsServerHttp3Test.pem("PRIVATE KEY", tls.key().getEncoded()));
        SjwsServerHttp3Test.runWithH3(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar().toAbsolutePath().toString()), root, cert, key, tls);
    }
}
