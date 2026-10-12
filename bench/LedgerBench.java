import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Closed-loop write benchmark for the production ledger, shaped like PR #16's LogBench so numbers are
 * comparable: N producers each append one 1-record batch, wait for the ack, repeat; latency = enqueue -> ack.
 *
 *   mvn -q -DskipTests compile
 *   java -cp target/classes bench/LedgerBench.java <dir> <none|group|each> <producers> <payloadBytes> <seconds> [lingerMicros]
 */
public class LedgerBench {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(a[0]), "ledgerbench");
        var mode = Durability.valueOf(a[1].toUpperCase());
        int producers = Integer.parseInt(a[2]), size = Integer.parseInt(a[3]);
        double secs = Double.parseDouble(a[4]);
        long linger = a.length > 5 ? Long.parseLong(a[5]) * 1000 : 0;
        var payload = new byte[size];
        try (var log = SegmentedLog.open(dir); var w = new LedgerWriter(log, mode, linger, LedgerWriter.DEFAULT_QUEUE, 4 << 20)) {
            long warm = System.nanoTime() + 1_000_000_000L;
            long end = warm + (long) (secs * 1e9);
            var lat = new long[producers][];
            var counts = new int[producers];
            var threads = new Thread[producers];
            for (int p = 0; p < producers; p++) {
                int id = p;
                lat[p] = new long[1 << 20];
                threads[p] = Thread.ofPlatform().start(() -> {
                    try {
                        while (true) {
                            long t0 = System.nanoTime();
                            if (t0 > end) break;
                            w.append(List.of(payload)).get();
                            long t1 = System.nanoTime();
                            if (t0 >= warm) {
                                if (counts[id] == lat[id].length) lat[id] = Arrays.copyOf(lat[id], lat[id].length * 2);
                                lat[id][counts[id]++] = t1 - t0;
                            }
                        }
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            long f0 = -1;
            Thread.sleep(Math.max(0, (warm - System.nanoTime()) / 1_000_000));
            f0 = w.snapshot().fsyncs();
            for (var t : threads) t.join();
            long fsyncs = w.snapshot().fsyncs() - f0;
            int total = Arrays.stream(counts).sum();
            var all = new long[total];
            int k = 0;
            for (int p = 0; p < producers; p++) { System.arraycopy(lat[p], 0, all, k, counts[p]); k += counts[p]; }
            Arrays.sort(all);
            double rps = total / secs;
            System.out.printf("mode=%s producers=%d size=%dB linger=%dus rec/s=%.0f MB/s=%.1f fsyncs=%d p50=%s p99=%s p99.9=%s%n",
                    mode, producers, size, linger / 1000, rps, rps * size / 1e6, fsyncs,
                    us(all, .50), us(all, .99), us(all, .999));
        }
        try (var s = Files.walk(dir)) { s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
    }

    static String us(long[] s, double q) {
        if (s.length == 0) return "n/a";
        double v = s[Math.min(s.length - 1, (int) (q * s.length))] / 1000.0;
        return v >= 1000 ? String.format("%.2fms", v / 1000) : String.format("%.0fus", v);
    }
}
