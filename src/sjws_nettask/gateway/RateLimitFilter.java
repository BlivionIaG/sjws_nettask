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

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Admits a request when {@link Quota#tryAcquire} succeeds. The key is the client address, a
 * header value, or the raw request target. A missing address or header shares one bucket.
 * {@link TokenBucket} is the local quota. A control plane can pass another {@link Quota.Factory}
 * for a cluster remainder; this filter only calls {@code tryAcquire} on the request thread.
 * <p>
 * Params: {@code by} ({@code ip}, {@code header}, {@code route}; default {@code ip}),
 * {@code header} (required when {@code by} is {@code header}), {@code capacity} (tokens, required),
 * {@code refill-per-second} (decimal, required), {@code max-keys} (default 10000),
 * {@code status} (default 429). A known key does not allocate. A new key past {@code max-keys}
 * is refused and the map does not grow.
 */
public final class RateLimitFilter implements RequestFilter {

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
        return create(spec, TokenBucket::create);
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

    @Override
    public Response filter(Request request, Chain next) {
        var key = key(request);
        if (this.quota.tryAcquire(key)) {
            return next.proceed(request);
        }
        var response = StaticFileHandler.error(this.status, request.headOnly());
        response.headers().set("retry-after", Integer.toString(Math.max(1, this.quota.retryAfterSeconds(key))));
        return response;
    }

    private String key(Request request) {
        return switch (this.kind) {
            case IP -> {
                var peer = request.peer();
                yield peer == null || peer.getAddress() == null ? null : peer.getAddress().getHostAddress();
            }
            case HEADER -> {
                var values = request.headers().values(this.header);
                yield values.isEmpty() || values.get(0).isEmpty() ? null : values.get(0);
            }
            case ROUTE -> request.target();
        };
    }

    private enum Kind {
        IP, HEADER, ROUTE
    }

    /**
     * Local token bucket. Credit is stored in millionths of a token so a fractional refill does
     * not use floating point. Each key has its own lock; the lock is taken only while this filter
     * is installed.
     */
    static final class TokenBucket implements Quota {

        private static final long UNIT = 1_000_000L;

        private final long capacity;
        private final long refillPerSecond;
        private final int maxKeys;
        private final LongSupplier clock;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
        private final Bucket anonymous;

        private TokenBucket(long capacity, long refillPerSecond, int maxKeys, LongSupplier clock) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.maxKeys = maxKeys;
            this.clock = clock;
            this.anonymous = new Bucket();
        }

        static Quota create(MiddlewareSpec spec) {
            return create(spec, System::nanoTime);
        }

        static Quota create(MiddlewareSpec spec, LongSupplier clock) {
            long capacity = parseLong(required(spec, "capacity"), "capacity");
            if (capacity < 1 || capacity > Long.MAX_VALUE / UNIT) {
                throw new IllegalArgumentException("capacity is out of range");
            }
            long refill = refillMicros(required(spec, "refill-per-second"));
            int maxKeys = 10_000;
            var max = spec.option("max-keys");
            if (max != null) {
                long n = parseLong(max, "max-keys");
                if (n < 1 || n > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("max-keys is out of range");
                }
                maxKeys = (int) n;
            }
            return new TokenBucket(capacity * UNIT, refill, maxKeys, clock);
        }

        @Override
        public boolean tryAcquire(String key) {
            long now = this.clock.getAsLong();
            if (key == null || key.isEmpty()) {
                return this.anonymous.tryTake(now, this.capacity, this.refillPerSecond);
            }
            var bucket = this.buckets.get(key);
            if (bucket == null) {
                if (this.buckets.size() >= this.maxKeys) {
                    return false;
                }
                var fresh = new Bucket();
                var prev = this.buckets.putIfAbsent(key, fresh);
                if (prev != null) {
                    bucket = prev;
                } else if (this.buckets.size() > this.maxKeys) {
                    this.buckets.remove(key, fresh);
                    return false;
                } else {
                    bucket = fresh;
                }
            }
            return bucket.tryTake(now, this.capacity, this.refillPerSecond);
        }

        @Override
        public int retryAfterSeconds(String key) {
            Bucket bucket;
            if (key == null || key.isEmpty()) {
                bucket = this.anonymous;
            } else {
                bucket = this.buckets.get(key);
            }
            if (bucket == null) {
                return 1;
            }
            return bucket.retryAfter();
        }

        private static String required(MiddlewareSpec spec, String name) {
            var value = spec.option(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required");
            }
            return value;
        }

        /** Whole and up to six fractional digits, in millionths of a token per second. */
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
            if (w > Long.MAX_VALUE / UNIT) {
                throw new IllegalArgumentException("refill-per-second is out of range");
            }
            long micros = w * UNIT + f;
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
    }

    private static final class Bucket {

        private final ReentrantLock lock = new ReentrantLock();
        private boolean open;
        private long tokens;
        private long lastNanos;
        private int retryAfter = 1;

        boolean tryTake(long now, long capacity, long refillPerSecond) {
            this.lock.lock();
            try {
                if (!this.open) {
                    this.open = true;
                    this.tokens = capacity;
                    this.lastNanos = now;
                } else if (now > this.lastNanos) {
                    long elapsed = now - this.lastNanos;
                    this.lastNanos = now;
                    long sum = this.tokens + added(elapsed, refillPerSecond, capacity);
                    this.tokens = sum < 0 || sum > capacity ? capacity : sum;
                }
                if (this.tokens < TokenBucket.UNIT) {
                    this.retryAfter = waitSeconds(TokenBucket.UNIT - this.tokens, refillPerSecond);
                    return false;
                }
                this.tokens -= TokenBucket.UNIT;
                return true;
            } finally {
                this.lock.unlock();
            }
        }

        int retryAfter() {
            return this.retryAfter;
        }

        private static long added(long elapsed, long refillPerSecond, long capacity) {
            if (elapsed <= 0) {
                return 0;
            }
            if (elapsed > Long.MAX_VALUE / refillPerSecond) {
                return capacity;
            }
            return elapsed * refillPerSecond / 1_000_000_000L;
        }

        private static int waitSeconds(long deficit, long refillPerSecond) {
            long nanos = (deficit * 1_000_000_000L + refillPerSecond - 1) / refillPerSecond;
            long secs = (nanos + 999_999_999L) / 1_000_000_000L;
            if (secs < 1) {
                return 1;
            }
            if (secs > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) secs;
        }
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
