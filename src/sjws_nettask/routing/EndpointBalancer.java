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
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.ReentrantLock;
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
 * 32×, one half-open probe at a time). Optional {@link ActiveHealth} probes, off unless
 * configured, send a synthetic GET or HEAD and feed the same circuit; they share the half-open
 * slot, and with them enabled a client request is not used as a probe. Local outlier state is
 * never pushed to the control plane. When more than {@code max_ejection} of the set is ejected
 * the balancer panics and ignores ejection. Retries (the upstream's {@code retry}) go to a
 * different endpoint when one is available. Never-populated sources answer 503.
 */
public final class EndpointBalancer implements Handler, AutoCloseable {

    private static final ReentrantLock CLOSER_LOCK = new ReentrantLock();
    private final ReentrantLock lock = new ReentrantLock();

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
    private final Admission admission;
    private final ActiveHealth activeHealth;
    private final ScheduledExecutorService probes; // null when active health is unset
    private final ExecutorService probeWorkers; // null when unset
    private final ConcurrentHashMap<String, Long> lastProbe = new ConcurrentHashMap<>();
    private final AtomicInteger probeInFlight = new AtomicInteger();
    private final AtomicInteger probesSent = new AtomicInteger();
    /** Next index in the current view at which {@link #probeTick} starts scanning. */
    private final AtomicInteger probeScan = new AtomicInteger();
    private final boolean syncProbes; // tests: run probes on the caller of probeTick()
    private volatile View view;
    private volatile boolean closed; // set true before executors are shut down

    public EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier) {
        this(source, pools, retry, outlier, Duration.ofSeconds(10), System::nanoTime, null, null);
    }

    /** @param admission shared pending/connection caps for the whole upstream, or null if unset */
    public EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier,
            Admission admission) {
        this(source, pools, retry, outlier, Duration.ofSeconds(10), System::nanoTime, admission, null);
    }

    /** @param activeHealth optional probes, or null for none (no threads or timers) */
    public EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier,
            ActiveHealth activeHealth) {
        this(source, pools, retry, outlier, Duration.ofSeconds(10), System::nanoTime, null, activeHealth);
    }

    /** @param admission shared caps, or null if unset; @param activeHealth optional probes, or null */
    public EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier,
            Admission admission, ActiveHealth activeHealth) {
        this(source, pools, retry, outlier, Duration.ofSeconds(10), System::nanoTime, admission, activeHealth);
    }

    EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier, Duration grace,
            LongSupplier clock) {
        this(source, pools, retry, outlier, grace, clock, null, null);
    }

    EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier, Duration grace,
            LongSupplier clock, Admission admission, ActiveHealth activeHealth) {
        this(source, pools, retry, outlier, grace, clock, admission, activeHealth, activeHealth != null);
    }

    /**
     * @param startScheduler when false (tests), no probe threads are started and {@link #probeTick}
     *        runs probes on the caller
     */
    EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier, Duration grace,
            LongSupplier clock, ActiveHealth activeHealth, boolean startScheduler) {
        this(source, pools, retry, outlier, grace, clock, null, activeHealth, startScheduler);
    }

    EndpointBalancer(UpstreamSource source, Pools pools, Resilience.Retry retry, Outlier outlier, Duration grace,
            LongSupplier clock, Admission admission, ActiveHealth activeHealth, boolean startScheduler) {
        this.source = Objects.requireNonNull(source, "source");
        this.factory = Objects.requireNonNull(pools, "pools");
        this.retry = retry;
        this.budget = retry == null ? null : new Resilience.RetryBudget(retry.budgetPercent());
        this.outlier = outlier == null ? Outlier.DEFAULT : outlier;
        this.grace = grace;
        this.clock = clock;
        this.admission = admission;
        this.activeHealth = activeHealth;
        this.subscription = source.subscribe(this::swap);
        var initial = source.current();
        if (initial != null) {
            swap(initial);
        }
        if (activeHealth == null) {
            this.probes = null;
            this.probeWorkers = null;
            this.syncProbes = false;
        } else if (!startScheduler) {
            this.probes = null;
            this.probeWorkers = null;
            this.syncProbes = true;
        } else {
            String label = initial != null ? initial.service() : "upstream";
            var exec = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "sjws-active-health-" + label);
                t.setDaemon(true);
                return t;
            });
            this.probes = exec;
            this.probeWorkers = Executors.newFixedThreadPool(MAX_PROBES_IN_FLIGHT, r -> {
                var t = new Thread(r, "sjws-active-health-probe-" + label);
                t.setDaemon(true);
                return t;
            });
            this.syncProbes = false;
            long tickMs = tickMillis(activeHealth);
            exec.scheduleAtFixedRate(this::probeTick, tickMs, tickMs, TimeUnit.MILLISECONDS);
        }
    }

    static long tickMillis(ActiveHealth health) {
        long a = Math.max(1, health.interval().toMillis());
        long b = Math.max(1, health.ejectInterval().toMillis());
        return Math.max(10, Math.min(50, Math.min(a, b) / 2));
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
        volatile boolean abandoned; // left every set: in-flight probes must not update health

        Pool(String key, ProxyHandler proxy, Health health) {
            this.key = key;
            this.proxy = proxy;
            this.health = health;
        }
    }

    private void swap(UpstreamSet set) {
        this.lock.lock();
        try {
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
                        pool.abandoned = true;
                        this.pools.remove(pool.key);
                        this.lastProbe.remove(pool.key);
                        pool.health.forget();
                        closeLater(pool.proxy);
                    }
                }
            }
        } finally {
            this.lock.unlock();
        }
    }

    private void closeLater(ProxyHandler proxy) {
        var c = closer;
        if (c == null) {
            CLOSER_LOCK.lock();
            try {
                    c = closer;
                    if (c == null) {
                        c = Executors.newSingleThreadScheduledExecutor(r -> {
                            var t = new Thread(r, "sjws-endpoint-pool-closer");
                            t.setDaemon(true);
                            return t;
                        });
                        closer = c;
                    }
            } finally {
                CLOSER_LOCK.unlock();
            }
        }
        // requests that picked this endpoint just before the swap still get a connection, but idle
        // sockets go now: an upstream that caps idle connections would otherwise close live ones
        proxy.retire();
        c.schedule(proxy::close, this.grace.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        this.lock.lock();
        boolean already;
        try {
            already = this.closed;
            this.closed = true;
        } finally {
            this.lock.unlock();
        }
        if (this.probes != null) {
            this.probes.shutdownNow();
        }
        if (this.probeWorkers != null) {
            this.probeWorkers.shutdownNow();
        }
        this.lock.lock();
        try {
            if (already) {
                return;
            }
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
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public Response handle(Request request) {
        var v = this.view;
        boolean head = request.headOnly();
        if (v == null) {
            return StaticFileHandler.error(503, head); // never populated
        }
        Admission.Hold pending = null;
        if (this.admission != null && this.admission.limitsPending()) {
            pending = this.admission.tryPending();
            if (pending == null) {
                return ProxyHandler.overloaded(head);
            }
        }
        try {
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
                if (i < 0) {
                    var refused = StaticFileHandler.error(503, head);
                    refused.headers().add("retry-after", Long.toString(retryAfterSeconds(v, now)));
                    var hold = pending;
                    pending = null;
                    return ProxyHandler.withPending(refused, hold);
                }
                var pool = v.pools[i];
                Resilience.On failed;
                Response response = null;
                int errorStatus = 0;
                pool.inflight.incrementAndGet();
                try {
                    response = pool.proxy.attempt(request);
                    failed = ProxyHandler.kind(response);
                } catch (IOException e) {
                    failed = ProxyHandler.kind(e);
                    errorStatus = ProxyHandler.status(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pool.health.release();
                    if (pending != null) {
                        pending.release();
                        pending = null;
                    }
                    return StaticFileHandler.error(502, head);
                } finally {
                    pool.inflight.decrementAndGet();
                }
                if (ProxyHandler.isLocalRefusal(response)) {
                    // Peer-budget (or other local) admission: never contacted the endpoint.
                    // Release any half-open probe reservation taken in pick() (Codex P2 on #298).
                    pool.health.release();
                    var hold = pending;
                    pending = null;
                    return ProxyHandler.withPending(response, hold);
                }
                if (failed == null) {
                    pool.health.success(this.clock.getAsLong());
                    var hold = pending;
                    pending = null;
                    return ProxyHandler.withPending(response, hold);
                }
                pool.health.failure(this.clock.getAsLong());
                boolean again = mayRetry && tries < retry.attempts() && retry.on().contains(failed)
                        && this.budget.withdraw();
                if (!again) {
                    var out = response != null ? response : StaticFileHandler.error(errorStatus, head);
                    var hold = pending;
                    pending = null;
                    return ProxyHandler.withPending(out, hold);
                }
                if (response != null) {
                    Body.discard(response.body());
                }
                tried[tries++] = i;
            }
        } finally {
            if (pending != null) {
                pending.release();
            }
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
            if (!contains(tried, nTried, i) && clientMayUse(v.pools[i], now, panic)) {
                return i;
            }
        }
        int start = ThreadLocalRandom.current().nextInt(n);
        for (int pass = 0; pass < 2; pass++) { // weighted endpoints first, then weight-0 ones
            for (int k = 0; k < n; k++) {
                int i = (start + k) % n;
                boolean weighted = v.set.endpointAt(i).effectiveWeight() > 0 || v.positive.length == n;
                if (weighted == (pass == 0) && !contains(tried, nTried, i)
                        && clientMayUse(v.pools[i], now, panic)) {
                    return i;
                }
            }
        }
        for (int k = 0; k < n; k++) { // only tried endpoints are healthy: retry one of them
            int i = (start + k) % n;
            if (clientMayUse(v.pools[i], now, panic)) {
                return i;
            }
        }
        if (this.activeHealth != null && !panic) {
            return -1; // probes own recovery; do not send client traffic as a half-open probe
        }
        return policyPick(v); // everything ejected and below the panic line: still answer
    }

    /**
     * With active health, ejected endpoints stay out of rotation until a synthetic probe succeeds;
     * the half-open slot is not given to a client request. Without it, {@link Health#available}
     * admits one client probe after {@code eject_for}.
     */
    private boolean clientMayUse(Pool pool, long now, boolean panic) {
        if (panic) {
            return true;
        }
        if (this.activeHealth != null) {
            return !pool.health.ejected();
        }
        return pool.health.available(now);
    }

    private long retryAfterSeconds(View v, long now) {
        long min = Long.MAX_VALUE;
        for (var pool : v.pools) {
            min = Math.min(min, pool.health.retryAfterSeconds(now));
        }
        return min == Long.MAX_VALUE ? 1 : min;
    }

    static final int MAX_PROBES_IN_FLIGHT = 2;

    void probeTick() {
        var v = this.view;
        var policy = this.activeHealth;
        if (v == null || policy == null || this.closed) {
            return;
        }
        int n = v.pools.length;
        if (n == 0) {
            return;
        }
        long now = this.clock.getAsLong();
        int start = Math.floorMod(this.probeScan.get(), n);
        int i = 0;
        for (; i < n; i++) {
            if (this.closed) {
                return;
            }
            if (this.probeInFlight.get() >= MAX_PROBES_IN_FLIGHT) {
                break;
            }
            var pool = v.pools[Math.floorMod(start + i, n)];
            boolean ejected = pool.health.ejected();
            if (ejected) {
                long last = this.lastProbe.getOrDefault(pool.key, 0L);
                if (last != 0L && now - last < policy.ejectInterval().toNanos()) {
                    continue;
                }
                if (!pool.health.available(now)) {
                    continue; // still in eject_for, or the half-open slot is taken
                }
                // available() has reserved the half-open slot
            } else {
                Long last = this.lastProbe.get(pool.key);
                if (last == null) {
                    // stagger the first in-rotation probe so a set does not fire as a herd
                    long interval = Math.max(1, policy.interval().toNanos());
                    long delay = Math.floorMod(pool.key.hashCode(), interval);
                    last = now - interval + delay;
                    this.lastProbe.put(pool.key, last);
                }
                if (now - last < policy.interval().toNanos()) {
                    continue;
                }
            }
            this.lastProbe.put(pool.key, now);
            this.probeInFlight.incrementAndGet();
            this.probesSent.incrementAndGet();
            if (this.syncProbes) {
                runProbe(pool, ejected);
                continue;
            }
            try {
                this.probeWorkers.execute(() -> runProbe(pool, ejected));
            } catch (RejectedExecutionException e) {
                this.probeInFlight.decrementAndGet();
                if (ejected) {
                    pool.health.release();
                }
                break;
            }
        }
        this.probeScan.set(Math.floorMod(start + i, n));
    }

    private void runProbe(Pool pool, boolean heldHalfOpen) {
        try {
            if (this.closed || pool.abandoned) {
                if (heldHalfOpen) {
                    pool.health.release();
                }
                return;
            }
            int status;
            try {
                status = pool.proxy.probe(this.activeHealth.method(), this.activeHealth.path(),
                        this.activeHealth.timeout());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (!this.closed && !pool.abandoned) {
                    pool.health.failure(this.clock.getAsLong());
                } else if (heldHalfOpen) {
                    pool.health.release();
                }
                return;
            }
            if (this.closed || pool.abandoned) {
                if (heldHalfOpen) {
                    pool.health.release();
                }
                return;
            }
            long now = this.clock.getAsLong();
            if (ActiveHealth.success(status)) {
                pool.health.success(now);
            } else {
                pool.health.failure(now);
            }
        } finally {
            this.probeInFlight.decrementAndGet();
        }
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

    public Admission admission() {
        return this.admission;
    }

    public ActiveHealth activeHealth() {
        return this.activeHealth;
    }

    public boolean hasActiveProbes() {
        return this.activeHealth != null;
    }

    int probesSent() {
        return this.probesSent.get();
    }

    int poolCount() {
        this.lock.lock();
        try {
            return this.pools.size();
        } finally {
            this.lock.unlock();
        }
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

        private final ReentrantLock lock = new ReentrantLock();
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

        /** Seconds until a probe is allowed, for Retry-After (at least 1). */
        long retryAfterSeconds(long now) {
            long until = this.ejectedUntil;
            if (until == 0) {
                return 1;
            }
            long left = until - now;
            return Math.max(1, (left + 999_999_999L) / 1_000_000_000L);
        }

        void release() {
            this.probing.set(false);
        }

        void success(long now) {
            this.consecutive.set(0);
            if (this.ejectedUntil != 0) {
                this.lock.lock();
                try {
                        if (this.ejectedUntil != 0) {
                            this.ejectedUntil = 0;
                            this.trips = 0;
                            this.buckets.set(0, 0);
                            this.buckets.set(1, 0);
                            this.ejectedCounter.decrementAndGet();
                        }
                } finally {
                    this.lock.unlock();
                }
                this.probing.set(false);
                return;
            }
            record(now, false);
        }

        void failure(long now) {
            int run = this.consecutive.incrementAndGet();
            if (this.ejectedUntil != 0) { // a failed probe (or a straggler): eject again, longer
                this.lock.lock();
                try {
                        if (this.ejectedUntil != 0) {
                            int t = Math.min(this.trips + 1, 5);
                            this.trips = t;
                            this.ejectedUntil = nonZero(now + (this.policy.ejectFor().toNanos() << t));
                        }
                } finally {
                    this.lock.unlock();
                }
                this.probing.set(false);
                return;
            }
            boolean rate = record(now, true);
            if (run >= this.policy.consecutive() || rate) {
                this.lock.lock();
                try {
                        if (this.ejectedUntil == 0) {
                            this.ejectedUntil = nonZero(now + this.policy.ejectFor().toNanos());
                            this.ejectedCounter.incrementAndGet();
                        }
                } finally {
                    this.lock.unlock();
                }
            }
        }

        /** The pool left every set: its ejection no longer counts toward panic. */
        void forget() {
            this.lock.lock();
            try {
                if (this.ejectedUntil != 0) {
                    this.ejectedUntil = 0;
                    this.ejectedCounter.decrementAndGet();
                }
            } finally {
                this.lock.unlock();
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
