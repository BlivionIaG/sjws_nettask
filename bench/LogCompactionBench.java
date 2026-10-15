import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import sjws_nettask.messaging.http.LogCompaction;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Key-compaction throughput (docs/design/durable-log-plan.md §1): fills a fresh log with {@code <records>} single-record
 * batches of {@code <bytes>} cycling over {@code <keys>} keys ({@code [klen:int][key][value]}), then times one full pass
 * (key map, copies + fsync + markers, swaps) over its sealed segments. Prints input MB/s and records/s.
 *
 *   java -cp target/classes bench/LogCompactionBench.java <dir> <records> <bytes> <keys> [segmentBytes]
 */
public class LogCompactionBench {

    public static void main(String[] args) throws Exception {
        var dir = Path.of(args[0]);
        int records = Integer.parseInt(args[1]);
        int bytes = Integer.parseInt(args[2]);
        int keys = Integer.parseInt(args[3]);
        long segBytes = args.length > 4 ? Long.parseLong(args[4]) : 64L << 20;
        Files.createDirectories(dir);
        try (var w = Files.list(dir)) { for (var p : w.toList()) Files.delete(p); }
        try (var log = SegmentedLog.open(dir, segBytes)) {
            var batch = new ArrayList<byte[]>(1);
            batch.add(null);
            for (int i = 0; i < records; i++) {
                var k = ("key-" + (i % keys)).getBytes();
                var b = ByteBuffer.allocate(Math.max(bytes, 4 + k.length)).putInt(k.length).put(k);
                batch.set(0, b.array());
                log.append(batch, i);
            }
            log.force();
            long sealedBytes = 0;
            int sealed = 0;
            for (var s : log.segments()) if (!s.active()) { sealedBytes += s.bytes(); sealed++; }
            long t0 = System.nanoTime();
            List<LogCompaction.Prepared> ps = LogCompaction.prepare(log);
            long t1 = System.nanoTime();
            long after = 0;
            for (var p : ps) { log.commitCompaction(p.baseOffset()); after += p.bytesAfter(); }
            long t2 = System.nanoTime();
            double secs = (t2 - t0) / 1e9;
            long sealedRecords = log.segments().stream().filter(s -> !s.active()).mapToLong(s -> s.nextOffset() - s.baseOffset()).sum();
            System.out.printf("compaction records=%d bytes=%d keys=%d sealedSegs=%d sealedMB=%.1f rewritten=%d afterMB=%.2f "
                    + "prepare_ms=%.1f swap_ms=%.1f MB/s=%.1f records/s=%.0f%n",
                    records, bytes, keys, sealed, sealedBytes / 1e6, ps.size(), after / 1e6,
                    (t1 - t0) / 1e6, (t2 - t1) / 1e6, sealedBytes / 1e6 / secs, sealedRecords / secs);
        }
    }
}
