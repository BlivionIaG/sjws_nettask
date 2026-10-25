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
 * Refused: the group's node was closed ({@link ReplicatedNode#close()}), so nothing was appended (#254). A closed
 * group never leads or campaigns again; a caller retries on another node or after a restart. Unlike
 * {@link NotLeaderException} there is no leader to redirect to from here.
 */
public final class GroupClosedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public GroupClosedException(GroupId groupId) {
        super(groupId + ": node closed; nothing was appended");
    }
}
