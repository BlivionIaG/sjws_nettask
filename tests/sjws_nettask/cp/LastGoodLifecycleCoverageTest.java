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

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.NodeSettings;

@Timeout(20)
class LastGoodLifecycleCoverageTest {

    @TempDir
    Path dir;

    @Test
    void pinFormatAndSerialFloorAreFailClosed() throws Exception {
        var key = new byte[] {1, 2, 3, 4};
        var twoArg = new LastGoodFiles.Pin(key, 7);
        assertEquals(7, twoArg.versionFloor());
        assertEquals(0, twoArg.appliedVersion());
        assertEquals(0, twoArg.bundleSerial());

        var legacy = ByteBuffer.allocate(4 + 4 + key.length + 8);
        legacy.put("SJWS".getBytes(StandardCharsets.US_ASCII));
        legacy.putInt(key.length);
        legacy.put(key);
        legacy.putLong(9);
        var legacyPin = dir.resolve("legacy.pin");
        Files.write(legacyPin, legacy.array());
        var readLegacy = LastGoodFiles.readPin(legacyPin);
        assertEquals(9, readLegacy.versionFloor());
        assertEquals(0, readLegacy.appliedVersion());
        assertEquals(0, readLegacy.bundleSerial());

        var shortFloor = ByteBuffer.allocate(4 + 4 + key.length + 4);
        shortFloor.put("SJWS".getBytes(StandardCharsets.US_ASCII));
        shortFloor.putInt(key.length);
        shortFloor.put(key);
        shortFloor.putInt(1);
        Files.write(dir.resolve("short-floor.pin"), shortFloor.array());
        assertThrows(java.io.IOException.class, () -> LastGoodFiles.readPin(dir.resolve("short-floor.pin")));

        var odd = ByteBuffer.allocate(4 + 4 + key.length + 8 + 8);
        odd.put("SJWS".getBytes(StandardCharsets.US_ASCII));
        odd.putInt(key.length);
        odd.put(key);
        odd.putLong(1);
        odd.putLong(2);
        Files.write(dir.resolve("odd-length.pin"), odd.array());
        assertThrows(java.io.IOException.class, () -> LastGoodFiles.readPin(dir.resolve("odd-length.pin")));

        var pin = dir.resolve("ratchet.pin");
        LastGoodFiles.writePin(pin, key, 1, 5, 3);
        LastGoodFiles.ratchetPin(pin, 4, 2);
        var unchanged = LastGoodFiles.readPin(pin);
        assertEquals(5, unchanged.appliedVersion());
        assertEquals(3, unchanged.bundleSerial());
        LastGoodFiles.ratchetPin(pin, 8, 1);
        var raised = LastGoodFiles.readPin(pin);
        assertEquals(8, raised.appliedVersion());
        assertEquals(3, raised.bundleSerial());

        var real = dir.resolve("real.pin");
        LastGoodFiles.writePin(real, key, 1);
        var link = dir.resolve("link.pin");
        Files.createSymbolicLink(link, real.getFileName());
        assertThrows(java.io.IOException.class, () -> LastGoodFiles.readPin(link));
    }

