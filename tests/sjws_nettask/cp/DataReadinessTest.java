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

import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.security.Signature;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.ha.HaSettings;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.NodeSettings;

@Timeout(20)
class DataReadinessTest {

    @TempDir
    Path dir;

    @Test
    void contentPortBindsAfterLastGoodAndReadyzTracksStaleness() throws Exception {
        var clock = new AtomicLong(1_000);
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "hello");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(loaded, null, false)) {
            assertFalse(server.contentBound());
            assertEquals(-1, server.tcpPort());
            assertFalse(server.dataPlane().ready());
            server.dataPlane().clock(clock::get);
            assertTrue(server.bindContentPort());
            assertTrue(server.contentBound());
            int port = server.tcpPort();
            assertTrue(port > 0);
            var notReady = HttpTestSupport.get(port, "/readyz", 2_000);
            assertEquals(503, notReady.statusCode());
            assertEquals("unavailable\n", notReady.bodyText());
            var page = HttpTestSupport.get(port, "/index.html", 2_000);
            assertEquals(200, page.statusCode());
            assertEquals("hello", page.bodyText());

            server.dataPlane().applied(DataPlaneGuardTest.snapshot(4, 1_000, 2_000), true);
            var ready = HttpTestSupport.get(port, "/readyz", 2_000);
            assertEquals(200, ready.statusCode());
            assertEquals("ok\n", ready.bodyText());
            clock.set(2_001);
            var stale = HttpTestSupport.get(port, "/readyz", 2_000);
            assertEquals(503, stale.statusCode());
            var still = HttpTestSupport.get(port, "/index.html", 2_000);
            assertEquals(200, still.statusCode(), "past max-staleness keeps serving");
            assertFalse(server.dataPlane().readiness().drain());
        }
    }

    @Test
    void signedLastGoodOnDiskBindsAndIsReadyUntilExpiry() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "cached");
        var state = dir.resolve("state");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var pin = persistVerifiedLastGood(dir, state, loaded.document(), 5);
        try (var server = SjwsServer.start(loaded, null, true, pin)) {
            assertTrue(server.contentBound());
            assertTrue(server.dataPlane().hasLastGood());
            assertTrue(server.dataPlane().ready());
            var ready = HttpTestSupport.get(server.tcpPort(), "/readyz", 2_000);
            assertEquals(200, ready.statusCode());
            assertEquals("cached", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
        }
    }

    @Test
    void forgedLastGoodOnDiskDoesNotSatisfyReadiness() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-forged"));
        Files.writeString(html.resolve("index.html"), "cached");
        var state = dir.resolve("state-forged");
        Files.createDirectories(LastGoodFiles.configDir(state));
        var snap = DataPlaneGuardTest.snapshot(5, System.currentTimeMillis(), System.currentTimeMillis() + 60_000);
        Files.writeString(LastGoodFiles.configDir(state).resolve("last-good.json"), LastGoodFiles.snapshotJson(snap));
        var yaml = dir.resolve("forged.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(DataApply.tryReadLastGood(state).isPresent());
        assertTrue(DataApply.tryReadVerifiedLastGood(state, dir.resolve("missing.pin")).isEmpty());
        try (var server = SjwsServer.start(loaded, null, true)) {
            assertTrue(server.contentBound());
            assertFalse(server.dataPlane().hasLastGood());
            assertFalse(server.dataPlane().ready());
            assertEquals(503, HttpTestSupport.get(server.tcpPort(), "/readyz", 2_000).statusCode());
        }
    }

    @Test
    void awaitTerminationSurvivesLateBind() throws Exception {
        var html = Files.createDirectory(dir.resolve("html-term"));
        Files.writeString(html.resolve("index.html"), "x");
        var yaml = dir.resolve("term.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + dir.resolve("term-state").toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var finished = new CountDownLatch(1);
        try (var server = SjwsServer.start(loaded, null, false)) {
            Thread waiter = new Thread(() -> {
                try {
                    server.awaitTermination();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }, "term-wait");
            waiter.start();
            assertTrue(server.bindContentPort());
            assertFalse(finished.await(300, TimeUnit.MILLISECONDS),
                    "late bind must not release awaitTermination");
            server.close();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    void persistLastGoodDoesNotOverwriteASignedSnapshot() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "x");
        var state = dir.resolve("state");
        Files.createDirectories(LastGoodFiles.configDir(state));
        var snap = DataPlaneGuardTest.snapshot(9, 10, 11);
        var path = LastGoodFiles.configDir(state).resolve("last-good.json");
        Files.writeString(path, LastGoodFiles.snapshotJson(snap));
        var before = Files.readString(path);
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + state.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(loaded, null, false)) {
            assertEquals(before, Files.readString(path));
            assertEquals(9, DataApply.tryReadLastGood(state).orElseThrow().version());
            assertNotNull(server);
        }
    }

    @Test
    void lateBindIsIdempotentAndClosedServerRefuses() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "x");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + dir.resolve("state").toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var server = SjwsServer.start(loaded, null, false);
        assertTrue(server.bindContentPort());
        int port = server.tcpPort();
        assertTrue(server.bindContentPort());
        assertEquals(port, server.tcpPort());
        server.close();
        assertFalse(server.contentBound());
        assertFalse(server.bindContentPort());
    }

    @Test
    void bindContentPortRollsBackWhenThePortIsBusy() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "x");
        try (var held = ServerSocketChannel.open()) {
            held.bind(new InetSocketAddress("127.0.0.1", 0));
            int busy = ((InetSocketAddress) held.getLocalAddress()).getPort();
            var yaml = dir.resolve("busy.yaml");
            Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + dir.resolve("busy-state").toAbsolutePath()
                    + "\nserver:\n  port: " + busy + "\n  root: " + html.toAbsolutePath() + "\n");
            var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
            try (var server = SjwsServer.start(loaded, null, false)) {
                assertFalse(server.contentBound());
                assertThrows(IOException.class, server::bindContentPort);
                assertFalse(server.contentBound());
                assertEquals(-1, server.tcpPort());
            }
        }
    }

    @Test
    void haReadyzFollowsDataPlaneLastGood() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "ha");
        var yaml = dir.resolve("ha.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + dir.resolve("ha-state").toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var ha = HaSettings.builder().drainDelay(java.time.Duration.ZERO).drainTimeout(java.time.Duration.ofMillis(200))
                .build();
        try (var server = SjwsServer.start(loaded, ha)) {
            assertNotNull(server.ha());
            assertTrue(server.contentBound());
            assertFalse(server.dataPlane().ready());
            assertFalse(server.ha().health().readyNow());
            var port = server.tcpPort();
            assertEquals(503, HttpTestSupport.get(port, "/readyz", 2_000).statusCode());
            assertEquals("ha", HttpTestSupport.get(port, "/index.html", 2_000).bodyText());
            server.dataPlane().applied(DataPlaneGuardTest.snapshot(8, System.currentTimeMillis(),
                    System.currentTimeMillis() + 60_000), true);
            assertTrue(server.dataPlane().ready());
            assertTrue(server.ha().health().readyNow());
            assertEquals(200, HttpTestSupport.get(port, "/readyz", 2_000).statusCode());
        }
    }

    @Test
    void readyzWrapsAnExistingFilterChain() throws Exception {
        var html = Files.createDirectory(dir.resolve("html"));
        Files.writeString(html.resolve("index.html"), "f");
        var yaml = dir.resolve("filter.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + dir.resolve("filter-state").toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        var filters = RequestFilters.create().add((request, next) -> next.proceed(request));
        try (var server = SjwsServer.start(loaded, filters)) {
            assertTrue(server.contentBound());
            int port = server.tcpPort();
            assertEquals(503, HttpTestSupport.get(port, "/readyz", 2_000).statusCode());
            assertEquals("f", HttpTestSupport.get(port, "/index.html", 2_000).bodyText());
        }
    }

    @Test
    void applyRemoteAdoptsEnvelopeVersionAndPushedMaxStaleness() throws Exception {
        var html = Files.createDirectory(dir.resolve("push-html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var state = dir.resolve("push-state");
        var yaml = dir.resolve("push.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: "
                + state.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        long now = 1_700_000_000_000L;
        var clock = new AtomicLong(now + 1);
        var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var apply = DataApply.open(keys, dir.resolve("push-pin"), state, clock::get);
        var node = new NodeSettings(NodeSettings.Mode.DATA, Map.of(), Duration.ofMinutes(15));
        var document = ConfigDocument.of(loaded.document().server(), loaded.document().acme(),
                loaded.document().routing(), loaded.document().middleware(), loaded.document().access(),
                node, 1, 99, loaded.document().configFile()).withStateDir(state);
        // Envelope version 2 and epoch 7 differ from the yaml bundle version and the reloader epoch.
        var snapshot = keys.sign(document, 2L, 7L, 1L, now, now + 3_600_000L, 0L);
        try (var server = SjwsServer.start(loaded, null, false)) {
            assertFalse(server.contentBound());
            server.attachControlPlane(apply);
            server.dataPlane().clock(clock::get);
            var result = server.applyRemote(snapshot);
            assertTrue(result.applied(), result.errors().toString());
            assertEquals(2L, result.snapshot().document().bundleVersion());
            assertEquals(7L, result.snapshot().epoch());
            assertTrue(server.contentBound());
            assertTrue(server.dataPlane().hasLastGood());
            assertTrue(server.dataPlane().ready());
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/readyz", 2_000).statusCode());
            clock.set(now + Duration.ofMinutes(16).toMillis());
            assertTrue(server.dataPlane().pastMaxStaleness());
            assertFalse(server.dataPlane().ready());
            assertEquals(503, HttpTestSupport.get(server.tcpPort(), "/readyz", 2_000).statusCode());
            assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
        }
    }

    @Test
    void applyRemoteWithAMatchingEpochStillApplies() throws Exception {
        var html = Files.createDirectory(dir.resolve("epoch-html"));
        Files.writeString(html.resolve("index.html"), "e");
        var state = dir.resolve("epoch-state");
        var yaml = dir.resolve("epoch.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: "
                + state.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        long now = 1_700_000_000_000L;
        var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var apply = DataApply.open(keys, dir.resolve("epoch-pin"), state, () -> now + 1);
        var document = ConfigDocument.of(loaded.document().server(), loaded.document().acme(),
                loaded.document().routing(), loaded.document().middleware(), loaded.document().access(),
                loaded.document().node(), 1, 2, loaded.document().configFile()).withStateDir(state);
        var snapshot = keys.sign(document, 2L, 0L, 1L, now, now + 3_600_000L, 0L);
        try (var server = SjwsServer.start(loaded, null, false)) {
            server.attachControlPlane(apply);
            var result = server.applyRemote(snapshot);
            assertTrue(result.applied(), result.errors().toString());
            assertEquals(0L, result.snapshot().epoch());
            assertTrue(server.contentBound());
            assertTrue(server.dataPlane().hasLastGood());
        }
    }

    @Test
    void applyRemoteKeepsLastGoodWhenContentBindFails() throws Exception {
        var html = Files.createDirectory(dir.resolve("busy-apply-html"));
        Files.writeString(html.resolve("index.html"), "b");
        try (var held = ServerSocketChannel.open()) {
            held.bind(new InetSocketAddress("127.0.0.1", 0));
            int busy = ((InetSocketAddress) held.getLocalAddress()).getPort();
            var state = dir.resolve("busy-apply-state");
            var yaml = dir.resolve("busy-apply.yaml");
            Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: "
                    + state.toAbsolutePath() + "\nserver:\n  port: " + busy + "\n  root: "
                    + html.toAbsolutePath() + "\n");
            var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
            assertTrue(loaded.ok(), loaded.errors().toString());
            long now = 1_700_000_000_000L;
            var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
            var apply = DataApply.open(keys, dir.resolve("busy-apply-pin"), state, () -> now + 1);
            var document = ConfigDocument.of(loaded.document().server(), loaded.document().acme(),
                    loaded.document().routing(), loaded.document().middleware(), loaded.document().access(),
                    loaded.document().node(), 1, 2, loaded.document().configFile()).withStateDir(state);
            var snapshot = keys.sign(document, 2L, 0L, 1L, now, now + 3_600_000L, 0L);
            try (var server = SjwsServer.start(loaded, null, false)) {
                server.attachControlPlane(apply);
                var result = server.applyRemote(snapshot);
                assertTrue(result.applied(), result.errors().toString());
                assertFalse(server.contentBound());
                assertTrue(server.dataPlane().hasLastGood());
            }
        }
    }

    @Test
    void applyRemoteRejectsASnapshotWhoseYamlDoesNotLoad() throws Exception {
        var html = Files.createDirectory(dir.resolve("bad-html"));
        Files.writeString(html.resolve("index.html"), "x");
        var state = dir.resolve("bad-state");
        var yaml = dir.resolve("bad.yaml");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: "
                + state.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        long now = 1_700_000_000_000L;
        var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var apply = DataApply.open(keys, dir.resolve("bad-pin"), state, () -> now + 1);
        var bad = "schema: 1\nversion: 2\nnode:\n  mode: data\nserver:\n  port: 0\n  root: "
                + html.toAbsolutePath() + "\n  not_a_real_key: true\n";
        var snapshot = signYaml(keys, bad, 2L, 0L, now, now + 3_600_000L);
        try (var server = SjwsServer.start(loaded, null, false)) {
            server.attachControlPlane(apply);
            var result = server.applyRemote(snapshot);
            assertFalse(result.applied());
            assertFalse(result.errors().isEmpty());
            assertFalse(server.contentBound());
        }
    }

    static SignedSnapshot signYaml(KeyRing keys, String yaml, long version, long epoch, long issued, long expires)
            throws Exception {
        var hash = KeyRing.hash(yaml);
        var nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        var unsigned = new SignedSnapshot(1, version, epoch, 1L, 0L, SnapshotCrypto.KIND_FULL, "", hash, issued,
                expires, nonce, keys.online().keyId(), new byte[0], 0L, yaml);
        var field = KeyRing.class.getDeclaredField("onlinePrivate");
        field.setAccessible(true);
        var key = (java.security.PrivateKey) field.get(keys);
        var sign = Signature.getInstance("Ed25519");
        sign.initSign(key);
        sign.update(SnapshotCrypto.snapshotInput(unsigned));
        return unsigned.withSignature(sign.sign());
    }

    static Path persistVerifiedLastGood(Path pinDir, Path stateDir, ConfigDocument document, long version)
            throws Exception {
        var keys = KeyRing.generate("online-1", System.currentTimeMillis() + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var pin = pinDir.resolve("root-" + version + ".pin");
        LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
        long now = System.currentTimeMillis();
        var snap = keys.sign(document, version, 1L, 1L, now, now + 3_600_000L, 0L);
        LastGoodFiles.write(stateDir, snap.version(), snap.epoch(), snap.keyId(), keys.bundle().keys(),
                keys.bundle().versionFloor(), keys.bundle().encoded(), snap);
        return pin;
    }

}
