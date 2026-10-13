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

/**
 * Cluster member identity. {@code wire} is the 16-bit id carried in heartbeats.
 * Zero is reserved for "no suspect" in the packet, so ids start at 1.
 */
public record NodeId(int wire, String name) {

    public NodeId {
        if (wire < 1 || wire > 0xFFFF) {
            throw new IllegalArgumentException("wire id must be 1..65535");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name");
        }
    }
}
