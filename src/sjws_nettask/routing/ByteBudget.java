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
 * ({@link UpstreamPool#READ_AHEAD}, 256 KiB). On top of that, while {@link #exhausted()} is true,
 * event loops stop reading ahead: autoRead is off and they do not call {@code read()}. An
 * exchange then reads again only once its own queue is empty, so slow clients wait (TCP
 * backpressure to the upstream) instead of growing the heap.
 *
 * <p><b>Worst-case overshoot</b> (the budget is soft). A {@code read()} is one socket read
 * ({@code maxMessagesPerRead = 1}) of at most {@link UpstreamPool#MAX_READ} (128 KiB), or
 * {@link UpstreamPool#SMALL_READ} (16 KiB) once the budget is {@link #pressured()} (at least 3/4
 * used). Steady state under pressure is therefore at most {@code limit + active × 16 KiB}. The
 * transient bound, for reads already issued when pressure began, is
 * {@code limit + active × 128 KiB}. "Active" means proxied responses being streamed: HTTP/1
 * connections plus h2/h3 streams. At 2048 of them, that is 64 MiB + 32 MiB steady and
 * 64 MiB + 256 MiB transient.
 *
 * <p>A streamed body that is dropped without being written is closed by the filter chain
 * ({@link sjws_nettask.core.Body#discard}). As a backstop, the idle timer abandons a paused
 * exchange whose consumer has not taken a chunk for twice {@code idle_read_timeout}.
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

    /** At least three quarters used: upstream reads shrink to {@link UpstreamPool#SMALL_READ}. */
    boolean pressured() {
        return this.inFlight.get() >= this.limit - (this.limit >> 2);
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
