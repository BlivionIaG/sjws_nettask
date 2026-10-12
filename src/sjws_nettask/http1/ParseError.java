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
package sjws_nettask.http1;

/**
 * A request head that must be answered with an error status (400, 414, 417,
 * 431, 501, 505). The connection is always closed afterwards, because the
 * stream position can no longer be trusted.
 */
public final class ParseError extends Exception {

    private final int status;

    public ParseError(int status, String reason) {
        super(reason, null, false, false);
        this.status = status;
    }

    public int status() {
        return this.status;
    }
}
