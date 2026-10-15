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
 * The {@code append()} future fails with this because this group's committed entries are not being applied fast enough: the
 * delivery queue (callbacks plus future completions) reached its limit. Retry later; nothing was appended.
 */
public final class ApplyLagException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final long lag;

    ApplyLagException(GroupId group, long lag, int limit) {
        super("group " + group.value() + " apply lags: " + lag + " deliveries pending (limit " + limit + ")");
        this.lag = lag;
    }

    /** Deliveries pending when the append was refused. */
    public long lag() {
        return lag;
    }
}
