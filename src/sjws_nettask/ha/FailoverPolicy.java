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
package sjws_nettask.ha;

import java.util.function.IntSupplier;

/**
 * How traffic reaches a node. Active/active leaves steering to an external load balancer.
 * Active/passive claims a virtual address and still serves on the node's own address.
 */
public enum FailoverPolicy {
    ACTIVE_ACTIVE,
    ACTIVE_PASSIVE;

    public boolean claimsVip() {
        return this == ACTIVE_PASSIVE;
    }

    /**
     * Accept-loop gate. Passive mode installs a supplier that returns 0: the loop observes the
     * role and does not pause. Active/active installs nothing.
     */
    public IntSupplier acceptPause() {
        if (this == ACTIVE_ACTIVE) {
            return null;
        }
        return () -> 0;
    }
}
