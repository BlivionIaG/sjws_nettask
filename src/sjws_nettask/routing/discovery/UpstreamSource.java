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
package sjws_nettask.routing.discovery;

import java.util.function.Consumer;

/**
 * Where one service's endpoint sets come from. The request path calls {@link #current()} only
 * (one volatile read). {@link #update} is called by the control-plane watch thread (or once by
 * the config compiler for the static source); listeners run on that thread, after the swap.
 * <p>
 * Rules every implementation keeps:
 * <ul>
 * <li>full snapshots, versions monotonic per service: a set whose version is not greater than the
 *     last one seen is ignored;</li>
 * <li>{@code DEAD} endpoints are dropped; {@code SUSPECT} ones stay with weight 0;</li>
 * <li>an update that would leave the service empty keeps the last non-empty set (its version is
 *     still recorded, so an older non-empty set cannot come back). A service that has never had a
 *     non-empty set has {@code current() == null} and its routes answer 503.</li>
 * </ul>
 */
public interface UpstreamSource {

    /** @return the set in use, or null until the first non-empty one */
    UpstreamSet current();

    /**
     * Calls {@code listener} with each set that becomes current, on the updating thread. Does not
     * replay the current set. A listener that throws is logged and skipped.
     *
     * @return closing it removes the listener
     */
    AutoCloseable subscribe(Consumer<UpstreamSet> listener);

    /**
     * Offers a new full snapshot.
     *
     * @return true when it became current; false when it was stale, equal, or empty after dropping
     *         {@code DEAD} endpoints
     * @throws IllegalArgumentException when {@code set.service()} is not this source's service
     */
    boolean update(UpstreamSet set);
}
