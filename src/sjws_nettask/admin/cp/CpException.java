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
package sjws_nettask.admin.cp;

/** A control-plane call failed. */
public final class CpException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** endpoint unreachable: try another one */
        UNAVAILABLE,
        /** a follower refused a write; {@link #leaderHint()} may name the leader */
        NOT_LEADER,
        /** the head moved since the draft's base version */
        CONFLICT,
        INVALID, FORBIDDEN, NOT_FOUND
    }

    private final Kind kind;
    private final String leaderHint;

    public CpException(Kind kind, String message) {
        this(kind, message, null);
    }

    public CpException(Kind kind, String message, String leaderHint) {
        super(kind + ": " + message);
        this.kind = kind;
        this.leaderHint = leaderHint;
    }

    public Kind kind() {
        return this.kind;
    }

    public String leaderHint() {
        return this.leaderHint;
    }
}
