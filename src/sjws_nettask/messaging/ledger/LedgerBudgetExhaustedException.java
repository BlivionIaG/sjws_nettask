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
 * The process-wide {@link LedgerWriterBudget} refused another writer (or refused materializing its
 * queue and direct buffer). Nothing was allocated for the refused step, so the caller can retry later
 * or free writers. HTTP maps this to 503 with Retry-After (#333).
 */
public final class LedgerBudgetExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LedgerBudgetExhaustedException(String message) {
        super(message, null, false, false);
    }
}
