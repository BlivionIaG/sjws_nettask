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

/**
 * Control-plane connection as {@code --status} prints it. Values that are not in this set are
 * reported as {@code disconnected}, so a bad binding cannot put arbitrary text on the status line.
 */
public enum ConnectionState {
    PENDING("pending"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    LEADER("leader"),
    FOLLOWER("follower");

    private final String wire;

    ConnectionState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return this.wire;
    }

    /** A known wire value, or {@code disconnected} when {@code raw} is not one. */
    public static String canonical(String raw) {
        if (raw == null) {
            return DISCONNECTED.wire;
        }
        for (var state : values()) {
            if (state.wire.equals(raw)) {
                return state.wire;
            }
        }
        return DISCONNECTED.wire;
    }
}
