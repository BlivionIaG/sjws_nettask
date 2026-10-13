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
package sjws_nettask.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.core.MiddlewareSpec;

class ConfigModelTest {

    private static final String SECRET = "super-secret-password";

    @TempDir
    Path dir;

    @Test
    void omittedNodeIsStandaloneAndVersioned() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var yaml = dir.resolve("sjws.yaml");
        var text = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
        Files.writeString(yaml, text);
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var document = loaded.document();
        assertEquals(NodeSettings.Mode.STANDALONE, document.node().mode());
        assertTrue(document.node().labels().isEmpty());
        assertEquals(ConfigDocument.SCHEMA, document.schemaVersion());
        assertEquals(0, document.bundleVersion());
        assertTrue(document.canonicalHash().matches("[0-9a-f]{64}"));
        assertNotEquals(sha256(Files.readAllBytes(yaml)), document.canonicalHash());
        assertEquals(LastGoodStore.defaultStateDir(), document.stateDir());
        assertFalse(ConfigCodec.text(document).contains(SECRET));
    }

    @Test
    void canonicalHashIgnoresKeyOrderAndTheKeyPassword() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var abs = root.toAbsolutePath();
        var first = dir.resolve("a.yaml");
        var second = dir.resolve("b.yaml");
        Files.writeString(first, "server:\n  root: " + abs + "\n  port: 9\n");
        Files.writeString(second, "server:\n  port: 9\n  root: " + abs + "\n");
        var left = ConfigLoader.load(new String[] {"--config=" + first}, Map.of());
        var right = ConfigLoader.load(new String[] {"--config=" + second}, Map.of());
        assertTrue(left.ok() && right.ok(), left.errors() + " " + right.errors());
        assertEquals(left.document().canonicalHash(), right.document().canonicalHash());
        var withSecret = left.document().withServer(left.document().server().toBuilder().tlsKeyPassword(SECRET).build());
        assertEquals(left.document().canonicalHash(), withSecret.canonicalHash());
        assertFalse(ConfigCodec.text(withSecret).contains(SECRET));
        var copy = dir.resolve("copy.yaml");
        Files.writeString(copy, ConfigCodec.text(left.document()));
        var again = ConfigLoader.load(new String[] {"--config=" + copy}, Map.of());
        assertTrue(again.ok(), again.errors().toString());
        assertEquals(left.document().canonicalHash(), again.document().canonicalHash());
    }

    @Test
    void nodeSchemaAndVersionAreStrict() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var abs = root.toAbsolutePath();
        assertTrue(error("node:\n  mode: banana\nserver:\n  root: " + abs + "\n").contains("standalone"));
        assertTrue(error("node:\n  labels:\n    bad key: edge\nserver:\n  root: " + abs + "\n").contains("invalid key"));
        assertTrue(error("node:\n  labels:\n    site: bad value\nserver:\n  root: " + abs + "\n").contains("invalid value"));
        assertTrue(error("node:\n  extra: true\nserver:\n  root: " + abs + "\n").contains("Unknown key"));
        assertTrue(error("schema: 2\nserver:\n  root: " + abs + "\n").contains("unsupported schema"));
        assertTrue(error("version: -1\nserver:\n  root: " + abs + "\n").contains("version"));
        var yaml = dir.resolve("data.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 4\nnode:\n  mode: Data\n  labels:\n    site: edge-a\nserver:\n  port: 0\n  root: " + abs + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(4, loaded.document().bundleVersion());
        assertEquals(NodeSettings.Mode.DATA, loaded.document().node().mode());
        assertEquals("edge-a", loaded.document().node().labels().get("site"));
        var tooMany = new StringBuilder("node:\n  labels:\n");
        for (int i = 0; i < 65; i++) {
            tooMany.append("    k").append(i).append(": v\n");
        }
        tooMany.append("server:\n  root: ").append(abs).append('\n');
        assertTrue(error(tooMany.toString()).contains("at most"));
    }

    @Test
    void dataPlaneBootsFromLastGoodWhenTheFileIsMissing() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "node:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        var env = Map.of("WEB_SERVER_STATE_DIR", state.toString());
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, env);
        assertTrue(loaded.ok(), loaded.errors().toString());
        assertEquals(state.toAbsolutePath().normalize(), loaded.document().stateDir());
        LastGoodStore.write(loaded.document());
        var json = LastGoodStore.file(state);
        assertTrue(Files.isRegularFile(json));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(json.getParent()));
        Files.delete(yaml);
        var again = ConfigLoader.load(new String[] {"--config=" + yaml}, env);
        assertTrue(again.ok(), again.errors().toString());
        assertTrue(again.warnings().stream().anyMatch(w -> w.contains("last good")));
        assertEquals(NodeSettings.Mode.DATA, again.document().node().mode());
        assertEquals(yaml, again.document().configFile());
        var legacy = LastGoodStore.beside(yaml);
        Files.writeString(legacy, "node:\n  mode: data\nserver:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        Files.delete(json);
        var migrated = ConfigLoader.load(new String[] {"--config=" + yaml}, env);
        assertTrue(migrated.ok(), migrated.errors().toString());
        assertTrue(migrated.warnings().stream().anyMatch(w -> w.contains(legacy.toAbsolutePath().toString())
                || w.contains("last good")));
        assertEquals(NodeSettings.Mode.DATA, migrated.document().node().mode());
    }

    @Test
    void standaloneLastGoodIsNotABootSource() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml, "--state-dir=" + dir}, Map.of());
        LastGoodStore.write(loaded.document());
        assertFalse(LastGoodStore.dataPlane(LastGoodStore.beside(yaml)));
        Files.delete(yaml);
        var missing = ConfigLoader.load(new String[] {"--config=" + yaml, "--state-dir=" + dir}, Map.of());
        assertFalse(missing.ok());
        assertTrue(missing.errors().get(0).contains("Cannot read config file"));
        assertFalse(missing.errors().get(0).contains("booted"));
        Files.writeString(LastGoodStore.beside(yaml), "[[[");
        assertFalse(LastGoodStore.dataPlane(LastGoodStore.beside(yaml)));
        assertEquals(LastGoodStore.beside(yaml), LastGoodStore.beside(LastGoodStore.beside(yaml)));
    }

    @Test
    void lastGoodWriteIsAtomicAndOwnerReadWrite() throws Exception {
        var document = ConfigDocument.defaults(ServerConfig.defaults(dir));
        assertDoesNotThrow(() -> LastGoodStore.write(document));
        var path = dir.resolve("sjws.yaml.last-good");
        LastGoodStore.write(path, document.withNode(new NodeSettings(NodeSettings.Mode.DATA, Map.of("site", "edge"))));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path));
        assertTrue(LastGoodStore.dataPlane(path));
        assertFalse(Files.readString(path).contains(SECRET));
        try (var list = Files.list(dir)) {
            assertTrue(list.map(p -> p.getFileName().toString()).noneMatch(name -> name.startsWith(".cfg-")));
        }
    }

    @Test
    void lastGoodWriteWorksWithoutPosixPermissions() throws Exception {
        var zip = dir.resolve("store.zip");
        var uri = URI.create("jar:" + zip.toUri());
        try (var fs = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            assertFalse(fs.supportedFileAttributeViews().contains("posix"));
            var path = fs.getPath("cfg.last-good");
            var document = ConfigDocument.defaults(ServerConfig.defaults(dir))
                    .withNode(new NodeSettings(NodeSettings.Mode.DATA, Map.of()));
            LastGoodStore.write(path, document);
            assertTrue(LastGoodStore.dataPlane(path));
            assertTrue(Files.readString(path).contains("mode: data"));
            try (var list = Files.list(fs.getPath("/"))) {
                assertTrue(list.map(p -> p.getFileName().toString()).noneMatch(name -> name.startsWith(".cfg-")));
            }
        }
    }

    @Test
    void codecOmitsSecretsAndCoversEverySection() throws Exception {
        var cert = dir.resolve("cert.pem");
        var key = dir.resolve("key.pem");
        Files.writeString(cert, "cert");
        Files.writeString(key, "key");
        var server = ServerConfig.defaults(dir).toBuilder()
                .port(9)
                .h3Port(443)
                .accessLog(dir.resolve("access.log").toString())
                .tlsCert(cert)
                .tlsKey(key)
                .tlsKeyPassword(SECRET)
                .build();
        var upstream = new Upstream("api", URI.create("http://127.0.0.1:9"), Duration.ofSeconds(45),
                Duration.ofMinutes(2), Duration.ofMillis(500), 1024, true);
        var hours = new Upstream("slow", URI.create("https://127.0.0.1:9/up"), Duration.ofHours(1),
                Duration.ofSeconds(30), Duration.ofDays(1), 2048, false);
        var routes = List.of(
                new RouteSpec("*.example.com", true, ".example.com", "/api", "/v1", new RouteSpec.Action.Proxy(upstream)),
                new RouteSpec(null, false, null, "/go", null, new RouteSpec.Action.Redirect(301, "/there", true)),
                new RouteSpec(null, false, null, "/files", null, new RouteSpec.Action.Static()));
        var routing = new Routing(true, ForwardedProto.HTTPS, List.of("10.0.0.0/8"),
                Map.of("slow", hours, "api", upstream), new RouteTable(routes));
        var middleware = List.of(new MiddlewareSpec("note", 1, "/api", Map.of("weird key", "a\"b\\c\n")));
        var access = new AccessSettings(List.of("*.bak"), List.of("hide:/drafts"), true, "www-data", true);
        var acme = new AcmeSettings(false, AcmeSettings.STAGING, "ops@example.com", List.of("example.com"),
                dir.resolve("acme"), 0, false, Duration.ofDays(30));
        var document = ConfigDocument.of(server, acme, routing, middleware, access,
                new NodeSettings(NodeSettings.Mode.CONTROL, Map.of("z", "1", "a", "2")), 1, 7, null);
        var text = ConfigCodec.text(document);
        assertFalse(text.contains(SECRET));
        assertTrue(text.contains("schema: 1"));
        assertTrue(text.contains("version: 7"));
        assertTrue(text.contains("mode: control"));
        assertTrue(text.indexOf("a:") < text.indexOf("z:"));
        assertTrue(text.contains("port: 443"));
        assertTrue(text.contains("45s"));
        assertTrue(text.contains("2m"));
        assertTrue(text.contains("500ms"));
        assertTrue(text.contains("1h"));
        assertTrue(text.contains("30d"));
        assertTrue(text.contains("proxy: \"api\""));
        assertTrue(text.contains("static: true"));
        assertTrue(text.contains("keep_path: true"));
        assertTrue(text.contains("allow_dotfiles: true"));
        assertEquals(document.canonicalHash(), ConfigCodec.sha256(text));
    }

    @Test
    void diffNamesSectionsWithoutSecrets() {
        var left = ConfigDocument.defaults(ServerConfig.defaults(dir));
        assertTrue(ConfigDiff.between(left, left, false).isEmpty());
        var root = left.withServer(left.server().toBuilder().root(dir.resolve("other")).build());
        assertEquals(ConfigDiff.Effect.LIVE, section(ConfigDiff.between(left, root, false), "server").effect());
        var port = left.withServer(left.server().withPort(9));
        var portChange = section(ConfigDiff.between(left, port, false), "server");
        assertEquals(ConfigDiff.Effect.RESTART, portChange.effect());
        assertTrue(portChange.detail().contains("port"));
        var both = left.withServer(left.server().withPort(9).toBuilder().root(dir.resolve("other")).build());
        var bothChange = section(ConfigDiff.between(left, both, false), "server");
        assertEquals(ConfigDiff.Effect.RESTART, bothChange.effect());
        assertTrue(bothChange.detail().contains("port") && bothChange.detail().contains("root"));
        var timeouts = left.withServer(left.server().toBuilder().idleTimeout(Duration.ofSeconds(4)).build());
        assertEquals(ConfigDiff.Effect.LIVE, section(ConfigDiff.between(left, timeouts, false), "server").effect());
        assertEquals(ConfigDiff.Effect.RESTART, section(ConfigDiff.between(left, timeouts, true), "server").effect());
        var http3 = left.withServer(left.server().toBuilder().h3Enabled(true).build());
        assertEquals(ConfigDiff.Effect.RESTART, section(ConfigDiff.between(left, http3, false), "http3").effect());
        var secret = left.withServer(left.server().toBuilder().tlsKeyPassword(SECRET).build());
        var tls = section(ConfigDiff.between(left, secret, false), "tls");
        assertEquals(ConfigDiff.Effect.LIVE, tls.effect());
        assertEquals("key_password", tls.detail());
        assertFalse(tls.detail().contains(SECRET));
        var labels = left.withNode(new NodeSettings(NodeSettings.Mode.STANDALONE, Map.of("site", "edge")));
        var labelChange = section(ConfigDiff.between(left, labels, false), "node");
        assertEquals(ConfigDiff.Effect.LIVE, labelChange.effect());
        assertEquals(ConfigDiff.Kind.ADDED, labelChange.kind());
        assertEquals("labels.site", labelChange.keys().get(0).key());
        assertEquals(ConfigDiff.Kind.ADDED, labelChange.keys().get(0).kind());
        var removed = section(ConfigDiff.between(labels, left, false), "node");
        assertEquals(ConfigDiff.Kind.REMOVED, removed.kind());
        assertEquals(ConfigDiff.Kind.CHANGED, portChange.kind());
        var mode = left.withNode(new NodeSettings(NodeSettings.Mode.DATA, Map.of("site", "edge")));
        assertEquals(ConfigDiff.Effect.RESTART, section(ConfigDiff.between(left, mode, false), "node").effect());
        var startup = left.withServer(left.server().toBuilder().h2Enabled(true).healthz(true)
                .metricsExposure(ServerConfig.MetricsExposure.LOCALHOST)
                .accessLogFormat(ServerConfig.AccessLogFormat.JSON).build());
        assertEquals(ConfigDiff.Effect.RESTART, section(ConfigDiff.between(left, startup, false), "startup").effect());
        var version = ConfigDocument.of(left.server(), left.acme(), left.routing(), left.middleware(), left.access(),
                left.node(), 2, 3, null);
        assertEquals("schema", section(ConfigDiff.between(left, version, false), "schema").detail());
        assertEquals(ConfigDiff.Effect.LIVE, section(ConfigDiff.between(left, version, false), "version").effect());
    }

    @Test
    void validateOnlyDoesNotRequireAServer() throws Exception {
        var missing = ConfigDocument.defaults(ServerConfig.defaults(dir.resolve("missing")));
        assertTrue(ConfigValidator.validate(missing).get(0).contains("does not exist"));
        var schema = ConfigDocument.of(ServerConfig.defaults(dir), AcmeSettings.disabled(), Routing.disabled(),
                List.of(), AccessSettings.DEFAULT, NodeSettings.STANDALONE, 2, 0, null);
        assertTrue(ConfigValidator.validate(schema).stream().anyMatch(e -> e.contains("unsupported schema")));
        var cert = dir.resolve("cert.pem");
        var key = dir.resolve("key.pem");
        var absent = ConfigDocument.defaults(ServerConfig.defaults(dir).toBuilder().tlsCert(cert).tlsKey(key).build());
        assertTrue(ConfigValidator.validate(absent).stream().anyMatch(e -> e.contains("tls.cert")));
        Files.writeString(cert, "cert");
        Files.writeString(key, "key");
        var present = ConfigDocument.defaults(ServerConfig.defaults(dir).toBuilder().tlsCert(cert).tlsKey(key).build());
        assertTrue(ConfigValidator.validate(present).isEmpty());
    }

    @Test
    void olderBundleIsRejectedAndApplyKeepsTheCurrentSnapshot() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "version: 2\nserver:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var board = new ChallengeBoard();
        var current = ConfigSnapshot.compile(loaded.document(), board, 1);
        var reloader = new ConfigReloader(loaded);
        var older = ConfigDocument.of(loaded.document().server(), loaded.document().acme(), loaded.document().routing(),
                loaded.document().middleware(), loaded.document().access(), loaded.document().node(), 1, 1, yaml);
        var rejected = reloader.apply(current, older, false, board, true);
        assertFalse(rejected.applied());
        assertTrue(rejected.errors().get(0).contains("older"));
        assertSame(current, rejected.snapshot());
        var broken = older.withServer(older.server().toBuilder().root(dir.resolve("nope")).build());
        var invalid = reloader.apply(current, ConfigDocument.of(broken.server(), broken.acme(), broken.routing(),
                broken.middleware(), broken.access(), broken.node(), 1, 3, yaml), false, board, true);
        assertFalse(invalid.applied());
        assertSame(current, invalid.snapshot());
    }

    @Test
    void applyRemoteSwapsAtomicallyAndKeepsLastGoodOnFailure() throws Exception {
        var first = Files.createDirectory(dir.resolve("one"));
        var second = Files.createDirectory(dir.resolve("two"));
        Files.writeString(first.resolve("index.html"), "one");
        Files.writeString(second.resolve("index.html"), "two");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "version: 2\nnode:\n  state_dir: " + dir.resolve("state").toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(loaded)) {
            assertEquals("one", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            assertFalse(Files.exists(LastGoodStore.beside(yaml)));
            assertFalse(Files.exists(LastGoodStore.file(loaded.document().stateDir())));
            var files = server.handler();
            var same = server.applyRemote(loaded.document());
            assertTrue(same.applied(), same.errors().toString());
            assertEquals(1, same.generation());
            assertSame(files, server.handler());
            var pushed = ConfigDocument.of(
                    loaded.document().server().toBuilder().root(second).port(9).tlsKeyPassword(SECRET).build(),
                    loaded.document().acme(), loaded.document().routing(), loaded.document().middleware(),
                    loaded.document().access(), new NodeSettings(NodeSettings.Mode.DATA, Map.of("site", "edge")),
                    1, 3, null);
            var applied = server.applyRemote(pushed);
            assertTrue(applied.applied(), applied.errors().toString());
            assertEquals(2, applied.generation());
            assertTrue(applied.needsRestart().stream().anyMatch(w -> w.contains("server.port")));
            assertTrue(applied.needsRestart().stream().anyMatch(w -> w.contains("node.mode")));
            assertEquals(NodeSettings.Mode.STANDALONE, applied.snapshot().document().node().mode());
            assertEquals("edge", applied.snapshot().document().node().labels().get("site"));
            assertEquals("two", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            assertFalse(Files.exists(LastGoodStore.beside(yaml)));
            assertEquals(yaml, applied.snapshot().document().configFile());
            var older = ConfigDocument.of(loaded.document().server().toBuilder().root(first).build(),
                    loaded.document().acme(), Routing.disabled(), List.of(), AccessSettings.DEFAULT,
                    NodeSettings.STANDALONE, 1, 1, null);
            var rejected = server.applyRemote(older);
            assertFalse(rejected.applied());
            assertEquals("two", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
        }
    }

    @Test
    void dataApplyVerifiesBeforeSwapAndWritesLastGoodOutsideTheRunningConfig() throws Exception {
        var first = Files.createDirectory(dir.resolve("one"));
        var second = Files.createDirectory(dir.resolve("two"));
        Files.writeString(first.resolve("index.html"), "one");
        Files.writeString(second.resolve("index.html"), "two");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "version: 2\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + first.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var closed = SjwsServer.start(loaded)) {
            var refused = closed.applyRemote(loaded.document().withServer(
                    loaded.document().server().toBuilder().root(second).build()));
            assertFalse(refused.applied());
            assertTrue(refused.errors().get(0).contains("verifier"));
            assertEquals("one", HttpTestSupport.get(closed.tcpPort(), "/", 2000).bodyText());
        }
        var key = "test-key".getBytes(StandardCharsets.UTF_8);
        var verifier = new MacSnapshotVerifier(key);
        try (var server = SjwsServer.start(loaded, verifier)) {
            var lastGood = LastGoodStore.file(state);
            assertTrue(Files.isRegularFile(lastGood));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(lastGood));
            var saved = Files.readString(lastGood);
            assertFalse(saved.contains(SECRET));
            var unsigned = ConfigDocument.of(
                    loaded.document().server().toBuilder().root(second).build(),
                    loaded.document().acme(), loaded.document().routing(), loaded.document().middleware(),
                    loaded.document().access(), loaded.document().node(), 1, 3, null).withStateDir(state);
            var missing = server.applyRemote(unsigned);
            assertFalse(missing.applied());
            assertTrue(missing.errors().get(0).contains("signature"));
            assertEquals("one", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            assertEquals(saved, Files.readString(lastGood));
            var bad = server.applyRemote(unsigned.withSignature(new byte[] {1, 2, 3}));
            assertFalse(bad.applied());
            assertTrue(bad.errors().get(0).contains("bad signature"));
            var signed = unsigned.withSignature(MacSnapshotVerifier.sign(key, unsigned));
            var applied = server.applyRemote(signed);
            assertTrue(applied.applied(), applied.errors().toString());
            assertEquals("two", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            var updated = Files.readString(lastGood);
            assertTrue(updated.contains("version: 3"));
            assertFalse(updated.contains(SECRET));
            var again = server.applyRemote(signed);
            assertTrue(again.applied());
            assertEquals(applied.generation(), again.generation());
            assertEquals(updated, Files.readString(lastGood));
            var older = signed.withServer(loaded.document().server().toBuilder().root(first).build());
            var rolled = ConfigDocument.of(older.server(), older.acme(), older.routing(), older.middleware(),
                    older.access(), older.node(), 1, 1, null).withStateDir(state);
            rolled = rolled.withSignature(MacSnapshotVerifier.sign(key, rolled));
            var rejected = server.applyRemote(rolled);
            assertFalse(rejected.applied());
            assertTrue(rejected.errors().get(0).contains("older"));
            assertEquals("two", HttpTestSupport.get(server.tcpPort(), "/", 2000).bodyText());
            assertEquals(updated, Files.readString(lastGood));
        }
    }

    @Test
    void verifierRejectsATamperedHashAndLastGoodIgnoresCorruptFiles() throws Exception {
        var key = "edge-key".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> new MacSnapshotVerifier(null));
        assertThrows(IllegalArgumentException.class, () -> new MacSnapshotVerifier(new byte[0]));
        var verifier = new MacSnapshotVerifier(key);
        var twin = new MacSnapshotVerifier(key.clone());
        assertEquals(verifier, twin);
        assertEquals(verifier.hashCode(), twin.hashCode());
        assertNotEquals(verifier, new MacSnapshotVerifier("other-key".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(verifier, "other-key");

        var root = Files.createDirectory(dir.resolve("edge-html"));
        var yaml = dir.resolve("edge.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var base = loaded.document();
        var emptySig = assertThrows(ConfigException.class, () -> verifier.verify(base.withSignature(new byte[0])));
        assertTrue(emptySig.getMessage().contains("signature"));
        var tampered = new ConfigDocument(base.server(), base.acme(), base.routing(), base.middleware(),
                base.access(), base.node(), base.schemaVersion(), base.bundleVersion(), base.configFile(),
                base.stateDir(), "ab", base.canonicalText(), new byte[] {1}, null);
        var hashMismatch = assertThrows(ConfigException.class, () -> verifier.verify(tampered));
        assertTrue(hashMismatch.getMessage().contains("canonical hash"));
        assertThrows(IllegalArgumentException.class, () -> new ConfigDocument(base.server(), base.acme(),
                base.routing(), base.middleware(), base.access(), base.node(), -1, 0, null, null, null, null, null,
                null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigDocument(base.server(), base.acme(),
                base.routing(), base.middleware(), base.access(), base.node(), 1, -1, null, null, "", null, null, null));
        var clearedHash = new ConfigDocument(base.server(), base.acme(), base.routing(), base.middleware(),
                base.access(), base.node(), 1, 0, null, base.stateDir(), null, null, null, null);
        assertEquals("", clearedHash.canonicalHash());
        var signed = base.withSignature(new byte[] {9});
        assertArrayEquals(new byte[] {9}, signed.withNode(signed.node()).signature());
        assertEquals(LastGoodStore.defaultStateDir(), base.withStateDir(null).stateDir());
        var clearedFile = base.withConfigFile(null);
        assertNull(clearedFile.configFile());
        assertSame(clearedFile, clearedFile.withConfigFile(null));
        assertEquals(yaml, clearedFile.withConfigFile(yaml).configFile());
        assertEquals(Path.of("sjws.yaml.last-good"), LastGoodStore.beside(Path.of("sjws.yaml")));

        var data = ConfigDocument.of(base.server(), base.acme(), base.routing(), base.middleware(), base.access(),
                new NodeSettings(NodeSettings.Mode.DATA, Map.of("site", "edge")),
                1, 4, yaml).withStateDir(dir.resolve("edge-state"));
        var path = dir.resolve("edge.json");
        LastGoodStore.write(path, data);
        assertEquals(data.canonicalText(), LastGoodStore.dataPlaneYaml(path));
        var special = data.withServer(data.server().toBuilder().root(dir.resolve("a\"b\\c\t\r\u0001")).build());
        var specialPath = dir.resolve("special.json");
        LastGoodStore.write(specialPath, special);
        var specialJson = Files.readString(specialPath);
        assertTrue(specialJson.contains("\\u0001"), specialJson);
        assertTrue(specialJson.contains("\\t"), specialJson);
        assertTrue(specialJson.contains("\\r"), specialJson);
        var rendered = new ConfigDocument(data.server(), data.acme(), data.routing(), data.middleware(),
                data.access(), data.node(), data.schemaVersion(), data.bundleVersion(), data.configFile(),
                data.stateDir(), data.canonicalHash(), null, null, null);
        var nullText = dir.resolve("null-text.json");
        LastGoodStore.write(nullText, rendered);
        assertEquals(data.canonicalText(), LastGoodStore.dataPlaneYaml(nullText));
        assertNull(LastGoodStore.signature(nullText));

        var blank = dir.resolve("blank.json");
        Files.writeString(blank, " \n");
        assertNull(LastGoodStore.dataPlaneYaml(blank));
        assertNull(LastGoodStore.signature(blank));
        assertFalse(LastGoodStore.dataPlane(blank));
        Files.writeString(dir.resolve("stand.yaml"), "node:\n  mode: standalone\n");
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("stand.yaml")));
        Files.writeString(dir.resolve("nonode.yaml"), "server:\n  port: 1\n");
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("nonode.yaml")));
        Files.writeString(dir.resolve("spaced.yaml"), "node:\n  mode: \" data \"\n");
        assertNotNull(LastGoodStore.dataPlaneYaml(dir.resolve("spaced.yaml")));
        Files.writeString(dir.resolve("num.yaml"), "node:\n  mode: 1\n");
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("num.yaml")));
        var brokenHash = Files.readString(path).replace(data.canonicalHash(), "00");
        Files.writeString(dir.resolve("bad-hash.json"), brokenHash);
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("bad-hash.json")));
        Files.writeString(dir.resolve("not-object.json"), "[1]");
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("not-object.json")));
        assertNull(LastGoodStore.signature(dir.resolve("not-object.json")));
        Files.writeString(dir.resolve("no-colon.json"), "{\"schema\" 1}");
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("no-colon.json")));
        Files.writeString(dir.resolve("bad-unicode.json"), "{\"canonical\":\"\\u12\"}");
        assertNull(LastGoodStore.dataPlaneYaml(dir.resolve("bad-unicode.json")));
        var simple = "node:\n  mode: data\n";
        var simpleHash = ConfigCodec.sha256(simple);
        Files.writeString(dir.resolve("unicode.json"),
                "{\"schema\":1,\"version\":1,\"canonicalHash\":\"" + simpleHash
                        + "\",\"signature\":\"\",\"canonical\":\"node:\\n  mode: \\u0064ata\\n\"}\n");
        assertEquals(simple, LastGoodStore.dataPlaneYaml(dir.resolve("unicode.json")));
        var raw = Files.readString(path);
        var extra = "{\"extra\":1,\"note\":\"z\",\"neg\":-2," + raw.substring(1);
        Files.writeString(dir.resolve("extra.json"), extra);
        assertEquals(data.canonicalText(), LastGoodStore.dataPlaneYaml(dir.resolve("extra.json")));

        var shifted = base.server().toBuilder().tlsKeyPassword("pw").build();
        var hashed = ConfigDocument.of(shifted, base.acme(), base.routing(), base.middleware(), base.access(),
                base.node(), base.schemaVersion(), 2, base.configFile());
        var bare = new ConfigDocument(shifted, base.acme(), base.routing(), base.middleware(), base.access(),
                base.node(), hashed.schemaVersion(), hashed.bundleVersion(), hashed.configFile(), hashed.stateDir(),
                hashed.canonicalHash(), null, new byte[] {7, 7}, null);
        var board = new ChallengeBoard();
        var current = ConfigSnapshot.compile(base, board, 1);
        var reloader = new ConfigReloader(loaded);
        var copied = reloader.apply(current, bare, false, board, true);
        assertTrue(copied.applied(), copied.errors().toString());
        assertArrayEquals(new byte[] {7, 7}, copied.snapshot().document().signature());
        var empty = new ConfigDocument(base.server(), base.acme(), base.routing(), base.middleware(), base.access(),
                base.node(), base.schemaVersion(), base.bundleVersion(), base.configFile(), base.stateDir(), "",
                base.canonicalText(), null, null);
        assertTrue(reloader.apply(current, empty, false, board, false).applied());
    }

    private String error(String yaml) throws Exception {
        var file = dir.resolve("bad-" + sha256(yaml.getBytes(StandardCharsets.UTF_8)).substring(0, 8) + ".yaml");
        Files.writeString(file, yaml);
        var loaded = ConfigLoader.load(new String[] {"--config=" + file}, Map.of());
        assertFalse(loaded.ok(), yaml);
        return loaded.errors().get(0);
    }

    private static ConfigDiff.Change section(List<ConfigDiff.Change> changes, String name) {
        return changes.stream().filter(c -> c.section().equals(name)).findFirst().orElseThrow();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
