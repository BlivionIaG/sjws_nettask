import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Heartbeat cost: a dispatch loop (produce → poll → ack, 1 KiB, one thread) runs while 10 000 leases held by group
 * "hold" are heartbeated every 5 s, spread evenly over the period:
 *   off     no heartbeats
 *   batched one extend(offsets[], attempts[]) of 100 leases every 50 ms  (20 records/s)
 *   single  one extend(offset, attempt) every 0.5 ms                     (2 000 records/s)
 *   idle    control: the heartbeat thread wakes on the batched schedule and does nothing
 *
 *   java -cp target/classes bench/HeartbeatBench.java <dir> <off|batched|single> <seconds>
 */
public class HeartbeatBench {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(a[0]), "hbbench");
        String mode = a[1];
        double secs = Double.parseDouble(a[2]);
        int leases = 10_000, batch = 100;
        long periodNs = 5_000_000_000L;
        try (var p = Partition.open(dir, "bench", RetryPolicy.DEFAULT, System::currentTimeMillis, Durability.ASYNC,
                SegmentedLog.DEFAULT_SEGMENT_BYTES)) {
            p.subscribe("hold", true);
            for (int i = 0; i < leases; i++) p.produce(0, 0, new byte[1024]);
            p.subscribe("g", false);
            var held = p.poll("hold", "worker", leases, 3_600_000);
            long[] offs = held.stream().mapToLong(d -> d.offset()).toArray();
            int[] atts = held.stream().mapToInt(d -> d.attempt()).toArray();
            var stop = new AtomicBoolean();
            long[] extends_ = {0}, extendNs = {0};
            var hb = Thread.ofPlatform().name("heartbeats").start(() -> {
                if (mode.equals("off")) return;
                boolean batched = !mode.equals("single"), idle = mode.equals("idle"); // idle: same wakeups, no extend
                int step = batched ? batch : 1;
                long gap = periodNs / (leases / step), next = System.nanoTime();
                int i = 0;
                while (!stop.get()) {
                    next += gap;
                    long wait = next - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                    long t0 = System.nanoTime();
                    if (idle) {
                        // control: wake on the batched schedule but do nothing
                    } else if (batched) {
                        p.extend("hold", java.util.Arrays.copyOfRange(offs, i, i + step), java.util.Arrays.copyOfRange(atts, i, i + step), 3_600_000);
                    } else if (!p.extend("hold", offs[i], atts[i], 3_600_000).ok()) throw new AssertionError("lost lease");
                    extendNs[0] += System.nanoTime() - t0;
                    extends_[0]++;
                    i = (i + step) % leases;
                }
            });
            var payload = new byte[1024];
            long warmEnd = System.nanoTime() + 2_000_000_000L, end = warmEnd + (long) (secs * 1e9);
            long ops = 0;
            while (true) {
                long t = System.nanoTime();
                if (t >= end) break;
                p.produce(0, 0, payload);
                for (var d : p.poll("g", "c", 1, 60_000)) p.ack("g", d.offset(), d.attempt());
                if (t >= warmEnd) ops++;
            }
            stop.set(true);
            hb.join();
            double wall = secs + 2;
            System.out.printf("mode=%s dispatch/s=%.0f heartbeatRecords=%d extendTime=%.3f%% of wall (%.1f us/call)%n", mode,
                    ops / secs, extends_[0], extendNs[0] / (wall * 1e7), extends_[0] == 0 ? 0 : extendNs[0] / 1e3 / extends_[0]);
        }
    }
}
