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
package sjws_nettask.h3;

/**
 * Minimum average send rate, checked once per window (security review M1): a peer that keeps a
 * response alive by extending its flow-control window just before the write timeout ("drip
 * reading") would otherwise hold a file slot forever. Not thread-safe (one per response).
 */
final class RateGuard {

    private final long windowNanos;
    private final long minBytesPerSecond;
    private long windowStart;
    private long windowStartBytes;

    /**
     * @param windowNanos how long a rate is averaged over before it is judged
     * @param minBytesPerSecond 0 disables the check
     * @param now {@link System#nanoTime()} when sending starts
     */
    RateGuard(long windowNanos, long minBytesPerSecond, long now) {
        this.windowNanos = windowNanos;
        this.minBytesPerSecond = minBytesPerSecond;
        this.windowStart = now;
    }

    /**
     * @param sentBytes bytes accepted by the stream so far
     * @return false if the last full window was slower than the minimum rate
     */
    boolean ok(long sentBytes, long now) {
        long elapsed = now - this.windowStart;
        if (this.minBytesPerSecond <= 0 || elapsed < this.windowNanos) {
            return true;
        }
        double required = this.minBytesPerSecond * (elapsed / 1e9);
        if (sentBytes - this.windowStartBytes < required) {
            return false;
        }
        this.windowStart = now;
        this.windowStartBytes = sentBytes;
        return true;
    }
}
