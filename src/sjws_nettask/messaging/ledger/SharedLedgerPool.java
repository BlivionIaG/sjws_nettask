package sjws_nettask.messaging.ledger;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Shared writer and syncer threads for all {@link LedgerWriter}s on this process.
 * <p>
 * Without this, every partition starts its own writer + syncer (~2 threads each). Under swarm
 * request/reply that is easily a dozen threads waking on every append while producers hold
 * {@code Partition.lock}, so a lock holder that is descheduled stalls submit, claim and reply
 * together (claim-wakeup write-up on #184).
 * <p>
 * Sized to the CPUs by default ({@value #PROPERTY} unset or {@code auto}). Set the property to
 * {@code 0} / {@code false} for the legacy per-writer threads. No threads run until the first
 * writer registers (zero cost when messaging is unused).
 */
public final class SharedLedgerPool {

    /** {@code 0}/{@code false} = per-writer threads; unset/{@code auto}/{@code true} = CPU count; or an int. */
    public static final String PROPERTY = "sjws.messaging.ledger.sharedWriters";

    private static final class Holder {
        static final SharedLedgerPool INSTANCE = new SharedLedgerPool(resolveSize());
    }

    /** Shared pool when size &gt; 0, else {@code null} (callers use dedicated threads). */
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
    private final AtomicInteger rr = new AtomicInteger();

    private SharedLedgerPool(int n) {
        if (n == 0) {
            this.shards = new Shard[0];
            return;
        }
        this.shards = new Shard[n];
        for (int i = 0; i < n; i++) {
            this.shards[i] = new Shard(i);
        }
    }

    int size() {
        return shards.length;
    }

    /** Registers {@code w} on a shard; returns the shard index for unpark. */
    int register(LedgerWriter w) {
        int i = Math.floorMod(rr.getAndIncrement(), shards.length);
        shards[i].add(w);
        return i;
    }

    void unregister(LedgerWriter w, int shard) {
        if (shard < 0 || shard >= shards.length) return;
        shards[shard].remove(w);
    }

    void unparkWriter(int shard) {
        if (shard >= 0 && shard < shards.length) LockSupport.unpark(shards[shard].writer);
    }

    void unparkSyncer(int shard) {
        if (shard >= 0 && shard < shards.length) LockSupport.unpark(shards[shard].syncer);
    }

    private static final class Shard {
        final int index;
        final CopyOnWriteArrayList<LedgerWriter> members = new CopyOnWriteArrayList<>();
        final Thread writer;
        final Thread syncer;
        volatile boolean stop;

        Shard(int index) {
            this.index = index;
            this.writer = Thread.ofPlatform().name("ledger-writer-shared-" + index).daemon().unstarted(this::writeLoop);
            this.syncer = Thread.ofPlatform().name("ledger-syncer-shared-" + index).daemon().unstarted(this::syncLoop);
            this.writer.start();
            this.syncer.start();
        }

        void add(LedgerWriter w) {
            members.addIfAbsent(w);
            LockSupport.unpark(writer);
            LockSupport.unpark(syncer);
        }

        void remove(LedgerWriter w) {
            members.remove(w);
        }

        private void writeLoop() {
            while (!stop) {
                boolean any = false;
                for (var w : members) {
                    if (w.drainAndCommit()) any = true;
                }
                if (!any) {
                    if (stop) return;
                    LockSupport.parkNanos(this, 100_000L); // 100 us: wake on offer unpark or backstop
                }
            }
        }

        private void syncLoop() {
            while (!stop) {
                boolean busy = false;
                for (var w : members) {
                    if (w.sharedSyncSlice()) busy = true;
                }
                if (!busy) {
                    if (stop) return;
                    LockSupport.parkNanos(this, LedgerWriter.IDLE_PARK_NANOS);
                }
            }
        }
    }
}
