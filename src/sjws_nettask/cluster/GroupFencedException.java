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

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Refused: the group failed closed earlier (a log write it could not roll back) and refuses every call until the
 * process restarts, so nothing was appended (#254). Other nodes can take over; this one cannot serve.
 */
public final class GroupFencedException extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    GroupFencedException(GroupId groupId, IOException cause) {
        super("group " + groupId.value() + " failed closed after a log write it could not roll back; restart to"
                + " recover from disk", cause);
    }
}
