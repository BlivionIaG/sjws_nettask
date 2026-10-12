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
package sjws_nettask.core;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;

/** Counts concurrent connections per client address and refuses those over a fixed limit. Thread-safe. */
public final class PerIpLimit {

    private final int max;
    private final ConcurrentHashMap<InetAddress, Integer> counts = new ConcurrentHashMap<>();

    /** @param max connections allowed per address (at least 1) */
    public PerIpLimit(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be positive: " + max);
        }
        this.max = max;
    }

    /** @return true if {@code address} had fewer than {@code max} connections; it now has one more */
    public boolean tryAcquire(InetAddress address) {
        var granted = new boolean[1];
        this.counts.compute(address, (a, n) -> {
            int current = n == null ? 0 : n;
            if (current >= this.max) {
                return n;
            }
            granted[0] = true;
            return current + 1;
        });
        return granted[0];
    }

    /** Ends one connection previously granted by {@link #tryAcquire}. */
    public void release(InetAddress address) {
        this.counts.computeIfPresent(address, (a, n) -> n <= 1 ? null : n - 1);
    }

    /** @return current connections from {@code address} */
    public int count(InetAddress address) {
        return this.counts.getOrDefault(address, 0);
    }

    /** @return addresses with at least one connection (entries are removed at zero) */
    public int addresses() {
        return this.counts.size();
    }
}
