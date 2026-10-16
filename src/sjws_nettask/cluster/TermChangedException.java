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
 * A conditional {@link ClusterNode#append(GroupId, java.util.List, Durability, long)} fails with this when
 * this node still leads the group but in a term other than the one the caller expected (it was deposed
 * and re-elected in between). Nothing was appended. A node that no longer leads fails with
 * {@link NotLeaderException} instead.
 */
public final class TermChangedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final long expectedTerm;
    private final long currentTerm;

    TermChangedException(GroupId group, long expectedTerm, long currentTerm) {
        super("group " + group.value() + ": expected term " + expectedTerm + ", now " + currentTerm
                + "; nothing appended");
        this.expectedTerm = expectedTerm;
        this.currentTerm = currentTerm;
    }

    /** The term the caller passed. */
    public long expectedTerm() {
        return expectedTerm;
    }

    /** This node's term when the append was refused. */
    public long currentTerm() {
        return currentTerm;
    }
}
