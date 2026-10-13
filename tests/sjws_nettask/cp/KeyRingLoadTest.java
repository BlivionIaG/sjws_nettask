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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KeyRing.load fails closed: permissions, pin, bundle, anti-rollback (both floors), 90-day cap. */
class KeyRingLoadTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 86_400_000L;

    @TempDir
    Path dir;

    private Path keyDir;
    private Path stateDir;
    private Path rootPub;
    private KeyPair root;
    private KeyPair online;

    @BeforeEach
    void setUp() throws Exception {
        keyDir = Files.createDirectory(dir.resolve("keys"), mode("rwx------"));
        stateDir = Files.createDirectory(dir.resolve("state"), mode("rwx------"));
        var etc = Files.createDirectory(dir.resolve("etc"), mode("rwxr-xr-x"));
        root = pair();
        online = pair();
        rootPub = write(etc.resolve("root.pub"), pem("PUBLIC KEY", root.getPublic().getEncoded()), "rw-r--r--");
        write(keyDir.resolve("online.key"), pem("PRIVATE KEY", online.getPrivate().getEncoded()), "rw-------");
    }

    private CpKeyConfig cfg(long minSerial) {
        return new CpKeyConfig(keyDir, rootPub, stateDir, null, false, minSerial);
    }

    private byte[] bundle(long serial, long floor, long issuedAt, long notAfter, long keyNotAfter) {
        var key = new TrustKey("voter-1", online.getPublic().getEncoded(), keyNotAfter, 0, 1000);
        return TrustBundle.sign(root.getPrivate(), root.getPublic().getEncoded(), serial, issuedAt, notAfter, floor,
                List.of(key));
    }

    private byte[] bundle(long serial, long floor) {
        return bundle(serial, floor, NOW - DAY, NOW + 30 * DAY, NOW + 30 * DAY);
    }

    private void install(byte[] bundle) throws Exception {
        Files.deleteIfExists(keyDir.resolve("trust.bundle"));
        write(keyDir.resolve("trust.bundle"), bundle, "rw-r--r--");
    }

    @Test
    void loadsAndAdvancesBothFloors() throws Exception {
        install(bundle(5, 3));
        var ring = KeyRing.load(cfg(5), NOW);
        assertEquals("voter-1", ring.online().keyId());
        assertEquals(5, ring.bundle().bundleSerial());
        assertEquals(3, ring.versionFloor());
        assertEquals(TrustBundle.fingerprint(root.getPublic().getEncoded()), ring.rootFingerprint());
        assertFalse(ring.insecurePermissions());
        assertEquals(1, ring.keys().size());
        assertEquals(64, ring.trustSignature().length);
        var a = TrustState.read(stateDir, false);
        assertEquals(a, TrustState.read(keyDir, false));
        assertEquals(5, a.maxBundleSerial());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                stateDir.resolve("trust.state"))));
        // Restart without the seed: the floors carry it. Signing still works on a loaded ring.
        var again = KeyRing.load(cfg(0), NOW);
        assertNotNull(again.sign(sjws_nettask.config.ConfigDocument.defaults(sjws_nettask.ServerConfig.defaults(dir)), 1, 1, 1, NOW, NOW + 1000, 0L));
    }

    @Test
    void freshNodeWithoutSeedFailsClosed() throws Exception {
        install(bundle(5, 0));
        var e = assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW));
        assertTrue(e.getMessage().contains("cp.minBundleSerial"), e.getMessage());
        assertFalse(Files.exists(stateDir.resolve("trust.state")));
    }

    @Test
    void wipedStateDirPlusReplayedOlderBundleIsRefused() throws Exception {
        install(bundle(7, 0));
        KeyRing.load(cfg(7), NOW);
        Files.delete(stateDir.resolve("trust.state"));
        install(bundle(6, 0));
        var e = assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW));
        assertTrue(e.getMessage().contains("rollback"), e.getMessage());
        // Wiping both and replaying still needs an explicit seed, which the old bundle cannot meet.
        Files.delete(keyDir.resolve("trust.state"));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(7), NOW));
    }

    @Test
    void floorCopyMismatchUsesTheHigherFloor() throws Exception {
        install(bundle(4, 0));
        KeyRing.load(cfg(4), NOW);
        var low = Files.readAllBytes(stateDir.resolve("trust.state"));
        install(bundle(9, 2));
        KeyRing.load(cfg(0), NOW);
        // Restore an old stateDir backup: keyDir still says 9.
        Files.delete(stateDir.resolve("trust.state"));
        write(stateDir.resolve("trust.state"), low, "rw-------");
        install(bundle(8, 2));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW));
        // Lowered keyDir copy, current stateDir: still the higher one.
        install(bundle(9, 2));
        KeyRing.load(cfg(0), NOW);
        Files.delete(keyDir.resolve("trust.state"));
        write(keyDir.resolve("trust.state"), low, "rw-------");
        install(bundle(8, 2));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW));
        // Loading the current bundle heals the lower copy.
        install(bundle(9, 2));
        KeyRing.load(cfg(0), NOW);
        assertEquals(TrustState.read(stateDir, false), TrustState.read(keyDir, false));
    }

    @Test
    void floorAndEqualSerialAndMinSerialRules() throws Exception {
        install(bundle(5, 4));
        KeyRing.load(cfg(5), NOW);
        install(bundle(6, 3));
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW)).getMessage()
                .contains("versionFloor"));
        install(bundle(5, 4, NOW - 2 * DAY, NOW + 30 * DAY, NOW + 30 * DAY));
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(0), NOW)).getMessage()
                .contains("differs"));
        install(bundle(6, 4));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(10), NOW));
        assertEquals(6, KeyRing.load(cfg(6), NOW).bundle().bundleSerial());
    }

    @Test
    void rootPinMismatchIsRefused() throws Exception {
        install(bundle(1, 0));
        var fp = TrustBundle.fingerprint(root.getPublic().getEncoded());
        var bad = new CpKeyConfig(keyDir, rootPub, stateDir, "00".repeat(32), false, 1);
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(bad, NOW)).getMessage()
                .contains("cp.rootPubSha256"));
        var colons = fp.toUpperCase().replaceAll("(..)(?!$)", "$1:");
        assertNotNull(KeyRing.load(new CpKeyConfig(keyDir, rootPub, stateDir, colons, false, 1), NOW));
        // A bundle signed for another root.
        var other = pair();
        Files.delete(rootPub);
        write(rootPub, other.getPublic().getEncoded(), "rw-r--r--");
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW)).getMessage()
                .contains("pinned root"));
        Files.delete(rootPub);
        write(rootPub, new byte[] {1, 2, 3}, "rw-r--r--");
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
    }

    @Test
    void permissionFailuresAreRefused() throws Exception {
        install(bundle(1, 0));
        Files.setPosixFilePermissions(keyDir.resolve("online.key"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        var insecure = KeyRing.load(new CpKeyConfig(keyDir, rootPub, stateDir, null, true, 1), NOW);
        assertTrue(insecure.insecurePermissions());
        Files.setPosixFilePermissions(keyDir.resolve("online.key"), PosixFilePermissions.fromString("rw-------"));

        Files.setPosixFilePermissions(rootPub, PosixFilePermissions.fromString("rw-rw-r--"));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        Files.setPosixFilePermissions(rootPub, PosixFilePermissions.fromString("rw-r--r--"));

        Files.setPosixFilePermissions(keyDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW)).getMessage()
                .contains("0700"));
        Files.setPosixFilePermissions(keyDir, PosixFilePermissions.fromString("rwx------"));

        // Symlinked leaf: refused even in insecure mode.
        var real = keyDir.resolve("online.key");
        var moved = Files.move(real, dir.resolve("elsewhere.key"));
        Files.createSymbolicLink(real, moved);
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        assertThrows(CpKeyException.class,
                () -> KeyRing.load(new CpKeyConfig(keyDir, rootPub, stateDir, null, true, 1), NOW));
        Files.delete(real);
        assertThrows(CpKeyException.class,
                () -> KeyRing.load(new CpKeyConfig(keyDir, rootPub, stateDir, null, true, 1), NOW));
        Files.createDirectory(real);
        assertThrows(CpKeyException.class,
                () -> KeyRing.load(new CpKeyConfig(keyDir, rootPub, stateDir, null, true, 1), NOW));
        assertThrows(CpKeyException.class,
                () -> KeyRing.load(new CpKeyConfig(dir.resolve("missing"), rootPub, stateDir, null, false, 1), NOW));
        assertThrows(CpKeyException.class,
                () -> KeyRing.load(new CpKeyConfig(rootPub, rootPub, stateDir, null, false, 1), NOW));
    }

    @Test
    void validityOver90DaysAndExpiryAreRefused() throws Exception {
        install(bundle(1, 0, NOW - DAY, NOW + 91 * DAY, NOW + 30 * DAY));
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW)).getMessage()
                .contains("90 days"));
        install(bundle(1, 0, NOW - DAY, NOW + 30 * DAY, NOW + 90 * DAY));
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW)).getMessage()
                .contains("90 days"));
        install(bundle(1, 0, NOW - 10 * DAY, NOW - DAY, NOW + DAY));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        install(bundle(1, 0, NOW + DAY, NOW + 30 * DAY, NOW + 30 * DAY));
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        install(bundle(1, 0, NOW - 2 * DAY, NOW + 30 * DAY, NOW - DAY));
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW)).getMessage()
                .contains("expired"));
    }

    @Test
    void onlineKeyMustBeInTheBundle() throws Exception {
        install(bundle(1, 0));
        Files.delete(keyDir.resolve("online.key"));
        write(keyDir.resolve("online.key"), pair().getPrivate().getEncoded(), "rw-------");
        assertTrue(assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW)).getMessage()
                .contains("does not match"));
        Files.delete(keyDir.resolve("online.key"));
        write(keyDir.resolve("online.key"), "-----BEGIN PRIVATE KEY-----\nAAAA".getBytes(), "rw-------");
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        Files.delete(keyDir.resolve("online.key"));
        write(keyDir.resolve("online.key"), "-----BEGIN PRIVATE KEY-----\n!!\n-----END PRIVATE KEY-----".getBytes(),
                "rw-------");
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
    }

    @Test
    void malformedTrustStateIsRefused() throws Exception {
        install(bundle(1, 0));
        write(stateDir.resolve("trust.state"), "sjws-cp-trust-state 1\nserial=x\n".getBytes(), "rw-------");
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        Files.delete(stateDir.resolve("trust.state"));
        write(stateDir.resolve("trust.state"), "sjws-cp-trust-state 1\nserial=1\nfloor=0\nsha256=zz\n".getBytes(),
                "rw-------");
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
        Files.delete(stateDir.resolve("trust.state"));
        write(stateDir.resolve("trust.state"), "sjws-cp-trust-state 1\nserial=1\nfloor=0\nsha256=\n".getBytes(),
                "rw-r--r--");
        assertThrows(CpKeyException.class, () -> KeyRing.load(cfg(1), NOW));
    }

    @Test
    void bundleParsingIsStrict() throws Exception {
        var spki = root.getPublic().getEncoded();
        var good = bundle(1, 0);
        assertEquals(1, TrustBundle.parse(good, spki).bundleSerial());
        var tampered = good.clone();
        tampered[30] ^= 1;
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(tampered, spki));
        var sigFlip = good.clone();
        sigFlip[sigFlip.length - 1] ^= 1;
        assertTrue(assertThrows(CpKeyException.class, () -> TrustBundle.parse(sigFlip, spki)).getMessage()
                .contains("signature"));
        var domain = good.clone();
        domain[0] = 'X';
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(domain, spki));
        var version = good.clone();
        version[TrustBundle.DOMAIN.length] = 9;
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(version, spki));
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(new byte[10], spki));
        assertThrows(CpKeyException.class,
                () -> TrustBundle.parse(java.util.Arrays.copyOf(good, TrustBundle.DOMAIN.length + 70), spki));
        var k1 = new TrustKey("b", online.getPublic().getEncoded(), NOW + DAY, 0, 1);
        var k2 = new TrustKey("a", pair().getPublic().getEncoded(), NOW + DAY, 0, 1);
        var unsorted = TrustBundle.sign(root.getPrivate(), spki, 1, NOW - DAY, NOW + DAY, 0, List.of(k1, k2));
        assertTrue(assertThrows(CpKeyException.class, () -> TrustBundle.parse(unsorted, spki)).getMessage()
                .contains("sorted"));
        var empty = TrustBundle.sign(root.getPrivate(), spki, 1, NOW - DAY, NOW + DAY, 0, List.of());
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(empty, spki));
        var backwards = TrustBundle.sign(root.getPrivate(), spki, 1, NOW, NOW - 1, 0, List.of(k1));
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(backwards, spki));
        // A snapshot-domain signature never verifies as a bundle.
        var legacy = SnapshotCrypto.trustInput(0, List.of(k1));
        assertThrows(CpKeyException.class, () -> TrustBundle.parse(legacy, spki));
        var parsed = TrustBundle.parse(good, spki);
        assertEquals(64, parsed.sha256().length());
        assertArrayEquals(good, parsed.encoded());
    }

    private static KeyPair pair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] pem(String label, byte[] der) {
        return ("-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + label
                + "-----\n").getBytes();
    }

    private static java.nio.file.attribute.FileAttribute<?> mode(String m) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(m));
    }

    private static Path write(Path file, byte[] bytes, String perms) throws Exception {
        Files.write(file, bytes);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(perms));
        return file;
    }
}
