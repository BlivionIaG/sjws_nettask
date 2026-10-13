import java.nio.file.Files;
import java.nio.file.Path;

import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Recovery time and resident heap for a partition holding {@code messages} unacked 256 B messages
 * (plus one poll+ack per 4 messages so group state is non-trivial).
 *
 *   java -Xmx4g -cp target/classes bench/RecoveryBench.java <dir> <messages>
 * Prints: log bytes, heap/message while live, reopen time with snapshots, reopen time with snapshots deleted.
 */
public class RecoveryBench {
    static long heap() throws InterruptedException {
        for (int i = 0; i < 4; i++) { System.gc(); Thread.sleep(100); }
        var r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(a[0]), "recoverybench");
        long n = Long.parseLong(a[1]);
        var payload = new byte[256];
        long h0 = heap(), h1, bytes;
        long t0 = System.nanoTime();
        try (var p = Partition.open(dir, "bench", RetryPolicy.DEFAULT, System::currentTimeMillis, Durability.ASYNC, SegmentedLog.DEFAULT_SEGMENT_BYTES)) {
            p.subscribe("g", true);
            for (long i = 0; i < n; i++) {
                p.produce(0, 0, payload);
                if ((i & 3) == 0) for (var d : p.poll("g", "c", 1, 60_000)) p.ack("g", d.offset());
            }
            p.produce(0, 0, payload, Durability.GROUP).durable().get();
            double w = (System.nanoTime() - t0) / 1e9;
            System.out.printf("write: %d msgs in %.2f s (%.0f msg/s)%n", n, w, n / w);
            Thread.sleep(2000); // let background snapshots land
            h1 = heap();
            bytes = dirBytes(dir, ".log");
            System.out.printf("log: %.3f GiB, heap while live: %.1f MiB = %.1f B/msg%n", bytes / 1073741824.0, (h1 - h0) / 1048576.0, (double) (h1 - h0) / n);
        }
        System.out.printf("reopen (snapshots):    %s%n", reopen(dir, bytes));
        try (var s = Files.walk(dir)) { for (var f : s.filter(x -> x.toString().endsWith(".snap")).toList()) Files.delete(f); }
        System.out.printf("reopen (no snapshot):  %s%n", reopen(dir, bytes));
    }

    static String reopen(Path dir, long bytes) throws Exception {
        long t = System.nanoTime();
        try (var p = Partition.open(dir, "bench", RetryPolicy.DEFAULT, System::currentTimeMillis, Durability.ASYNC, SegmentedLog.DEFAULT_SEGMENT_BYTES)) {
            double s = (System.nanoTime() - t) / 1e9;
            return String.format("%.3f s = %.3f s/GiB (next=%d)", s, s / (bytes / 1073741824.0), p.counts().nextOffset());
        }
    }

    static long dirBytes(Path d, String suf) throws Exception {
        try (var s = Files.walk(d)) { return s.filter(x -> x.toString().endsWith(suf)).mapToLong(x -> x.toFile().length()).sum(); }
    }
}
