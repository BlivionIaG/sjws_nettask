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
import java.util.Locale;
import java.util.function.LongSupplier;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.GcraTable;

/**
 * Admits a request when {@link Quota#tryAcquire} succeeds. The key is the client address, a
 * header value, or the raw request target. A missing address or header shares one bucket.
 * The default quota is {@link GcraTable}: a fixed stripe table, so memory does not grow with
 * the number of keys, and a full stripe answers with {@code Retry-After}. A control plane can
 * pass another {@link Quota.Factory} for a cluster remainder; this filter only calls
 * {@code tryAcquire} on the request thread.
 * <p>
 * Params: {@code by} ({@code ip}, {@code header}, {@code route}; default {@code ip}),
 * {@code header} (required when {@code by} is {@code header}), {@code capacity} (burst, required,
 * 1..1000000), {@code refill-per-second} (decimal, required, at most 1e9), {@code buckets}
 * (power of two, default 4096) or {@code max-keys} (legacy table size, rounded up to a power of
 * two; it does not refuse new keys), {@code status} (default 429). An address key is hashed
 * without copying it to a string.
 */
public final class RateLimitFilter implements RequestFilter {

    private static final long MICROS = 1_000_000L;
    private static final long MAX_PER_SECOND = 1_000_000_000L;

    private final Kind kind;
    private final String header;
    private final int status;
    private final Quota quota;

    private RateLimitFilter(Kind kind, String header, int status, Quota quota) {
        this.kind = kind;
        this.header = header;
        this.status = status;
        this.quota = quota;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        return create(spec, s -> local(s, System::nanoTime));
    }

    static RequestFilter create(MiddlewareSpec spec, Quota.Factory quotas) {
        var by = spec.option("by");
        if (by == null || by.isBlank()) {
            by = "ip";
        }
        var kind = switch (by.trim().toLowerCase(Locale.ROOT)) {
            case "ip" -> Kind.IP;
            case "header" -> Kind.HEADER;
            case "route" -> Kind.ROUTE;
            default -> throw new IllegalArgumentException("rate-limit by must be ip, header or route");
        };
        String header = null;
        if (kind == Kind.HEADER) {
            header = spec.option("header");
            if (header == null || header.isBlank()) {
                throw new IllegalArgumentException("rate-limit header is required");
            }
            header = header.trim().toLowerCase(Locale.ROOT);
        }
        int status = 429;
        var statusText = spec.option("status");
        if (statusText != null) {
            long n = parseLong(statusText, "status");
            if (n < 400 || n > 599) {
                throw new IllegalArgumentException("rate-limit status must be 400..599");
            }
            status = (int) n;
        }
        var quota = quotas.create(spec);
        if (quota == null) {
            throw new IllegalArgumentException("rate-limit quota is required");
        }
        return new RateLimitFilter(kind, header, status, quota);
    }

    /** Local quota: one {@link GcraTable} sized from this entry. */
    static Quota local(MiddlewareSpec spec, LongSupplier clock) {
        long capacity = parseLong(required(spec, "capacity"), "capacity");
        if (capacity < 1 || capacity > 1_000_000L) {
            throw new IllegalArgumentException("capacity is out of range");
        }
        long micros = refillMicros(required(spec, "refill-per-second"));
        if (micros > MAX_PER_SECOND * MICROS) {
            throw new IllegalArgumentException("refill-per-second is out of range");
        }
        // 1e15 / micros = nanoseconds between requests. 1/s is 1e6 micros and a 1e9 ns interval.
        long interval = Math.max(1L, (MAX_PER_SECOND * MICROS) / micros);
        return new LocalQuota(new GcraTable(interval, (int) capacity, stripes(spec), clock));
    }

    /**
     * Stripe count. {@code buckets} is exact. {@code max-keys} is the legacy name: rounded up to
     * a power of two and used as the table size. New keys are hashed in, not refused.
     */
    static int stripes(MiddlewareSpec spec) {
        var exact = spec.option("buckets");
        var legacy = spec.option("max-keys");
        if (exact != null && legacy != null) {
            throw new IllegalArgumentException("set buckets or max-keys, not both");
        }
        if (exact != null) {
            long n = parseLong(exact, "buckets");
            if (n < 1 || n > 1 << 20 || Integer.bitCount((int) n) != 1) {
                throw new IllegalArgumentException("buckets must be a power of two, 1..1048576");
            }
            return (int) n;
        }
        if (legacy != null) {
            long n = parseLong(legacy, "max-keys");
            if (n < 1 || n > 1 << 20) {
                throw new IllegalArgumentException("max-keys is out of range");
            }
            return ceilPowerOfTwo((int) n);
        }
        return 4096;
    }

