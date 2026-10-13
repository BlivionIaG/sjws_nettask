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
package sjws_nettask.clusterharness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.service.Bootstrap;
import sjws_nettask.service.NodeEnrollment;
import sjws_nettask.service.ServiceLaunch;
import sjws_nettask.service.ServiceStatus;

/** Pending bootstrap files, and the refusal to pretend a data node has joined. */
class NodeFixturesTest {

    @TempDir
    Path root;

    @AfterEach
    void clearEnrollment() {
        var current = NodeEnrollment.current();
        if (current != null) {
            current.close();
        }
    }

    @Test
    void pendingDataNodeReportsConnectionPending() throws Exception {
        var node = NodeFixtures.pending(root, "data");
        assertEquals(root.toAbsolutePath().resolve("state"), node.stateDir());
        assertEquals(root.toAbsolutePath().resolve("bootstrap"), node.bootstrap());
        assertFalse(node.pin().startsWith(node.stateDir()));
        assertFalse(node.bootstrap().startsWith(node.stateDir()));
        assertFalse(Files.exists(node.stateDir()), "state dir is created on load, not by the fixture");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(node.bootstrap()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(node.joinToken()));
        assertFalse(Files.readString(node.bootstrap()).contains(NodeFixtures.JOIN_SECRET));

        var loaded = Bootstrap.load(node.bootstrap(), "data");
        assertEquals("gw-1", loaded.nodeId());
        assertEquals("gateway", loaded.dataRole().wire());
        assertEquals(node.stateDir(), loaded.stateDir());
        assertTrue(loaded.startupLine().contains("connection=pending"), loaded.startupLine());
        assertTrue(loaded.startupLine().contains("config-version=0"), loaded.startupLine());

        var html = Files.createDirectory(root.resolve("html"));
        var config = ServerConfig.builder(html).port(0).h3Enabled(false).build();
        var hooks = ServiceLaunch.prepare(node.bootstrap(), "data");
        var pid = root.resolve("node.pid");
        try (var server = SjwsServer.start(config)) {
            hooks.onListening(server, pid, Duration.ofSeconds(1));
            try {
                var status = Files.readString(ServiceStatus.beside(pid));
                assertTrue(status.contains("role=data"), status);
                assertTrue(status.contains("data-role=gateway"), status);
                assertTrue(status.contains("connection=pending"), status);
                assertTrue(status.contains("config-version=0"), status);
                assertFalse(status.contains(NodeFixtures.JOIN_SECRET), status);
                assertNull(NodeEnrollment.current().binding());
                assertTrue(server.tcpPort() > 0);
            } finally {
                hooks.onShutdown();
            }
        }
        assertNull(NodeEnrollment.current());
        assertFalse(Files.exists(ServiceStatus.beside(pid)));
    }

    @Test
    void pendingControlFileNamesTheVoter() throws Exception {
        var cp = NodeFixtures.pending(root.resolve("cp"), "control");
        var loaded = Bootstrap.load(cp.bootstrap(), "control");
        assertEquals("cp-1", loaded.nodeId());
        assertNull(loaded.dataRole());
        assertEquals("127.0.0.1:9443", loaded.listen());
        assertTrue(loaded.startupLine().contains("connection=pending"), loaded.startupLine());
        assertEquals(cp.stateDir(), loaded.stateDir());
        assertTrue(cp.stateDir().startsWith(root.toAbsolutePath()));
    }

    @Test
    void controlPlaneScenariosStayPendingWithoutVoters() {
        assertFalse(new PendingControlPlane().available());
        assertTrue(NodeFixtures.dataRoleCanJoin());
        assertTrue(NodeFixtures.VOTERS_PENDING.startsWith("TODO"));
        assertTrue(NodeFixtures.VOTERS_PENDING.contains(ControlPlaneDesign.PR));
        assertNull(NodeEnrollment.current());
        assertThrows(IllegalArgumentException.class, () -> NodeFixtures.pending(root, "standalone"));
    }
}
