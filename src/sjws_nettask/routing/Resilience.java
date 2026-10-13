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

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional retry and circuit-breaking policy for one upstream (design §13). A {@link ProxyHandler}
 * without one keeps the plain single-attempt path.
 *
 * @param retry null for no retries
 * @param circuit null for no circuit breaker
 */
public record Resilience(Retry retry, Circuit circuit) {

    /** What may be retried. */
    public enum On { CONNECT, TIMEOUT, RESET, STATUS_502, STATUS_503, STATUS_504 }

    /**
     * Retries are made only for idempotent methods without a request body, only before any
     * response byte has reached the client, and only while the retry budget has tokens.
     *
     * @param attempts extra attempts after the first (1..5)
     * @param on failures that are retried
     * @param budgetPercent retries allowed as a percentage of requests (1..100); a burst of up to
     *        10 retries is allowed
     */
    public record Retry(int attempts, Set<On> on, int budgetPercent) {
        public Retry {
            if (attempts < 1 || attempts > 5) {
                throw new IllegalArgumentException("retry attempts must be 1..5");
            }
            if (budgetPercent < 1 || budgetPercent > 100) {
                throw new IllegalArgumentException("retry budget must be 1..100 %");
            }
            on = Set.copyOf(Objects.requireNonNull(on, "on"));
        }
    }

    /**
     * @param failures consecutive failures (connect, timeout, reset, 502/503/504) that open it
     * @param openFor how long it stays open the first time; each failed probe doubles it, up to 32×
     */
    public record Circuit(int failures, Duration openFor) {
        public Circuit {
            if (failures < 1) {
                throw new IllegalArgumentException("circuit failures must be >= 1");
            }
            if (openFor == null || openFor.isNegative() || openFor.isZero()) {
                throw new IllegalArgumentException("circuit eject_for must be > 0");
            }
        }
    }

    public Resilience {
        if (retry == null && circuit == null) {
            throw new IllegalArgumentException("retry or circuit required (use null for neither)");
        }
    }

    /** Token bucket in milli-tokens: each request adds {@code budgetPercent × 10}, a retry costs 1000. Lock-free. */
    static final class RetryBudget {
        private static final long COST = 1000;
        private static final long CAP = 10 * COST;
        private final long perRequest;
        private final AtomicLong tokens = new AtomicLong(CAP);

        RetryBudget(int budgetPercent) {
            this.perRequest = budgetPercent * 10L;
        }

        void deposit() {
            long t = this.tokens.get();
            if (t < CAP) {
                this.tokens.compareAndSet(t, Math.min(CAP, t + this.perRequest)); // a lost race only skips one deposit
            }
        }

        boolean withdraw() {
            while (true) {
                long t = this.tokens.get();
                if (t < COST) {
                    return false;
                }
                if (this.tokens.compareAndSet(t, t - COST)) {
                    return true;
                }
            }
        }

        long tokens() {
            return this.tokens.get();
        }
    }

    /**
     * Closed → open after {@code failures} consecutive failures → half-open after {@code openFor}
     * (one probe at a time) → closed on a successful probe, or open again for twice as long.
     */
    static final class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private final int threshold;
        private final long openNanos;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicBoolean probing = new AtomicBoolean();
        private volatile long openUntil; // 0 = closed
        private volatile int trips;

        CircuitBreaker(Circuit circuit) {
            this.threshold = circuit.failures();
            this.openNanos = circuit.openFor().toNanos();
        }

        /** @return true if a request may go to the upstream now (a half-open breaker admits one probe) */
        boolean allow() {
            long until = this.openUntil;
            if (until == 0) {
                return true;
            }
            if (System.nanoTime() - until < 0) {
                return false;
            }
            return this.probing.compareAndSet(false, true);
        }

        /** Ends a half-open probe without a verdict. */
        void release() {
            this.probing.set(false);
        }

        void success() {
            if (this.openUntil != 0 || this.failures.get() != 0) {
                this.failures.set(0);
                this.trips = 0;
                this.openUntil = 0;
                this.probing.set(false);
            }
        }

        void failure() {
            if (this.openUntil != 0) { // a failed half-open probe (or a straggler): open again, longer
                int t = Math.min(this.trips + 1, 5);
                this.trips = t;
                this.openUntil = System.nanoTime() + (this.openNanos << t);
                this.probing.set(false);
                return;
            }
            if (this.failures.incrementAndGet() >= this.threshold) {
                this.openUntil = System.nanoTime() + this.openNanos;
                this.probing.set(false);
            }
        }

        State state() {
            long until = this.openUntil;
            return until == 0 ? State.CLOSED : System.nanoTime() - until < 0 ? State.OPEN : State.HALF_OPEN;
        }

        /** Seconds until a probe is allowed, for Retry-After (at least 1). */
        long retryAfterSeconds() {
            long left = this.openUntil - System.nanoTime();
            return Math.max(1, (left + 999_999_999L) / 1_000_000_000L);
        }
    }
}
