package sjws_nettask.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Join-token and bootstrap rules: modes, paths, identity, and redaction. */
@Timeout(30)
class BootstrapTest {

    private static final String SECRET = "0123456789abcdef";
    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_R = PosixFilePermissions.fromString("r--------");
    private static final Set<PosixFilePermission> WORLD = PosixFilePermissions.fromString("rw-r--r--");

    @TempDir
    Path tmp;

    @AfterEach
    void clearEnrollment() {
        var current = NodeEnrollment.current();
        if (current != null) {
            current.close();
        }
    }

    @Test
    void dataBootstrapHoldsTheTokenOutsideTheStatusAndTheLogLine() throws Exception {
        var fix = dataTree();
        var boot = Bootstrap.load(fix.boot, "data");
        assertEquals(NodeRole.DATA, boot.role());
        assertEquals(DataPlaneRole.GATEWAY, boot.dataRole());
        assertEquals("gw-1", boot.nodeId());
        assertEquals("10.0.0.2:9443", boot.controlPlane());
        assertEquals("10.1.0.1:9443", boot.listen());
        assertEquals(java.util.List.of("10.0.0.3:9443"), boot.peers());
        assertEquals(java.util.List.of("http1", "proxy"), boot.capabilities());
        assertEquals("canary", boot.labels().get("track"));
        assertFalse(boot.joinedBefore());
        assertArrayEquals(Files.readAllBytes(fix.pin), boot.pin());
        assertEquals("node-1", boot.join().tokenId());
        assertEquals("JoinMaterial[node-1]", boot.join().toString());
        assertFalse(boot.toString().contains(SECRET), boot.toString());
        assertFalse(boot.startupLine().contains(SECRET), boot.startupLine());
        assertTrue(boot.startupLine().contains("role=data/gateway"));
        assertTrue(boot.startupLine().contains("connection=pending"));
        var perms = Files.getPosixFilePermissions(fix.state);
        assertEquals(PosixFilePermissions.fromString("rwx------"), perms);
        var copy = boot.join().copySecret();
        assertArrayEquals(SECRET.getBytes(java.nio.charset.StandardCharsets.US_ASCII), copy);
        copy[0] = 'x';
        assertNotEquals('x', boot.join().copySecret()[0]);
        java.util.Arrays.fill(copy, (byte) 0);
    }

    @Test
    void controlBootstrapRejectsADataContactAndAllowsAnEmptyPeerList() throws Exception {
        var fix = controlTree("");
        var boot = Bootstrap.load(fix.boot, null);
        assertEquals(NodeRole.CONTROL, boot.role());
        assertNull(boot.dataRole());
        assertNull(boot.controlPlane());
        assertTrue(boot.peers().isEmpty());
        assertEquals("[::1]:9443", boot.listen());
    }

    @Test
    void ipv6PeersAndOwnerReadTokenAreAccepted() throws Exception {
        var fix = dataTree();
        Files.setPosixFilePermissions(fix.token, OWNER_R);
        Files.writeString(fix.boot, baseData(fix).replace("10.0.0.3:9443", "[2001:db8::1]:9443"));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        var boot = Bootstrap.load(fix.boot, "data");
        assertEquals("[2001:db8::1]:9443", boot.peers().get(0));
    }