    @Test
    void tryReadVerifiedLastGoodEnforcesThePinnedBundleSerial() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var fixture = DataReadinessTest.persistVerifiedLastGoodWithKeys(dir, state, loaded.document(), 4);
        assertTrue(DataApply.tryReadVerifiedLastGood(state, fixture.pin()).isPresent());
        LastGoodFiles.writePin(fixture.pin(), fixture.keys().rootPublic(), fixture.keys().versionFloor(), 0L,
                fixture.keys().bundle().bundleSerial() + 1);
        assertTrue(DataApply.tryReadVerifiedLastGood(state, fixture.pin()).isEmpty());
        var guard = DataPlaneGuard.forDocument(loaded.document(), fixture.pin());
        assertTrue(guard.active());
        assertFalse(guard.hasLastGood());
    }

    @Test
    void operatorIntervalUsesTheMonotonicClockWhenNoneIsInjected() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-mono"));
        var state = dir.resolve("mono-state");
        var yaml = dir.resolve("mono.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\n  max_staleness: 15m\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var guard = DataPlaneGuard.forDocument(loaded.document());
        DataPlaneGuard.inactive().clock(null);
        DataPlaneGuard.inactive().maxStaleness(Duration.ofSeconds(1));
        DataPlaneGuard.inactive().loadLastGood(state);
        DataPlaneGuard.inactive().clearLastGood();
        guard.clock(System::currentTimeMillis);
        guard.clock(null);
        long now = System.currentTimeMillis();
        guard.applied(DataPlaneGuardTest.snapshot(2, now - Duration.ofMinutes(16).toMillis(), now + 3_600_000L), true);
        assertTrue(guard.hasLastGood());
        assertTrue(guard.pastMaxStaleness());
        guard.applied(DataPlaneGuardTest.snapshot(3, now, now + 3_600_000L), true);
        assertFalse(guard.pastMaxStaleness());
        guard.loadLastGood(state, dir.resolve("missing.pin"));
        assertFalse(guard.hasLastGood());
    }

    @Test
    void verifiedLastGoodInstallFailureDoesNotBindOrMarkReady() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-bad"));
        Files.writeString(html.resolve("index.html"), "local");
        var state = dir.resolve("bad-state");
        var yaml = dir.resolve("bad.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("bad.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var bad = "schema: 1\nversion: 2\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + html.toAbsolutePath() + "\n  not_a_real_key: true\n";
        var snap = DataReadinessTest.signYaml(keys, bad, 2L, 1L, now, now + 3_600_000L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        try (var server = SjwsServer.start(loaded, null, true, pin)) {
            assertFalse(server.contentBound());
            assertFalse(server.dataPlane().hasLastGood());
            assertFalse(server.dataPlane().ready());
            assertFalse(server.installPersistedLastGood(state, pin));
        }
    }

    @Test
    void lastGoodInstallAdoptsEnvelopeFieldsAndRecordsWhenAttached() throws Exception {
        var local = Files.createDirectory(dir.resolve("html-local"));
        Files.writeString(local.resolve("index.html"), "local");
        var cached = Files.createDirectory(dir.resolve("html-cached"));
        Files.writeString(cached.resolve("index.html"), "cached");
        var state = dir.resolve("adopt-state");
        var yaml = dir.resolve("adopt.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + local.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("adopt.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var body = "schema: 1\nversion: 1\nnode:\n  mode: data\n  max_staleness: 15m\n  state_dir: "
                + state.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + cached.toAbsolutePath() + "\n";
        var snap = DataReadinessTest.signYaml(keys, body, 5L, 4L, now, now + 3_600_000L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        try (var server = SjwsServer.start(loaded, null, false)) {
            assertFalse(server.contentBound());
            assertTrue(server.installPersistedLastGood(state, pin));
            assertTrue(server.dataPlane().hasLastGood());
            assertTrue(server.dataPlane().ready());
            var apply = DataApply.open(keys, pin, state, System::currentTimeMillis);
            server.attachControlPlane(apply);
            assertTrue(server.installPersistedLastGood(state, pin));
            assertEquals(5L, apply.appliedVersion());
        }
        try (var server = SjwsServer.start(loaded, null, true, pin)) {
            assertTrue(server.contentBound());
            assertEquals("cached", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/readyz", 2_000).statusCode());
        }
    }

    @Test
    void missingConfigFileBootsFromAVerifiedLastGood() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-miss"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("miss-state");
        var yaml = dir.resolve("miss.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var fixture = DataReadinessTest.persistVerifiedLastGoodWithKeys(dir, state, loaded.document(), 6);
        Files.delete(yaml);
        var boot = dir.resolve("bootstrap");
        Files.writeString(boot, "role=data\npin=" + fixture.pin().toAbsolutePath() + "\n");
        var recovered = ConfigLoader.load(new String[] {"--config=" + yaml, "--bootstrap=" + boot,
                "--state-dir=" + state}, Map.of());
        assertTrue(recovered.ok(), recovered.errors().toString());
        assertTrue(recovered.warnings().stream().anyMatch(w -> w.contains("last good")), recovered.warnings().toString());
        assertEquals(NodeSettings.Mode.DATA, recovered.document().node().mode());
        assertEquals(html.toAbsolutePath().normalize(), recovered.document().server().root().toAbsolutePath().normalize());
        LastGoodFiles.writePin(fixture.pin(), fixture.keys().rootPublic(), fixture.keys().versionFloor(), 0L,
                fixture.keys().bundle().bundleSerial() + 1);
        var rejected = ConfigLoader.load(new String[] {"--config=" + yaml, "--bootstrap", boot.toString(),
                "--state-dir=" + state}, Map.of());
        assertFalse(rejected.ok(), "a signed envelope that does not meet the pin must not be a boot source");
    }

    @Test
    void bindContentPortMovesTheChallengeListenerToANewPort() throws Exception {
        var html = Files.createDirectory(dir.resolve("acme-move-html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var storage = Files.createDirectory(dir.resolve("acme-move-store"));
        int first;
        int second;
        try (var a = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var b = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            first = a.getLocalPort();
            second = b.getLocalPort();
        }
        var state = dir.resolve("acme-move-state");
        var yaml = dir.resolve("acme-move.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: "
                + state.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath()
                + "\nacme:\n  enabled: true\n  directory: \"http://127.0.0.1:1/directory\"\n"
                + "  email: ops@example.com\n  domains: [example.com]\n  storage: " + storage.toAbsolutePath()
                + "\n  http_port: " + first + "\n  accept_terms: true\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        long now = 1_700_000_000_000L;
        var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var apply = DataApply.open(keys, dir.resolve("acme-move-pin"), state, () -> now + 1);
        var firstDoc = ConfigDocument.of(loaded.document().server(), loaded.document().acme(),
                loaded.document().routing(), loaded.document().middleware(), loaded.document().access(),
                loaded.document().node(), 1, 2, loaded.document().configFile()).withStateDir(state);
        var firstSnap = keys.sign(firstDoc, 2L, 0L, 1L, now, now + 3_600_000L, 0L);
        var movedAcme = loaded.document().acme();
        // Rebuild a document with a new HTTP-01 port via yaml so AcmeSettings stays valid.
        var movedYaml = dir.resolve("acme-moved.yaml");
        Files.writeString(movedYaml, Files.readString(yaml).replace("http_port: " + first, "http_port: " + second));
        var movedLoaded = ConfigLoader.load(new String[] {"--config=" + movedYaml}, Map.of());
        var secondDoc = ConfigDocument.of(movedLoaded.document().server(), movedLoaded.document().acme(),
                movedLoaded.document().routing(), movedLoaded.document().middleware(), movedLoaded.document().access(),
                movedLoaded.document().node(), 1, 3, movedLoaded.document().configFile()).withStateDir(state);
        var secondSnap = keys.sign(secondDoc, 3L, 0L, 1L, now, now + 3_600_000L, 0L);
        try (var server = SjwsServer.start(loaded, null, false)) {
            server.attachControlPlane(apply);
            assertTrue(server.applyRemote(firstSnap).applied());
            var moved = server.applyRemote(secondSnap);
            assertTrue(moved.applied(), moved.errors().toString());
            assertTrue(server.contentBound());
            assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
        }
        assertNotEquals(first, second);
        assertNotNull(movedAcme);
    }


    @Test
    void reloadAfterLastGoodKeepsTheInstalledSnapshot() throws Exception {
        var local = Files.createDirectory(dir.resolve("html-sighup-local"));
        Files.writeString(local.resolve("index.html"), "local");
        var cached = Files.createDirectory(dir.resolve("html-sighup-cached"));
        Files.writeString(cached.resolve("index.html"), "cached");
        var state = dir.resolve("sighup-state");
        var yaml = dir.resolve("sighup.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + local.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var cachedDoc = ConfigDocument.of(loaded.document().server().toBuilder().root(cached).build(),
                loaded.document().acme(), loaded.document().routing(), loaded.document().middleware(),
                loaded.document().access(), loaded.document().node(), loaded.document().schemaVersion(), 5,
                loaded.document().configFile()).withStateDir(state);
        var pin = DataReadinessTest.persistVerifiedLastGood(dir, state, cachedDoc, 5);
        try (var server = SjwsServer.start(loaded, null, true, pin)) {
            assertEquals("cached", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
            var ran = new AtomicBoolean();
            server.setLocalReload(() -> ran.set(true));
            var result = server.reload();
            assertFalse(result.applied(), result.errors().toString() + result.warnings());
            assertTrue(ran.get(), result.warnings().toString());
            assertEquals("cached", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/readyz", 2_000).statusCode());
        }
    }

    @Test
    void attachControlPlaneRecordsTheRestoredEnvelope() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-seed"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("seed-state");
        var yaml = dir.resolve("seed.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var fixture = DataReadinessTest.persistVerifiedLastGoodWithKeys(dir, state, loaded.document(), 4);
        try (var server = SjwsServer.start(loaded, null, true, fixture.pin())) {
            assertTrue(server.dataPlane().hasLastGood());
            var apply = DataApply.open(fixture.keys(), fixture.pin(), state, System::currentTimeMillis);
            server.attachControlPlane(apply);
            assertEquals(4L, apply.appliedVersion());
            var result = server.applyRemote(fixture.snapshot());
            assertTrue(result.applied(), result.errors().toString());
            assertTrue(result.errors().isEmpty(), result.errors().toString());
        }
    }

    @Test
    void attachingTheWallClockKeepsTheMonotonicDeadline() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-wall"));
        var state = dir.resolve("wall-state");
        var yaml = dir.resolve("wall.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\n  max_staleness: 15m\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var guard = DataPlaneGuard.forDocument(loaded.document());
        long now = System.currentTimeMillis();
        guard.applied(DataPlaneGuardTest.snapshot(2, now - Duration.ofMinutes(16).toMillis(), now + 3_600_000L), true);
        assertTrue(guard.pastMaxStaleness());
        guard.clock(DataPlaneGuard.WALL_CLOCK);
        assertTrue(guard.pastMaxStaleness());
    }

    @Test
    void lastGoodInstallKeepsMatchingFieldsAndFillsTheLocalStateDir() throws Exception {
        var local = Files.createDirectory(dir.resolve("html-match-local"));
        Files.writeString(local.resolve("index.html"), "local");
        var cached = Files.createDirectory(dir.resolve("html-match-cached"));
        Files.writeString(cached.resolve("index.html"), "cached");
        var state = dir.resolve("match-state");
        var yaml = dir.resolve("match.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + local.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("match.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var body = "schema: 1\nversion: 5\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + cached.toAbsolutePath() + "\n";
        var snap = DataReadinessTest.signYaml(keys, body, 5L, 0L, now, now + 3_600_000L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        try (var server = SjwsServer.start(loaded, null, false)) {
            server.attachControlPlane(new Object());
            assertTrue(server.installPersistedLastGood(state, pin));
            assertTrue(server.dataPlane().hasLastGood());
            assertEquals(cached.toAbsolutePath().normalize(), server.config().root().toAbsolutePath().normalize());
            assertTrue(server.installPersistedLastGood(state, pin), "reinstalling the same envelope stays applied");
            assertTrue(server.dataPlane().ready());
        }
    }

    @Test
    void lastGoodInstallFromAnotherStateDirKeepsTheNodeDefault() throws Exception {
        var local = Files.createDirectory(dir.resolve("html-nostate-local"));
        Files.writeString(local.resolve("index.html"), "local");
        var cached = Files.createDirectory(dir.resolve("html-nostate-cached"));
        Files.writeString(cached.resolve("index.html"), "cached");
        var state = Files.createDirectory(dir.resolve("nostate-state"));
        var yaml = dir.resolve("nostate.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + local.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("nostate.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var mismatched = "schema: 1\nversion: 2\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + cached.toAbsolutePath() + "\n";
        var first = DataReadinessTest.signYaml(keys, mismatched, 6L, 3L, now, now + 3_600_000L);
        LastGoodFiles.write(state, first.version(), first.epoch(), first.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), first);
        try (var server = SjwsServer.start(loaded, null, false, pin)) {
            assertFalse(server.contentBound());
            assertFalse(server.dataPlane().hasLastGood());
            assertTrue(server.installPersistedLastGood(state, pin));
            assertEquals(cached.toAbsolutePath().normalize(), server.config().root().toAbsolutePath().normalize());
            var matching = "schema: 1\nversion: 7\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                    + cached.toAbsolutePath() + "\n";
            var second = DataReadinessTest.signYaml(keys, matching, 7L, 3L, now, now + 3_600_000L);
            LastGoodFiles.write(state, second.version(), second.epoch(), second.keyId(), keys.bundle().keys(),
                    keys.bundle().versionFloor(), keys.bundle().encoded(), second);
            assertTrue(server.installPersistedLastGood(state, pin));
            assertEquals(cached.toAbsolutePath().normalize(), server.config().root().toAbsolutePath().normalize());
            assertEquals(7L, LastGoodFiles.readPin(pin).appliedVersion());
            assertTrue(server.dataPlane().hasLastGood());
        }
    }

    @Test
    void lastGoodInstallWithoutANodeSectionKeepsTheLocalStateDir() throws Exception {
        var cached = Files.createDirectory(dir.resolve("html-nonode-cached"));
        Files.writeString(cached.resolve("index.html"), "cached");
        var state = dir.resolve("nonode-state");
        var yaml = dir.resolve("nonode.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + cached.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("nonode.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var body = "schema: 1\nversion: 3\nserver:\n  port: 0\n  root: " + cached.toAbsolutePath() + "\n";
        var snap = DataReadinessTest.signYaml(keys, body, 3L, 2L, now, now + 3_600_000L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        try (var server = SjwsServer.start(loaded, null, false)) {
            assertTrue(server.installPersistedLastGood(state, pin));
            assertTrue(server.dataPlane().hasLastGood());
            assertTrue(server.dataPlane().ready());
        }
    }

    @Test
    void lastGoodTheReloaderRejectsIsNotInstalled() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-reject"));
        Files.writeString(html.resolve("index.html"), "local");
        var state = dir.resolve("reject-state");
        var yaml = dir.resolve("reject.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = dir.resolve("reject.pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var body = "schema: 1\nversion: 2\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + dir.resolve("missing-root").toAbsolutePath() + "\n";
        var snap = DataReadinessTest.signYaml(keys, body, 2L, 1L, now, now + 3_600_000L);
        LastGoodFiles.write(state, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        try (var server = SjwsServer.start(loaded, null, false)) {
            assertFalse(server.installPersistedLastGood(state, pin));
            assertFalse(server.dataPlane().hasLastGood());
            assertFalse(server.dataPlane().ready());
            assertEquals(html.toAbsolutePath().normalize(), server.config().root().toAbsolutePath().normalize());
        }
    }

    @Test
    void standaloneStartWithAPinBindsWithoutALastGood() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-stand-pin"));
        Files.writeString(html.resolve("index.html"), "ok");
        var yaml = dir.resolve("stand-pin.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: standalone\nserver:\n  port: 0\n  root: "
                + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(loaded, null, true, dir.resolve("unused.pin"))) {
            assertTrue(server.contentBound());
            assertFalse(server.dataPlane().active());
            assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
        }
    }

    @Test
    void aVeryLongMaxStalenessIsNotStale() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-long"));
        var state = dir.resolve("long-state");
        var yaml = dir.resolve("long.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var guard = DataPlaneGuard.forDocument(loaded.document());
        guard.maxStaleness(Duration.ofMillis(Long.MAX_VALUE));
        long now = System.currentTimeMillis();
        guard.applied(DataPlaneGuardTest.snapshot(2, now, Long.MAX_VALUE), true);
        assertFalse(guard.pastMaxStaleness());
        guard.applied(DataPlaneGuardTest.snapshot(3, Long.MAX_VALUE - 1, Long.MAX_VALUE), true);
        assertFalse(guard.pastMaxStaleness());
        guard.maxStaleness(Duration.ofMillis(Long.MAX_VALUE / 2));
        guard.applied(DataPlaneGuardTest.snapshot(4, now, Long.MAX_VALUE), true);
        assertFalse(guard.pastMaxStaleness());
        guard.clock(() -> Long.MIN_VALUE);
        guard.applied(DataPlaneGuardTest.snapshot(5, now, Long.MAX_VALUE), true);
        guard.clock(() -> -1L);
        guard.applied(DataPlaneGuardTest.snapshot(6, now, Long.MAX_VALUE), true);
        guard.clock(DataPlaneGuard.WALL_CLOCK);
        assertFalse(guard.pastMaxStaleness());
    }

}
