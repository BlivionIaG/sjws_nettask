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

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.discovery.Endpoint;
import sjws_nettask.routing.discovery.LbPolicy;
import sjws_nettask.routing.discovery.UpstreamSet;
import sjws_nettask.routing.discovery.UpstreamSource;

/**
 * Proxies to one endpoint of a multi-endpoint upstream (design §13, discovery). The endpoint set
 * comes from an {@link UpstreamSource}; each set change rebuilds an immutable {@link View} off the
 * request path and swaps it by reference. One {@link ProxyHandler} (one connection pool) per
 * {@code address:port}, reference-counted across sets, so a membership change keeps warm
 * connections; a pool no set uses any more is closed after a grace period.
 * <p>
 * The gateway owns passive health: each endpoint has a consecutive-failure counter, an
 * error-rate window and a circuit (eject for {@code eject_for}, doubling per failed probe up to
 * 32×, one half-open probe at a time). When more than {@code max_ejection} of the set is ejected
 * the balancer panics and ignores ejection. Retries (the upstream's {@code retry}) go to a
 * different endpoint when one is available. Never-populated sources answer 503.
 */
public final class EndpointBalancer implements Handler, AutoCloseable {

    /**
     * Passive ejection. An endpoint is ejected after {@code consecutive} failures in a row, or
     * when at least {@code minRequests} requests in the last {@code window} failed at
     * {@code errorRatePercent} or more.
     */
    public record Outlier(int consecutive, int errorRatePercent, int minRequests, Duration window, Duration ejectFor,
            int maxEjectionPercent) {

        public static final Outlier DEFAULT = new Outlier(5, 50, 20, Duration.ofSeconds(10), Duration.ofSeconds(10), 50);

        public Outlier {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(ejectFor, "ejectFor");
            if (consecutive < 1) {
                throw new IllegalArgumentException("consecutive failures must be >= 1");
            }
            if (errorRatePercent < 1 || errorRatePercent > 100) {
                throw new IllegalArgumentException("error rate must be 1..100%");
            }
            if (minRequests < 1) {
                throw new IllegalArgumentException("min requests must be >= 1");
            }
            if (window.toMillis() < 2 || ejectFor.isNegative() || ejectFor.isZero()) {
                throw new IllegalArgumentException("window and eject_for must be positive");
            }
            if (maxEjectionPercent < 0 || maxEjectionPercent > 100) {
                throw new IllegalArgumentException("max ejection must be 0..100%");
            }
        }
    }

    /** Builds the proxy (and its pool) for one endpoint. */
    @FunctionalInterface
    public interface Pools {
        ProxyHandler create(Endpoint endpoint);
    }

    private static volatile ScheduledExecutorService closer;

    private final UpstreamSource source;
    private final Pools factory;
    private final Resilience.Retry retry;
    private final Resilience.RetryBudget budget;
    private final Outlier outlier;
    private final Duration grace;
    private final LongSupplier clock;
    private final Map<String, Pool> pools = new HashMap<>(); // guarded by this
    private final AtomicInteger ejected = new AtomicInteger();
    private final AtomicInteger cursor = new AtomicInteger();
    private final AutoCloseable subscription;
    private volatile View view;
    private boolean closed; // guarded by this

