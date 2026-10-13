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

import java.time.Duration;

/** Monotonic time source. Tests supply a fake; production uses {@link #SYSTEM}. */
public interface Clock {

    long nanoTime();

    /**
     * Wall-clock millis for heartbeat authentication. The monotonic clock is not comparable
     * across hosts; this value is. Production uses {@link System#currentTimeMillis()}.
     */
    default long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    void sleep(Duration duration) throws InterruptedException;

    Clock SYSTEM = new Clock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            if (duration.isZero() || duration.isNegative()) {
                return;
            }
            Thread.sleep(duration);
        }
    };
}
