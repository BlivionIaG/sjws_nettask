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

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongSupplier;

/**
 * Fixed table of GCRA theoretical arrival times. One {@link AtomicLongArray} is allocated at
 * construction; admission is one CAS and allocates nothing. Stripe {@code buckets} holds requests
 * that have no key. Two callers that hash to the same stripe share that stripe's budget, which is
 * stricter, never looser.
 * <p>
 * This is the only rate-limit table in the process. {@link RateLimitFilter} uses it for
 * {@code rate_limit:} in routing, and the gateway {@code rate-limit} filter uses the same class.
 */
public final class GcraTable {

    private final AtomicLongArray tat;
    private final int mask;
    private final long interval;
    private final long tolerance;
    private final LongSupplier clock;
    private final long origin;

    /**
     * @param intervalNanos nanoseconds between admitted requests at the steady rate, at least 1
     * @param burst requests a full stripe admits at once
     * @param buckets stripes, a power of two; one extra slot is reserved for keyless requests
     * @param clock nanosecond clock. A test passes a fixed or stepped clock
     */
    public GcraTable(long intervalNanos, int burst, int buckets, LongSupplier clock) {
        if (intervalNanos < 1) {
            throw new IllegalArgumentException("interval must be >= 1 ns");
        }
        if (burst < 1 || burst > 1_000_000) {
            throw new IllegalArgumentException("burst must be 1..1000000");
        }
        if (buckets < 1 || buckets > 1 << 20 || Integer.bitCount(buckets) != 1) {
            throw new IllegalArgumentException("buckets must be a power of two, 1..1048576");
        }
        this.tat = new AtomicLongArray(buckets + 1);
        this.mask = buckets - 1;
        this.interval = intervalNanos;
        long productHigh = Math.multiplyHigh(intervalNanos, burst);
        this.tolerance = productHigh != 0 ? Long.MAX_VALUE / 4 : Math.min(Long.MAX_VALUE / 4, intervalNanos * burst);
        this.clock = clock;
        this.origin = clock.getAsLong() - 1; // now >= 1, so a fresh bucket (0) starts full
    }

    public int buckets() {
        return this.mask + 1;
    }

    /** Slot shared by requests that have no key. */
    public int keylessSlot() {
        return this.mask + 1;
    }

    /** Stripe for a caller-chosen hash. The keyless slot is not in this range. */
    public int stripe(int hash) {
        return (int) mix(hash) & this.mask;
    }

    /** SplitMix64 finalizer: spreads adjacent addresses over the table. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * @param slot a stripe from {@link #stripe} or {@link #keylessSlot}
     * @return 0 when admitted, else nanoseconds until one more request would be
     */
    public long admit(int slot) {
        long now = this.clock.getAsLong() - this.origin;
        while (true) {
            long t = this.tat.get(slot);
            long next = Math.max(t, now) + this.interval;
            long over = next - now - this.tolerance;
            if (over > 0) {
                return over;
            }
            if (this.tat.compareAndSet(slot, t, next)) {
                return 0;
            }
        }
    }

    /**
     * Nanoseconds until {@link #admit} would succeed, without taking a token. Zero when a request
     * would be admitted now. A hint: another caller may change the stripe between this read and
     * the next admit.
     */
    public long delay(int slot) {
        long now = this.clock.getAsLong() - this.origin;
        long t = this.tat.get(slot);
        long next = Math.max(t, now) + this.interval;
        long over = next - now - this.tolerance;
        return over > 0 ? over : 0;
    }
}