    public EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier) {
        this(source, pools, retry, outlier, Duration.ofSeconds(10), System::nanoTime);
    }

    EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier, Duration grace,
            LongSupplier clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.factory = Objects.requireNonNull(pools, "pools");
        this.retry = retry;
        this.budget = retry == null ? null : new Resilience.RetryBudget(retry.budgetPercent());
        this.outlier = outlier == null ? Outlier.DEFAULT : outlier;
        this.grace = grace;
        this.clock = clock;
        this.subscription = source.subscribe(this::swap);
        var initial = source.current();
        if (initial != null) {
            swap(initial);
        }
    }

    /** Immutable per set: the pools in set order, and the policy's precomputed structures. */
    static final class View {
        final UpstreamSet set;
        final Pool[] pools;
        final LbPolicy policy;
        final AliasTable table;
        final int[] positive; // indexes with effective weight > 0 (all of them when none is)

        View(UpstreamSet set, Pool[] pools) {
            this.set = set;
            this.pools = pools;
            this.policy = set.policy();
            int n = pools.length;
            var weights = new long[n];
            int pos = 0;
            for (int i = 0; i < n; i++) {
                weights[i] = set.endpointAt(i).effectiveWeight();
                if (weights[i] > 0) {
                    pos++;
                }
            }
            if (pos == 0) { // every endpoint SUSPECT (or weight 0): use them all, equally
                java.util.Arrays.fill(weights, 1);
                pos = n;
            }
            this.positive = new int[pos];
            for (int i = 0, k = 0; i < n; i++) {
                if (weights[i] > 0) {
                    this.positive[k++] = i;
                }
            }
            this.table = new AliasTable(weights);
        }
    }

    /** One endpoint's pool and passive health; shared by every view that lists the endpoint. */
    static final class Pool {
        final String key;
        final ProxyHandler proxy;
        final AtomicInteger inflight = new AtomicInteger();
        final Health health;
        int refs; // guarded by the balancer

        Pool(String key, ProxyHandler proxy, Health health) {
            this.key = key;
            this.proxy = proxy;
            this.health = health;
        }
    }

    private synchronized void swap(UpstreamSet set) {
        if (this.closed) {
            return;
        }
        var next = new Pool[set.size()];
        for (int i = 0; i < next.length; i++) {
            var endpoint = set.endpointAt(i);
            var key = endpoint.authority();
            var pool = this.pools.get(key);
            if (pool == null) {
                pool = new Pool(key, this.factory.create(endpoint), new Health(this.outlier, this.ejected));
                this.pools.put(key, pool);
            }
            pool.refs++;
            next[i] = pool;
        }
        var old = this.view;
        this.view = new View(set, next);
        if (old != null) {
            for (var pool : old.pools) {
                if (--pool.refs == 0) {
                    this.pools.remove(pool.key);
                    pool.health.forget();
                    closeLater(pool.proxy);
                }
            }
        }
    }

    private void closeLater(ProxyHandler proxy) {
        var c = closer;
        if (c == null) {
            synchronized (EndpointBalancer.class) {
                c = closer;
                if (c == null) {
                    c = Executors.newSingleThreadScheduledExecutor(r -> {
                        var t = new Thread(r, "sjws-endpoint-pool-closer");
                        t.setDaemon(true);
                        return t;
                    });
                    closer = c;
                }
            }
        }
        // requests that picked this endpoint just before the swap still get a connection
        c.schedule(proxy::close, this.grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.subscription.close();
        } catch (Exception e) {
            System.err.println("WARN: unsubscribing from upstream source: " + e);
        }
        for (var pool : this.pools.values()) {
            pool.proxy.close();
        }
        this.pools.clear();
        this.view = null;
    }

    @Override
    public Response handle(Request request) {
        var v = this.view;
        boolean head = request.headOnly();
        if (v == null) {
            return StaticFileHandler.error(503, head); // never populated
        }
        var retry = this.retry;
        boolean mayRetry = retry != null && request.body().length() == 0 && ProxyHandler.idempotent(request.method());
        if (retry != null) {
            this.budget.deposit();
        }
        int[] tried = mayRetry ? new int[retry.attempts()] : null;
        int tries = 0;
        while (true) {
            long now = this.clock.getAsLong();
            int i = pick(v, tried, tries, now);
            var pool = v.pools[i];
            Resilience.On failed;
            Response response = null;
            int errorStatus = 0;
            pool.inflight.incrementAndGet();
            try {
                response = pool.proxy.attempt(request);
                failed = ProxyHandler.kind(response.status());
            } catch (IOException e) {
                failed = ProxyHandler.kind(e);
                errorStatus = ProxyHandler.status(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.health.release();
                return StaticFileHandler.error(502, head);
            } finally {
                pool.inflight.decrementAndGet();
            }
            if (failed == null) {
                pool.health.success(this.clock.getAsLong());
                return response;
            }
            pool.health.failure(this.clock.getAsLong());
            boolean again = mayRetry && tries < retry.attempts() && retry.on().contains(failed) && this.budget.withdraw();
            if (!again) {
                return response != null ? response : StaticFileHandler.error(errorStatus, head);
            }
            if (response != null) {
                Body.discard(response.body());
            }
            tried[tries++] = i;
        }
    }

    /**
     * An endpoint that is not ejected (unless the balancer panics) and, for a retry, not tried yet.
     * Falls back to a tried endpoint, then to any endpoint, so a request always gets one.
     */
    int pick(View v, int[] tried, int nTried, long now) {
        int n = v.pools.length;
        boolean panic = (long) this.ejected.get() * 100 > (long) this.outlier.maxEjectionPercent() * n;
        int draws = Math.min(n, 4);
        for (int d = 0; d < draws; d++) {
            int i = policyPick(v);
            if (!contains(tried, nTried, i) && (panic || v.pools[i].health.available(now))) {
                return i;
            }
        }
        int start = ThreadLocalRandom.current().nextInt(n);
        for (int pass = 0; pass < 2; pass++) { // weighted endpoints first, then weight-0 ones
            for (int k = 0; k < n; k++) {
                int i = (start + k) % n;
                boolean weighted = v.set.endpointAt(i).effectiveWeight() > 0 || v.positive.length == n;
                if (weighted == (pass == 0) && !contains(tried, nTried, i)
                        && (panic || v.pools[i].health.available(now))) {
                    return i;
                }
            }
        }
        for (int k = 0; k < n; k++) { // only tried endpoints are healthy: retry one of them
            int i = (start + k) % n;
            if (panic || v.pools[i].health.available(now)) {
                return i;
            }
        }
        return policyPick(v); // everything ejected and below the panic line: still answer
    }

    private int policyPick(View v) {
        return switch (v.policy) {
            case WEIGHTED_RANDOM -> v.table.pick(ThreadLocalRandom.current().nextLong());
            case ROUND_ROBIN -> v.positive[Math.floorMod(this.cursor.getAndIncrement(), v.positive.length)];
            case LEAST_REQUEST -> {
                var rnd = ThreadLocalRandom.current();
                int a = v.positive[rnd.nextInt(v.positive.length)];
                int b = v.positive[rnd.nextInt(v.positive.length)];
                yield v.pools[b].inflight.get() < v.pools[a].inflight.get() ? b : a;
            }
        };
    }

    private static boolean contains(int[] tried, int n, int i) {
        for (int k = 0; k < n; k++) {
            if (tried[k] == i) {
                return true;
            }
        }
        return false;
    }

    /** The set in use, for tests and status; null before the first one. */
    UpstreamSet currentSet() {
        var v = this.view;
        return v == null ? null : v.set;
    }

    /** Ejected endpoints of the pools in use. */
    int ejectedCount() {
        return this.ejected.get();
    }

    synchronized int poolCount() {
        return this.pools.size();
    }

    /** Pool of an endpoint in the current view, for tests. */
    Pool poolOf(String authority) {
        var v = this.view;
        if (v != null) {
            for (var p : v.pools) {
                if (p.key.equals(authority)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Per-endpoint passive health: consecutive failures, a two-bucket error-rate window (each
     * bucket one {@code AtomicLong}: 24-bit epoch, 20-bit total, 20-bit failures), and the circuit.
     */
    static final class Health {
        private static final long COUNT = 1L << 20;
        private static final long MASK = COUNT - 1;

        private final Outlier policy;
        private final AtomicInteger ejectedCounter;
        private final long half;
        private final AtomicInteger consecutive = new AtomicInteger();
        private final AtomicLongArray buckets = new AtomicLongArray(2);
        private final AtomicBoolean probing = new AtomicBoolean();
        private volatile long ejectedUntil; // 0 = in rotation
        private volatile int trips;

        Health(Outlier policy, AtomicInteger ejectedCounter) {
            this.policy = policy;
            this.ejectedCounter = ejectedCounter;
            this.half = Math.max(1, policy.window().toNanos() / 2);
        }

        /** In rotation, or the one half-open probe after {@code eject_for}. */
        boolean available(long now) {
            long until = this.ejectedUntil;
            if (until == 0) {
                return true;
            }
            if (now - until < 0) {
                return false;
            }
            return this.probing.compareAndSet(false, true);
        }

        boolean ejected() {
            return this.ejectedUntil != 0;
        }

        void release() {
            this.probing.set(false);
        }

        void success(long now) {
            this.consecutive.set(0);
            if (this.ejectedUntil != 0) {
                synchronized (this) {
                    if (this.ejectedUntil != 0) {
                        this.ejectedUntil = 0;
                        this.trips = 0;
                        this.buckets.set(0, 0);
                        this.buckets.set(1, 0);
                        this.ejectedCounter.decrementAndGet();
                    }
                }
                this.probing.set(false);
                return;
            }
            record(now, false);
        }

        void failure(long now) {
            int run = this.consecutive.incrementAndGet();
            if (this.ejectedUntil != 0) { // a failed probe (or a straggler): eject again, longer
                synchronized (this) {
                    if (this.ejectedUntil != 0) {
                        int t = Math.min(this.trips + 1, 5);
                        this.trips = t;
                        this.ejectedUntil = nonZero(now + (this.policy.ejectFor().toNanos() << t));
                    }
                }
                this.probing.set(false);
                return;
            }
            boolean rate = record(now, true);
            if (run >= this.policy.consecutive() || rate) {
                synchronized (this) {
                    if (this.ejectedUntil == 0) {
                        this.ejectedUntil = nonZero(now + this.policy.ejectFor().toNanos());
                        this.ejectedCounter.incrementAndGet();
                    }
                }
            }
        }

        /** The pool left every set: its ejection no longer counts toward panic. */
        synchronized void forget() {
            if (this.ejectedUntil != 0) {
                this.ejectedUntil = 0;
                this.ejectedCounter.decrementAndGet();
            }
        }

        /** @return true when the window now meets the error-rate threshold */
        private boolean record(long now, boolean fail) {
            long epoch = Math.floorDiv(now, this.half) & 0xFF_FFFF;
            int idx = (int) (epoch & 1);
            while (true) {
                long v = this.buckets.get(idx);
                long next;
                if (v >>> 40 != epoch) {
                    next = epoch << 40 | COUNT | (fail ? 1 : 0);
                } else if ((v >>> 20 & MASK) == MASK) {
                    break; // saturated: the rate is still meaningful
                } else {
                    next = v + COUNT + (fail ? 1 : 0);
                }
                if (this.buckets.compareAndSet(idx, v, next)) {
                    break;
                }
            }
            if (!fail) {
                return false;
            }
            long total = 0;
            long fails = 0;
            long cur = this.buckets.get(idx);
            if (cur >>> 40 == epoch) {
                total += cur >>> 20 & MASK;
                fails += cur & MASK;
            }
            long prev = this.buckets.get(idx ^ 1);
            if (prev >>> 40 == (epoch - 1 & 0xFF_FFFF)) {
                total += prev >>> 20 & MASK;
                fails += prev & MASK;
            }
            return total >= this.policy.minRequests() && fails * 100 >= this.policy.errorRatePercent() * total;
        }

        private static long nonZero(long t) {
            return t == 0 ? 1 : t;
        }
    }
}
