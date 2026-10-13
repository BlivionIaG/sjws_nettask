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
 *                               by LeaseKeeper.start(5 s), spread: one batched extend of 100 every 50 ms. Prints
 *                               dispatch tasks/s and tick latency. Target: < 2 % loss vs off.
 *   reqreply <rate> <secs>      ReplyRouter.call at <rate>/s open loop; one worker claims (blocking, 50 ms) up to 64
 *                               and replies (ASYNC) with one batched fenced ack after the replies are durable. Prints round-trip p50/p99. Target at 10k/s: p50 < 50 us, p99 < 500 us.
 *
 *   http <token|-> <secs>        /swarm/v1 in process through SwarmModule's mounted handler: submit (1 KiB TASK
 *                               frame) + claim + ack per round, one thread. Sends "Authorization: Bearer <token>"
 *                               unless "-". Run with -Dsjws.swarm.tokens.file=... for gate on; against a master
 *                               build (no gate) for gate off. Prints rounds/s and per-call p50/p99.
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
                case "http" -> http(dir, a[2], secs);
                default -> throw new IllegalArgumentException(mode);
            }
        }
    }

    static sjws_nettask.core.RequestBody body(byte[] data) {
        return new sjws_nettask.core.RequestBody() {
            int pos;

            public long length() { return data.length; }

            public int read(byte[] into, int off, int len) {
                if (pos >= data.length) return -1;
                int n = Math.min(len, data.length - pos);
                System.arraycopy(data, pos, into, off, n);
                pos += n;
                return n;
            }
        };
    }

    static void http(Path dir, String token, double secs) throws Exception {
        System.setProperty("sjws.swarm.dir", dir.resolve("http").toString());
        var module = new sjws_nettask.swarm.SwarmModule();
        sjws_nettask.core.Handler fallback = r -> sjws_nettask.core.StaticFileHandler.error(404, false);
        var h = sjws_nettask.core.HandlerMounts.install(java.util.List.of(module), fallback);
        var fields = new java.util.HashMap<String, java.util.List<String>>();
        if (!token.equals("-")) fields.put("authorization", java.util.List.of("Bearer " + token));
        var headers = sjws_nettask.core.RequestHeaders.of(fields);
        var task = new sjws_nettask.swarm.wire.Frame(sjws_nettask.swarm.wire.Frame.Type.TASK, 0, new byte[1024]).encode();
        var lat = new long[12_000_000];
        int n = 0;
        long rounds = 0;
        long warmEnd = System.nanoTime() + 2_000_000_000L, end = warmEnd + (long) (secs * 1e9);
        while (true) {
            long t0 = System.nanoTime();
            if (t0 > end) break;
            var r1 = h.handle(new sjws_nettask.core.Request("POST", "/swarm/v1/tasks/p", headers, null, false, null, body(task)));
            long t1 = System.nanoTime();
            var r2 = h.handle(new sjws_nettask.core.Request("POST", "/swarm/v1/tasks/p:claim", headers, null, false, null, body(new byte[0])));
            long t2 = System.nanoTime();
            if (r1.status() != 202 || r2.status() != 200) throw new IllegalStateException("status " + r1.status() + "/" + r2.status());
            var b = ((sjws_nettask.core.Body.Bytes) r2.body()).data().duplicate();
            var f = sjws_nettask.swarm.wire.Frame.decode(b);
            var p = java.nio.ByteBuffer.wrap(f.payload());
            var id = new byte[p.getShort()];
            p.get(id);
            var lease = java.net.URLEncoder.encode(new String(id, java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8);
            long t3 = System.nanoTime();
            var r3 = h.handle(new sjws_nettask.core.Request("POST", "/swarm/v1/leases/" + lease + ":ack", headers, null, false, null, body(new byte[0])));
            long t4 = System.nanoTime();
            if (r3.status() != 204) throw new IllegalStateException("ack " + r3.status());
            if (t0 >= warmEnd && n + 3 <= lat.length) {
                lat[n++] = t1 - t0; lat[n++] = t2 - t1; lat[n++] = t4 - t3;
                rounds++;
            }
        }
        module.close();
        System.out.printf("http rounds/s=%.0f call_p50_us=%.2f call_p99_us=%.2f%n", rounds / secs, pct(lat, n, 0.50) / 1e3,
                pct(lat, n, 0.99) / 1e3);
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
        LeaseKeeper keeper = null;
        var stop = new AtomicBoolean();
        if (mode.equals("keeper")) {
            keeper = s.leaseKeeper(15_000, (x, r) -> { throw new AssertionError("lost lease " + r); });
            held.forEach(keeper::hold);
            keeper.start(5_000); // spread: one batched extend of LeaseKeeper.SLICE leases every 50 ms
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
        String tick = "";
        if (keeper != null) {
            keeper.close();
            tick = " batches=" + keeper.batches();
        }
        System.out.printf("heartbeat mode=%s dispatch_tasks/s=%.0f%s%n", mode, done / secs, tick);
    }

    static void reqreply(Swarm s, int rate, double secs) throws Exception {
        var t = s.tasks();
        var stop = new AtomicBoolean();
        var worker = Thread.ofPlatform().name("worker").start(() -> {
            long seq = 0;
            while (!stop.get()) {
                // blocking claim (#99, 50 ms), then one reply per task and one batched fenced ack (#100)
                var got = t.claim("echo", "w", 64, 30_000, 50_000_000L);
                if (got.isEmpty()) continue;
                var bodies = new ArrayList<byte[]>(got.size());
                for (Task task : got) bodies.add(task.envelope().body());
                t.replyAll(got, 2, seq + 1, "", bodies);
                seq += got.size();
            }
        });
        long gap = 1_000_000_000L / rate;
        long warm = (long) (2e9 / gap), total = warm + (long) (secs * 1e9 / gap);
        var lat = new long[(int) (total - warm)];
        var done = new AtomicLong();
        long start = System.nanoTime();
        var body = new byte[256];
        for (long i = 0; i < total; i++) {
            long due = start + i * gap;
            while (System.nanoTime() < due) Thread.onSpinWait();
            long idx = i - warm;
            var f = s.replies().call("echo", "", body, System.currentTimeMillis() + 10_000, "");
            f.whenComplete((r, e) -> { if (e == null && idx >= 0) lat[(int) idx] = System.nanoTime() - due; done.incrementAndGet(); });
        }
        long until = System.nanoTime() + 30_000_000_000L;
        while (done.get() < total && System.nanoTime() < until) Thread.sleep(1);
        stop.set(true);
        worker.join();
        System.out.printf("reqreply rate=%d rtt_p50_us=%.1f rtt_p99_us=%.1f rtt_max_us=%.1f n=%d%n", rate,
                pct(lat, lat.length, 0.50) / 1e3, pct(lat, lat.length, 0.99) / 1e3, pct(lat, lat.length, 1.0) / 1e3,
                lat.length);
    }
}
