import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sjws_nettask.messaging.delivery.LedgerInspector;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Closed-loop benchmark for the delivery layer (same style as LedgerBench). Latency = call → durable ack
 * (produce/ack) or call → return (poll). 1 s warm-up, then {@code seconds} measured.
 *
 *   mvn -q -DskipTests compile
 *   java -cp target/classes bench/DeliveryBench.java <dir> <async|group> produce <producers> <seconds>
 *   java -cp target/classes bench/DeliveryBench.java <dir> <async|group> consume <consumers> <seconds>
 *   java -cp target/classes bench/DeliveryBench.java <dir> <async|group> inspect <producers> <seconds> <consumers> <readers> [spin]
 *   (readers loop on LedgerInspector counts + a 50-item IN_FLIGHT page; "spin" = CPU-burning control readers)
 */
public class DeliveryBench {
    static final int SIZE = 256;
    static volatile long warmEnd, end;

    static final class Rec {
        long[] v = new long[1 << 16];
        int n;
        void add(long t0, long t1) { if (t0 < warmEnd) return; if (n == v.length) v = Arrays.copyOf(v, n * 2); v[n++] = t1 - t0; }
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(a[0]), "deliverybench");
        var mode = Durability.valueOf(a[1].toUpperCase());
        String scenario = a[2];
        int n = Integer.parseInt(a[3]);
        double secs = Double.parseDouble(a[4]);
        int consumers = a.length > 5 ? Integer.parseInt(a[5]) : 0, readers = a.length > 6 ? Integer.parseInt(a[6]) : 0;
        boolean spinControl = a.length > 7 && a[7].equals("spin"); // readers burn CPU without touching the inspector
        var payload = new byte[SIZE];
        try (var p = Partition.open(dir, "bench", RetryPolicy.DEFAULT, System::currentTimeMillis, mode, SegmentedLog.DEFAULT_SEGMENT_BYTES)) {
            p.subscribe("g", true);
            if (scenario.equals("consume")) { // prefill enough for the run
                long target = (long) (n * 60_000 * (secs + 1));
                target = Math.min(target, 1_200_000);
                for (long i = 0; i < target; i++) p.produce(0, 0, payload);
                p.produce(0, 0, payload, Durability.GROUP).durable().get();
            }
            warmEnd = System.nanoTime() + 1_000_000_000L;
            end = warmEnd + (long) (secs * 1e9);
            var stop = new AtomicBoolean();
            var threads = new java.util.ArrayList<Thread>();
            var produceRecs = new Rec[scenario.equals("consume") ? 0 : n];
            var pollRecs = new Rec[scenario.equals("consume") ? n : consumers];
            var ackRecs = new Rec[pollRecs.length];
            var readRecs = new Rec[readers];
            var seq = new AtomicLong();
            for (int i = 0; i < produceRecs.length; i++) {
                var r = produceRecs[i] = new Rec();
                long pid = i + 1;
                threads.add(Thread.ofPlatform().start(() -> run(() -> {
                    long t0 = System.nanoTime();
                    p.produce(pid, seq.incrementAndGet(), payload).durable().get();
                    r.add(t0, System.nanoTime());
                })));
            }
            for (int i = 0; i < pollRecs.length; i++) {
                var pr = pollRecs[i] = new Rec();
                var ar = ackRecs[i] = new Rec();
                String c = "c" + i;
                threads.add(Thread.ofPlatform().start(() -> run(() -> {
                    long t0 = System.nanoTime();
                    var d = p.poll("g", c, 1, 30_000);
                    long t1 = System.nanoTime();
                    if (d.isEmpty()) { Thread.onSpinWait(); return; }
                    pr.add(t0, t1);
                    p.ack("g", d.getFirst().offset(), null).get();
                    ar.add(t1, System.nanoTime());
                })));
            }
            var insp = new LedgerInspector(p);
            for (int i = 0; i < readers; i++) {
                var r = readRecs[i] = new Rec();
                threads.add(Thread.ofPlatform().start(() -> run(() -> {
                    long t0 = System.nanoTime();
                    if (spinControl) { for (int k = 0; k < 20; k++) Thread.onSpinWait(); r.add(t0, System.nanoTime()); return; }
                    var c = insp.counts("g");
                    insp.page("g", sjws_nettask.messaging.delivery.MessageState.IN_FLIGHT, 0, 50);
                    if (c.acked() < 0) throw new AssertionError();
                    r.add(t0, System.nanoTime());
                })));
            }
            for (var t : threads) t.join();
            stop.set(true);
            System.out.printf("mode=%s scenario=%s n=%d consumers=%d readers=%d%s%n", mode, scenario, n, consumers, readers, spinControl ? " (spin control)" : "");
            report("produce", produceRecs, secs);
            report("poll", pollRecs, secs);
            report("ack", ackRecs, secs);
            report("inspect", readRecs, secs);
        }
        try (var s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> x.toFile().delete()); }
    }

    interface Op { void run() throws Exception; }

    static void run(Op op) {
        try { while (System.nanoTime() < end) op.run(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void report(String name, Rec[] recs, double secs) {
        if (recs.length == 0) return;
        int total = 0;
        for (var r : recs) total += r.n;
        var all = new long[total];
        int k = 0;
        for (var r : recs) { System.arraycopy(r.v, 0, all, k, r.n); k += r.n; }
        Arrays.sort(all);
        System.out.printf("  %-8s ops/s=%.0f p50=%s p99=%s%n", name, total / secs, us(all, .5), us(all, .99));
    }

    static String us(long[] s, double q) {
        if (s.length == 0) return "n/a";
        double v = s[Math.min(s.length - 1, (int) (q * s.length))] / 1000.0;
        return v >= 1000 ? String.format("%.2fms", v / 1000) : String.format("%.1fus", v);
    }
}
