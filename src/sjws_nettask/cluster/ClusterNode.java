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
package sjws_nettask.cluster;

import java.util.function.Consumer;

/**
 * Membership face of a cluster node. Replication ({@code append}, snapshots, leadership)
 * is added on this type by the lease-and-sync work; this package does not import HTTP.
 */
public interface ClusterNode extends AutoCloseable {

    NodeId self();

    MembershipView membership();

    void onMembershipChange(Consumer<MembershipView> listener);

    @Override
    void close();
}
