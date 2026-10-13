import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.swarm.LeaseKeeper;
import sjws_nettask.swarm.ReplyRouter;
import sjws_nettask.swarm.Swarm;
import sjws_nettask.swarm.Task;
import sjws_nettask.swarm.TaskEnvelope;

/**
 * Swarm S1 benches against the agent-swarm.md §13 targets (single node, in-process, ASYNC ledger).
 *   dispatch <workers> <secs>   pool "p" is prefilled with 1 KiB tasks; each worker claims 1 and acks it (fenced).
 *                               Prints tasks/s and claim() latency p50/p99. Target: >= 50k tasks/s, claim p99 < 100 us.
 *   heartbeat <off|keeper> <secs>  one dispatch worker (claim+ack, 1 KiB) while 10 000 leases in pool "hold" are kept
 *                               by a LeaseKeeper ticking every 5 s (one batched extend of 10 000 per tick). Prints
 *                               dispatch tasks/s and tick latency. Target: < 2 % loss vs off.
 *   reqreply <rate> <secs>      ReplyRouter.call at <rate>/s open loop; one worker claims and replies (reply ASYNC,
 *                               then ack). Prints round-trip p50/p99. Target at 10k/s: p50 < 50 us, p99 < 500 us.
 *
 *   java -cp target/classes bench/SwarmBench.java <dir> <mode> <arg> <secs>
 */
public class SwarmBench {
    static long pct(long[] a, int n, double p) {
        var c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[Math.min(n - 1, (int) (n * p))];
    }

