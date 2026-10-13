import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Settle throughput: one consumer loops poll(1) → ack or nack over a 2.5M backlog (async durability).
 * Backoff is 10 min so nacked messages are not redelivered during the run (poll stays on the cursor path).
 * FENCED=true uses the attempt-fenced overloads; set it to false to run on a build without them.
 *
 *   java -cp target/classes bench/AckBench.java <dir> <ack|nack> <seconds>
 */
public class AckBench {
    static final boolean FENCED = true;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(args[0]), "ackbench");
        boolean nack = args[1].equals("nack");
        double secs = Double.parseDouble(args[2]);
        var payload = new byte[256];
        try (var p = Partition.open(dir, "bench", new RetryPolicy(5, 600_000, 600_000), System::currentTimeMillis,
                Durability.ASYNC, SegmentedLog.DEFAULT_SEGMENT_BYTES)) {
            p.subscribe("g", true);
            for (int i = 0; i < 2_500_000; i++) p.produce(0, 0, payload);
            p.produce(0, 0, payload, Durability.GROUP).durable().get();
            long warmEnd = System.nanoTime() + 1_000_000_000L, end = warmEnd + (long) (secs * 1e9);
            long[] lat = new long[1 << 21];
            int n = 0;
            long ops = 0;
            while (true) {
                var ds = p.poll("g", "c", 1, 600_000);
                if (ds.isEmpty()) throw new IllegalStateException("backlog exhausted");
                var d = ds.getFirst();
                long t0 = System.nanoTime();
                if (t0 >= end) break;
                boolean ok = settle(p, d.offset(), d.attempt(), nack);
                long t1 = System.nanoTime();
                if (!ok) throw new AssertionError("settle refused");
                if (t0 >= warmEnd) {
                    ops++;
                    if (n < lat.length) lat[n++] = t1 - t0;
                }
            }
            long sum = 0;
            for (int i = 0; i < n; i++) sum += lat[i];
            Arrays.sort(lat, 0, n);
            // loop = poll+settle iterations/s; settle = 1 / mean settle-call time (the part fencing changes)
            System.out.printf("%s fenced=%b loop/s=%.0f settle/s=%.0f p50=%.2fus p99=%.2fus%n", args[1], FENCED,
                    ops / secs, n * 1e9 / sum, lat[n / 2] / 1e3, lat[(int) (n * 0.99)] / 1e3);
        }
    }

    static boolean settle(Partition p, long offset, int attempt, boolean nack) {
        if (nack) return p.nack("g", offset, attempt, 0L).ok();   // FENCED
        return p.ack("g", offset, attempt).ok();                  // FENCED
    }
}
