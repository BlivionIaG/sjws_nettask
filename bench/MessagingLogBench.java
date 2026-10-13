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

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.messaging.http.MessagingModule;

/**
 * Partition-log benches (docs/design/partition-log.md R11, gap G4), in-process through the public
 * {@code /messaging/v1} handler, so they measure the handler + log + writer without the network:
 *   produce <bytes> <threads> <secs> [durability]  each thread POSTs one record of <bytes>; prints records/s, MB/s, p50/p99/p999
 *   fetch   <bytes> <threads> <secs>               prefills 10 000 records of <bytes>, then each thread GETs one batch at a
 *                                                  random committed offset (page cache hot); prints fetches/s, MB/s, p50/p99/p999
 * End-to-end over HTTP (wrk): bench/messaging-http/run.sh.
 *
 *   java -cp target/classes bench/MessagingLogBench.java <dir> <mode> <bytes> <threads> <secs> [durability]
 */
public class MessagingLogBench {

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

    static byte[] record(int n) {
        var b = ByteBuffer.allocate(4 + n).putInt(n);
        while (b.hasRemaining()) b.put((byte) 'x');
        return b.array();
    }

    /** Consumes a response body like a transport would (closing a stream releases its pooled buffer); returns its bytes. */
    static long drain(Body body) {
        if (body instanceof Body.Stream st) {
            long n = 0;
            try (var src = st.source()) {
                java.nio.ByteBuffer b;
                while ((b = src.next()) != null) { n += b.remaining(); b.position(b.limit()); }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return n;
        }
        return body.length();
    }

    static long pct(long[] a, int n, double p) { return n == 0 ? 0 : a[Math.min(n - 1, (int) (n * p))]; }

    public static void main(String[] args) throws Exception {
        var dir = Path.of(args[0]);
        var mode = args[1];
        int bytes = Integer.parseInt(args[2]);
        int threads = Integer.parseInt(args[3]);
        int secs = Integer.parseInt(args[4]);
        var durability = args.length > 5 ? args[5] : null;
        Files.createDirectories(dir);
        System.setProperty("sjws.messaging.dir", dir.toString());
        var module = new MessagingModule();
        Handler h = HandlerMounts.install(List.of(module), r -> null);
        var topic = "/messaging/v1/topics/bench" + bytes + "/records";
        var rec = record(bytes);
        var headers = durability == null ? RequestHeaders.of(Map.of()) : RequestHeaders.of(Map.of("durability", List.of(durability)));
        int prefill = 0;
        if (mode.equals("fetch")) {
            prefill = 10_000;
            for (int i = 0; i < prefill; i++) h.handle(new Request("POST", topic, headers).withBody(body(rec)));
        }
        final int committed = prefill;
        var lat = new long[threads][];
        var counts = new int[threads];
        var ts = new Thread[threads];
        long warmEnd = System.nanoTime() + 2_000_000_000L;
        long end = warmEnd + secs * 1_000_000_000L;
        for (int t = 0; t < threads; t++) {
            int id = t;
            lat[t] = new long[8_000_000 / threads];
            ts[t] = Thread.ofPlatform().start(() -> {
                var rnd = new java.util.SplittableRandom(id);
                var l = lat[id];
                int n = 0;
                while (true) {
                    long t0 = System.nanoTime();
                    if (t0 > end) break;
                    var r = mode.equals("produce")
                            ? h.handle(new Request("POST", topic, headers).withBody(body(rec)))
                            : h.handle(new Request("GET", topic + "?offset=" + rnd.nextInt(committed) + "&maxBytes=1"));
                    long t1 = System.nanoTime();
                    if (r.status() != 200) throw new IllegalStateException("status " + r.status());
                    long got = drain(r.body()); // a streamed (pooled) body must be consumed and closed, as a transport does
                    if (mode.equals("fetch") && got < bytes) throw new IllegalStateException("short fetch");
                    if (t0 >= warmEnd && n < l.length) l[n++] = t1 - t0;
                }
                counts[id] = n;
            });
        }
        for (var t : ts) t.join();
        int total = 0;
        for (int c : counts) total += c;
        var all = new long[total];
        int k = 0;
        for (int t = 0; t < threads; t++) { System.arraycopy(lat[t], 0, all, k, counts[t]); k += counts[t]; }
        Arrays.sort(all);
        double rate = total / (double) secs;
        System.out.printf("messaging %s bytes=%d threads=%d durability=%s ops/s=%.0f MB/s=%.1f p50_us=%.1f p99_us=%.1f p999_us=%.1f n=%d%n",
                mode, bytes, threads, durability == null ? "default" : durability, rate, rate * bytes / 1e6,
                pct(all, total, .50) / 1e3, pct(all, total, .99) / 1e3, pct(all, total, .999) / 1e3, total);
        module.close();
    }
}
