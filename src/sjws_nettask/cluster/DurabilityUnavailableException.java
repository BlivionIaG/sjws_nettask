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
 * A STRICT override append fails with this when the leader has not heard from enough data voters
 * within the last lease to store it on {@code dataCopiesRequired()} disks (for example a
 * two-data-plus-witness group with one data node down). Nothing was appended, so later RELAXED
 * appends are not held behind it. Retry when the data node is back, or append RELAXED.
 */
public final class DurabilityUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final int reachable;
    private final int required;

    DurabilityUnavailableException(GroupId group, int reachable, int required) {
        super("group " + group.value() + ": STRICT needs " + required + " data copies, " + reachable
                + " reachable; nothing appended");
        this.reachable = reachable;
        this.required = required;
    }

    /** Data copies (the leader included) heard from within the last lease. */
    public int reachable() {
        return reachable;
    }

    /** Data copies a STRICT entry needs in this group. */
    public int required() {
        return required;
    }
}
