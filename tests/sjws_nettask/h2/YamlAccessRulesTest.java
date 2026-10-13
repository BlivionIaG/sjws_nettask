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
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.AccessSettings;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.core.AccessRules;

/**
 * Regression: the YAML {@code access:} section is enforced (it used to be stored and silently
 * ignored), over HTTP/1.1 and HTTP/2, at startup and across reloads, merged with the command line.
 */
class YamlAccessRulesTest {

    @TempDir
    Path dir;

    private Path root() throws Exception {
        var root = Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("secret.txt"), "s");
        Files.writeString(root.resolve("old.bak"), "b");
        Files.createDirectories(root.resolve("private"));
        Files.writeString(root.resolve("private/x.txt"), "x");
        return root;
    }

    private Path yaml(Path root, String access) throws Exception {
        return Files.writeString(this.dir.resolve("sjws.yaml"),
                "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n" + access);
    }

    private static int h1(SjwsServer s, String path) throws Exception {
        return HttpTestSupport.get(s.tcpPort(), path, 4000).statusCode();
    }

    private static String h2(SjwsServer s, String path) throws Exception {
        try (var h2 = new RawH2(((Http2Server) s.h2()).h2cPort(), 65535)) {
            h2.get(1, path);
            var hpack = new io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder();
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.HEADERS && f.stream() == 1) {
                    return hpack.decodeHeaders(1, io.netty.buffer.Unpooled.wrappedBuffer(f.payload())).status()
                            .toString();
                }
            }
        }
        fail("no response on HTTP/2 for " + path);
        return "";
    }

    @Test
    void yamlDenyIsEnforcedOverHttp1AndHttp2AndFollowsReload() throws Exception {
        var root = root();
        var file = yaml(root, "access:\n  deny: [\"*.bak\"]\n  rules: [\"deny:/secret.txt\", \"hide:/private\"]\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + file, "--h2", "--h2-port=0", "--h2c-port=0"},
                Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertTrue(loaded.warnings().stream().noneMatch(w -> w.contains("not enforced")), loaded.warnings().toString());
        try (var server = SjwsServer.start(loaded)) {
            assertEquals(200, h1(server, "/a.txt"));
            assertEquals(403, h1(server, "/secret.txt"));
            assertEquals(403, h1(server, "/old.bak"));
            assertEquals(403, h1(server, "/private/../secret.txt"));
            assertEquals(404, h1(server, "/private/x.txt"));
            assertEquals("200", h2(server, "/a.txt"));
            assertEquals("403", h2(server, "/secret.txt"));
            assertEquals("403", h2(server, "/old.bak"));
            assertEquals("404", h2(server, "/private/x.txt"));

            // reload: remove every rule, they are lifted
            yaml(root, "");
            var lifted = server.reload();
            assertTrue(lifted.applied(), lifted.errors().toString());
            assertEquals(200, h1(server, "/secret.txt"));
            assertEquals(200, h1(server, "/old.bak"));
            assertEquals("200", h2(server, "/secret.txt"));
            assertEquals("200", h2(server, "/private/x.txt"));

            // reload: add a rule back, it is enforced
            yaml(root, "access:\n  rules:\n    - deny:/a.txt\n");
            var added = server.reload();
            assertTrue(added.applied(), added.errors().toString());
            assertEquals(403, h1(server, "/a.txt"));
            assertEquals("403", h2(server, "/a.txt"));
            assertEquals(200, h1(server, "/secret.txt"));

            // a malformed owner keeps the previous (enforcing) snapshot
            yaml(root, "access:\n  require_owner: no-such-user-sjws-test\n");
            assertFalse(server.reload().applied());
            assertEquals(403, h1(server, "/a.txt"));
        }
    }

    @Test
    void commandLineAndYamlAreMerged() throws Exception {
        var root = root();
        var file = yaml(root, "access:\n  deny: [\"*.bak\"]\n  rules: [\"deny:/a.txt\"]\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + file, "--deny=/secret.txt",
                "--access-rule=allow:/a.txt"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            assertEquals(403, h1(server, "/secret.txt"), "CLI deny list");
            assertEquals(403, h1(server, "/old.bak"), "YAML deny list");
            assertEquals(200, h1(server, "/a.txt"), "CLI rule comes first and wins");
        }
    }

    @Test
    void mergePrecedence() {
        var cli = new AccessRules(java.util.List.of("/c"), java.util.List.of(AccessRules.Rule.parse("hide:/x")),
                false, null, false);
        assertSame(cli, ConfigSnapshot.effectiveAccess(cli, AccessSettings.DEFAULT), "no YAML: unchanged");
        var merged = ConfigSnapshot.effectiveAccess(cli, new AccessSettings(java.util.List.of("/y"),
                java.util.List.of("allow:/x", "deny:/z"), true, "0", true));
        assertEquals(java.util.List.of("/c", "/y"), merged.denyList().stream().map(AccessRules.Rule::pattern).toList());
        assertEquals("hide:/x", merged.rules().get(0).toString());
        assertEquals(404, merged.check("/x"));
        assertEquals(403, merged.check("/z"));
        assertTrue(merged.allowDotfiles());
        assertTrue(merged.requireWorldReadable());
        assertEquals("0", merged.requireOwner());
        var cliOwner = new AccessRules(java.util.List.of(), java.util.List.of(), false, "1", false);
        assertEquals("1", ConfigSnapshot.effectiveAccess(cliOwner,
                new AccessSettings(java.util.List.of(), java.util.List.of(), false, "0", false)).requireOwner());
    }
}
