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
package sjws_nettask.messaging.ledger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide memory budget for {@link LedgerWriter}s (#333). Fail-closed: opening or materializing a
 * writer that would push {@link #charged()} over {@link #budget()} throws
 * {@link LedgerBudgetExhaustedException} instead of letting the JVM OOM.
 * <p>
 * Each writer charges {@link #idleCostBytes()} at construction (no queue or direct buffer yet) and the
 * remaining {@link #materializeCostBytes(int)} when its {@code ArrayBlockingQueue} and 1 MiB direct
 * buffer are first allocated. {@link #release(long)} returns the charge on close.
 * <p>
 * Size (first of):
 * <ul>
 *   <li>{@value #BYTES_PROPERTY} when set to a positive long (bytes), or {@code 0}/{@code off}/{@code false}
 *       for unlimited;</li>
 *   <li>else {@value #FRACTION_PROPERTY} × {@link Runtime#maxMemory()} (default {@value #DEFAULT_FRACTION}).</li>
 * </ul>
 * ConfigLoader may set those properties from a node-local {@code ledger:} YAML section before the first
 * writer opens. A malformed property fails at first use with {@link IllegalArgumentException}.
 */
public final class LedgerWriterBudget {

    /** Absolute budget in bytes; {@code 0}/{@code off}/{@code false} = unlimited. */
    public static final String BYTES_PROPERTY = "sjws.messaging.ledger.budget.bytes";
    /** Fraction of {@link Runtime#maxMemory()} when {@link #BYTES_PROPERTY} is unset. */
    public static final String FRACTION_PROPERTY = "sjws.messaging.ledger.budget.heapFraction";
    /** Default heap fraction when neither property forces a size. */
    public static final double DEFAULT_FRACTION = 0.25d;
    /**
     * Heap bytes charged for an idle writer (object, thread bookkeeping). The queue and direct buffer are
     * not allocated yet (#333 lazy sizing).
     */
    public static final long IDLE_COST_BYTES = 64L * 1024L;
    /** Direct buffer reserved on first write ({@code ByteBuffer.allocateDirect(1 << 20)}). */
    public static final int DIRECT_BUF_BYTES = 1 << 20;
    /** Bytes per queue slot assumed for admission (compressed oops may use 4; charge 8 to stay closed). */
    public static final int QUEUE_SLOT_BYTES = 8;

    private static final java.util.concurrent.atomic.AtomicReference<LedgerWriterBudget> INSTANCE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * The process budget. Resolved once from the properties above on first use, unless
     * {@link #install} replaced it (tests and ConfigLoader-driven setups).
     */
    public static LedgerWriterBudget shared() {
        var b = INSTANCE.get();
        if (b != null) return b;
        var created = resolve();
        if (INSTANCE.compareAndSet(null, created)) return created;
        return INSTANCE.get();
    }

    /** Replaces the process budget (does not rewind charges already taken). */
    public static void install(LedgerWriterBudget budget) {
        INSTANCE.set(java.util.Objects.requireNonNull(budget));
    }

    /** Clears the cached instance so the next {@link #shared} re-reads properties (tests). */
    public static void resetForTests() {
        INSTANCE.set(null);
    }

    /** Test hook: a fresh budget of {@code budgetBytes} (0 = unlimited). */
    public static LedgerWriterBudget ofBytes(long budgetBytes) {
        if (budgetBytes < 0) throw new IllegalArgumentException("budgetBytes >= 0: " + budgetBytes);
        return new LedgerWriterBudget(budgetBytes, budgetBytes == 0 ? "unlimited" : "test:" + budgetBytes);
    }

    static LedgerWriterBudget resolve() {
        String bytes = System.getProperty(BYTES_PROPERTY);
        if (bytes != null && !bytes.isBlank()) {
            String v = bytes.trim();
            if (v.equals("0") || v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false")
                    || v.equalsIgnoreCase("unlimited")) {
                return new LedgerWriterBudget(0, BYTES_PROPERTY + "=unlimited");
            }
            try {
                long n = Long.parseLong(v);
                if (n < 0) throw new IllegalArgumentException(BYTES_PROPERTY + " >= 0: " + n);
                if (n > 0 && n < IDLE_COST_BYTES) {
                    throw new IllegalArgumentException(BYTES_PROPERTY + " must be 0 (unlimited) or >="
                            + IDLE_COST_BYTES + ": " + n);
                }
                return new LedgerWriterBudget(n, BYTES_PROPERTY + "=" + n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(BYTES_PROPERTY + ": expected bytes or off, got \"" + v + "\"", e);
            }
        }
        double fraction = DEFAULT_FRACTION;
        String frac = System.getProperty(FRACTION_PROPERTY);
        if (frac != null && !frac.isBlank()) {
            try {
                fraction = Double.parseDouble(frac.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(FRACTION_PROPERTY + ": expected a double, got \"" + frac + "\"", e);
            }
            if (!(fraction > 0.0d) || fraction > 1.0d) {
                throw new IllegalArgumentException(FRACTION_PROPERTY + " must be in (0, 1]: " + fraction);
            }
        }
        long max = Runtime.getRuntime().maxMemory();
        long n = (long) (fraction * max);
        if (n < IDLE_COST_BYTES) n = IDLE_COST_BYTES;
        return new LedgerWriterBudget(n, FRACTION_PROPERTY + "=" + fraction + " of " + max);
    }

    /** Worst-case heap+direct bytes once the queue (2× soft capacity) and 1 MiB direct buf exist. */
    public static long fullCostBytes(int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity");
        long queue = 2L * queueCapacity * QUEUE_SLOT_BYTES;
        return IDLE_COST_BYTES + queue + DIRECT_BUF_BYTES;
    }

    /** Extra bytes charged when materializing the queue and direct buffer. */
    public static long materializeCostBytes(int queueCapacity) {
        return fullCostBytes(queueCapacity) - IDLE_COST_BYTES;
    }

    public static long idleCostBytes() {
        return IDLE_COST_BYTES;
    }

    private final long budget; // 0 = unlimited
    private final String source;
    private final AtomicLong charged = new AtomicLong();
    private final AtomicLong writers = new AtomicLong();
    private final AtomicLong refusals = new AtomicLong();

    private LedgerWriterBudget(long budget, String source) {
        this.budget = budget;
        this.source = source;
    }

    public long budget() {
        return budget;
    }

    public String source() {
        return source;
    }

    public long charged() {
        return charged.get();
    }

    public long writers() {
        return writers.get();
    }

    public long refusals() {
        return refusals.get();
    }

    public boolean unlimited() {
        return budget == 0;
    }

    /** Reserves idle cost for a new writer; throws when the budget cannot take it. */
    public long acquireIdle() {
        long c = acquire(IDLE_COST_BYTES, "idle LedgerWriter");
        writers.incrementAndGet();
        return c;
    }

    /** Reserves the materialize delta; throws when the budget cannot take it (queue not allocated). */
    public long acquireMaterialize(int queueCapacity) {
        return acquire(materializeCostBytes(queueCapacity), "LedgerWriter queue/direct buffer");
    }

    private long acquire(long cost, String what) {
        if (cost < 0) throw new IllegalArgumentException("cost");
        if (unlimited()) {
            charged.addAndGet(cost); // still track for metrics
            return cost;
        }
        while (true) {
            long cur = charged.get();
            if (cur > budget - cost) { // cur + cost > budget without overflow
                refusals.incrementAndGet();
                throw new LedgerBudgetExhaustedException(
                        "ledger writer budget exhausted (" + source + "): need " + cost + " B for " + what
                                + ", charged " + cur + " / " + budget + " B");
            }
            if (charged.compareAndSet(cur, cur + cost)) return cost;
        }
    }

    /** Returns charged bytes without changing the writer count (materialize rollback). */
    public void releaseBytes(long cost) {
        if (cost < 0) throw new IllegalArgumentException("cost");
        if (cost == 0) return;
        long left = charged.addAndGet(-cost);
        if (left < 0) charged.compareAndSet(left, 0); // underflow guard
    }

    /** Returns all bytes previously acquired for this writer and drops its writer count. */
    public void release(long cost) {
        releaseBytes(cost);
        writers.decrementAndGet();
    }
}
