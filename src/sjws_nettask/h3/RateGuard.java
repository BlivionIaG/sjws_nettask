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

import java.util.concurrent.locks.ReentrantLock;

/**
 * Average send rate of one connection, across all its responses, judged once per window (security
 * review M1, verification V5). A peer that keeps responses alive by extending its flow-control window
 * just before the write timeout ("drip reading") would otherwise hold file slots forever; measuring
 * the connection rather than each stream keeps many parallel slow downloads (a browser on a slow link)
 * from being cut. The writer acts on a slow verdict only while file slots are contended. Windows
 * restart whenever the connection goes from no active response to one. Thread-safe.
 */
final class RateGuard {

    private final ReentrantLock lock = new ReentrantLock();
    private final long windowNanos;
    private final long minBytesPerSecond;
    private long total;
    private int active;
    private long windowStart;
    private long windowStartBytes;
    private boolean slow;

    /**
     * @param windowNanos how long a rate is averaged over before it is judged
     * @param minBytesPerSecond 0 disables the check
     */
    RateGuard(long windowNanos, long minBytesPerSecond) {
        this.windowNanos = windowNanos;
        this.minBytesPerSecond = minBytesPerSecond;
    }

    /** A response starts sending at {@code now}. */
    void begin(long now) {
        this.lock.lock();
        try {
            if (this.active++ == 0) { // idle time doesn't count
                this.windowStart = now;
                this.windowStartBytes = this.total;
                this.slow = false;
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** A response stopped sending. */
    void end() {
        this.lock.lock();
        try {
            this.active--;
        } finally {
            this.lock.unlock();
        }
    }

    /** {@code bytes} more were taken by the peer. */
    void sent(long bytes) {
        this.lock.lock();
        try {
            this.total += bytes;
        } finally {
            this.lock.unlock();
        }
    }

    /** @return false if the last complete window of this connection was slower than the minimum */
    boolean ok(long now) {
        if (this.minBytesPerSecond <= 0) {
            return true;
        }
        this.lock.lock();
        try {
            long elapsed = now - this.windowStart;
            if (elapsed >= this.windowNanos) {
                this.slow = this.total - this.windowStartBytes < this.minBytesPerSecond * (elapsed / 1e9);
                this.windowStart = now;
                this.windowStartBytes = this.total;
            }
            return !this.slow;
        } finally {
            this.lock.unlock();
        }
    }
}
