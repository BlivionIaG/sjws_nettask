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
 * The replicated log refused this write before it entered the log: nothing was appended, so resending the same
 * body cannot duplicate it (#254). {@link #retryable()}: a transient refusal (apply backpressure, too few data
 * copies reachable for STRICT, a storage error whose batch was rolled back cleanly) that may succeed later; otherwise
 * the request itself is unacceptable (empty or oversized batch). A refusal for not leading is
 * {@link LeaderUnavailableException} instead, which carries the redirect target.
 */
public final class WriteRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final boolean retryable;

    public WriteRefusedException(String message, boolean retryable, Throwable cause) {
        super(message, cause, false, false);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
