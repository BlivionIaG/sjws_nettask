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

/**
 * One immutable generation of serving state. Readers keep the reference they loaded;
 * a newer generation is a different instance, published by {@link SnapshotHolder}.
 *
 * @param <T> the node-local value (the HTTP consumer stores the {@code Handler} for this generation)
 */
public final class Snapshot<T> {

    private final long generation;
    private final long contentHash;
    private final T value;

    public Snapshot(long generation, long contentHash, T value) {
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be positive: " + generation);
        }
        this.generation = generation;
        this.contentHash = contentHash;
        this.value = Objects.requireNonNull(value, "value");
    }

    public long generation() {
        return this.generation;
    }

    public long contentHash() {
        return this.contentHash;
    }

    public T value() {
        return this.value;
    }
}
