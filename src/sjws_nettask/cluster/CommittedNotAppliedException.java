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
 * The entry behind this {@code append()} future <b>is committed</b> (it is in the replicated log and will be applied
 * on every replica, including this one when it reopens and replays), but this node closed before applying it
 * locally. Not a failure of the write: do not retry it as if it had not landed. Distinct from
 * {@link NotLeaderException} and {@link ApplyLagException}, which mean nothing was appended, and from
 * {@link OutcomeUnknownException}, which means it was appended and may or may not commit.
 */
public final class CommittedNotAppliedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String group;
    private final long index;

    public CommittedNotAppliedException(String group, long index) {
        super("group " + group + ": committed at index " + index + ", not yet applied (node closing); outcome is"
                + " committed");
        this.group = group;
        this.index = index;
    }

    /** Group id value. */
    public String group() {
        return group;
    }

    /** Log index of the committed entry. */
    public long index() {
        return index;
    }
}
