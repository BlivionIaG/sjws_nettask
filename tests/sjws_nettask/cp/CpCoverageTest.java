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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpsExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.cluster.CommittedEntry;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.config.AppliedConfig;
import sjws_nettask.config.ConfigDocument;
import sjws_nettask.controlplane.Rbac;
import sjws_nettask.controlplane.Role;

/** Branches the store, admin adapter, and signed-snapshot checks otherwise leave dark. */
class CpCoverageTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = 60_000L;

    @TempDir
    Path dir;

    @Test
    void adminRoutesAndStoreGuards() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        try (var store = CpStore.open(dir.resolve("cp"), keys, clock)) {
            assertFalse(store.ready(true));
            assertFalse(store.bindsContentPort());
            assertEquals(NodeLifecycle.Startup.DATA, CpStore.resolveRole(null, "data", "control"));
            assertFalse(store.dataPlane(false, true, true).ready());
            assertTrue(store.nodesJson().contains("follower"));
            assertEquals("{\"activated\":null}", store.configJson());
            assertEquals(503, store.propose("server:\n").status());
            assertEquals(503, store.activate(NOW, TTL).status());
            assertEquals(503, store.rollback(1, NOW, TTL).status());
            assertEquals(503, store.grant("urn:sjws:admin:root", Role.ADMIN).status());
            assertEquals(503, store.revoke("n1").status());
            assertEquals(503, store.rollout(List.of("edge")).status());
            assertEquals(503, store.advance().status());
            assertEquals(503, store.issueJoinToken("tok", new byte[16], NOW, TTL).status());
            assertEquals("token", store.presentJoin("edge-1", "tok", new byte[16], NOW).error(), "never issued");

            elect(store, clock);
            assertTrue(store.ready(true));
            assertFalse(store.ready(false));
            assertNotNull(store.binding());
            assertNotNull(store.keys());
            assertNotNull(store.rights());
            assertTrue(store.healthJson().contains("\"leaderReady\":true"));

            var admin = new CpAdmin(store, NOW, TTL);
            var cert = new Rbac.ClientCert("urn:sjws:admin:root");
            var yaml = "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n";
            assertEquals(404, admin.handle(cert, "GET", "/", null, "").status());
            assertEquals(200, admin.handle(cert, "GET", "/cp/v1/health/", null, "").status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/config", null, null).status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/activate", null, "").status());
            assertEquals(400, admin.handle(cert, "POST", "/cp/v1/config", null, "trust:\n  a: 1\n").status());
            var missing = admin.handle(cert, "POST", "/cp/v1/config", null,
                    "server:\n  port: 0\n  root: " + dir.resolve("missing") + "\n");
            assertEquals(400, missing.status(), missing.body());
            assertEquals(400, admin.handle(cert, "POST", "/cp/v1/config", null, yaml + """
                    messaging:
                      enabled: true
                      topics:
                        orders:
                          durability: none
                          replication: 1
                    """).status());
            assertEquals(400, admin.handle(cert, "POST", "/cp/v1/config", null, yaml + """
                    messaging:
                      enabled: true
                      assignment:
                        "orders/0": [n1]
                    """).status());
            assertEquals(409, admin.handle(cert, "POST", "/cp/v1/activate", null, "").status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/config", null, topic(yaml, false)).status());
            assertEquals(409, admin.handle(cert, "POST", "/cp/v1/config", null, topic(yaml, false)).status());
            assertEquals(200, admin.handle(cert, "POST", "/cp/v1/activate", null, "").status());
            assertEquals(400, admin.handle(cert, "POST", "/cp/v1/config", null, yaml).status());
            assertEquals(200, admin.handle(cert, "GET", "/cp/v1/config", null, "").status());
            assertEquals(200, admin.handle(cert, "GET", "/cp/v1/nodes", null, "").status());
            assertEquals(404, admin.handle(cert, "GET", "/cp/v1/config/diff", "from=9&to=9", "").status());
            var next = store.propose(topic(yaml, false) + "node:\n  labels:\n    site: edge\n");
            assertEquals(200, next.status(), next.body());
            assertEquals(200, store.activate(NOW, TTL).status());
            var diff = admin.handle(cert, "GET", "/cp/v1/config/diff", "from=1&to=2", "");
            assertEquals(200, diff.status(), diff.body());
            assertEquals(200, store.diff(1, 2, true).status());
            assertEquals(1L, CpAdmin.queryLong("from=1&to=2", "from"));
            assertEquals(-1L, CpAdmin.queryLong(null, "from"));
            assertEquals(-1L, CpAdmin.jsonLong(null, "toVersion"));
            assertEquals(-1L, CpAdmin.jsonLong("{\"toVersion\"}", "toVersion"));
            assertEquals(-1L, CpAdmin.jsonLong("{\"toVersion\":", "toVersion"));
            assertEquals(-1L, CpAdmin.jsonLong("{\"other\":1}", "toVersion"));
            assertEquals(12L, CpAdmin.jsonLong("{\"toVersion\": 12}", "toVersion"));
            assertEquals(-1L, CpAdmin.jsonLong("{\"toVersion\": x}", "toVersion"));
            assertNull(CpAdmin.jsonString(null, "san"));
            assertTrue(CpAdmin.selectors(null).isEmpty());
            assertTrue(CpAdmin.selectors("no brackets").isEmpty());
            assertEquals(List.of("edge"), CpAdmin.selectors("{\"selectors\":[\"edge\", plain]}"));
            assertThrows(IllegalArgumentException.class, () -> CpAdmin.parseRole("nope"));
            assertThrows(IllegalArgumentException.class, () -> admin.handle(cert, "POST", "/cp/v1/rbac", null,
                    "{\"san\":\"urn:sjws:admin:viewer\",\"role\":\"nope\"}"));
            assertEquals(400, admin.handle(cert, "POST", "/cp/v1/rbac", null, "{}").status());
            assertEquals(400, store.grant("not a subject", Role.VIEWER).status());
            assertEquals(200, store.grant("urn:one:root", Role.VIEWER).status());
            assertEquals(200, store.grant("urn:two:root", Role.VIEWER).status());
            assertEquals(200, store.grant("urn:" + "b".repeat(80), Role.VIEWER).status());
            assertEquals(200, store.grant("urn:___", Role.VIEWER).status());
            assertEquals(200, store.grant("urn:sjws:admin:root", Role.VIEWER).status());
            assertEquals(Role.ADMIN, store.roleOfSan("urn:sjws:admin:root"));
            assertNull(store.roleOfSan("not a subject"));
            assertEquals(404, admin.handle(cert, "POST", "/cp/v1/rollback", null, "{\"toVersion\": 99}").status());
            assertEquals(200, store.rollout(List.of()).status());
            assertEquals(200, store.advance().status());
            assertEquals(0, store.stage());
            assertEquals(200, store.revoke("cp-1").status());
            assertTrue(store.nodesJson().contains("\"revoked\":true"));

            for (int i = 0; i < 8; i++) {
                var body = topic(yaml, false) + "node:\n  labels:\n    n: \"" + i + "\"\n";
                var proposed = store.propose(body);
                assertEquals(200, proposed.status(), proposed.body());
                assertEquals(200, store.activate(NOW, TTL).status());
            }
            assertEquals(8, store.history().size());

            var lead = CpStore.class.getDeclaredField("leadership");
            lead.setAccessible(true);
            var saved = lead.get(store);
            lead.set(store, null);
            assertEquals("pending", store.binding().connectionState());
            assertFalse(store.ready(true));
            lead.set(store, saved);
            var onLeader = ControlLeadership.class.getDeclaredMethod("onLeader", long.class, Optional.class);
            onLeader.setAccessible(true);
            onLeader.invoke(store.leadership(), 1L, Optional.empty());
            onLeader.invoke(store.leadership(), 1L, Optional.of(new NodeId(9, "other")));
            var onNoop = ControlLeadership.class.getDeclaredMethod("onNoop", long.class, long.class);
            onNoop.setAccessible(true);
            onNoop.invoke(store.leadership(), 999L, 1L);
            onNoop.invoke(store.leadership(), store.leadership().term(), 1L);
            var committed = CpStore.class.getDeclaredMethod("onCommitted", CommittedEntry.class);
            committed.setAccessible(true);
            long idx = 10_000;
            committed.invoke(store, new CommittedEntry(CpStore.GROUP, 1L, idx++, ByteBuffer.allocate(0)));
            committed.invoke(store, new CommittedEntry(CpStore.GROUP, 1L, idx++, ByteBuffer.wrap(new byte[] {9})));
            committed.invoke(store, new CommittedEntry(CpStore.GROUP, 1L, idx++, propose("not: [")));
            committed.invoke(store, new CommittedEntry(CpStore.GROUP, 1L, idx++, signedAtIndexZero()));
        }
    }

    @Test
    void decideRejectsTheRemainingSnapshotCases(@TempDir Path home) throws Exception {
        var keys = KeyRing.generate("online-1", NOW + 86_400_000L, 1, 20);
        var apply = DataApply.open(keys, home.resolve("pin"), home.resolve("node"), () -> NOW);
        var document = ConfigDocument.defaults(ServerConfig.defaults(home.resolve("root")));
        var good = keys.sign(copy(document, 3), 3, 2, 1, NOW, NOW + 1_000, 0);
        assertTrue(apply.decide(good, installed(3, good.canonicalHashHex())).noop());
        assertEquals("version", apply.decide(good, installed(4, "aa")).error());
        assertEquals("version", apply.decide(good, installed(3, "aa")).error());
        assertEquals("canonicalHash", apply.decide(resized(good, 8, 16), installed(0, "")).error());
        assertEquals("canonicalHash", apply.decide(resized(good, 32, 4), installed(0, "")).error());
        assertEquals("keyId", apply.decide(withKey(good, "missing"), installed(0, "")).error());
        assertEquals("rbac", apply.decide(hashed("schema: 1\nrbac:\n"), installed(0, "")).error());
        assertEquals("rbac", apply.decide(hashed("schema: 1\ntrust:\n"), installed(0, "")).error());
        assertEquals("rbac", apply.decide(hashed("schema: 1\nkey_password: x\n"), installed(0, "")).error());
        assertEquals("online key", apply.decide(keys.sign(copy(document, 1), 0, 1, 1, NOW, NOW + 1_000, 0),
                installed(0, "")).error());
        assertEquals("online key", apply.decide(keys.sign(copy(document, 1), 21, 1, 1, NOW, NOW + 1_000, 0),
                installed(0, "")).error());
        var expired = keys.sign(copy(document, 4), 4, 1, 1, NOW - 2_000, NOW - 1, 0);
        assertEquals("expired", apply.decide(expired, installed(0, "")).error());
        var hugeVersion = Long.MAX_VALUE - SnapshotCrypto.MAX_VERSION_JUMP + 1;
        var huge = keys.sign(copy(document, 4), hugeVersion, 1, 1, NOW, NOW + 1_000, 0);
        assertEquals("version", apply.decide(huge, installed(hugeVersion - 1, "")).error());
        assertEquals("rollback", apply.decide(keys.sign(copy(document, 4), 5, 1, 1, NOW, NOW + 1_000, 3),
                installed(0, "")).error());
        apply.remember(good);
        var other = ConfigDocument.defaults(ServerConfig.defaults(home.resolve("other")));
        assertEquals("rollback", apply.decide(keys.sign(copy(other, 4), 6, 3, 2, NOW, NOW + 1_000, 3),
                installed(3, good.canonicalHashHex())).error());
        assertEquals("epoch", apply.decide(keys.sign(copy(document, 4), 7, 1, 2, NOW, NOW + 1_000, 0),
                installed(3, good.canonicalHashHex())).error());
        for (int version = 1; version <= 9; version++) {
            apply.remember(keys.sign(copy(document, 4), version, 2, version, NOW, NOW + 1_000, 0));
        }
        assertNull(apply.retainedHash(1));
        assertNotNull(apply.retainedHash(9));
        var staleKey = KeyRing.generate("online-1", NOW - 1, 0, 10);
        var stale = DataApply.open(staleKey, home.resolve("pin-stale"), home.resolve("stale"), () -> NOW);
        var signed = staleKey.sign(copy(document, 2), 2, 1, 1, NOW - 10, NOW + 1_000, 0);
        assertEquals("online key", stale.decide(signed, installed(0, "")).error());
        Files.writeString(home.resolve("file"), "x");
        var blocked = DataApply.open(keys, home.resolve("pin-file"), home.resolve("file"), () -> NOW);
        assertThrows(IOException.class, () -> blocked.remember(good));
        assertNull(SnapshotCrypto.publicKey(new byte[] {1, 2, 3}));
        PublicKey publicKey = SnapshotCrypto.publicKey(keys.rootPublic());
        assertNotNull(publicKey);
        assertFalse(SnapshotCrypto.verify(null, new byte[] {1}, new byte[] {1}));
        assertFalse(SnapshotCrypto.verify(publicKey, null, new byte[] {1}));
        assertFalse(SnapshotCrypto.verify(publicKey, new byte[] {1}, new byte[0]));
        assertFalse(SnapshotCrypto.verify(publicKey, new byte[] {1}, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new TrustKey(null, new byte[] {1}, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TrustKey(" ", new byte[] {1}, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TrustKey("k", new byte[] {1}, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TrustKey("k", new byte[] {1}, 1, 2, 1));
        var key = new TrustKey("k", new byte[] {1, 2}, 5, 0, 4);
        LastGoodFiles.write(home.resolve("files"), 3, 4, "k\t\"\n", List.of(key,
                new TrustKey("k2", new byte[] {3}, 6, 0, 4)), 1, new byte[] {9}, good);
        assertEquals("k\t\"\n", LastGoodFiles.readString(Files.readString(home.resolve("files/config/floor.json")),
                "keyId"));
        assertThrows(IOException.class, () -> LastGoodFiles.readString("{}", "keyId"));
        assertThrows(IOException.class, () -> LastGoodFiles.readString("{\"keyId\": 1}", "keyId"));
        assertThrows(IOException.class, () -> LastGoodFiles.readString("{\"keyId\": \"", "keyId"));
        assertThrows(IOException.class, () -> LastGoodFiles.readString("{\"keyId\": \"\\", "keyId"));
        assertThrows(IOException.class, () -> LastGoodFiles.readString("{\"keyId\": \"\\q\"}", "keyId"));
        assertThrows(IOException.class, () -> LastGoodFiles.readString("{\"keyId\": \"\\u12\"", "keyId"));
        assertEquals("a\n\r\t\\b", LastGoodFiles.readString("{\"keyId\": \"a\\n\\r\\t\\\\b\\u0021\"}", "keyId")
                .substring(0, 6));
        assertEquals('!', LastGoodFiles.readString("{\"keyId\": \"\\u0021\"}", "keyId").charAt(0));
        assertThrows(IOException.class, () -> LastGoodFiles.readLong("{}", "n"));
        assertThrows(IOException.class, () -> LastGoodFiles.readLong("{\"n\": x}", "n"));
        assertEquals(-4L, LastGoodFiles.readLong("{\"n\": -4}", "n"));
        assertTrue(LastGoodFiles.jsonString("a\u0001").contains("\\u"));
        Files.write(home.resolve("bad-pin"), new byte[] {'N', 'O', 'P', 'E', 0, 0, 0, 1, 1});
        assertThrows(IOException.class, () -> LastGoodFiles.readPin(home.resolve("bad-pin")));
        var pin = ByteBuffer.allocate(12);
        pin.put("SJWS".getBytes(StandardCharsets.US_ASCII));
        pin.putInt(50);
        Files.write(home.resolve("short-pin"), pin.array());
        assertThrows(IOException.class, () -> LastGoodFiles.readPin(home.resolve("short-pin")));
    }

    @Test
    void signedSnapshotEqualityAndPeerCertificate() throws Exception {
        var left = new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, null, null, 5, 6, null, null, null, 0,
                null);
        assertEquals(left, left);
        assertEquals(left.hashCode(), new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[0], "", new byte[0], 0, "").hashCode());
        assertNotEquals(left, null);
        assertNotEquals(left, "snapshot");
        assertNotEquals(left, left.withSchema(2));
        assertNotEquals(left, new SignedSnapshot(1, 9, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 9, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 9, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, left.withParentVersion(1));
        assertNotEquals(left, left.withKind((byte) 2));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[0], "", new byte[0], 8, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 9, 6,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 9,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "p", new byte[0], 5, 6,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[0], "k", new byte[0], 0, ""));
        assertNotEquals(left, left.withCanonicalYaml("schema: 1\n"));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[] {1}, 5, 6,
                new byte[0], "", new byte[0], 0, ""));
        assertNotEquals(left, new SignedSnapshot(1, 2, 3, 4, 0, SnapshotCrypto.KIND_FULL, "", new byte[0], 5, 6,
                new byte[] {1}, "", new byte[0], 0, ""));
        assertNotEquals(left, left.withSignature(new byte[] {1}));

        assertNull(AdminServer.credential(new Plain()));
        assertNull(AdminServer.credential(new Tls(null)));
        assertNull(AdminServer.credential(new Tls(session(new SSLPeerUnverifiedException("peer")))));
        assertNull(AdminServer.credential(new Tls(session((Certificate[]) null))));
        assertNull(AdminServer.credential(new Tls(session(new Certificate[0]))));
        assertNull(AdminServer.credential(new Tls(session(new Certificate[] {new Raw()}))));
        var principal = (Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {cert(null)})));
        assertTrue(principal.subject().contains("cn=admin"));
        assertTrue(((Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {
                cert(List.of(List.of(2, "dns")))})))).subject().contains("cn=admin"));
        assertTrue(((Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {
                cert(List.of(List.of(6)))})))).subject().contains("cn=admin"));
        assertTrue(((Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {
                cert(List.of(List.of("6", "urn:x")))})))).subject().contains("cn=admin"));
        assertTrue(((Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {
                cert(List.of(List.of(6, 1)))})))).subject().contains("cn=admin"));
        assertTrue(((Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {
                cert(List.of(List.of(6, "https://x")))})))).subject().contains("cn=admin"));
        var uri = (Rbac.ClientCert) AdminServer.credential(new Tls(session(new Certificate[] {
                cert(List.of(List.of(6, "urn:sjws:admin:root")))})));
        assertEquals("urn:sjws:admin:root", uri.subject());
    }

    private static void elect(CpStore store, FakeClock clock) {
        clock.advance(Duration.ofMillis(100));
        store.drive();
        assertTrue(store.leadership().leaderReady());
    }

    private static String topic(String yaml, boolean destructive) {
        return yaml + """
                messaging:
                  enabled: true
                  topics:
                    orders:
                      partitions: 2
                      replication: 1
                      durability: async
                      allow_destructive: %s
                """.formatted(destructive);
    }

    private static ByteBuffer propose(String yaml) {
        var raw = yaml.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(1 + 8 + 4 + raw.length);
        buffer.put((byte) 1);
        buffer.putLong(1);
        buffer.putInt(raw.length);
        buffer.put(raw);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer signedAtIndexZero() {
        var buffer = ByteBuffer.allocate(1 + 4 + 8 * 8 + 1 + 8 + 32 + 16);
        buffer.put((byte) 5);
        buffer.putInt(1);
        buffer.putLong(1);
        buffer.putLong(1);
        buffer.putLong(0);
        buffer.putLong(0);
        buffer.put((byte) 1);
        buffer.putLong(0);
        buffer.putLong(0);
        buffer.putLong(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put(new byte[32]);
        buffer.put(new byte[16]);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.flip();
        return buffer;
    }

    private static SignedSnapshot hashed(String yaml) {
        return new SignedSnapshot(1, 1, 1, 1, 0, SnapshotCrypto.KIND_FULL, "", KeyRing.hash(yaml), NOW, NOW + 1_000,
                new byte[16], "online-1", new byte[64], 0, yaml);
    }

    private static SignedSnapshot resized(SignedSnapshot snapshot, int hash, int nonce) {
        return new SignedSnapshot(snapshot.schema(), snapshot.version(), snapshot.epoch(), snapshot.raftIndex(),
                snapshot.parentVersion(), snapshot.kind(), snapshot.projectionId(), new byte[hash],
                snapshot.issuedAtMillis(), snapshot.expiresAtMillis(), new byte[nonce], snapshot.keyId(),
                snapshot.signature(), snapshot.rollbackOf(), snapshot.canonicalYaml());
    }

    private static SignedSnapshot withKey(SignedSnapshot snapshot, String keyId) {
        return new SignedSnapshot(snapshot.schema(), snapshot.version(), snapshot.epoch(), snapshot.raftIndex(),
                snapshot.parentVersion(), snapshot.kind(), snapshot.projectionId(), snapshot.canonicalHash(),
                snapshot.issuedAtMillis(), snapshot.expiresAtMillis(), snapshot.nonce(), keyId, snapshot.signature(),
                snapshot.rollbackOf(), snapshot.canonicalYaml());
    }

    private static ConfigDocument copy(ConfigDocument document, long version) {
        return ConfigDocument.of(document.server(), document.acme(), document.routing(), document.middleware(),
                document.access(), document.node(), document.schemaVersion(), version, null, null);
    }

    private static AppliedConfig installed(long version, String hash) {
        return new AppliedConfig() {
            public long version() {
                return version;
            }

            public String canonicalHash() {
                return hash;
            }

            public long epoch() {
                return 0;
            }
        };
    }

    private static SSLSession session(Object certificates) {
        return (SSLSession) java.lang.reflect.Proxy.newProxyInstance(SSLSession.class.getClassLoader(),
                new Class<?>[] {SSLSession.class}, (proxy, method, args) -> {
                    if ("getPeerCertificates".equals(method.getName())) {
                        if (certificates instanceof Exception failure) {
                            throw failure;
                        }
                        return certificates;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
    }

    private static X509Certificate cert(Collection<List<?>> names) {
        return new X509Certificate() {
            public Collection<List<?>> getSubjectAlternativeNames() {
                return names;
            }

            public X500Principal getSubjectX500Principal() {
                return new X500Principal("CN=admin");
            }

            public void checkValidity() {
            }

            public void checkValidity(java.util.Date date) {
            }

            public int getVersion() {
                return 3;
            }

            public java.math.BigInteger getSerialNumber() {
                return java.math.BigInteger.ONE;
            }

            public java.security.Principal getIssuerDN() {
                return getSubjectX500Principal();
            }

            public java.security.Principal getSubjectDN() {
                return getSubjectX500Principal();
            }

            public java.util.Date getNotBefore() {
                return new java.util.Date(0);
            }

            public java.util.Date getNotAfter() {
                return new java.util.Date(Long.MAX_VALUE);
            }

            public byte[] getTBSCertificate() {
                return new byte[0];
            }

            public byte[] getSignature() {
                return new byte[0];
            }

            public String getSigAlgName() {
                return "Ed25519";
            }

            public String getSigAlgOID() {
                return "1.3.101.112";
            }

            public byte[] getSigAlgParams() {
                return null;
            }

            public boolean[] getIssuerUniqueID() {
                return null;
            }

            public boolean[] getSubjectUniqueID() {
                return null;
            }

            public boolean[] getKeyUsage() {
                return null;
            }

            public int getBasicConstraints() {
                return -1;
            }

            public byte[] getEncoded() {
                return new byte[0];
            }

            public void verify(PublicKey key) {
            }

            public void verify(PublicKey key, String provider) {
            }

            public String toString() {
                return "cert";
            }

            public PublicKey getPublicKey() {
                return null;
            }

            public byte[] getExtensionValue(String oid) {
                return null;
            }

            public java.util.Set<String> getCriticalExtensionOIDs() {
                return java.util.Set.of();
            }

            public java.util.Set<String> getNonCriticalExtensionOIDs() {
                return java.util.Set.of();
            }

            public boolean hasUnsupportedCriticalExtension() {
                return false;
            }
        };
    }

    private static final class Raw extends Certificate {
        Raw() {
            super("raw");
        }

        public byte[] getEncoded() {
            return new byte[0];
        }

        public void verify(PublicKey key) {
        }

        public void verify(PublicKey key, String provider) {
        }

        public String toString() {
            return "raw";
        }

        public PublicKey getPublicKey() {
            return null;
        }
    }

    private static class Exchange extends HttpExchange {
        public Headers getRequestHeaders() {
            throw new UnsupportedOperationException();
        }

        public Headers getResponseHeaders() {
            throw new UnsupportedOperationException();
        }

        public URI getRequestURI() {
            throw new UnsupportedOperationException();
        }

        public String getRequestMethod() {
            throw new UnsupportedOperationException();
        }

        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException();
        }

        public void close() {
        }

        public InputStream getRequestBody() {
            throw new UnsupportedOperationException();
        }

        public OutputStream getResponseBody() {
            throw new UnsupportedOperationException();
        }

        public void sendResponseHeaders(int status, long length) {
        }

        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException();
        }

        public int getResponseCode() {
            return -1;
        }

        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException();
        }

        public String getProtocol() {
            return "HTTP/1.1";
        }

        public Object getAttribute(String name) {
            return null;
        }

        public void setAttribute(String name, Object value) {
        }

        public void setStreams(InputStream in, OutputStream out) {
        }

        public HttpPrincipal getPrincipal() {
            return null;
        }
    }

    private static final class Plain extends Exchange {
    }

    private static final class Tls extends HttpsExchange {
        private final SSLSession session;

        Tls(SSLSession session) {
            this.session = session;
        }

        public SSLSession getSSLSession() {
            return session;
        }

        public Headers getRequestHeaders() {
            throw new UnsupportedOperationException();
        }

        public Headers getResponseHeaders() {
            throw new UnsupportedOperationException();
        }

        public URI getRequestURI() {
            throw new UnsupportedOperationException();
        }

        public String getRequestMethod() {
            throw new UnsupportedOperationException();
        }

        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException();
        }

        public void close() {
        }

        public InputStream getRequestBody() {
            throw new UnsupportedOperationException();
        }

        public OutputStream getResponseBody() {
            throw new UnsupportedOperationException();
        }

        public void sendResponseHeaders(int status, long length) {
        }

        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException();
        }

        public int getResponseCode() {
            return -1;
        }

        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException();
        }

        public String getProtocol() {
            return "HTTP/1.1";
        }

        public Object getAttribute(String name) {
            return null;
        }

        public void setAttribute(String name, Object value) {
        }

        public void setStreams(InputStream in, OutputStream out) {
        }

        public HttpPrincipal getPrincipal() {
            return null;
        }
    }

}
