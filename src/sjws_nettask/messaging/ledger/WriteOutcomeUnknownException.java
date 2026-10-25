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
 * The replicated log accepted this write, but whether it commits is unknown: the leader lost leadership, failed
 * closed or closed before it committed (the consensus layer's outcome-unknown error), or the ack did not arrive in
 * time. Unlike {@link LeaderUnavailableException} (nothing appended, safe to resend to the leader), resending the
 * same body can duplicate it, so HTTP maps it to a 503 with the leader as a hint and never to a 307 (#254). Callers
 * retry only with their own deduplication, or read back first.
 */
public final class WriteOutcomeUnknownException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final long term;
    private final String leaderName;
    private final String leaderUrl;

    public WriteOutcomeUnknownException(String message, long term, String leaderName, String leaderUrl, Throwable cause) {
        super(message, cause, false, false);
        this.term = term;
        this.leaderName = leaderName;
        this.leaderUrl = leaderUrl;
    }

    public long term() { return term; }

    /** Null when unknown. */
    public String leaderName() { return leaderName; }

    /** Null when unknown or this node. */
    public String leaderUrl() { return leaderUrl; }
}
