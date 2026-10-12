/*
 * Control-plane benchmarks for sjws_nettask. Single-file program, run with the source launcher:
 *   java -cp target/sjws_nettask-*.jar bench/controlplane/CpBench.java <mode> [options]
 * Modes:
 *   swap  --routes N --iters N        snapshot compile time, LiveConfig publish cost, reader visibility
 *   route --routes N --seconds S      compiled Router lookup (first / middle / last route) ns/op
 *   serve --config F [--push-ms N]    serve F; with --push-ms > 0 apply a config push every N ms
 *   gen   --routes N --root DIR --port P [--mode M] [--out F]   write a config file
 * A push is SjwsServer.applyRemote(document) when that method exists (PR #42), else SjwsServer.reload().
 * Prints only measured values.
 */
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.config.LiveConfig;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

public final class CpBench {

    static volatile Object sink;
    static final long[] cost = new long[1 << 20];
    static volatile int pushes;

    public static void main(String[] args) throws Exception {
        var opt = new HashMap<String, String>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            opt.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        switch (args.length == 0 ? "" : args[0]) {
            case "gen" -> System.out.print(gen(opt));
            case "swap" -> swap(opt);
            case "route" -> route(opt);
            case "serve" -> serve(opt);
            default -> throw new IllegalArgumentException("mode: gen|swap|route|serve");
        }
    }

    static String gen(Map<String, String> o) throws Exception {
        int n = Integer.parseInt(o.getOrDefault("routes", "1000"));
        var sb = new StringBuilder();
        sb.append("server:\n  port: ").append(o.getOrDefault("port", "18090")).append("\n  root: ")
                .append(o.getOrDefault("root", "/tmp")).append("\n  max_connections: 20000\n")
                .append("  max_connections_per_ip: 20000\n  backlog: 4096\n  idle_timeout: 3600\n")
                .append("  header_timeout: 3600\n");
        if (o.containsKey("mode")) {
            sb.append("node:\n  mode: ").append(o.get("mode")).append("\n");
            if (o.containsKey("root")) {
                sb.append("  state_dir: ").append(o.get("root")).append("\n");
            }
        }
        if (n > 0) {
            sb.append("routing:\n  enabled: true\n  routes:\n");
            for (int i = 0; i < n; i++) {
                sb.append("    - prefix: \"/r").append(i).append("\"\n      redirect: { status: 301, location: \"/x")
                        .append(i).append("/\", keep_path: false }\n");
            }
        }
        if (o.containsKey("out")) {
            Files.writeString(Path.of(o.get("out")), sb);
            return "";
        }
        return sb.toString();
    }

    static ConfigLoader.Loaded load(Map<String, String> o) throws Exception {
        Path f = o.containsKey("config") ? Path.of(o.get("config")) : null;
        if (f == null) {
            var root = Files.createTempDirectory("cpbench");
            f = root.resolve("sjws.yaml");
            var g = new HashMap<>(o);
            g.put("root", root.toString());
            g.put("out", f.toString());
            gen(g);
        }
        var loaded = ConfigLoader.load(new String[] {"--config=" + f}, Map.of());
        if (!loaded.ok()) {
            throw new IllegalStateException("config: " + loaded.errors());
        }
        return loaded;
    }

    static String pct(long[] ns) {
        Arrays.sort(ns);
        return String.format("p50=%d p90=%d p99=%d p99.9=%d max=%d (ns, n=%d)", ns[ns.length / 2],
                ns[(int) (ns.length * 0.9)], ns[(int) (ns.length * 0.99)], ns[(int) (ns.length * 0.999)],
                ns[ns.length - 1], ns.length);
    }

