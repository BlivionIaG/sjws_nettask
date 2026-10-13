import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;
import sjws_nettask.core.buf.BufferPool;

/**
 * Microbench for core.buf.BufferPool: acquire + one write + release of a 2 KiB buffer, ns/op and bytes allocated per
 * op on the measuring thread (ThreadMXBean), best of 5 one-second rounds after warm-up.
 *   java -cp target/classes bench/BufferPoolBench.java [size=2048] [threads=1,8]
 * Cases: plain heap ByteBuffer.allocate (what callers do today), BufferPool.disabled() (pool off), pool on with leak
 * tracking off / 1 in 64 (default) / every acquire, on a platform thread (magazine) and on virtual threads.
 */
public class BufferPoolBench {
    static volatile Object sink;

    interface Op { void run(); }

    public static void main(String[] a) throws Exception {
        int size = a.length > 0 ? Integer.parseInt(a[0]) : 2048;
        var threads = a.length > 1 ? a[1].split(",") : new String[] {"1", "8"};
        var off = BufferPool.disabled();
        var s0 = BufferPool.create(new BufferPool.Settings(true, 256L << 20, Map.of(BufferPool.MESSAGING, 0.5), false, 0, 8));
        var s64 = BufferPool.create(new BufferPool.Settings(true, 256L << 20, Map.of(BufferPool.MESSAGING, 0.5), false, 64, 8));
        var s1 = BufferPool.create(new BufferPool.Settings(true, 256L << 20, Map.of(BufferPool.MESSAGING, 0.5), false, 1, 8));
        var noMag = BufferPool.create(new BufferPool.Settings(true, 256L << 20, Map.of(BufferPool.MESSAGING, 0.5), false, 64, 0));
        Op heap = () -> { var b = ByteBuffer.allocate(size); b.putLong(0, 1); sink = b; };
        Op disabled = poolOp(off, size);
        Op on0 = poolOp(s0, size), on64 = poolOp(s64, size), on1 = poolOp(s1, size), onNoMag = poolOp(noMag, size);
        for (var t : threads) {
            int n = Integer.parseInt(t);
            run("heap allocate", heap, n, false);
            run("pool off (disabled)", disabled, n, false);
            run("pool on, leak sample 0", on0, n, false);
            run("pool on, leak sample 1/64", on64, n, false);
            run("pool on, leak sample 1/1", on1, n, false);
            run("pool on, 1/64, no magazine", onNoMag, n, false);
            run("pool on, 1/64, virtual threads", on64, n, true);
        }
    }

    static Op poolOp(BufferPool p, int size) {
        var http = p.budget(BufferPool.HTTP);
        return () -> { var b = http.acquire(size); b.buffer().putLong(0, 1); b.release(); };
    }

    static void run(String name, Op op, int threads, boolean virtual) throws Exception {
        double best = Double.MAX_VALUE, bytes = 0;
        for (int round = 0; round < 7; round++) {
            var ops = new LongAdder();
            var alloc = new LongAdder();
            var ts = new Thread[threads];
            long end = System.nanoTime() + 1_000_000_000L;
            for (int i = 0; i < threads; i++) {
                Runnable body = () -> {
                    var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
                    long a0 = virtual ? 0 : mx.getCurrentThreadAllocatedBytes();
                    long n = 0;
                    while (System.nanoTime() < end) {
                        for (int k = 0; k < 1000; k++) op.run();
                        n += 1000;
                    }
                    if (!virtual) alloc.add(mx.getCurrentThreadAllocatedBytes() - a0);
                    ops.add(n);
                };
                ts[i] = virtual ? Thread.ofVirtual().start(body) : Thread.ofPlatform().start(body);
            }
            for (var t : ts) t.join();
            if (round < 2) continue; // warm-up
            double ns = 1e9 * threads / ops.sum();
            if (ns < best) { best = ns; bytes = virtual ? Double.NaN : (double) alloc.sum() / ops.sum(); }
        }
        System.out.printf("%-34s threads=%d  %7.1f ns/op per thread  %s%n", name, threads, best,
                Double.isNaN(bytes) ? "alloc n/a (virtual)" : String.format("%.1f B/op", bytes));
    }
}
