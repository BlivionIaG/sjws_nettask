import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.messaging.http.MessagingModule;

/**
 * Offset-commit cost (durable-log-plan §2), in process through /messaging/v1: prefills topic "t" with 1000 records,
 * then <threads> threads each PUT /topics/t/offsets/g<thread> in a loop for <secs> (2 s warmup). Prints commits/s and
 * p50/p99/p999. Durability: the __offsets default (group) unless [durability] is given (header; may only strengthen).
 *
 *   java -cp target/classes bench/OffsetCommitBench.java <dir> <threads> <secs> [durability]
 */
public class OffsetCommitBench {

    static RequestBody body(byte[] data) {
        return new RequestBody() {
            int pos;
            @Override public long length() { return data.length; }
            @Override public int read(byte[] into, int off, int len) {
                if (pos >= data.length) return -1;
                int k = Math.min(len, data.length - pos);
                System.arraycopy(data, pos, into, off, k);
                pos += k;
                return k;
            }
        };
    }

    public static void main(String[] args) throws Exception {
        var dir = Path.of(args[0]);
        int threads = Integer.parseInt(args[1]);
        int secs = Integer.parseInt(args[2]);
        var durability = args.length > 3 ? args[3] : null;
        Files.createDirectories(dir);
        System.setProperty("sjws.messaging.dir", dir.toString());
        var module = new MessagingModule();
        Handler h = HandlerMounts.install(List.of(module), r -> null);
        var rec = new byte[] {0, 0, 0, 4, 'x', 'x', 'x', 'x'};
        for (int i = 0; i < 1000; i++) h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(body(rec)));
        var headers = durability == null ? RequestHeaders.of(Map.of()) : RequestHeaders.of(Map.of("durability", List.of(durability)));
        var lat = new long[threads][2_000_000];
        var counts = new int[threads];
        long warmEnd = System.nanoTime() + 2_000_000_000L, end = warmEnd + secs * 1_000_000_000L;
        var ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int id = t;
            ts[t] = Thread.ofPlatform().start(() -> {
                int n = 0;
                for (long o = 0; ; o = (o + 1) % 1000) {
                    long t0 = System.nanoTime();
                    if (t0 > end) break;
                    var b = ("{\"offset\":" + o + "}").getBytes(StandardCharsets.US_ASCII);
                    var r = h.handle(new Request("PUT", "/messaging/v1/topics/t/offsets/g" + id, headers).withBody(body(b)));
                    long t1 = System.nanoTime();
                    if (r.status() != 200) throw new IllegalStateException("status " + r.status());
                    if (t0 >= warmEnd && n < lat[id].length) lat[id][n++] = t1 - t0;
                }
                counts[id] = n;
            });
        }
        for (var t : ts) t.join();
        int total = Arrays.stream(counts).sum(), k = 0;
        var all = new long[total];
        for (int t = 0; t < threads; t++) { System.arraycopy(lat[t], 0, all, k, counts[t]); k += counts[t]; }
        Arrays.sort(all);
        System.out.printf("offsets commit threads=%d durability=%s commits/s=%.0f p50_us=%.1f p99_us=%.1f p999_us=%.1f n=%d%n",
                threads, durability == null ? "default" : durability, total / (double) secs,
                all[total / 2] / 1e3, all[Math.min(total - 1, (int) (total * .99))] / 1e3, all[Math.min(total - 1, (int) (total * .999))] / 1e3, total);
        module.close();
        System.exit(0);
    }
}