    @Override
    public Response filter(Request request, Chain next) {
        if (this.kind == Kind.IP) {
            var address = peerAddress(request);
            if (this.quota.tryAcquireAddress(address)) {
                return next.proceed(request);
            }
            return refused(request, this.quota.retryAfterAddress(address));
        }
        var key = key(request);
        if (this.quota.tryAcquire(key)) {
            return next.proceed(request);
        }
        return refused(request, this.quota.retryAfterSeconds(key));
    }

    private Response refused(Request request, int retryAfter) {
        var response = StaticFileHandler.error(this.status, request.headOnly());
        response.headers().set("retry-after", Integer.toString(Math.max(1, retryAfter)));
        return response;
    }

    private static InetAddress peerAddress(Request request) {
        var peer = request.peer();
        return peer == null ? null : peer.getAddress();
    }

    private String key(Request request) {
        if (this.kind == Kind.ROUTE) {
            return request.target();
        }
        var values = request.headers().values(this.header);
        return values.isEmpty() || values.get(0).isEmpty() ? null : values.get(0);
    }

    private enum Kind {
        IP, HEADER, ROUTE
    }

    /** Default quota. Hashes the key into {@link GcraTable} and does not retain it. */
    private static final class LocalQuota implements Quota {

        private final GcraTable table;

        LocalQuota(GcraTable table) {
            this.table = table;
        }

        @Override
        public boolean tryAcquire(String key) {
            return this.table.admit(slot(key)) == 0;
        }

        @Override
        public boolean tryAcquireAddress(InetAddress address) {
            int slot = address == null ? this.table.keylessSlot() : this.table.stripe(address.hashCode());
            return this.table.admit(slot) == 0;
        }

        @Override
        public int retryAfterSeconds(String key) {
            return seconds(this.table.delay(slot(key)));
        }

        @Override
        public int retryAfterAddress(InetAddress address) {
            int slot = address == null ? this.table.keylessSlot() : this.table.stripe(address.hashCode());
            return seconds(this.table.delay(slot));
        }

        private int slot(String key) {
            return key == null || key.isEmpty() ? this.table.keylessSlot() : this.table.stripe(key.hashCode());
        }
    }

    private static int seconds(long nanos) {
        if (nanos <= 0) {
            return 1;
        }
        return (int) ((nanos + 999_999_999L) / 1_000_000_000L);
    }

    /** Rounds a legacy {@code max-keys} up to a stripe count. {@code n} is 1..1048576. */
    private static int ceilPowerOfTwo(int n) {
        if (n <= 1) {
            return 1;
        }
        return 1 << (32 - Integer.numberOfLeadingZeros(n - 1));
    }

    private static String required(MiddlewareSpec spec, String name) {
        var value = spec.option(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /** Whole and up to six fractional digits, in millionths of a request per second. */
    static long refillMicros(String raw) {
        var text = raw.trim();
        if (text.isEmpty() || text.charAt(0) == '+' || text.charAt(0) == '-') {
            throw new IllegalArgumentException("refill-per-second must be a positive number");
        }
        int dot = text.indexOf('.');
        if (dot != text.lastIndexOf('.')) {
            throw new IllegalArgumentException("refill-per-second must be a positive number");
        }
        var whole = dot < 0 ? text : text.substring(0, dot);
        var frac = dot < 0 ? "" : text.substring(dot + 1);
        if (whole.isEmpty()) {
            whole = "0";
        }
        if (frac.length() > 6 || !digits(whole) || !digits(frac)) {
            throw new IllegalArgumentException("refill-per-second must be a positive number");
        }
        while (frac.length() < 6) {
            frac = frac + "0";
        }
        long w;
        long f;
        try {
            w = Long.parseLong(whole);
            f = Long.parseLong(frac);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("refill-per-second must be a positive number");
        }
        if (w > Long.MAX_VALUE / MICROS) {
            throw new IllegalArgumentException("refill-per-second is out of range");
        }
        long micros = w * MICROS + f;
        if (micros <= 0) {
            throw new IllegalArgumentException("refill-per-second must be a positive number");
        }
        return micros;
    }

    private static boolean digits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String raw, String name) {
        var text = raw.trim();
        if (text.isEmpty() || text.charAt(0) == '+' || text.charAt(0) == '-') {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
