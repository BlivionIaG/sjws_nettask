import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Worker settling a leased batch: poll(B), then settle all B with the given durability and wait until durable.
 * mode single = B fenced acks (one ledger record and future each; wait on the last), batch = one ack(offsets[], attempts[]).
 *
 *   java -cp target/classes bench/BatchAckBench.java <dir> <single|batch> <B> <ASYNC|GROUP|EACH> <seconds>
 */
public class BatchAckBench {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(args[0]), "batchack");
        boolean batch = args[1].equals("batch");
        int b = Integer.parseInt(args[2]);
        var dur = Durability.valueOf(args[3]);
        double secs = Double.parseDouble(args[4]);
        try (var p = Partition.open(dir, "bench", new RetryPolicy(5, 600_000, 600_000), System::currentTimeMillis,
                Durability.ASYNC, SegmentedLog.DEFAULT_SEGMENT_BYTES)) {
            p.subscribe("g", true);
            var payload = new byte[256];
            for (int i = 0; i < 2_000_000; i++) p.produce(0, 0, payload);
            p.produce(0, 0, payload, Durability.GROUP).durable().get();
            long warmEnd = System.nanoTime() + 1_000_000_000L, end = warmEnd + (long) (secs * 1e9);
            long msgs = 0;
            var offs = new long[b];
            var atts = new int[b];
            while (true) {
                var ds = p.poll("g", "c", b, 600_000);
                if (ds.size() < b) throw new IllegalStateException("backlog exhausted");
                long t0 = System.nanoTime();
                if (t0 >= end) break;
                CompletableFuture<Long> last = null;
                if (batch) {
                    for (int i = 0; i < b; i++) { offs[i] = ds.get(i).offset(); atts[i] = ds.get(i).attempt(); }
                    last = p.ack("g", offs, atts, dur).durable();
                } else {
                    for (var d : ds) last = p.ack("g", d.offset(), d.attempt(), dur).durable();
                }
                last.get();
                if (t0 >= warmEnd) msgs += b;
            }
            System.out.printf("%s B=%d %s acked/s=%.0f%n", args[1], b, dur, msgs / secs);
        }
    }
}
