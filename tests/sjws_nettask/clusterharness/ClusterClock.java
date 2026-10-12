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
package sjws_nettask.clusterharness;

import java.time.Duration;

/**
 * Clock the control plane must read for election, heartbeat, suspect, and injected delay.
 * {@link Manual} is the deterministic clock the fault scenarios advance. The system clock
 * refuses {@link #advance} so a test cannot pretend time moved.
 */
public interface ClusterClock {

    ClusterClock SYSTEM = new ClusterClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };

    long nanoTime();

    /** Move a {@link Manual} clock forward. The system clock refuses. */
    default void advance(Duration duration) {
        throw new UnsupportedOperationException("this clock does not advance");
    }

    /** Monotonic clock tests can step. Starts at zero. */
    final class Manual implements ClusterClock {
        private long nanos;

        public static Manual startingAtZero() {
            return new Manual();
        }

        @Override
        public long nanoTime() {
            return this.nanos;
        }

        @Override
        public void advance(Duration duration) {
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("advance by a non-negative duration");
            }
            this.nanos += duration.toNanos();
        }
    }
}
