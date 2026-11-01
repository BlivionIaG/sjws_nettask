import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.GroupId;

/**
 * Closed-loop FileLogStorage append (+ periodic force) microbench for #207 CRC cost.
 *
 * <pre>java -cp target/classes bench/FileLogAppendBench.java DIR BYTES FORCE_EVERY SECONDS</pre>
 */
public class FileLogAppendBench {
    public static void main(String[] a) throws Exception {
        var dir = Path.of(a[0]);
        int bytes = Integer.parseInt(a[1]);
        int forceEvery = Integer.parseInt(a[2]);
        double secs = Double.parseDouble(a[3]);
        Files.createDirectories(dir);
        var group = new GroupId("bench/flog");
        var payload = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(payload);
        var log = new FileLogStorage(dir);
        try {
            long warmUntil = System.nanoTime() + (long) (1.0 * 1_000_000_000L);
            long index = 1;
            while (System.nanoTime() < warmUntil) {
                log.append(group, index, 1L, payload);
                if (index % forceEvery == 0) {
                    log.force(group);
                }
                index++;
            }
            log.force(group);
            long end = System.nanoTime() + (long) (secs * 1_000_000_000L);
            long counted = 0;
            long t0 = System.nanoTime();
            while (System.nanoTime() < end) {
                log.append(group, index, 1L, payload);
                if (index % forceEvery == 0) {
                    log.force(group);
                }
                index++;
                counted++;
            }
            log.force(group);
            double elapsed = (System.nanoTime() - t0) / 1e9;
            double ops = counted / elapsed;
            System.out.printf("filelog-append bytes=%d forceEvery=%d ops/s=%.1f n=%d secs=%.3f%n",
                    bytes, forceEvery, ops, counted, elapsed);
        } finally {
            log.close();
        }
    }
}