    static TaskEnvelope env(byte[] body) {
        var id = ReplyRouter.newId();
        return new TaskEnvelope(id, id, "", TaskEnvelope.NO_DEADLINE, "", "", "", "", body);
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(a[0]), "swarmbench");
        String mode = a[1];
        double secs = Double.parseDouble(a[3]);
        try (var s = Swarm.open(dir, "bench", 1, RetryPolicy.DEFAULT, System::currentTimeMillis, 600_000)) {
            switch (mode) {
                case "dispatch" -> dispatch(s, Integer.parseInt(a[2]), secs);
                case "heartbeat" -> heartbeat(s, a[2], secs);
                case "reqreply" -> reqreply(s, Integer.parseInt(a[2]), secs);
                default -> throw new IllegalArgumentException(mode);
            }
        }
    }

    static void dispatch(Swarm s, int workers, double secs) throws Exception {
        var t = s.tasks();
        var body = new byte[1024];
        var seq = new AtomicLong();
        // Prefilled backlog, no concurrent producer: the measurement is claim (poll) + fenced ack only.
        long backlog = (long) ((secs + 2) * 120_000);
        for (long i = 0; i < backlog; i++) t.submit("p", 1, seq.incrementAndGet(), env(body));
        long warmEnd = System.nanoTime() + 2_000_000_000L, end = warmEnd + (long) (secs * 1e9);
        var counts = new long[workers];
        var lat = new long[workers][];
        var ns = new int[workers];
        var threads = new ArrayList<Thread>();
        for (int w = 0; w < workers; w++) {
            int id = w;
            lat[w] = new long[4_000_000];
            threads.add(Thread.ofPlatform().start(() -> {
                String me = "w" + id;
                while (true) {
                    long t0 = System.nanoTime();
                    if (t0 > end) break;
                    var got = t.claim("p", me, 1, 30_000);
                    long t1 = System.nanoTime();
                    for (Task task : got) t.ack(task);
                    if (t0 >= warmEnd && !got.isEmpty()) {
                        counts[id]++;
                        if (ns[id] < lat[id].length) lat[id][ns[id]++] = t1 - t0;
                    }
                }
            }));
        }
        for (var th : threads) th.join();
        long total = Arrays.stream(counts).sum();
        int n = Arrays.stream(ns).sum();
        var all = new long[n];
        int k = 0;
        for (int w = 0; w < workers; w++) { System.arraycopy(lat[w], 0, all, k, ns[w]); k += ns[w]; }
        System.out.printf("dispatch workers=%d tasks/s=%.0f claim_p50_us=%.2f claim_p99_us=%.2f%n", workers, total / secs,
                pct(all, n, 0.50) / 1e3, pct(all, n, 0.99) / 1e3);
    }

    static void heartbeat(Swarm s, String mode, double secs) throws Exception {
        var t = s.tasks();
        int leases = 10_000;
        for (int i = 0; i < leases; i++) t.submit("hold", 1, i + 1, env(new byte[1024]));
        var held = t.claim("hold", "holder", leases, 3_600_000);
        var ticks = new ArrayList<Long>();
        LeaseKeeper keeper = null;
        Thread ticker = null;
        var stop = new AtomicBoolean();
        if (mode.equals("keeper")) {
            keeper = s.leaseKeeper(15_000, (x, r) -> { throw new AssertionError("lost lease " + r); });
            held.forEach(keeper::hold);
            var k = keeper;
            ticker = Thread.ofPlatform().name("ticker").start(() -> {
                long next = System.nanoTime();
                while (!stop.get()) {
                    next += 5_000_000_000L;
                    while (!stop.get() && System.nanoTime() < next) LockSupport.parkNanos(10_000_000L);
                    if (stop.get()) break;
                    long t0 = System.nanoTime();
                    k.tick();
                    synchronized (ticks) { ticks.add(System.nanoTime() - t0); }
                }
            });
        }
        var body = new byte[1024];
        long warmEnd = System.nanoTime() + 2_000_000_000L, end = warmEnd + (long) (secs * 1e9);
        long done = 0, seq = 0;
        while (true) {
            long now = System.nanoTime();
            if (now > end) break;
            t.submit("p", 1, ++seq, env(body));
            for (Task task : t.claim("p", "w", 1, 30_000)) t.ack(task);
            if (now >= warmEnd) done++;
        }
        stop.set(true);
        if (ticker != null) ticker.join();
        String tick = "";
        synchronized (ticks) {
            if (!ticks.isEmpty()) {
                tick = String.format(" ticks=%d tick_mean_ms=%.2f tick_max_ms=%.2f batches=%d", ticks.size(),
                        ticks.stream().mapToLong(x -> x).average().orElse(0) / 1e6,
                        ticks.stream().mapToLong(x -> x).max().orElse(0) / 1e6, keeper.batches());
            }
        }
        System.out.printf("heartbeat mode=%s dispatch_tasks/s=%.0f%s%n", mode, done / secs, tick);
    }

    static void reqreply(Swarm s, int rate, double secs) throws Exception {
        var t = s.tasks();
        var stop = new AtomicBoolean();
        var worker = Thread.ofPlatform().name("worker").start(() -> {
            long seq = 0;
            while (!stop.get()) {
                var got = t.claim("echo", "w", 64, 30_000);
                if (got.isEmpty()) { Thread.onSpinWait(); continue; }
                for (Task task : got) t.reply(task, 2, ++seq, "", task.envelope().body());
            }
        });
        long gap = 1_000_000_000L / rate;
        long warm = (long) (2e9 / gap), total = warm + (long) (secs * 1e9 / gap);
        var lat = new long[(int) (total - warm)];
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        long start = System.nanoTime();
        var body = new byte[256];
        for (long i = 0; i < total; i++) {
            long due = start + i * gap;
            while (System.nanoTime() < due) Thread.onSpinWait();
            long idx = i - warm;
            var f = s.replies().call("echo", "", body, System.currentTimeMillis() + 10_000, "");
            futures.add(f.thenAccept(r -> { if (idx >= 0) lat[(int) idx] = System.nanoTime() - due; }));
        }
        for (var f : futures) f.get(30, TimeUnit.SECONDS);
        stop.set(true);
        worker.join();
        System.out.printf("reqreply rate=%d rtt_p50_us=%.1f rtt_p99_us=%.1f rtt_max_us=%.1f n=%d%n", rate,
                pct(lat, lat.length, 0.50) / 1e3, pct(lat, lat.length, 0.99) / 1e3, pct(lat, lat.length, 1.0) / 1e3,
                lat.length);
    }
}
