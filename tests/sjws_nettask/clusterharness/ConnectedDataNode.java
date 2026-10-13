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

import sjws_nettask.clusterharness.ClusterHarness.DataRole;

/**
 * One data-role node ({@link DataRole#DATA}) joined to an in-process control plane.
 * {@link NodeFixtures#connected(java.nio.file.Path)} is the only constructor, and it throws
 * {@link ControlPlanePendingException} until a watch client exists. These methods throw the
 * same way so a caller cannot observe {@code connection=connected} without that client.
 */
public final class ConnectedDataNode implements AutoCloseable {

    ConnectedDataNode() {
        throw new ControlPlanePendingException(NodeFixtures.CONNECTED_PENDING);
    }

    /** Always {@link DataRole#DATA}. */
    public DataRole role() {
        throw new ControlPlanePendingException(NodeFixtures.CONNECTED_PENDING);
    }

    /** {@code connected} once the watch is up. Not {@code pending}. */
    public String connection() {
        throw new ControlPlanePendingException(NodeFixtures.CONNECTED_PENDING);
    }

    /** Applied desired-config version. {@code 0} before the first push lands. */
    public long configVersion() {
        throw new ControlPlanePendingException(NodeFixtures.CONNECTED_PENDING);
    }

    /** Content listener. Ephemeral, assigned by the kernel. */
    public int httpPort() {
        throw new ControlPlanePendingException(NodeFixtures.CONNECTED_PENDING);
    }

    /**
     * Validate, propose, and activate canonical YAML on the live control plane.
     * Returns the version the data node has applied.
     */
    public long push(String canonicalYaml) {
        throw new ControlPlanePendingException(NodeFixtures.CONNECTED_PENDING);
    }

    @Override
    public void close() {
        // Not started. A real node closes its server and the control plane here.
    }
}
