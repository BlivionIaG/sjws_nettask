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
package sjws_nettask.service;

/** Data-plane projection kind. Meaningful only when the process role is {@link NodeRole#DATA}. */
public enum DataPlaneRole {
    GATEWAY("gateway"),
    STATIC("static"),
    BROKER("broker"),
    EDGE("edge");

    private final String wire;

    DataPlaneRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return this.wire;
    }

    /** @return null when {@code raw} is not a data-plane role */
    public static DataPlaneRole parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (var role : values()) {
            if (role.wire.equals(raw)) {
                return role;
            }
        }
        return null;
    }
}
