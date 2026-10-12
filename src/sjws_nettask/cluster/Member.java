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

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * One member of the static seed list. A witness votes and does not store a replicated log;
 * that storage rule is enforced by the replication group, not by the failure detector.
 */
public record Member(NodeId id, SiteId site, InetSocketAddress address, long incarnation, MemberState state,
        boolean witness) {

    public Member {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(state, "state");
        if (incarnation < 0) {
            throw new IllegalArgumentException("incarnation");
        }
        if (address.getPort() == 0) {
            throw new IllegalArgumentException("address port");
        }
    }
}
