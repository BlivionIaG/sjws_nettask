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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.clusterharness.ClusterHarness.DataRole;

/**
 * Data-role node joined to a live control plane. Disabled until
 * {@link NodeFixtures#dataRoleCanJoin()} is true. Enabling this against
 * {@link PendingControlPlane}, or by attaching a binding in the test, is a fake connection.
 */
@Tag("cluster")
class ConnectedDataNodeTest {

    @TempDir
    Path root;

    @Test
    @Disabled(NodeFixtures.CONNECTED_PENDING)
    void dataNodeReportsConnectedAndAppliesPushedVersion() throws Exception {
        try (var node = NodeFixtures.connected(root)) {
            assertEquals(DataRole.DATA, node.role());
            assertEquals("connected", node.connection());
            assertTrue(node.httpPort() > 0);
            long version = node.push("schema: 1\nserver:\n  port: 0\n");
            assertTrue(version > 0L);
            assertEquals(version, node.configVersion());
            assertEquals("connected", node.connection());
        }
    }
}
