package sjws_nettask.messaging.ledger;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared background <em>syncer</em> threads for all {@link LedgerWriter}s on this process.
 * <p>
 * Each partition still has a dedicated writer thread ({@code ArrayBlockingQueue.take} wake), so the
 * produce / request-reply p50 path stays as on master. Syncers are pooled and sized to the CPUs so a
 * swarm with many partitions does not run one fsync loop per log (claim-wakeup #184: ~15 writer+syncer
 * threads on few CPUs). Writers unpark the shard syncer after ASYNC writes, same as a dedicated syncer.
 * <p>
 * Default size is the CPU count ({@value #PROPERTY} unset or {@code auto}). Set the property to
 * {@code 0} / {@code false} for a dedicated syncer per writer (fully legacy). Shard threads start
 * lazily on the first registration and stop when the last writer leaves (zero cost when unused).
 */
public final class SharedLedgerPool {

    /** {@code 0}/{@code false} = dedicated syncers; unset/{@code auto}/{@code true} = CPU count; or an int. */
    public static final String PROPERTY = "sjws.messaging.ledger.sharedWriters";

    private static final class Holder {
        static final SharedLedgerPool INSTANCE = new SharedLedgerPool(resolveSize());
    }

    /** Shared syncer pool when size &gt; 0, else {@code null}. */
    public static SharedLedgerPool sharedOrNull() {
        var p = Holder.INSTANCE;
        return p.shards.length == 0 ? null : p;
    }

    static int resolveSize() {
        String v = System.getProperty(PROPERTY);
        if (v == null || v.isBlank() || v.equalsIgnoreCase("auto") || v.equalsIgnoreCase("true")) {
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        if (v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off")) {
            return 0;
        }
        int n = Integer.parseInt(v.trim());
        if (n < 0) throw new IllegalArgumentException(PROPERTY + " >= 0: " + n);
        return n;
    }

    private final Shard[] shards;

    private SharedLedgerPool(int n) {
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

    /** Registers on the least-loaded shard (prefers empty). Starts that shard's syncer on first member. */
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
        final Object startLock = new Object();
        volatile Thread syncer;
        volatile boolean stop;

        Shard(int index) {
            this.index = index;
        }

        void add(LedgerWriter w) {
            members.addIfAbsent(w);
            ensureStarted();
            unpark();
        }

        void remove(LedgerWriter w) {
            members.remove(w);
            unpark();
            if (members.isEmpty()) stopSyncer();
        }

        void unpark() {
            var t = syncer;
            if (t != null) LockSupport.unpark(t);
        }

        private void ensureStarted() {
            if (syncer != null) return;
            synchronized (startLock) {
                if (syncer != null) return;
                stop = false;
                var t = Thread.ofPlatform().name("ledger-syncer-shared-" + index).daemon().unstarted(this::syncLoop);
                syncer = t;
                t.start();
            }
        }

        private void stopSyncer() {
            synchronized (startLock) {
                stop = true;
                var t = syncer;
                syncer = null;
                if (t != null) {
                    LockSupport.unpark(t);
                    try {
                        t.join(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                stop = false;
            }
        }

        private void syncLoop() {
            while (!stop) {
                boolean busy = false;
                for (var w : members) {
                    if (w.sharedSyncSlice()) busy = true;
                }
                if (busy) continue;
                if (stop || members.isEmpty()) return;
                // Brief spin so an unpark that races the idle check is not delayed by a long park.
                for (int i = 0; i < 256; i++) {
                    if (stop) return;
                    for (var w : members) {
                        if (w.needsSharedSync()) {
                            busy = true;
                            break;
                        }
                    }
                    if (busy) break;
                    Thread.onSpinWait();
                }
                if (busy) continue;
                if (stop || members.isEmpty()) return;
                LockSupport.parkNanos(this, LedgerWriter.IDLE_PARK_NANOS);
            }
        }
    }
}
