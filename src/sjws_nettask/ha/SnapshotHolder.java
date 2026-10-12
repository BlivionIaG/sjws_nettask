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
package sjws_nettask.ha;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Atomic snapshot swap. {@link #current()} is one volatile read; {@link #publish(Snapshot)}
 * replaces the reference and never blocks a reader. A generation older than the one already
 * published is rejected so a stale sync cannot roll the node backwards.
 */
public final class SnapshotHolder<T> {

    private final AtomicReference<Snapshot<T>> ref = new AtomicReference<>();

    public Snapshot<T> current() {
        return this.ref.get();
    }

    /**
     * @return the snapshot that was current before this publish (null on the first one)
     */
    public Snapshot<T> publish(Snapshot<T> next) {
        Objects.requireNonNull(next, "snapshot");
        while (true) {
            var cur = this.ref.get();
            if (cur != null && next.generation() < cur.generation()) {
                throw new IllegalArgumentException("generation " + next.generation()
                        + " is older than " + cur.generation());
            }
            if (this.ref.compareAndSet(cur, next)) {
                return cur;
            }
        }
    }
}