    @Test
    void looseTokenSymlinkAndEmbeddedSecretAreRefusedWithoutEchoingTheSecret() throws Exception {
        var fix = dataTree();
        Files.setPosixFilePermissions(fix.token, WORLD);
        var loose = assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data"));
        assertTrue(loose.getMessage().contains("0600"), loose.getMessage());
        assertFalse(loose.getMessage().contains(SECRET), loose.getMessage());

        Files.setPosixFilePermissions(fix.token, OWNER_RW);
        var link = tmp.resolve("token-link");
        Files.createSymbolicLink(link, fix.token);
        Files.writeString(fix.boot, baseData(fix).replace(fix.token.toString(), link.toString()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        var symlink = assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data"));
        assertTrue(symlink.getMessage().contains("symlink"), symlink.getMessage());

        Files.writeString(fix.boot, baseData(fix) + "secret=" + SECRET + "\n");
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        var embedded = assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data"));
        assertTrue(embedded.getMessage().contains("must not contain a secret"), embedded.getMessage());
        assertFalse(embedded.getMessage().contains(SECRET), embedded.getMessage());
    }

    @Test
    void worldReadableBootstrapRelativePinAndRoleMismatchFail() throws Exception {
        var fix = dataTree();
        Files.setPosixFilePermissions(fix.boot, WORLD);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("world-accessible"));

        Files.writeString(fix.boot, baseData(fix).replace("pin=" + fix.pin, "pin=root.pub"));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("absolute"));

        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        var mismatch = assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "control"));
        assertTrue(mismatch.getMessage().contains("role is data"), mismatch.getMessage());
        assertFalse(mismatch.getMessage().contains(SECRET));
    }

    @Test
    void cpTlsBlockIsOptionalAndAllOrNothing() throws Exception {
        var fix = dataTree();
        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertNull(Bootstrap.load(fix.boot, "data").cpTls(), "off by default");
        Files.writeString(fix.boot, baseData(fix) + "cp-tls-cert=/etc/sjws/node.pem\ncp-tls-key=/etc/sjws/node-key.pem\n"
                + "cp-tls-ca=/etc/sjws/cp-ca.pem\ncp-tls-peer=urn:sjws:cp\n");
        var tls = Bootstrap.load(fix.boot, "data").cpTls();
        assertEquals(Path.of("/etc/sjws/cp-ca.pem"), tls.ca());
        assertEquals("urn:sjws:cp", tls.peer());
        assertRejected(baseData(dataTree()) + "cp-tls-cert=/a.pem\n", "together");
        assertRejected(baseData(dataTree()) + "cp-tls-peer=urn:sjws:cp\n", "cp-tls-peer needs");
        assertRejected(baseData(dataTree()) + "cp-tls-cert=a.pem\ncp-tls-key=/k\ncp-tls-ca=/c\n", "absolute");
    }

    @Test
    void rejectedShapes() throws Exception {
        assertRejected("role=standalone\n", "role must be control or data");
        assertRejected(baseData(dataTree()).replace("data-role=gateway\n", ""), "data-role");
        assertRejected(controlText(controlTree(""), "data-role=gateway\n"), "does not take data-role");
        assertRejected(controlText(controlTree(""), "control-plane=10.0.0.9:1\n"), "does not set control-plane");
        assertRejected(baseData(dataTree()).replace("control-plane=10.0.0.2:9443\n", ""), "requires control-plane");
        assertRejected(baseData(dataTree()).replace("node-id=gw-1", "node-id=../x"), "node-id");
        assertRejected(baseData(dataTree()).replace("capabilities=http1,proxy", "capabilities=http1,http1"),
                "capability");
        assertRejected(baseData(dataTree()).replace("capabilities=http1,proxy", "capabilities=root"), "capability");
        assertRejected(baseData(dataTree()) + "label.Bad Key=x\n", "invalid label");
        assertRejected(baseData(dataTree()) + "label.track=canary\n", "duplicate label");
        assertRejected(baseData(dataTree()) + "role=data\n", "duplicate role");
        assertRejected(baseData(dataTree()) + "nope\n", "not key=value");
        assertRejected(baseData(dataTree()) + "Unknown=1\n", "invalid key");
        assertRejected(baseData(dataTree()) + "extra=1\n", "unknown key");
        assertRejected(baseData(dataTree()).replace("10.0.0.2:9443", "10.0.0.2"), "host:port");
        assertRejected(baseData(dataTree()).replace("10.0.0.2:9443", "user@10.0.0.2:9443"), "host:port");
        assertRejected(baseData(dataTree()).replace("10.0.0.2:9443", "10.0.0.2:0"), "invalid port");
        assertRejected(baseData(dataTree()).replace("10.0.0.2:9443", "10.0.0.2:99999"), "invalid port");
        assertRejected(baseData(dataTree()).replace("10.0.0.2:9443", "[::1]"), "ipv6");
        assertRejected(baseData(dataTree()).replace("10.0.0.2:9443", "[zzzz]:80"), "ipv6");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=10.0.0.3:9443,"), "empty endpoint");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=..:80"), "invalid host");
    }

    @Test
    void tokenFileShapes() throws Exception {
        var fix = dataTree();
        writeToken(fix.token, "token-id=node-1\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("requires token-id and secret"));
        writeToken(fix.token, "token-id=node-1\nsecret=short\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("16..512"));
        writeToken(fix.token, "token-id=node-1\nsecret=" + SECRET + " \n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("printable"));
        writeToken(fix.token, "token-id=bad/id\nsecret=" + SECRET + "\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("token-id"));
        writeToken(fix.token, "token-id=node-1\nsecret=" + SECRET + "\nsecret=" + SECRET + "\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("duplicate secret"));
        writeToken(fix.token, "nope\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("without '='"));
        writeToken(fix.token, "password=" + SECRET + "\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("unknown key"));
        Files.write(fix.token, new byte[5000]);
        Files.setPosixFilePermissions(fix.token, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("too large"));
    }

    @Test
    void pinAndTokenMustStayOutsideStateAndThePinMustExist() throws Exception {
        var fix = dataTree();
        var inside = fix.state.resolve("pin");
        Files.createDirectories(fix.state);
        Files.writeString(inside, "k");
        Files.writeString(fix.boot, baseData(fix).replace(fix.pin.toString(), inside.toString()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("outside state-dir"));

        Files.writeString(fix.boot, baseData(fix).replace("join-token=" + fix.token, "join-token=" + fix.state.resolve("t")));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("outside state-dir"));

        Files.delete(fix.pin);
        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("is not a file"));
    }

    @Test
    void firstStartRequiresATokenAndALaterStartCanOmitIt() throws Exception {
        var fix = dataTree();
        Files.writeString(fix.boot, baseData(fix).replaceAll("join-token=.*\n", ""));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("first start"));

        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        var first = Bootstrap.load(fix.boot, "data");
        var pid = tmp.resolve("node.pid");
        var enrollment = NodeEnrollment.open(first, pid, 25_000);
        try {
            var suffix = ServiceStatus.runningSuffix(pid, ProcessHandle.current().pid(),
                    ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli());
            assertTrue(suffix.contains("role=data"), suffix);
            assertTrue(suffix.contains("data-role=gateway"), suffix);
            assertTrue(suffix.contains("connection=pending"), suffix);
            assertTrue(suffix.contains("config-version=0"), suffix);
            assertFalse(Files.readString(ServiceStatus.beside(pid)).contains(SECRET));
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"),
                    Files.getPosixFilePermissions(ServiceStatus.beside(pid)));

            enrollment.attach(new ControlPlaneBinding() {
                @Override
                public String connectionState() {
                    return "not a state " + SECRET;
                }

                @Override
                public long configVersion() {
                    return -4;
                }
            });
            var updated = Files.readString(ServiceStatus.beside(pid));
            assertTrue(updated.contains("connection=disconnected"), updated);
            assertTrue(updated.contains("config-version=0"), updated);
            assertFalse(updated.contains(SECRET), updated);

            enrollment.attach(new ControlPlaneBinding() {
                @Override
                public String connectionState() {
                    return "connected";
                }

                @Override
                public long configVersion() {
                    return 9;
                }
            });
            assertTrue(ServiceStatus.runningSuffix(pid, ProcessHandle.current().pid(),
                    ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli())
                    .contains("config-version=9"));
            assertEquals(25_000, ServiceStatus.drainBudget(pid, ProcessHandle.current().pid(),
                    ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()));

            enrollment.tokenConsumed();
            assertFalse(Files.exists(fix.token));
            assertTrue(enrollment.bootstrap().join() == null || enrollment.bootstrap().join().discarded());
            assertThrows(IllegalStateException.class, enrollment::copySecret);
            assertTrue(Files.exists(fix.state.resolve("node.id")));
            var idPerms = Files.getPosixFilePermissions(fix.state.resolve("node.id"));
            assertFalse(idPerms.contains(PosixFilePermission.GROUP_READ), idPerms.toString());
            enrollment.tokenConsumed();
        } finally {
            enrollment.close();
        }

        Files.writeString(fix.boot, baseData(fix).replaceAll("join-token=.*\n", ""));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        var again = Bootstrap.load(fix.boot, "data");
        assertTrue(again.joinedBefore());
        assertNull(again.join());

        Files.writeString(fix.state.resolve("node.id"), "node-id=other\nrole=data\ndata-role=gateway\n");
        Files.setPosixFilePermissions(fix.state.resolve("node.id"), OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("does not match"));
    }

    @Test
    void statusIgnoresARecycledPidAndAHostileFile() throws Exception {
        var pid = tmp.resolve("s.pid");
        long me = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        ServiceStatus.write(pid, me, started, "data", "edge", "leader", 3, 50);
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started + 1));
        assertEquals(ServiceStatus.NO_BUDGET, ServiceStatus.drainBudget(pid, me + 1, started));
        assertEquals("", ServiceStatus.runningSuffix(null, me, started));
        assertEquals(ServiceStatus.NO_BUDGET, ServiceStatus.drainBudget(null, me, started));
        ServiceStatus.deleteIfMatch(pid, me, started + 1);
        assertTrue(Files.exists(ServiceStatus.beside(pid)));
        ServiceStatus.deleteIfMatch(pid, me, started);
        assertFalse(Files.exists(ServiceStatus.beside(pid)));

        ServiceStatus.write(pid, me, started, "standalone", "", "pending", 0, 80);
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
        assertEquals(80, ServiceStatus.drainBudget(pid, me, started));

        Files.writeString(ServiceStatus.beside(pid), "pid=1\nstart=2\nrole=data\nconnection=" + SECRET + "\n");
        var hostile = ServiceStatus.runningSuffix(pid, 1, 2);
        assertTrue(hostile.contains("connection=disconnected"), hostile);
        assertFalse(hostile.contains(SECRET), hostile);
        var link = tmp.resolve("linked.pid");
        Files.writeString(link, "1\n");
        Files.createSymbolicLink(ServiceStatus.beside(link), ServiceStatus.beside(pid));
        assertEquals("", ServiceStatus.runningSuffix(link, 1, 2));
        assertEquals("disconnected", ConnectionState.canonical("nope"));
        assertEquals("disconnected", ConnectionState.canonical(null));
        assertEquals("leader", ConnectionState.canonical("leader"));
        assertNull(NodeRole.parse("nope"));
        assertNull(DataPlaneRole.parse(null));
    }

    @Test
    void closedEnrollmentDoesNotRecreateStatusAndASecondOpenIsRefused() throws Exception {
        var fix = dataTree();
        var boot = Bootstrap.load(fix.boot, "data");
        var pid = tmp.resolve("one.pid");
        var enrollment = NodeEnrollment.open(boot, pid, -1);
        assertThrows(BootstrapException.class, () -> NodeEnrollment.open(boot, pid, -1));
        enrollment.close();
        assertThrows(IllegalStateException.class, () -> enrollment.attach(new ControlPlaneBinding() {
            @Override
            public String connectionState() {
                return "connected";
            }

            @Override
            public long configVersion() {
                return 1;
            }
        }));
        assertFalse(Files.exists(ServiceStatus.beside(pid)));
        assertThrows(IllegalStateException.class, enrollment::tokenConsumed);
        enrollment.close();
    }

    @Test
    void groupWritableBootstrapAndMissingFile() throws Exception {
        var fix = dataTree();
        Files.setPosixFilePermissions(fix.boot, PosixFilePermissions.fromString("rw-rw----"));
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("group-writable"));
        var missing = assertThrows(BootstrapException.class, () -> Bootstrap.load(tmp.resolve("missing"), "data"));
        assertTrue(missing.getMessage().contains("not a file") || missing.getMessage().contains("does not exist"),
                missing.getMessage());
        assertThrows(BootstrapException.class, () -> Bootstrap.load(null, "data"));
    }

    @Test
    void leftoverPathsStatusAndTokenEdges() throws Exception {
        var fix = dataTree();
        var boot = Bootstrap.load(fix.boot, "data");
        assertEquals(fix.pin, boot.pinFile());
        assertEquals(fix.state, boot.stateDir());
        assertEquals(fix.boot.toAbsolutePath(), boot.source());
        assertFalse(boot.join().discarded());
        assertFalse(boot.join().memoryCleared());
        assertEquals(fix.token.toAbsolutePath(), boot.join().path());
        var hooks = ServiceLaunch.prepare(fix.boot, "data");
        hooks.onShutdown();
        assertTrue(hooks.startupLine().contains("node=gw-1"));

        var control = Bootstrap.load(controlTree("10.0.0.4:1,10.0.0.5:2").boot, null);
        assertTrue(control.startupLine().startsWith("control-plane role=control node=cp-1"));
        assertFalse(control.startupLine().contains("/"));
        assertNull(NodeRole.parse(null));
        assertNull(DataPlaneRole.parse("nope"));
        assertEquals("connecting", ConnectionState.canonical("connecting"));
        assertEquals("pending", ConnectionState.canonical("pending"));

        assertThrows(BootstrapException.class, () -> PosixMode.requireOwnerOnly(tmp.resolve("absent-token")));
        assertThrows(BootstrapException.class, () -> PosixMode.requireBootstrap(tmp.resolve("absent-boot")));
        assertThrows(BootstrapException.class, () -> PosixMode.requirePrivateDir(tmp.resolve("absent-dir")));
        assertTrue(assertThrows(BootstrapException.class, () -> TokenFiles.readCapped(tmp.resolve("absent-bytes"), 8))
                .getMessage().contains("does not exist"));
        var locked = tmp.resolve("locked-bytes");
        Files.writeString(locked, "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            var unusable = assertThrows(BootstrapException.class, () -> TokenFiles.readCapped(locked, 8));
            assertTrue(unusable.getMessage().contains("not a usable"), unusable.getMessage());
        } finally {
            Files.setPosixFilePermissions(locked, OWNER_RW);
        }
        assertThrows(BootstrapException.class, () -> AtomicFiles.write(Path.of("/"), new byte[] { 1 }, PosixMode.OWNER_RW));
        var blocked = Files.createDirectory(tmp.resolve("blocked"));
        Files.writeString(blocked.resolve("keep"), "k");
        assertThrows(java.io.IOException.class,
                () -> AtomicFiles.write(blocked, "x".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        PosixMode.OWNER_RW));

        var pid = tmp.resolve("edges.pid");
        long me = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        ServiceStatus.write(pid, me, started, "data", null, "connected", -3, -1);
        assertTrue(Files.readString(ServiceStatus.beside(pid)).contains("config-version=0"));
        assertFalse(Files.readString(ServiceStatus.beside(pid)).contains("data-role="));
        Files.writeString(ServiceStatus.beside(pid),
                "pid=" + me + "\r\nstart=" + started + "\r\n# note\r\n\r\nrole=data\r\ndata-role=nope\r\n"
                        + "connection=leader\r\nconfig-version=-2\r\ndrain-ms=-1\r\nextra=1\r\n");
        assertTrue(ServiceStatus.runningSuffix(pid, me, started).contains("connection=leader"));
        assertEquals(ServiceStatus.NO_BUDGET, ServiceStatus.drainBudget(pid, me, started));
        Files.writeString(ServiceStatus.beside(pid), " \n");
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
        Files.writeString(ServiceStatus.beside(pid), "pid=no\nstart=" + started + "\nrole=data\n");
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
        Files.writeString(ServiceStatus.beside(pid), "nope\n");
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
        Files.write(ServiceStatus.beside(pid), new byte[5000]);
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
        Files.delete(ServiceStatus.beside(pid));
        Files.createDirectory(ServiceStatus.beside(pid));
        assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
        Files.delete(ServiceStatus.beside(pid));
        ServiceStatus.deleteIfMatch(null, me, started);

        var enrollment = NodeEnrollment.open(boot, pid, 1);
        var join = boot.join();
        try {
            assertNull(enrollment.binding());
            assertSame(boot, enrollment.bootstrap());
            assertThrows(IllegalArgumentException.class, () -> enrollment.attach(null));
            enrollment.tokenConsumed();
            assertNull(boot.join());
            assertTrue(join.discarded());
            assertTrue(join.memoryCleared());
            assertThrows(IllegalStateException.class, join::copySecret);
        } finally {
            enrollment.close();
        }

        Files.writeString(fix.boot, baseData(fix).replace(fix.token.toString(), tmp.resolve("gone").toString()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertNull(Bootstrap.load(fix.boot, "data").join());

        Files.writeString(fix.state.resolve("node.id"), "node-id=gw-1\nrole=data\ndata-role=edge\n");
        Files.setPosixFilePermissions(fix.state.resolve("node.id"), OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("does not match"));
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "DATA")).getMessage()
                .contains("rejected"));
        Files.delete(fix.state.resolve("node.id"));
        Files.writeString(fix.pin, "test-root-public-key\n");

        writeToken(fix.token, "token-id=node-1\r\nsecret=" + SECRET + "\r\n");
        Files.writeString(fix.boot, "# note\n\n" + baseData(fix) + "  password = " + SECRET + "\n");
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertFalse(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains(SECRET));
        for (var key : java.util.List.of("token", "join-secret")) {
            Files.writeString(fix.boot, baseData(fix) + key + "=" + SECRET + "\n");
            Files.setPosixFilePermissions(fix.boot, OWNER_RW);
            assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                    .contains("must not contain a secret"));
        }
        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);

        writeToken(fix.token, "token-id=node-1\nsecret=" + SECRET + "\ntoken-id=node-1\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("duplicate token-id"));
        writeToken(fix.token, "bad key=1\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("invalid token file key"));
        writeToken(fix.token, "secret=" + "a".repeat(513) + "\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("16..512"));
        writeToken(fix.token, "\n# c\nsecret=" + SECRET + "\n");
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("requires token-id"));

        Files.writeString(fix.boot, baseData(fix).replace("pin=" + fix.pin, "pin=" + fix.boot.toAbsolutePath()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("different file"));
        var nested = fix.state.resolve("inner-boot");
        Files.writeString(nested, baseData(fix));
        Files.setPosixFilePermissions(nested, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(nested, "data")).getMessage()
                .contains("outside state-dir"));
        Files.writeString(fix.boot, baseData(fix).replace(fix.token.toString(), fix.pin.toString()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("not the pin"));
        writeToken(fix.token, "token-id=node-1\nsecret=" + SECRET + "\n");
        Files.writeString(fix.pin, "");
        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("is empty"));

        var fileState = tmp.resolve("file-state");
        Files.writeString(fileState, "x");
        Files.writeString(fix.boot, baseData(fix).replace(fix.state.toString(), fileState.toString()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("not a directory"));
        var link = tmp.resolve("state-link");
        Files.createSymbolicLink(link, fix.state);
        Files.writeString(fix.boot, baseData(fix).replace(fix.state.toString(), link.toString()));
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("not a directory"));

        var nul = (baseData(fix) + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(fix.boot, nul);
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        assertTrue(assertThrows(BootstrapException.class, () -> Bootstrap.load(fix.boot, "data")).getMessage()
                .contains("NUL"));

        var optional = dataTree();
        Files.writeString(optional.boot, baseData(optional).replace("listen=10.1.0.1:9443\n", "listen=\n")
                .replace("capabilities=http1,proxy\n", "capabilities=\n").replace("peers=10.0.0.3:9443\n", "peers=\n"));
        Files.setPosixFilePermissions(optional.boot, OWNER_RW);
        var blank = Bootstrap.load(optional.boot, "data");
        assertNull(blank.listen());
        assertTrue(blank.capabilities().isEmpty());
        assertTrue(blank.peers().isEmpty());
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=10.0.0.3:9443/x"), "host:port");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=10.0.0.3:9443?x"), "host:port");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=10.0.0.3:9443#x"), "host:port");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=10.0.0.3: 9443"), "host:port");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=a:b:80"), "host:port");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=h..h:80"), "invalid host");
        assertRejected(baseData(dataTree()).replace("peers=10.0.0.3:9443", "peers=host:abc"), "invalid port");
        assertRejected(baseData(dataTree()).replace("listen=10.1.0.1:9443", "listen=[::1]:0"), "invalid port");
        assertRejected(baseData(dataTree()).replace("listen=10.1.0.1:9443", "listen=[abcd]:80"), "ipv6");
        assertRejected(baseData(dataTree()).replace("state-dir=" + fix.state, "state-dir="), "requires state-dir");
    }

    private int rejected;

    private void assertRejected(String text, String fragment) throws Exception {
        var boot = tmp.resolve("bad-" + (++rejected));
        Files.writeString(boot, text);
        Files.setPosixFilePermissions(boot, OWNER_RW);
        var error = assertThrows(BootstrapException.class, () -> Bootstrap.load(boot, null));
        assertTrue(error.getMessage().contains(fragment), error.getMessage());
        assertFalse(error.getMessage().contains(SECRET), error.getMessage());
    }

    private String controlText(Fix fix, String extra) throws Exception {
        return "role=control\nnode-id=cp-1\npin=" + fix.pin + "\njoin-token=" + fix.token + "\nstate-dir=" + fix.state
                + "\nlisten=[::1]:9443\n" + extra;
    }

    private Fix dataTree() throws Exception {
        var fix = new Fix(tmp);
        Files.writeString(fix.pin, "test-root-public-key\n");
        writeToken(fix.token, "token-id=node-1\n# comment\nsecret=" + SECRET + "\n");
        Files.writeString(fix.boot, baseData(fix));
        Files.setPosixFilePermissions(fix.boot, PosixFilePermissions.fromString("rw-r-----"));
        return fix;
    }

    private String baseData(Fix fix) {
        return "role=data\ndata-role=gateway\nnode-id=gw-1\ncontrol-plane=10.0.0.2:9443\nlisten=10.1.0.1:9443\n"
                + "peers=10.0.0.3:9443\ncapabilities=http1,proxy\nlabel.track=canary\npin=" + fix.pin
                + "\njoin-token=" + fix.token + "\nstate-dir=" + fix.state + "\n";
    }

    private Fix controlTree(String peers) throws Exception {
        var fix = new Fix(tmp.resolve("control"));
        Files.createDirectories(fix.root);
        Files.writeString(fix.pin, "root-key\n");
        writeToken(fix.token, "token-id=cp-1\nsecret=" + SECRET + "\n");
        var peersLine = peers.isEmpty() ? "" : "peers=" + peers + "\n";
        Files.writeString(fix.boot, "role=control\nnode-id=cp-1\npin=" + fix.pin + "\njoin-token=" + fix.token
                + "\nstate-dir=" + fix.state + "\nlisten=[::1]:9443\n" + peersLine);
        Files.setPosixFilePermissions(fix.boot, OWNER_RW);
        return fix;
    }

    private static void writeToken(Path path, String text) throws Exception {
        Files.writeString(path, text);
        Files.setPosixFilePermissions(path, OWNER_RW);
    }

    private record Fix(Path root, Path boot, Path pin, Path token, Path state) {
        Fix(Path root) {
            this(root, root.resolve("bootstrap"), root.resolve("root.pub"), root.resolve("join.token"),
                    root.resolve("state"));
        }
    }
}
