package sjws_nettask.messaging.ledger;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Process-wide shared <em>syncer</em> and (for {@link Durability#NONE}/{@link Durability#ASYNC}) shared
 * <em>writer</em> shards (#336 / #337).
 * <p>
 * Syncers: CPU-sized by default ({@link #SYNCERS_PROPERTY}); {@code 0}/{@code false} → dedicated syncer
 * per writer. Legacy {@link #PROPERTY} ({@code sharedWriters}) is a deprecated alias for one release.
 * <p>
 * Writers: NONE/ASYNC partitions register on N sticky shards ({@link #WRITER_THREADS_PROPERTY}, default
 * {@code max(2, min(CPUs, 16))}), hashed by XXH64 of the log dir's canonical real path. GROUP/EACH stay
 * on a dedicated writer thread. The writer pool is created lazily on the first NONE/ASYNC open so
 * messaging-off means 0 writer-pool threads.
 */
public final class SharedLedgerPool {

    private static final Logger LOG = Logger.getLogger(SharedLedgerPool.class.getName());

    /** Syncer shard count. Unset/{@code auto}/{@code true} = CPU count; {@code 0}/{@code false}/{@code off} = dedicated. */
    public static final String SYNCERS_PROPERTY = "sjws.messaging.ledger.sharedSyncers";

    /**
     * Deprecated alias of {@link #SYNCERS_PROPERTY} for one release. Prefer {@code sharedSyncers}.
     * @deprecated use {@link #SYNCERS_PROPERTY}
     */
    @Deprecated
    public static final String PROPERTY = "sjws.messaging.ledger.sharedWriters";

    /**
     * Writer shard count for NONE/ASYNC. Default {@code max(2, min(CPUs, 16))}; {@code 0}/{@code false}/{@code off}
     * = dedicated writers (legacy). Resolved once at first NONE/ASYNC open and fixed for the process lifetime.
     */
    public static final String WRITER_THREADS_PROPERTY = "sjws.messaging.ledger.sharedWriterThreads";

    private static final AtomicLong DEPRECATED_WARNED = new AtomicLong();

    private static final class SyncerHolder {
        static final SharedLedgerPool INSTANCE = new SharedLedgerPool(resolveSyncerSize());
    }

    /** Writer pool; null until first NONE/ASYNC registration (or permanently empty when size resolves to 0). */
    private static final Object WRITER_LOCK = new Object();
    private static volatile WriterPool WRITERS;
    private static volatile boolean WRITERS_RESOLVED;
    private static volatile int WRITER_SIZE = -1;

    /** Shared syncer pool when size &gt; 0, else {@code null}. */
    public static SharedLedgerPool sharedOrNull() {
        var p = SyncerHolder.INSTANCE;
        return p.shards.length == 0 ? null : p;
    }

    /** Process-wide writer pool when enabled and already created; else {@code null}. Does not create. */
    public static WriterPool writersOrNull() {
        return WRITERS;
    }

    /**
     * Returns the writer pool, creating it lazily on first call when {@link #resolveWriterThreads()} &gt; 0.
     * Messaging off / never called ⇒ no writer-pool threads.
     */
    public static WriterPool writers() {
        var w = WRITERS;
        if (w != null) return w;
        synchronized (WRITER_LOCK) {
            if (WRITERS != null) return WRITERS;
            int n = resolveWriterThreads();
            WRITER_SIZE = n;
            WRITERS_RESOLVED = true;
            if (n == 0) {
                WRITERS = WriterPool.DISABLED;
                return WRITERS;
            }
            WRITERS = new WriterPool(n);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("ledger-writer-pool-shutdown").unstarted(WRITERS::shutdown));
            return WRITERS;
        }
    }

    /** Test-only: clears the lazy writer pool so the next {@link #writers()} re-resolves. */
    static void resetWriterPoolForTests() {
        synchronized (WRITER_LOCK) {
            var w = WRITERS;
            WRITERS = null;
            WRITERS_RESOLVED = false;
            WRITER_SIZE = -1;
            if (w != null && w != WriterPool.DISABLED) w.shutdown();
        }
    }

    /** @deprecated use {@link #resolveSyncerSize()} */
    @Deprecated
    static int resolveSize() {
        return resolveSyncerSize();
    }

    static int resolveSyncerSize() {
        String neu = System.getProperty(SYNCERS_PROPERTY);
        String legacy = System.getProperty(PROPERTY);
        String v;
        if (neu != null && !neu.isBlank()) {
            v = neu;
            if (legacy != null && !legacy.isBlank() && !legacy.trim().equals(neu.trim())) {
                warnDeprecated("both " + SYNCERS_PROPERTY + " and " + PROPERTY + " set; preferring " + SYNCERS_PROPERTY);
            }
        } else if (legacy != null && !legacy.isBlank()) {
            v = legacy;
            warnDeprecated(PROPERTY + " is deprecated; use " + SYNCERS_PROPERTY + " (alias honored for one release)");
        } else {
            v = null;
        }
        if (v == null || v.isBlank() || v.equalsIgnoreCase("auto") || v.equalsIgnoreCase("true")) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        if (v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")) {
            return 0;
        }
        int n = Integer.parseInt(v.trim());
        if (n < 0) throw new IllegalArgumentException(SYNCERS_PROPERTY + " >= 0: " + n);
        return n;
    }

    static int resolveWriterThreads() {
        if (WRITERS_RESOLVED && WRITER_SIZE >= 0) return WRITER_SIZE;
        String v = System.getProperty(WRITER_THREADS_PROPERTY);
        if (v == null || v.isBlank() || v.equalsIgnoreCase("auto") || v.equalsIgnoreCase("true")) {
            int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
            return Math.max(2, Math.min(cpus, 16));
        }
        if (v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")) {
            return 0;
        }
        int n = Integer.parseInt(v.trim());
        if (n < 0) throw new IllegalArgumentException(WRITER_THREADS_PROPERTY + " >= 0: " + n);
        return n;
    }

    private static void warnDeprecated(String msg) {
        if (DEPRECATED_WARNED.getAndIncrement() == 0) {
            LOG.warning(msg);
        }
    }

    private final Shard[] shards;

    SharedLedgerPool(int n) { // package-private for tests
        if (n == 0) {
            this.shards = new Shard[0];
            return;
        }
        this.shards = new Shard[n];
        for (int i = 0; i < n; i++) this.shards[i] = new Shard(i);
    }

    int size() {
        return shards.length;
    }

    /** Registers on the least-loaded syncer shard (prefers empty). Starts that shard's syncer on first member. */
    int register(LedgerWriter w) {
        int best = 0;
        for (int i = 0; i < shards.length; i++) {
            int n = shards[i].members.size();
            if (n == 0) {
                best = i;
                break;
            }
            if (n < shards[best].members.size()) best = i;
        }
        shards[best].add(w);
        return best;
    }

    void unregister(LedgerWriter w, int shard) {
        if (shard < 0 || shard >= shards.length) return;
        shards[shard].remove(w);
    }

    void unparkSyncer(int shard) {
        if (shard >= 0 && shard < shards.length) shards[shard].unpark();
    }

    private static final class Shard {
        final int index;
        final CopyOnWriteArrayList<LedgerWriter> members = new CopyOnWriteArrayList<>();
        final ReentrantLock startLock = new ReentrantLock();
        volatile Thread syncer;
        volatile boolean pinged;

        Shard(int index) {
            this.index = index;
        }

        void add(LedgerWriter w) {
            startLock.lock();
            try {
                members.addIfAbsent(w);
                if (syncer == null) {
                    var t = Thread.ofPlatform().name("ledger-syncer-shared-" + index).daemon().unstarted(this::syncLoop);
                    syncer = t;
                    t.start();
                }
            } finally {
                startLock.unlock();
            }
            unpark();
        }

        void remove(LedgerWriter w) {
            startLock.lock();
            try {
                members.remove(w);
            } finally {
                startLock.unlock();
            }
            unpark();
        }

        int memberCount() {
            return members.size();
        }

        Thread syncerThread() {
            return syncer;
        }

        void unpark() {
            pinged = true;
            var t = syncer;
            if (t != null) LockSupport.unpark(t);
        }

        private boolean retire() {
            if (!members.isEmpty()) return false;
            startLock.lock();
            try {
                if (!members.isEmpty()) return false;
                syncer = null;
                return true;
            } finally {
                startLock.unlock();
            }
        }

        private void syncLoop() {
            while (true) {
                pinged = false;
                boolean busy = false;
                for (var w : members) {
                    if (w.sharedSyncSlice()) busy = true;
                }
                if (busy) continue;
                if (retire()) return;
                for (int i = 0; i < 256 && !pinged; i++) Thread.onSpinWait();
                if (pinged) continue;
                if (retire()) return;
                LockSupport.parkNanos(this, LedgerWriter.IDLE_PARK_NANOS);
            }
        }
    }

    int memberCount(int shard) {
        return shards[shard].memberCount();
    }

    Thread syncerThread(int shard) {
        return shards[shard].syncerThread();
    }

    /**
     * Sticky writer-shard pool for NONE/ASYNC. Shard index =
     * {@code Long.remainderUnsigned(XXH64(utf8(realPath)), N)}.
     */
    public static final class WriterPool {
        static final WriterPool DISABLED = new WriterPool(0);

        private final WriterShard[] shards;
        private final ReentrantLock life = new ReentrantLock();
        private volatile boolean closing;

        WriterPool(int n) {
            if (n == 0) {
                this.shards = new WriterShard[0];
                return;
            }
            this.shards = new WriterShard[n];
            for (int i = 0; i < n; i++) this.shards[i] = new WriterShard(i);
        }

        public int size() {
            return shards.length;
        }

        public boolean disabled() {
            return shards.length == 0;
        }

        /** Canonical real-path UTF-8 → sticky shard. */
        static int shardFor(Path realDir, int n) {
            if (n <= 0) throw new IllegalArgumentException("n");
            long h = XXH64.hashUtf8(realDir.toString());
            return (int) Long.remainderUnsigned(h, n);
        }

        /**
         * Registers {@code w} on its sticky shard. Returns the shard index, or -1 when disabled / closing.
         * Starts the shard thread lazily on first registration that needs drain (first Pending unparks).
         */
        int register(LedgerWriter w, Path realDir) {
            if (shards.length == 0) return -1;
            life.lock();
            try {
                if (closing || w.isClosed()) return -1;
                int idx = shardFor(realDir, shards.length);
                shards[idx].add(w);
                return idx;
            } finally {
                life.unlock();
            }
        }

        void unregister(LedgerWriter w, int shard) {
            if (shard < 0 || shard >= shards.length) return;
            shards[shard].remove(w);
        }

        void unparkWriter(int shard) {
            if (shard >= 0 && shard < shards.length) shards[shard].unpark();
        }

        /** Per-shard pending-queue depth (plain long written by the shard thread; free to read). */
        public long queueDepth(int shard) {
            if (shard < 0 || shard >= shards.length) return 0;
            return shards[shard].queueDepth;
        }

        public int memberCount(int shard) {
            return shards[shard].memberCount();
        }

        Thread writerThread(int shard) {
            return shards[shard].writerThread();
        }

        /** Occupancy (#members) max and mean across shards; for the 1k-group sweep. */
        public double[] occupancyMaxMean() {
            if (shards.length == 0) return new double[] {0, 0};
            int max = 0;
            long sum = 0;
            for (var s : shards) {
                int n = s.memberCount();
                max = Math.max(max, n);
                sum += n;
            }
            return new double[] {max, (double) sum / shards.length};
        }

        /** True when any writer-pool platform thread is live. */
        public boolean anyThreadAlive() {
            for (var s : shards) {
                var t = s.writerThread();
                if (t != null && t.isAlive()) return true;
            }
            return false;
        }

        void shutdown() {
            life.lock();
            try {
                closing = true;
            } finally {
                life.unlock();
            }
            for (var s : shards) s.shutdown();
        }
    }

    /**
     * One writer shard: drains per-partition inboxes FIFO, round-robin across members (T10), one direct buffer.
     */
    static final class WriterShard {
        final int index;
        final CopyOnWriteArrayList<LedgerWriter> members = new CopyOnWriteArrayList<>();
        final ReentrantLock startLock = new ReentrantLock();
        final AtomicInteger rr = new AtomicInteger();
        volatile Thread writer;
        volatile boolean pinged;
        /** Written by the shard thread after each loop; readers pay nothing on the offer path. */
        volatile long queueDepth;
        private ByteBuffer buf;
        private int directAccounted;
        private long shardCharged;
        private final LedgerWriterBudget budget = LedgerWriterBudget.shared();
        private volatile boolean stop;

        WriterShard(int index) {
            this.index = index;
        }

        void add(LedgerWriter w) {
            startLock.lock();
            try {
                members.addIfAbsent(w);
                // Thread starts on first unpark (first Pending), not at registration — messaging-off / idle = 0 threads.
            } finally {
                startLock.unlock();
            }
        }

        void remove(LedgerWriter w) {
            startLock.lock();
            try {
                members.remove(w);
                if (members.isEmpty()) {
                    // Keep thread parked until shutdown or next member; release shard buffer/charge when empty.
                    releaseShardCharge();
                }
            } finally {
                startLock.unlock();
            }
            unpark();
        }

        int memberCount() {
            return members.size();
        }

        Thread writerThread() {
            return writer;
        }

        void unpark() {
            pinged = true;
            ensureStarted();
            var t = writer;
            if (t != null) LockSupport.unpark(t);
        }

        private void ensureStarted() {
            if (writer != null || stop) return;
            startLock.lock();
            try {
                if (writer != null || stop) return;
                var t = Thread.ofPlatform().name("ledger-writer-shared-" + index).daemon().unstarted(this::writeLoop);
                writer = t;
                t.start();
            } finally {
                startLock.unlock();
            }
        }

        private void materializeBuffer() {
            if (buf != null) return;
            startLock.lock();
            try {
                if (buf != null) return;
                // One queue-equivalent + 1 MiB direct, once per shard (#337 §4).
                long cost = LedgerWriterBudget.materializeCostBytes(LedgerWriter.DEFAULT_QUEUE);
                long got = budget.acquireExtra(cost, "SharedLedgerPool writer shard " + index);
                shardCharged += got;
                try {
                    buf = ByteBuffer.allocateDirect(LedgerWriterBudget.DIRECT_BUF_BYTES);
                    directAccounted = LedgerWriterBudget.DIRECT_BUF_BYTES;
                } catch (Throwable t) {
                    budget.releaseBytes(got);
                    shardCharged -= got;
                    throw t;
                }
            } finally {
                startLock.unlock();
            }
        }

        private void releaseShardCharge() {
            long c = shardCharged;
            shardCharged = 0;
            buf = null;
            directAccounted = 0;
            if (c > 0) budget.releaseBytes(c);
        }

        ByteBuffer ensureCapacity(int total) {
            materializeBuffer();
            var b = buf;
            if (b.capacity() >= total) return b;
            int cap = Integer.highestOneBit(total) << 1;
            long need = (long) cap - directAccounted;
            long got = 0;
            int prev = directAccounted;
            if (need > 0) {
                got = budget.acquireExtra(need, "SharedLedgerPool shard buffer growth");
                shardCharged += got;
                directAccounted = cap;
            }
            try {
                b = ByteBuffer.allocateDirect(cap);
            } catch (Throwable t) {
                if (got > 0) {
                    budget.releaseBytes(got);
                    shardCharged -= got;
                    directAccounted = prev;
                }
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error err) throw err;
                throw new RuntimeException(t);
            }
            buf = b;
            return b;
        }

        private void writeLoop() {
            while (!stop) {
                pinged = false;
                long depth = 0;
                for (var w : members) depth += w.pooledPending();
                queueDepth = depth;
                boolean busy = false;
                // Snapshot so unregister during drain cannot AIOOBE the round-robin index.
                LedgerWriter[] snap = members.toArray(LedgerWriter[]::new);
                int n = snap.length;
                if (n > 0) {
                    int start = Math.floorMod(rr.getAndIncrement(), n);
                    for (int k = 0; k < n; k++) {
                        var w = snap[(start + k) % n];
                        try {
                            if (w.pooledDrainSlice(this)) busy = true;
                        } catch (LedgerBudgetExhaustedException e) {
                            // Fail only that batch inside pooledDrainSlice; never fence the shard.
                            busy = true;
                        } catch (Throwable t) {
                            // Per-partition IO fences that writer only (handled inside drain).
                            busy = true;
                        }
                    }
                }
                if (busy) continue;
                if (members.isEmpty() && !stop) {
                    // Park; may retire thread when empty to keep messaging-off / idle at 0 threads after drain.
                    startLock.lock();
                    try {
                        if (members.isEmpty()) {
                            writer = null;
                            releaseShardCharge();
                            return;
                        }
                    } finally {
                        startLock.unlock();
                    }
                }
                for (int i = 0; i < 256 && !pinged && !stop; i++) Thread.onSpinWait();
                if (pinged || stop) continue;
                LockSupport.parkNanos(this, LedgerWriter.IDLE_PARK_NANOS);
            }
            // Shutdown drain
            for (var w : members) {
                try {
                    while (w.pooledDrainSlice(this)) { /* drain */ }
                } catch (Throwable ignored) { }
            }
            startLock.lock();
            try {
                writer = null;
                releaseShardCharge();
            } finally {
                startLock.unlock();
            }
        }

        void shutdown() {
            stop = true;
            unpark();
            var t = writer;
            if (t != null) {
                try {
                    t.join(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            startLock.lock();
            try {
                releaseShardCharge();
            } finally {
                startLock.unlock();
            }
        }
    }
}
