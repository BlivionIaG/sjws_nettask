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
 *   batched <W>x<B> <secs>      as dispatch, but each worker claims up to B (claim max > 1) and settles them with
 *                               one batched fenced ack (TaskService.ackAll). claim p50/p99 is per claim call.
 *                               A variant, not the §13 gate.
 *   heartbeat <off|keeper> <secs>  one dispatch worker (claim+ack, 1 KiB) while 10 000 leases in pool "hold" are kept
 *                               by LeaseKeeper.start(5 s), spread: one batched extend of 100 every 50 ms. Prints
 *                               dispatch tasks/s and tick latency. Target: < 2 % loss vs off.
 *   reqreply <rate> <secs>      ReplyRouter.call at <rate>/s open loop; one worker claims (blocking, 50 ms) up to 64
 *                               and replies (ASYNC) with one batched fenced ack after the replies are durable. Prints round-trip p50/p99. Target at 10k/s: p50 < 50 us, p99 < 500 us.
 *
 *   reqreply-mw <W>x<rate> <secs>  as reqreply with W workers claiming from the same pool, each with its own
 *                               reply producer. A variant, not the S1c gate (the gate's reqreply uses one worker).
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
        if (n == 0) return 0; // a run too short to sample (the backlog drained during warm-up)
        var c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return c[Math.min(n - 1, (int) (n * p))];
    }

    static TaskEnvelope env(byte[] body) {
        var id = ReplyRouter.newId();
        return new TaskEnvelope(id, id, "", TaskEnvelope.NO_DEADLINE, "", "", "", "", body);
    }

    /** Minimum usable space on the bench directory's file system (override: -Dsjws.bench.minFreeBytes). */
    static final long MIN_FREE = Long.getLong("sjws.bench.minFreeBytes", 10L << 30);

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (java.io.IOException e) { /* best effort */ }
            });
        } catch (java.io.IOException e) {
            System.err.println("WARN: could not delete " + dir + ": " + e);
        }
    }

    /** Set when a run had failed or missing calls; main exits 3 after cleanup. */
    static volatile boolean invalid;

    public static void main(String[] a) throws Exception {
        long free = Files.getFileStore(Path.of(a[0])).getUsableSpace();
        if (free < MIN_FREE) {
            System.err.printf("ABORT: %s has %d MiB free, below %d MiB (sjws.bench.minFreeBytes); the box disk is shared%n",
                    a[0], free >> 20, MIN_FREE >> 20);
            System.exit(2);
        }
        Path dir = Files.createTempDirectory(Path.of(a[0]), "swarmbench");
        // The ledger of a run is gigabytes: always remove it (normal exit, exception, or SIGTERM/SIGINT).
        var cleanup = new Thread(() -> deleteTree(dir), "swarmbench-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            run(dir, a);
        } finally {
            deleteTree(dir);
        }
        if (invalid) System.exit(3);
    }

    static void run(Path dir, String[] a) throws Exception {
        String mode = a[1];
        double secs = Double.parseDouble(a[3]);
        try (var s = Swarm.open(dir, "bench", 1, RetryPolicy.DEFAULT, System::currentTimeMillis, 600_000)) {
            switch (mode) {
                case "dispatch" -> dispatch(s, Integer.parseInt(a[2]), secs, 1);
                case "batched" -> {
                    var wb = a[2].split("x"); // <workers>x<batch>
                    dispatch(s, Integer.parseInt(wb[0]), secs, Integer.parseInt(wb[1]));
                }
                case "heartbeat" -> heartbeat(s, a[2], secs);
                case "reqreply" -> reqreply(s, Integer.parseInt(a[2]), secs, 1);
                case "reqreply-mw" -> {
                    var wr = a[2].split("x"); // <workers>x<rate>
                    if (wr.length != 2) throw new IllegalArgumentException("reqreply-mw takes <workers>x<rate>: " + a[2]);
                    reqreply(s, Integer.parseInt(wr[1]), secs, Integer.parseInt(wr[0]));
                }
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

    static void dispatch(Swarm s, int workers, double secs, int batch) throws Exception {
        var t = s.tasks();
        var body = new byte[1024];
        var seq = new AtomicLong();
        // Prefilled backlog, no concurrent producer: the measurement is claim (poll) + fenced ack only.
        // batched and sharded pools drain faster; tasks/s is over the window the backlog actually lasted
        long backlog = (long) ((secs + 2) * (batch == 1 ? 120_000 : 400_000)) * Integer.getInteger("sjws.swarm.pool.shards", 1);
        for (long i = 0; i < backlog; i++) t.submit("p", 1, seq.incrementAndGet(), env(body));
        long warmEnd = System.nanoTime() + 2_000_000_000L, end = warmEnd + (long) (secs * 1e9);
        var counts = new long[workers];
        var lat = new long[workers][];
        var ns = new int[workers];
        var lastWork = new long[workers];
        var threads = new ArrayList<Thread>();
        for (int w = 0; w < workers; w++) {
            int id = w;
            lat[w] = new long[4_000_000];
            threads.add(Thread.ofPlatform().start(() -> {
                String me = "w" + id;
                while (true) {
                    long t0 = System.nanoTime();
                    if (t0 > end) break;
                    var got = t.claim("p", me, batch, 30_000);
                    long t1 = System.nanoTime();
                    if (batch == 1) {
                        for (Task task : got) t.ack(task);
                    } else {
                        t.ackAll(got);
                    }
                    if (t0 >= warmEnd && !got.isEmpty()) {
                        counts[id] += got.size();
                        lastWork[id] = t1;
                        if (ns[id] < lat[id].length) lat[id][ns[id]++] = t1 - t0;
                    }
                }
            }));
        }
        for (var th : threads) th.join();
        long total = Arrays.stream(counts).sum();
        long last = Arrays.stream(lastWork).max().orElse(0);
        double window = last > warmEnd && last < end - 50_000_000L ? (last - warmEnd) / 1e9 : secs; // drained early?
        if (window < secs) System.err.printf("WARN: backlog drained after %.2f of %.2f s%n", window, secs);
        int n = Arrays.stream(ns).sum();
        var all = new long[n];
        int k = 0;
        for (int w = 0; w < workers; w++) { System.arraycopy(lat[w], 0, all, k, ns[w]); k += ns[w]; }
        System.out.printf((batch == 1 ? "dispatch" : "batched batch=" + batch) + " workers=%d tasks/s=%.0f claim_p50_us=%.2f claim_p99_us=%.2f%n", workers, total / window,
                pct(all, n, 0.50) / 1e3, pct(all, n, 0.99) / 1e3);
    }

    static void heartbeat(Swarm s, String mode, double secs) throws Exception {
        var t = s.tasks();
        int leases = 10_000;
        for (int i = 0; i < leases; i++) t.submit("hold", 1, i + 1, env(new byte[1024]));
        var held = new ArrayList<Task>(leases);
        while (held.size() < leases) {
            var got = t.claim("hold", "holder", leases - held.size(), 3_600_000);
            if (got.isEmpty()) throw new IllegalStateException("held " + held.size() + " of " + leases);
            held.addAll(got);
        }
        System.err.println("heartbeat leases held=" + held.size());
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

    /** The measured-call index carried in the first 8 bytes of a reqreply body, or -1 (warm-up, short body). */
    static int hopIndex(byte[] body, long warm, int measured) {
        if (body.length < 8) return -1;
        long v = 0;
        for (int b = 0; b < 8; b++) v = (v << 8) | (body[b] & 0xff); // no ByteBuffer per task on the workers
        long k = v - warm;
        return k >= 0 && k < measured ? (int) k : -1;
    }

    static void reqreply(Swarm s, int rate, double secs, int workers) throws Exception {
        if (workers < 1) throw new IllegalArgumentException("workers >= 1: " + workers);
        if (rate < 1) throw new IllegalArgumentException("rate >= 1: " + rate);
        // Test hooks: every Nth task is acked without a reply, and calls use a short deadline, so failures are real.
        long dropEvery = Long.getLong("sjws.bench.dropEvery", 0);
        long callDeadlineMs = Long.getLong("sjws.bench.callDeadlineMs", 10_000);
        var seen = new AtomicLong();
        var t = s.tasks();
        // Per-hop timestamps (absolute nanoTime, by call index; the index rides in the first 8 body bytes):
        // submitted = call() returned, claimed = claim() returned it, written = replyAll() returned for its batch.
        // Derived once: the sender loop, lat[] and hopIndex all use these. A redelivery (dropEvery > 0) overwrites
        // claimed[k], so queue_to_claim then measures the last attempt, not the first.
        long gap = 1_000_000_000L / rate;
        long warm = (long) (2e9 / gap), total = warm + (long) (secs * 1e9 / gap);
        int measured = (int) (total - warm);
        long warm0 = warm;
        var submitted = new long[measured];
        var claimed = new long[measured];
        var written = new long[measured];
        var stop = new AtomicBoolean();
        var pool = new ArrayList<Thread>(workers);
        for (int w = 0; w < workers; w++) {
            long producer = 2 + w; // one reply producer per worker, so dedup windows never collide
            String consumer = "w" + w; // a distinct consumer per worker
            pool.add(Thread.ofPlatform().name("worker-" + w).start(() -> {
                long seq = 0;
                while (!stop.get()) {
                    // blocking claim (#99, 50 ms), then one reply per task and one batched fenced ack (#100)
                    var got = t.claim("echo", consumer, 64, 30_000, 50_000_000L);
                    if (got.isEmpty()) continue;
                    long tc = System.nanoTime();
                    for (Task task : got) {
                        int k = hopIndex(task.envelope().body(), warm0, measured);
                        if (k >= 0) claimed[k] = tc;
                    }
                    if (dropEvery > 0) {
                        var keep = new ArrayList<Task>(got.size());
                        for (Task task : got) {
                            if (seen.incrementAndGet() % dropEvery == 0) t.ack(task); else keep.add(task);
                        }
                        got = keep;
                        if (got.isEmpty()) continue;
                    }
                    var bodies = new ArrayList<byte[]>(got.size());
                    for (Task task : got) bodies.add(task.envelope().body());
                    t.replyAll(got, producer, seq + 1, "", bodies);
                    long tw = System.nanoTime();
                    for (byte[] b : bodies) {
                        int k = hopIndex(b, warm0, measured);
                        if (k >= 0) written[k] = tw;
                    }
                    seq += got.size();
                }
            }));
        }
        var lat = new long[measured];
        java.util.Arrays.fill(lat, -1);
        var done = new AtomicLong();
        var ok = new AtomicLong();
        var failed = new AtomicLong();
        long start = System.nanoTime();
        var delivered = new long[lat.length];
        // Bodies preallocated before the clock starts (each carries its call index), so the timed loop allocates
        // nothing per call: the same workload as the old single reused buffer, plus 8 index bytes.
        var calls = new byte[(int) total][];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = new byte[256];
            for (int b = 0; b < 8; b++) calls[i][b] = (byte) (i >>> (56 - 8 * b));
        }
        for (long i = 0; i < total; i++) {
            long due = start + i * gap;
            while (System.nanoTime() < due) Thread.onSpinWait();
            long idx = i - warm;
            var body = calls[(int) i];
            var f = s.replies().call("echo", "", body, System.currentTimeMillis() + callDeadlineMs, "");
            if (idx >= 0) submitted[(int) idx] = System.nanoTime();
            f.whenComplete((r, e) -> {
                if (idx >= 0) {
                    if (e == null) {
                        long td = System.nanoTime();
                        delivered[(int) idx] = td;
                        lat[(int) idx] = td - due;
                        ok.incrementAndGet();
                    } else {
                        failed.incrementAndGet(); // a failure is a failure, never a 0 us sample
                    }
                }
                done.incrementAndGet();
            });
        }
        long until = System.nanoTime() + 30_000_000_000L;
        while (done.get() < total && System.nanoTime() < until) Thread.sleep(1);
        stop.set(true);
        for (var w : pool) w.join();
        // percentiles over completed calls only; failed and still-pending (missing) calls are reported separately
        var okLat = java.util.Arrays.stream(lat).filter(v -> v >= 0).toArray();
        int n = okLat.length;
        long missing = lat.length - n - failed.get();
        System.out.printf("reqreply workers=%d rate=%d rtt_p50_us=%.1f rtt_p99_us=%.1f rtt_p999_us=%.1f rtt_max_us=%.1f n=%d ok=%d failed=%d missing=%d%n",
                workers, rate, n == 0 ? 0 : pct(okLat, n, 0.50) / 1e3, n == 0 ? 0 : pct(okLat, n, 0.99) / 1e3,
                n == 0 ? 0 : pct(okLat, n, 0.999) / 1e3, n == 0 ? 0 : pct(okLat, n, 1.0) / 1e3, n, ok.get(), failed.get(), missing);
        // per-hop p50/p99 over completed calls with every timestamp present
        long[][] hop = new long[4][lat.length];
        int h = 0;
        for (int k = 0; k < lat.length; k++) {
            if (lat[k] < 0 || claimed[k] == 0 || written[k] == 0) continue;
            long due = start + (k + warm) * gap;
            hop[0][h] = submitted[k] - due;
            hop[1][h] = claimed[k] - submitted[k];
            hop[2][h] = written[k] - claimed[k];
            // With completeLocal, the caller may finish before replyAll returns; clamp so the hop stays non-negative.
            hop[3][h] = Math.max(0L, delivered[k] - written[k]);
            h++;
        }
        String[] names = {"submit", "queue_to_claim", "claim_to_reply_written", "reply_delivered"};
        var sb = new StringBuilder("reqreply hops n=" + h);
        for (int x = 0; x < 4; x++) {
            var v = java.util.Arrays.copyOf(hop[x], h);
            sb.append(String.format(" %s_p50_us=%.1f %s_p99_us=%.1f %s_p999_us=%.1f", names[x],
                    h == 0 ? 0 : pct(v, h, 0.50) / 1e3, names[x], h == 0 ? 0 : pct(v, h, 0.99) / 1e3,
                    names[x], h == 0 ? 0 : pct(v, h, 0.999) / 1e3));
        }
        System.out.println(sb);
        if (failed.get() + missing > 0) {
            System.out.println("INVALID: " + failed.get() + " failed, " + missing + " missing of " + lat.length);
            invalid = true;
        }
    }
}
