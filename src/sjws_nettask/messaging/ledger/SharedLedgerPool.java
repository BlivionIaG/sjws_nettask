package sjws_nettask.messaging.ledger;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared background <em>syncer</em> threads for all {@link LedgerWriter}s on this process.
 * <p>
 * Each partition still has a dedicated writer thread ({@code ArrayBlockingQueue.take} wake), so the
 * produce / request-reply p50 path stays as on master. Syncers are pooled and sized to the CPUs so a
 * swarm with many partitions does not run one fsync loop per log (claim-wakeup #184: ~15 writer+syncer
 * threads on few CPUs). Writers unpark the shard syncer after ASYNC writes (not NONE), same as a dedicated syncer.
 * The fsync floor is enforced per member but slept at <em>shard</em> level so one member's interval
 * cannot hold off a sibling's due force (HOL).
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

    /**
     * One shared syncer. Membership and the {@code syncer} field change only under {@code startLock}, and the syncer
     * exits only after it has seen, under that lock, that the shard is empty and has cleared itself. A writer added
     * after that sees {@code syncer == null} and starts a new thread, so a member never ends up without a syncer
     * (#219 review: start/stop race left ASYNC {@code durable()} hanging).
     */
    private static final class Shard {
        final int index;
        final CopyOnWriteArrayList<LedgerWriter> members = new CopyOnWriteArrayList<>();
        final ReentrantLock startLock = new ReentrantLock();
        volatile Thread syncer;
        /**
         * Set by {@link #unpark} before {@link LockSupport#unpark}; the idle spin watches this instead of
         * locking every member (#329). No lost wakeup: {@code written} is published before the unpark,
         * {@code unpark} sets {@code pinged} before {@code LockSupport.unpark}, and a signal that arrives
         * after the {@code if (pinged)} check but before {@code parkNanos} leaves a park permit so the
         * subsequent park returns immediately and the next loop iteration re-slices members.
         */
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
            // The syncer notices the empty shard on its next idle check and retires itself.
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

        /**
         * True when the shard is empty; the syncer then clears itself under the lock. Only the running syncer clears
         * {@code syncer} and {@code add} sets it only when null, so {@code syncer == current thread} holds here.
         */
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
                long floorWait = -1;
                for (var w : members) {
                    int r = w.sharedSyncSlice();
                    if (r == LedgerWriter.SYNC_BUSY) busy = true;
                    else if (r == LedgerWriter.SYNC_FLOORED) {
                        long wait = w.sharedSyncFloorWaitNanos();
                        if (floorWait < 0 || wait < floorWait) floorWait = wait;
                    }
                }
                if (busy) continue;
                // Shard-level floor: sleep once for the soonest eligible member instead of parking inside
                // one member's slice (which blocked siblings). Mutation: omit this park and fall through to
                // IDLE_PARK_NANOS, or park inside sharedSyncSlice again, breaks the HOL bound test.
                if (floorWait >= 0) {
                    if (floorWait > 0) LockSupport.parkNanos(this, floorWait);
                    continue;
                }
                if (retire()) return;
                // Brief spin so an unpark that races the idle check is not delayed by a long park.
                // Watch {@link #pinged} instead of scanning every member on each iteration (#329 durableNow hotspot).
                for (int i = 0; i < 256 && !pinged; i++) Thread.onSpinWait();
                if (pinged) continue;
                if (retire()) return;
                LockSupport.parkNanos(this, LedgerWriter.IDLE_PARK_NANOS);
            }
        }
    }

    /** Test hooks. */
    int memberCount(int shard) {
        return shards[shard].memberCount();
    }

    Thread syncerThread(int shard) {
        return shards[shard].syncerThread();
    }
}
