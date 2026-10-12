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
package sjws_nettask.routing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global backstop for proxied body bytes held in memory (security finding F5).
 *
 * <p>Every proxy exchange counts the bytes it read from an upstream and has not yet handed to
 * the client writer. Each exchange is already bounded by its read-ahead window
 * ({@link UpstreamPool#READ_AHEAD}). On top of that, while {@link #exhausted()} is true, event
 * loops stop reading ahead: autoRead is off and they do not call {@code read()}. An exchange then
 * reads again only once its own queue is empty. So the total stays near {@link #limit()}: it can
 * exceed it by at most about one socket read per active exchange, and never by a whole response.
 * Slow clients therefore wait (TCP backpressure to the upstream) instead of growing the heap.
 *
 * <p>The default is 64 MiB, or {@code -Dsjws.proxy.maxInflightBytes=<bytes>}. One instance is
 * shared by all upstream pools in the process ({@link #shared()}). Tests make their own.
 */
public final class ByteBudget {
    private static final ByteBudget SHARED = new ByteBudget(Long.getLong("sjws.proxy.maxInflightBytes", 64L << 20));

    private final long limit;
    private final AtomicLong inFlight = new AtomicLong();

    public ByteBudget(long limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        this.limit = limit;
    }

    public static ByteBudget shared() {
        return SHARED;
    }

    public long limit() {
        return this.limit;
    }

    /** Bytes read from upstreams and not yet taken by a client writer (the F5 regression-test hook). */
    public long inFlight() {
        return this.inFlight.get();
    }

    boolean exhausted() {
        return this.inFlight.get() >= this.limit;
    }

    void add(long bytes) {
        this.inFlight.addAndGet(bytes);
    }

    void release(long bytes) {
        this.inFlight.addAndGet(-bytes);
    }
}
