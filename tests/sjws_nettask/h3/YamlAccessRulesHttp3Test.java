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

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.tls.TlsMaterial;

/** Regression: the YAML {@code access:} section is enforced over HTTP/3, at startup and across reloads. */
@Tag("h3")
class YamlAccessRulesHttp3Test {

    @TempDir
    Path base;

    @BeforeAll
    static void natives() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Http3Server.isAvailable(),
                () -> "native QUIC unavailable: " + Http3Server.unavailabilityCause());
    }

    @Test
    void yamlDenyIsEnforcedOverHttp3AndFollowsReload() throws Exception {
        var root = H3Fixture.webRoot(this.base);
        var tls = TlsMaterial.selfSigned("localhost");
        var cert = Files.writeString(this.base.resolve("cert.pem"),
                SjwsServerHttp3Test.pem("CERTIFICATE", tls.certificate().getEncoded()));
        var key = Files.writeString(this.base.resolve("key.pem"),
                SjwsServerHttp3Test.pem("PRIVATE KEY", tls.key().getEncoded()));
        var yaml = this.base.resolve("sjws.yaml");
        var head = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
        Files.writeString(yaml, head + "access:\n  rules: [\"deny:/hello.txt\"]\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--h3", "--h3-port=0",
                "--h3-host=127.0.0.1", "--cert=" + cert, "--key=" + key}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded); var client = new H3TestClient(tls.certificate())) {
            assertNull(server.h3Failure());
            var quic = client.connect(new InetSocketAddress("127.0.0.1", server.udpPort()));
            assertEquals(403, H3TestClient.get(quic, "/hello.txt").status());
            assertEquals(403, H3TestClient.get(quic, "/sub/../hello.txt").status());
            assertEquals(200, H3TestClient.get(quic, "/style.css").status());

            Files.writeString(yaml, head);
            var lifted = server.reload();
            assertTrue(lifted.applied(), lifted.errors().toString());
            assertEquals(200, H3TestClient.get(quic, "/hello.txt").status());

            Files.writeString(yaml, head + "access:\n  deny: [\"*.css\"]\n");
            var added = server.reload();
            assertTrue(added.applied(), added.errors().toString());
            assertEquals(403, H3TestClient.get(quic, "/style.css").status());
            assertEquals(200, H3TestClient.get(quic, "/hello.txt").status());
        }
    }
}
