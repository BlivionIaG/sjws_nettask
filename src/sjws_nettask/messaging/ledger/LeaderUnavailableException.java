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
package sjws_nettask.messaging.ledger;

/**
 * This node cannot accept the operation because it is not the ready leader of the partition, or the request carried a
 * stale term. HTTP maps it to 307 to {@link #leaderUrl} when known, else 503 with Retry-After (sketch §6).
 */
public final class LeaderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final long term;
    private final String leaderName;
    private final String leaderUrl;

    public LeaderUnavailableException(String message, long term, String leaderName, String leaderUrl) {
        super(message, null, false, false);
        this.term = term;
        this.leaderName = leaderName;
        this.leaderUrl = leaderUrl;
    }

    public long term() { return term; }

    /** Null when unknown. */
    public String leaderName() { return leaderName; }

    /** Null when unknown. */
    public String leaderUrl() { return leaderUrl; }
}
