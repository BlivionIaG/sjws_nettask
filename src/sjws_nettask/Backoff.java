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
package sjws_nettask;

/**
 * Exponential back-off used by the accept loop after errors such as EMFILE.
 */
final class Backoff {

    private final long initialMs;
    private final long maxMs;
    private long nextMs;

    Backoff(long initialMs, long maxMs) {
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.nextMs = initialMs;
    }

    /** @return the delay to wait now; subsequent calls double it up to the maximum */
    long next() {
        long delay = this.nextMs;
        this.nextMs = Math.min(this.maxMs, this.nextMs * 2);
        return delay;
    }

    void reset() {
        this.nextMs = this.initialMs;
    }

    /** @return true if no error has been recorded since the last reset */
    boolean idle() {
        return this.nextMs == this.initialMs;
    }
}
