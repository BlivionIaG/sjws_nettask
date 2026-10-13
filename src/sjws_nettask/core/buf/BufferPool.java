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
package sjws_nettask.core.buf;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pooled direct buffers in power-of-two size classes ({@link #MIN_CLASS} .. {@link #MAX_CLASS}) carved from
 * {@link #SLAB_BYTES} direct slabs, plus a small cache of large transient buffers (up to {@link #MAX_LARGE}). See the
 * package documentation for the API contract and {@code docs/design/buffer-pool.md} for the design.
 * <p>
 * Free chunks live in striped stacks (one short {@code ReentrantLock} section per pop/push, nothing held across a
 * park, so virtual threads may migrate carriers between acquire and release). Platform threads also keep a small
 * per-thread magazine. Nothing is allocated until the first acquire; a slab whose chunks are all free can be dropped
 * by {@link #trim}.
 */
public final class BufferPool {

    public static final String HTTP = "http";
    public static final String MESSAGING = "messaging";

    public static final int MIN_CLASS = 256;
    public static final int MAX_CLASS = 64 * 1024;
    public static final int MAX_LARGE = 4 << 20;
    static final int CLASSES = Integer.numberOfTrailingZeros(MAX_CLASS / MIN_CLASS) + 1; // 9
    public static final int SLAB_BYTES = 1 << 20;
    static final int LARGE_CLASSES = Integer.numberOfTrailingZeros(MAX_LARGE / (MAX_CLASS * 2)) + 1; // 128K..4M: 6
    static final int LARGE_CACHED = 4;
    private static final long LEAK_LOG_INTERVAL_NS = 60_000_000_000L;
    private static final Cleaner CLEANER = Cleaner.create();
    private static final byte POISON = (byte) 0xDE;

    /**
     * Configuration. {@code budgets} maps tenant to a fraction of {@code maxBytes} (0..1); {@link #HTTP} gets
     * whatever the others leave.
     */
    public record Settings(boolean enabled, long maxBytes, Map<String, Double> budgets, boolean debug, int leakSample,
            int magazine) {

        public static final Settings OFF = new Settings(false, 0, Map.of(), false, 0, 0);

        public Settings {
            budgets = Map.copyOf(budgets);
            double sum = 0;
            for (var e : budgets.entrySet()) {
                if (e.getKey().equals(HTTP) || e.getValue() < 0 || e.getValue() > 1) {
                    throw new IllegalArgumentException("bad budget " + e);
                }
                sum += e.getValue();
            }
            if (sum > 1) {
                throw new IllegalArgumentException("budgets add up to more than 100%: " + budgets);
            }
            if (enabled && (maxBytes < SLAB_BYTES || leakSample < 0 || magazine < 0)) {
                throw new IllegalArgumentException("bad pool settings");
            }
        }

        /** Pool on with the defaults: 64 MiB, messaging 50 %, leak sampling 1 in 64, magazines of 8. */
        public static Settings defaults() {
            return new Settings(true, 64L << 20, Map.of(MESSAGING, 0.5), false, 64, 8);
        }

        /**
         * From system properties: {@code sjws.buf.pool=on|off} (default off), {@code sjws.buf.maxBytes} (bytes, or
         * with a k/m/g suffix; default 64m), {@code sjws.buf.budgets} ({@code messaging=50%,...}),
         * {@code sjws.buf.debug=true}, {@code sjws.buf.leakSample} (1 in N tracked, 0 = none; default 64),
         * {@code sjws.buf.magazine} (per-platform-thread chunks per class, default 8).
         */
        public static Settings fromSystemProperties() {
            if (!"on".equalsIgnoreCase(System.getProperty("sjws.buf.pool", "off"))) {
                return OFF;
            }
            var d = defaults();
            var budgets = new LinkedHashMap<String, Double>(d.budgets());
            var spec = System.getProperty("sjws.buf.budgets");
            if (spec != null && !spec.isBlank()) {
                budgets.clear();
                for (var part : spec.split(",")) {
                    var kv = part.trim().split("=", 2);
                    var v = kv[1].trim();
                    budgets.put(kv[0].trim(), v.endsWith("%") ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100
                            : Double.parseDouble(v));
                }
            }
            boolean debug = Boolean.getBoolean("sjws.buf.debug");
            return new Settings(true, bytes(System.getProperty("sjws.buf.maxBytes", "64m")), budgets, debug,
                    Integer.getInteger("sjws.buf.leakSample", d.leakSample()),
                    Integer.getInteger("sjws.buf.magazine", d.magazine()));
        }

        static long bytes(String s) {
            s = s.trim().toLowerCase(Locale.ROOT);
            long mul = switch (s.charAt(s.length() - 1)) {
                case 'k' -> 1L << 10;
                case 'm' -> 1L << 20;
                case 'g' -> 1L << 30;
                default -> 1;
            };
            return Long.parseLong(mul == 1 ? s : s.substring(0, s.length() - 1)) * mul;
        }
    }

    /** Counters; {@code tenants} in creation order. */
    public record Stats(boolean enabled, long maxBytes, long reservedBytes, int slabs, long leaked, long trimmedSlabs,
            List<Tenant> tenants) {
        public record Tenant(String name, long limitBytes, long usedBytes, long acquired, long denied, long fallbacks) {
        }
    }

    private static final class Holder {
        static final BufferPool SHARED = create(Settings.fromSystemProperties());
    }

    private static final BufferPool DISABLED = new BufferPool(Settings.OFF);

    /** The process-wide pool, configured once from system properties; {@link #disabled()} unless enabled. */
    public static BufferPool shared() {
        return Holder.SHARED;
    }

    /** The pool-off implementation: plain heap buffers, no accounting. */
    public static BufferPool disabled() {
        return DISABLED;
    }

    public static BufferPool create(Settings settings) {
        return settings.enabled() ? new BufferPool(settings) : DISABLED;
    }

    // ---- state ----

    final boolean enabled;
    private final Settings settings;
    private final Map<String, BufferBudget> budgets;
    private final SizeClass[] classes;
    private final Stripe[][] large; // per large class: one stack of cached free buffers
    private final AtomicLong reserved = new AtomicLong();
    private final LongAdder leaked = new LongAdder();
    private final LongAdder trimmed = new LongAdder();
    private final AtomicLong lastLeakLog = new AtomicLong(System.nanoTime() - LEAK_LOG_INTERVAL_NS);
    private final ConcurrentHashMap<PooledBuffer.Tracker, Boolean> live = new ConcurrentHashMap<>();
    private final ThreadLocal<Magazine> magazines;
    private final int stripeMask;

    private BufferPool(Settings s) {
        this.settings = s;
        this.enabled = s.enabled();
        var map = new LinkedHashMap<String, BufferBudget>();
        double rest = 1;
        for (var e : s.budgets().entrySet()) {
            rest -= e.getValue();
        }
        map.put(HTTP, new BufferBudget(this, HTTP, this.enabled ? (long) (s.maxBytes() * rest) : Long.MAX_VALUE));
        for (var e : s.budgets().entrySet()) {
            map.put(e.getKey(), new BufferBudget(this, e.getKey(),
                    this.enabled ? (long) (s.maxBytes() * e.getValue()) : Long.MAX_VALUE));
        }
        if (!this.enabled) {
            map.putIfAbsent(MESSAGING, new BufferBudget(this, MESSAGING, Long.MAX_VALUE));
        }
        this.budgets = Map.copyOf(map);
        int stripes = Integer.highestOneBit(Math.max(1, Runtime.getRuntime().availableProcessors()) * 2 - 1);
        this.stripeMask = stripes - 1;
        this.classes = new SizeClass[this.enabled ? CLASSES : 0];
        for (int c = 0; c < this.classes.length; c++) {
            this.classes[c] = new SizeClass(MIN_CLASS << c, stripes);
        }
        this.large = new Stripe[this.enabled ? LARGE_CLASSES : 0][];
        for (int c = 0; c < this.large.length; c++) {
            this.large[c] = new Stripe[] {new Stripe(LARGE_CACHED)};
        }
        this.magazines = this.enabled && s.magazine() > 0 ? new ThreadLocal<>() : null;
    }

    public boolean enabled() {
        return this.enabled;
    }

    /**
     * The view for {@code tenant} ({@link #HTTP}, {@link #MESSAGING}, or a name configured in {@code budgets}).
     *
     * @throws IllegalArgumentException for an unknown tenant
     */
    public BufferBudget budget(String tenant) {
        var b = this.budgets.get(tenant);
        if (b == null) {
            throw new IllegalArgumentException("unknown buffer budget: " + tenant);
        }
        return b;
    }

    public Stats stats() {
        var tenants = new ArrayList<Stats.Tenant>();
        for (var name : orderedTenants()) {
            var b = this.budgets.get(name);
            tenants.add(new Stats.Tenant(name, b.limitBytes(), b.usedBytes(), b.acquired.sum(), b.denied.sum(),
                    b.fallbacks.sum()));
        }
        int slabs = 0;
        for (var c : this.classes) {
            slabs += c.slabs.size();
        }
        return new Stats(this.enabled, this.settings.maxBytes(), this.reserved.get(), slabs, this.leaked.sum(),
                this.trimmed.sum(), List.copyOf(tenants));
    }

    private List<String> orderedTenants() {
        var names = new ArrayList<>(this.budgets.keySet());
        names.sort(null);
        return names;
    }

    /** Debug mode: where each still-held tracked buffer was acquired. Empty otherwise. */
    public List<Throwable> outstanding() {
        var out = new ArrayList<Throwable>();
        for (var t : this.live.keySet()) {
            if (t.acquiredAt != null) {
                out.add(t.acquiredAt);
            }
        }
        return out;
    }

    /** Tracked buffers not yet released (debug mode tracks all; otherwise a sample). */
    public int trackedOutstanding() {
        return this.live.size();
    }

    // ---- acquire ----

    static int sizeClass(int min) {
        return min <= MIN_CLASS ? 0 : 32 - Integer.numberOfLeadingZeros(min - 1) - Integer.numberOfTrailingZeros(MIN_CLASS);
    }

    PooledBuffer take(BufferBudget budget, int min, boolean tryMode) {
        if (min < 1 || min > MAX_CLASS) {
            throw new IllegalArgumentException("capacity " + min + " outside 1.." + MAX_CLASS + " (use acquireLarge)");
        }
        if (!this.enabled) {
            return new PooledBuffer(ByteBuffer.allocate(min), null, null, 0, null);
        }
        int c = sizeClass(min);
        var cls = this.classes[c];
        if (!budget.charge(cls.size)) {
            return deny(budget, tryMode);
        }
        var slot = pop(cls, c);
        if (slot == null) {
            budget.refund(cls.size);
            return deny(budget, tryMode);
        }
        budget.acquired.increment();
        return handle(slot.buf.clear(), slot, budget, cls.size);
    }

    PooledBuffer takeLarge(BufferBudget budget, int min) {
        if (min < 1) {
            throw new IllegalArgumentException("capacity " + min);
        }
        if (!this.enabled || min > MAX_LARGE) {
            return null; // caller falls back to unpooled
        }
        if (min <= MAX_CLASS) {
            return take(budget, min, false);
        }
        int lc = 32 - Integer.numberOfLeadingZeros(min - 1) - Integer.numberOfTrailingZeros(MAX_CLASS * 2);
        int size = (MAX_CLASS * 2) << lc;
        if (!budget.charge(size)) {
            budget.fallbacks.increment();
            return null;
        }
        var stack = this.large[lc][0];
        Slot slot = stack.pop();
        if (slot == null) {
            if (!reserve(size)) {
                budget.refund(size);
                budget.fallbacks.increment();
                return null;
            }
            slot = new Slot(ByteBuffer.allocateDirect(size), -1 - lc, null);
        }
        budget.acquired.increment();
        return handle(slot.buf.clear(), slot, budget, size);
    }

    private PooledBuffer deny(BufferBudget budget, boolean tryMode) {
        if (tryMode) {
            budget.denied.increment();
        }
        return null;
    }

    /** Unpooled heap buffer for {@code acquire} when the pool can't serve it; not charged, counted. */
    PooledBuffer fallback(BufferBudget budget, int min) {
        if (this.enabled) {
            budget.fallbacks.increment();
        }
        return new PooledBuffer(ByteBuffer.allocate(min), null, null, 0, null);
    }

    private PooledBuffer handle(ByteBuffer buf, Slot slot, BufferBudget budget, int charged) {
        PooledBuffer.Tracker tracker = null;
        int sample = this.settings.leakSample();
        if (this.settings.debug() || (sample > 0 && (sample == 1 || ThreadLocalRandom.current().nextInt(sample) == 0))) {
            tracker = new PooledBuffer.Tracker(buf, slot, budget, charged,
                    this.settings.debug() ? new Throwable("buffer acquired here") : null);
            this.live.put(tracker, Boolean.TRUE);
        }
        var h = new PooledBuffer(buf, slot, budget, charged, tracker);
        if (tracker != null) {
            tracker.cleanable = CLEANER.register(h, tracker);
        }
        return h;
    }

    void untrack(PooledBuffer.Tracker t) {
        this.live.remove(t);
    }

    private boolean reserve(long bytes) {
        for (;;) {
            long r = this.reserved.get();
            if (r + bytes > this.settings.maxBytes()) {
                return false;
            }
            if (this.reserved.compareAndSet(r, r + bytes)) {
                return true;
            }
        }
    }

    private Slot pop(SizeClass cls, int c) {
        var mag = magazine(false);
        if (mag != null) {
            var s = mag.pop(c);
            if (s != null) {
                return s;
            }
        }
        int home = (int) Thread.currentThread().threadId() & this.stripeMask;
        for (int i = 0; i <= this.stripeMask; i++) {
            var s = cls.stripes[(home + i) & this.stripeMask].pop();
            if (s != null) {
                return s;
            }
        }
        return grow(cls, c, home);
    }

    /** New slab for {@code cls}: one chunk is returned, the rest go to the caller's stripe. */
    private Slot grow(SizeClass cls, int c, int home) {
        cls.lock.lock();
        try {
            // another thread may have grown meanwhile
            var again = cls.stripes[home].pop();
            if (again != null) {
                return again;
            }
            if (!reserve(SLAB_BYTES)) {
                return null;
            }
            var mem = ByteBuffer.allocateDirect(SLAB_BYTES);
            int n = SLAB_BYTES / cls.size;
            var slab = new Slab();
            var slots = new Slot[n];
            for (int i = 0; i < n; i++) {
                slots[i] = new Slot(mem.slice(i * cls.size, cls.size), c, slab);
            }
            slab.slots = slots;
            cls.slabs.add(slab);
            cls.stripes[home].pushAll(slots, 1, n);
            return slots[0];
        } finally {
            cls.lock.unlock();
        }
    }

    // ---- release ----

    void recycle(ByteBuffer buf, Slot slot, BufferBudget budget, int charged, boolean wasLeak) {
        if (slot == null) {
            return;
        }
        budget.refund(charged);
        if (this.settings.debug()) {
            poison(buf);
        }
        if (wasLeak) {
            this.leaked.increment();
            long now = System.nanoTime(), last = this.lastLeakLog.get();
            if (now - last >= LEAK_LOG_INTERVAL_NS && this.lastLeakLog.compareAndSet(last, now)) {
                System.err.println("WARN: buffer leak: a " + buf.capacity() + "-byte " + budget.name()
                        + " buffer was not released (returned to the pool; -Dsjws.buf.debug=true shows where)");
            }
        }
        if (slot.cls < 0) {
            if (!this.large[-1 - slot.cls][0].push(slot)) {
                this.reserved.addAndGet(-buf.capacity()); // cache full: let it go
            }
            return;
        }
        slot.freedAt = System.nanoTime(); // the releasing thread owns the slot: no shared write
        var mag = wasLeak ? null : magazine(true); // a leak is recycled on the Cleaner thread: no magazine there
        if (mag != null && mag.push(slot)) {
            return;
        }
        var cls = this.classes[slot.cls];
        int home = (int) Thread.currentThread().threadId() & this.stripeMask;
        for (int i = 0;; i++) {
            if (cls.stripes[(home + i) & this.stripeMask].push(slot)) {
                return;
            }
        }
    }

    private static final byte[] POISON_BLOCK = new byte[4096];

    static {
        Arrays.fill(POISON_BLOCK, POISON);
    }

    /**
     * Fills the whole chunk with {@link #POISON}. Goes through {@code duplicate().clear()}: absolute puts are checked
     * against the limit, and a caller may release after {@code flip()} (limit &lt; capacity) or with any position.
     */
    static void poison(ByteBuffer buf) {
        var all = buf.duplicate().clear();
        for (int i = 0, n = all.capacity(); i < n; i += POISON_BLOCK.length) {
            all.put(i, POISON_BLOCK, 0, Math.min(POISON_BLOCK.length, n - i));
        }
    }

    // ---- magazines (platform threads only) ----

    private Magazine magazine(boolean create) {
        if (this.magazines == null || Thread.currentThread().isVirtual()) {
            return null;
        }
        var m = this.magazines.get();
        if (m == null && create) {
            m = new Magazine(CLASSES, this.settings.magazine());
            this.magazines.set(m);
            var mag = m;
            CLEANER.register(Thread.currentThread(), () -> flush(mag)); // thread gone: give its chunks back
        }
        return m;
    }

    private void flush(Magazine m) {
        m.lock.lock();
        try {
            for (int c = 0; c < m.slots.length; c++) {
                while (m.counts[c] > 0) {
                    var s = m.slots[c][--m.counts[c]];
                    m.slots[c][m.counts[c]] = null;
                    var cls = this.classes[c];
                    for (int i = 0;; i++) {
                        if (cls.stripes[i & this.stripeMask].push(s)) {
                            break;
                        }
                    }
                }
            }
        } finally {
            m.lock.unlock();
        }
    }

    // ---- trim ----

    /**
     * Frees slabs whose chunks have all been free (in the shared stripes) for at least {@code idleNanos}, and drops
     * cached large buffers. Their direct memory is reclaimed by the JDK once unreachable.
     *
     * @return slabs freed
     */
    public int trim(long idleNanos) {
        if (!this.enabled) {
            return 0;
        }
        int freed = 0;
        long now = System.nanoTime();
        for (var cls : this.classes) {
            for (var slab : cls.slabs) {
                cls.lock.lock();
        try {
                    if (removeIfIdle(cls, slab, now - idleNanos)) {
                        cls.slabs.remove(slab);
                        this.reserved.addAndGet(-SLAB_BYTES);
                        this.trimmed.increment();
                        freed++;
                    }
                } finally {
            cls.lock.unlock();
        }
            }
        }
        for (var l : this.large) {
            Slot s;
            while ((s = l[0].pop()) != null) {
                this.reserved.addAndGet(-s.buf.capacity());
            }
        }
        return freed;
    }

    private boolean removeIfIdle(SizeClass cls, Slab slab, long freedBefore) {
        // lock every stripe (fixed order), check, then remove; a slot held in a magazine keeps the slab
        return lockAll(cls.stripes, 0, () -> {
            for (var s : slab.slots) {
                if (s.freedAt - freedBefore > 0) {
                    return false; // used too recently (or still out)
                }
            }
            int found = 0;
            for (var st : cls.stripes) {
                found += st.count(slab);
            }
            if (found != slab.slots.length) {
                return false;
            }
            for (var st : cls.stripes) {
                st.removeAll(slab);
            }
            return true;
        });
    }

    private static boolean lockAll(Stripe[] stripes, int i, java.util.function.BooleanSupplier body) {
        if (i == stripes.length) {
            return body.getAsBoolean();
        }
        stripes[i].lock.lock();
        try {
            return lockAll(stripes, i + 1, body);
        } finally {
            stripes[i].lock.unlock();
        }
    }

    // ---- structures ----

    /** One pooled chunk (or cached large buffer when {@code cls < 0}); created once, reused. */
    static final class Slot {
        final ByteBuffer buf;
        final int cls;
        final Slab slab;
        /** When it was last released (or created); written by the releasing thread, read by trim under the stripe locks. */
        long freedAt = System.nanoTime();

        Slot(ByteBuffer buf, int cls, Slab slab) {
            this.buf = buf;
            this.cls = cls;
            this.slab = slab;
        }
    }

    static final class Slab {
        Slot[] slots;
    }

    static final class SizeClass {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final int size;
        final Stripe[] stripes;
        final List<Slab> slabs = new CopyOnWriteArrayList<>();

        SizeClass(int size, int stripes) {
            this.size = size;
            this.stripes = new Stripe[stripes];
            for (int i = 0; i < stripes; i++) {
                this.stripes[i] = new Stripe(0);
            }
        }
    }

    /** A stack of free slots. {@code bound} 0 = unbounded (grows). */
    static final class Stripe {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private Slot[] items = new Slot[16];
        private int n;
        private final int bound;

        Stripe(int bound) {
            this.bound = bound;
            if (bound > 0) {
                this.items = new Slot[bound];
            }
        }

        Slot pop() {
            this.lock.lock();
            try {
            if (this.n == 0) {
                return null;
            }
            var s = this.items[--this.n];
            this.items[this.n] = null;
            return s;
        } finally {
                this.lock.unlock();
            }
        }

        boolean push(Slot s) {
            this.lock.lock();
            try {
            if (this.n == this.items.length) {
                if (this.bound > 0) {
                    return false;
                }
                this.items = Arrays.copyOf(this.items, this.n * 2);
            }
            this.items[this.n++] = s;
            return true;
        } finally {
                this.lock.unlock();
            }
        }

        void pushAll(Slot[] slots, int from, int to) {
            this.lock.lock();
            try {
            int need = this.n + (to - from);
            if (need > this.items.length) {
                this.items = Arrays.copyOf(this.items, Integer.highestOneBit(need) << 1);
            }
            System.arraycopy(slots, from, this.items, this.n, to - from);
            this.n = need;
        } finally {
                this.lock.unlock();
            }
        }

        int count(Slab slab) {
            int k = 0;
            for (int i = 0; i < this.n; i++) {
                if (this.items[i].slab == slab) {
                    k++;
                }
            }
            return k;
        }

        void removeAll(Slab slab) {
            int w = 0;
            for (int i = 0; i < this.n; i++) {
                if (this.items[i].slab != slab) {
                    this.items[w++] = this.items[i];
                }
            }
            Arrays.fill(this.items, w, this.n, null);
            this.n = w;
        }
    }

    /** Per-platform-thread free chunks; touched by its owner, or by {@link #flush} after the owner died. */
    static final class Magazine {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final Slot[][] slots;
        final int[] counts;

        Magazine(int classes, int size) {
            this.slots = new Slot[classes][size];
            this.counts = new int[classes];
        }

        Slot pop(int c) {
            this.lock.lock();
            try {
            if (this.counts[c] == 0) {
                return null;
            }
            var s = this.slots[c][--this.counts[c]];
            this.slots[c][this.counts[c]] = null;
            return s;
        } finally {
                this.lock.unlock();
            }
        }

        boolean push(Slot s) {
            this.lock.lock();
            try {
            int c = s.cls;
            if (this.counts[c] == this.slots[c].length) {
                return false;
            }
            this.slots[c][this.counts[c]++] = s;
            return true;
        } finally {
                this.lock.unlock();
            }
        }
    }
}
