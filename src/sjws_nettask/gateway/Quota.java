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
package sjws_nettask.gateway;

import java.net.InetAddress;
import sjws_nettask.core.MiddlewareSpec;

/**
 * One unit of admission for a rate-limit key. The request path calls {@link #tryAcquire} and
 * nothing else. An implementation must not block and must not do I/O: a cluster quota refills a
 * local remainder on another thread and only reads that remainder here.
 */
public interface Quota {

    /**
     * @param key partition already chosen by the filter (ip, header value, or request target).
     *        Null and empty mean "no identity" and share one bucket.
     * @return false when the quota is exhausted
     */
    boolean tryAcquire(String key);

    /**
     * Seconds a refused caller may wait. Meaningful after {@link #tryAcquire} returned false
     * for the same key. The default is a one-second hint.
     */
    default int retryAfterSeconds(String key) {
        return 1;
    }

    /**
     * Admission for a client address. The default encodes the address and calls {@link #tryAcquire}.
     * The striped table overrides this so an address is hashed without copying it to a string.
     */
    default boolean tryAcquireAddress(InetAddress address) {
        return tryAcquire(address == null ? null : address.getHostAddress());
    }

    /** Same hint as {@link #retryAfterSeconds} for an address key. */
    default int retryAfterAddress(InetAddress address) {
        return retryAfterSeconds(address == null ? null : address.getHostAddress());
    }

    /** Builds the quota for one filter entry. The striped GCRA table is the default. */
    @FunctionalInterface
    interface Factory {

        Quota create(MiddlewareSpec spec);
    }
}
