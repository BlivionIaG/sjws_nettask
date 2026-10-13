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
package sjws_nettask.controlplane;

import java.util.EnumSet;
import java.util.Set;

/** Fixed roles. Anything not listed is denied. */
public enum Role {
    VIEWER(EnumSet.of(Permission.READ)),
    OPERATOR(EnumSet.of(Permission.READ, Permission.EDIT_ROUTES, Permission.ROLLOUT, Permission.ROLLBACK,
            Permission.NODE_DRAIN)),
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(EnumSet<Permission> permissions) {
        this.permissions = java.util.Collections.unmodifiableSet(permissions);
    }

    public Set<Permission> permissions() {
        return this.permissions;
    }

    public boolean allows(Permission p) {
        return this.permissions.contains(p);
    }
}
