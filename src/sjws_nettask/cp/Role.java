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
package sjws_nettask.cp;

/** Admin role. {@link #VIEW} is the lowest. */
public enum Role {
    VIEW, OPERATE, ADMIN;

    public boolean atLeast(Role required) {
        return ordinal() >= required.ordinal();
    }

    public static Role parse(String raw) {
        return switch (raw) {
            case "view" -> VIEW;
            case "operate" -> OPERATE;
            case "admin" -> ADMIN;
            default -> throw new IllegalArgumentException("role");
        };
    }
}