    static void swap(Map<String, String> o) throws Exception {
        var doc = load(o).document();
        int iters = Integer.parseInt(o.getOrDefault("iters", "2000"));
        int routes = doc.routing().enabled() ? doc.routing().routes().size() : 0;
        for (int i = 0; i < 200; i++) {
            sink = ConfigSnapshot.compile(doc, null, i);
        }
        var compile = new long[iters];
        var snaps = new ConfigSnapshot[iters];
        for (int i = 0; i < iters; i++) {
            long t = System.nanoTime();
            snaps[i] = ConfigSnapshot.compile(doc, null, i + 1);
            compile[i] = System.nanoTime() - t;
        }
        System.out.println("routes=" + routes + " compile " + pct(compile));

        var live = new LiveConfig(snaps[0]);
        int readers = Integer.parseInt(o.getOrDefault("readers", "4"));
        var seen = new AtomicLong[readers];
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var reads = new AtomicLong();
        var threads = new Thread[readers];
        for (int r = 0; r < readers; r++) {
            seen[r] = new AtomicLong(-1);
            var mine = seen[r];
            threads[r] = Thread.ofPlatform().daemon().start(() -> {
                long n = 0;
                Handler last = null;
                while (!stop.get()) {
                    var snap = live.get();
                    Handler h = snap.handler();
                    if (h != last) {
                        last = h;
                        mine.set(snap.generation());
                    }
                    n++;
                }
                reads.addAndGet(n);
            });
        }
        Thread.sleep(500);
        var publish = new long[iters - 1];
        var visible = new long[iters - 1];
        for (int i = 1; i < iters; i++) {
            long t = System.nanoTime();
            live.set(snaps[i]);
            long t1 = System.nanoTime();
            for (var s : seen) {
                while (s.get() != snaps[i].generation()) {
                    Thread.onSpinWait();
                }
            }
            visible[i - 1] = System.nanoTime() - t;
            publish[i - 1] = t1 - t;
        }
        stop.set(true);
        for (var t : threads) {
            t.join();
        }
        System.out.println("publish LiveConfig.set " + pct(publish));
        System.out.println("visible to all " + readers + " readers " + pct(visible));
        sink = snaps;
    }

    static void route(Map<String, String> o) throws Exception {
        var loaded = load(o);
        var snap = ConfigSnapshot.compile(loaded.document(), null, 1);
        int n = loaded.document().routing().routes().size();
        double secs = Double.parseDouble(o.getOrDefault("seconds", "3"));
        var h = snap.handler();
        System.out.println("handler=" + h.getClass().getSimpleName() + " routes=" + n);
        for (int idx : new int[] {0, n / 2, n - 1}) {
            var req = new Request("GET", "/r" + idx + "/a", RequestHeaders.NONE);
            for (int w = 0; w < 3; w++) { // warmup rounds
                bench(h, req, 0.5);
            }
            double best = Double.MAX_VALUE;
            for (int rep = 0; rep < 5; rep++) {
                best = Math.min(best, bench(h, req, secs / 5));
            }
            System.out.printf("route[%d] best-of-5 %.1f ns/op  (%.2f M lookups/s)%n", idx, best, 1000 / best);
        }
    }

    static double bench(Handler h, Request req, double secs) {
        long end = System.nanoTime() + (long) (secs * 1e9), ops = 0, t0 = System.nanoTime();
        Object s = null;
        while (true) {
            for (int i = 0; i < 1000; i++) {
                s = h.handle(req);
            }
            ops += 1000;
            if (System.nanoTime() > end) {
                break;
            }
        }
        sink = s;
        return (System.nanoTime() - t0) / (double) ops;
    }

    static void serve(Map<String, String> o) throws Exception {
        var loaded = load(o);
        var server = SjwsServer.start(loaded);
        long pushMs = Long.parseLong(o.getOrDefault("push-ms", "0"));
        Method remote = null;
        for (var m : SjwsServer.class.getMethods()) {
            if (m.getName().equals("applyRemote") && m.getParameterCount() == 1) {
                remote = m;
            }
        }
        System.out.println("serving port=" + loaded.document().server().port() + " push-ms=" + pushMs + " via="
                + (remote != null ? "applyRemote" : "reload"));
        System.out.flush();
        if (pushMs <= 0) {
            Thread.sleep(Long.MAX_VALUE);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            int n = pushes;
            if (n > 0) {
                System.out.println("pushes=" + n + " apply " + pct(Arrays.copyOf(cost, n)));
                System.out.flush();
            }
        }));
        long deadline = System.nanoTime() + Long.parseLong(o.getOrDefault("push-seconds", "3600")) * 1_000_000_000L;
        while (System.nanoTime() < deadline && pushes < cost.length) {
            Thread.sleep(pushMs);
            long t = System.nanoTime();
            Object r = remote != null ? remote.invoke(server, loaded.document()) : server.reload();
            cost[pushes] = System.nanoTime() - t;
            pushes++;
            if (!(boolean) r.getClass().getMethod("applied").invoke(r)) {
                throw new IllegalStateException("push rejected: " + r);
            }
        }
        Thread.sleep(Long.MAX_VALUE);
    }
}
