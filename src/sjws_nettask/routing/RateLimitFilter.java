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

import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Local rate limit for a route or the whole router (design §13). Keys are the client address,
 * a header value or the route itself, hashed into a fixed table of buckets: memory is fixed at
 * construction and never grows, whatever the number of distinct clients. Two keys that share a
 * stripe share a budget (stricter, never looser). Each bucket is one {@code AtomicLong} holding the
 * GCRA theoretical arrival time, so admission is a single CAS with no lock and no allocation.
 * A refused request gets 429 with {@code Retry-After}; nothing reaches the next handler.
 * <p>
 * The gateway {@code rate-limit} filter uses this same {@link GcraTable}. A cluster quota can
 * still replace that filter's local table. This class is the one attached by {@code rate_limit:}
 * in the routing section.
 */
public final class RateLimitFilter implements RequestFilter {

    public enum By { PEER, HEADER, ROUTE }

    /**
     * @param count requests per {@code unit}
     * @param unit 's', 'm' or 'h'
     * @param burst requests admitted at once from a full bucket
     * @param buckets stripes, a power of two (1 for {@code by: route})
     */
    public record Limit(By by, String header, long count, char unit, int burst, int buckets) {
        public Limit {
            Objects.requireNonNull(by, "by");
            if (by == By.HEADER) {
                Objects.requireNonNull(header, "header");
                header = header.toLowerCase(Locale.ROOT);
            } else if (header != null) {
                throw new IllegalArgumentException("header is only valid with by: header");
            }
            if (unit != 's' && unit != 'm' && unit != 'h') {
                throw new IllegalArgumentException("unit must be s, m or h");
            }
            if (count < 1 || count > 1_000_000_000L) {
                throw new IllegalArgumentException("rate must be 1..1e9");
            }
            if (burst < 1 || burst > 1_000_000) {
                throw new IllegalArgumentException("burst must be 1..1000000");
            }
            if (by == By.ROUTE) {
                buckets = 1;
            }
            if (buckets < 1 || buckets > 1 << 20 || Integer.bitCount(buckets) != 1) {
                throw new IllegalArgumentException("buckets must be a power of two, 1..1048576");
            }
        }

        /** Nanoseconds between admitted requests at the steady rate (at least 1). */
        public long intervalNanos() {
            long per = switch (this.unit) {
                case 's' -> 1_000_000_000L;
                case 'm' -> 60_000_000_000L;
                default -> 3_600_000_000_000L;
            };
            return Math.max(1, per / this.count);
        }

        /** {@code 100/s} */
        public String rate() {
            return this.count + "/" + this.unit;
        }
    }

    private final Limit limit;
    private final GcraTable table;

    public RateLimitFilter(Limit limit) {
        this(limit, System::nanoTime);
    }

    RateLimitFilter(Limit limit, LongSupplier clock) {
        this.limit = Objects.requireNonNull(limit, "limit");
        this.table = new GcraTable(limit.intervalNanos(), limit.burst(), limit.buckets(), clock);
    }

    public Limit limit() {
        return this.limit;
    }

    @Override
    public Response filter(Request request, Chain next) {
        long wait = admit(slot(request));
        if (wait == 0) {
            return next.proceed(request);
        }
        var refused = StaticFileHandler.error(429, request.headOnly());
        refused.headers().set("retry-after", Long.toString(Math.max(1, (wait + 999_999_999L) / 1_000_000_000L)));
        return refused;
    }

    /** @return 0 when admitted, else nanoseconds until one more request would be */
    long admit(int slot) {
        return this.table.admit(slot);
    }

    int slot(Request request) {
        int keyless = this.table.keylessSlot();
        return switch (this.limit.by()) {
            case ROUTE -> 0;
            case PEER -> {
                var peer = request.peer();
                var address = peer == null ? null : peer.getAddress();
                yield address == null ? keyless : this.table.stripe(address.hashCode());
            }
            case HEADER -> {
                var values = request.headers().values(this.limit.header());
                yield values.isEmpty() || values.get(0).isEmpty() ? keyless
                        : this.table.stripe(values.get(0).hashCode());
            }
        };
    }

    /** SplitMix64 finalizer: spreads adjacent addresses over the table. */
    static final class Mix {
        private Mix() {
        }

        static long mix(long z) {
            return GcraTable.mix(z);
        }
    }
}
