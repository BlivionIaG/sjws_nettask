import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.Replica;
import sjws_nettask.messaging.ledger.SegmentedLog;
import sjws_nettask.core.Handler;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.messaging.http.MessagingModule;
import sjws_nettask.msgrepl.RaftReplicator;

/**
 * HA-on produce latency (messaging replication phase A): two data nodes and a witness on loopback TCP, file-backed
 * consensus logs, one {@code msg/t} group. Closed-loop producers append 1-record batches of 256 B through the leader's
 * replica; the ack waits for commit (RELAXED for async, STRICT for group) and the local apply.
 *
 * <pre>java -cp target/classes bench/ReplicatedProduceBench.java DIR async|group PRODUCERS SECONDS [PATH]</pre>
 *
 * <p>{@code PATH}: {@code replica} (default) appends through the replica; {@code replica-dedup} through
 * {@code Replica.appendDeduplicated} (M2), one producer id per thread; {@code http} posts through the
 * {@code /messaging/v1} handler of a {@link MessagingModule} on each data node (no network between client and handler);
 * {@code http-dedup} adds {@code Producer-Id}/{@code Producer-Seq} headers (run with
 * {@code -Dsjws.messaging.producer.dedup=true}; a build without M2 ignores the headers, which is the baseline).
 */
public class ReplicatedProduceBench {

    public static void main(String[] a) throws Exception {
        var dir = Path.of(a[0]);
        var mode = Durability.valueOf(a[1].toUpperCase());
        int producers = Integer.parseInt(a[2]);
        double secs = Double.parseDouble(a[3]);
        var path = a.length > 4 ? a[4] : "replica";
        boolean http = path.startsWith("http");
        boolean dedup = path.endsWith("-dedup");
        Files.createDirectories(dir);
        var key = ClusterKey.of("raft-bench-key-0123456789abcdef".getBytes(), null);
        var members = new ArrayList<Member>();
        for (int w = 1; w <= 3; w++) {
            members.add(new Member(new NodeId(w, "n" + w), new SiteId("s"), new InetSocketAddress("127.0.0.1", freePort()), 1L,
                    MemberState.ALIVE, w == 3));
        }
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var election = Duration.ofMillis(300);
        var heartbeat = Duration.ofMillis(50);
        var nodes = new ArrayList<ReplicatedNode>();
        var transports = new ArrayList<TcpTransport>();
        var logs = new ArrayList<SegmentedLog>();
        var writers = new ArrayList<LedgerWriter>();
        var replicas = new ArrayList<Replica>();
        var modules = new ArrayList<MessagingModule>();
        var handlers = new ArrayList<Handler>();
        for (var m : members) {
            var t = TcpTransport.open(m.id(), members, key, Clock.SYSTEM, 4096);
            transports.add(t);
            var node = ReplicatedNode.open(m.id(), voters, Clock.SYSTEM, new FileLogStorage(dir.resolve(m.id().name() + "/raft")), t);
            nodes.add(node);
            if (m.witness()) {
                RaftReplicator.openForWitness(node, Set.of("t"), election, heartbeat);
                continue;
            }
            var log = SegmentedLog.open(dir.resolve(m.id().name() + "/ledger"));
            var writer = new LedgerWriter(log, Durability.ASYNC);
            logs.add(log);
            writers.add(writer);
            var replicator = new RaftReplicator(node, Set.of("t"), Map.of(), dir.resolve(m.id().name() + "/state"), election, heartbeat);
            if (http) {
                System.setProperty("sjws.messaging.dir", dir.resolve(m.id().name() + "/messaging").toString());
                var module = new MessagingModule();
                module.replication(replicator);
                modules.add(module);
                handlers.add(HandlerMounts.install(List.of(module), r -> null));
                continue;
            }
            replicas.add(replicator.open("t", writer, Durability.ASYNC, r -> { }));
        }
        for (var node : nodes) node.begin(Duration.ofMillis(1));
        var rec = List.of(new byte[256]);
        if (http) {
            var body = new byte[4 + 256];
            body[2] = 1; // [len=256][256 zero bytes]
            Handler lead = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (lead == null && System.nanoTime() < until) {
                for (var h : handlers) {
                    var r = h.handle(new Request("POST", "/messaging/v1/topics/t/records", RequestHeaders.of(Map.of())).withBody(body(body)));
                    if (r.status() == 200) lead = h;
                }
                Thread.sleep(10);
            }
            if (lead == null) throw new IllegalStateException("no ready leader");
            var h = lead;
            runHttp(h, body, mode, dedup, producers, 2.0, false, 1_000_000);
            runHttp(h, body, mode, dedup, producers, secs, true, 2_000_000);
            for (var mod : modules) mod.close();
            for (var node : nodes) node.close();
            for (var t : transports) t.close();
            for (var w : writers) w.close();
            for (var l : logs) l.close();
            System.exit(0);
        }
        Replica leader = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (leader == null && System.nanoTime() < deadline) {
            for (var r : replicas) if (r.leaderReady()) leader = r;
            Thread.sleep(10);
        }
        if (leader == null) throw new IllegalStateException("no ready leader");
        var lead = leader;
        var dedupCall = dedup ? Replica.class.getMethod("appendDeduplicated", List.class, Durability.class, int.class, long.class, long.class) : null;
        run(lead, rec, mode, producers, 2.0, false, dedupCall, 1_000_000); // warm-up
        run(lead, rec, mode, producers, secs, true, dedupCall, 2_000_000);
        for (var r : replicas) r.close();
        for (var node : nodes) node.close();
        for (var t : transports) t.close();
        for (var w : writers) w.close();
        for (var l : logs) l.close();
        System.exit(0);
    }

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

    /** As {@link #run} through the handler; with {@code dedup}, producer {@code idBase + thread} sends seqs 1, 2, ... */
    static void runHttp(Handler h, byte[] body, Durability mode, boolean dedup, int producers, double secs, boolean report,
            long idBase) throws Exception {
        long end = System.nanoTime() + (long) (secs * 1e9);
        var lats = new long[producers][];
        var counts = new int[producers];
        var errors = new AtomicLong();
        var ts = new ArrayList<Thread>();
        long t0 = System.nanoTime();
        if (report) ERROR_KINDS.clear(); // the warm-up's failures are not the measured run's
        var dur = mode.name().toLowerCase();
        for (int p = 0; p < producers; p++) {
            int id = p;
            lats[id] = new long[1 << 16];
            ts.add(Thread.ofPlatform().start(() -> {
                long seq = 0;
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    var hs = dedup ? Map.of("durability", List.of(dur), "producer-id", List.of(Long.toString(idBase + id)),
                            "producer-seq", List.of(Long.toString(seq + 1))) : Map.of("durability", List.of(dur));
                    var r = h.handle(new Request("POST", "/messaging/v1/topics/t/records", RequestHeaders.of(hs)).withBody(body(body)));
                    if (r.status() != 200) {
                        errors.incrementAndGet();
                        ERROR_KINDS.computeIfAbsent("http" + r.status(), k -> new java.util.concurrent.atomic.LongAdder()).increment();
                        continue; // a retry resends the same seq
                    }
                    seq++;
                    if (counts[id] == lats[id].length) lats[id] = Arrays.copyOf(lats[id], counts[id] * 2);
                    lats[id][counts[id]++] = System.nanoTime() - s;
                }
            }));
        }
        for (var t : ts) t.join();
        if (report) report("http" + (dedup ? "-dedup" : ""), mode, producers, lats, counts, errors.get(), (System.nanoTime() - t0) / 1e9);
    }

    /** Failed attempts by exception class (replica paths) or HTTP status, measured run only (#278 HA N5). */
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> ERROR_KINDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void report(String path, Durability mode, int producers, long[][] lats, int[] counts, long errors, double took) {
        int n = 0;
        for (int c : counts) n += c;
        var all = new long[n];
        int o = 0;
        for (int p = 0; p < producers; p++) {
            System.arraycopy(lats[p], 0, all, o, counts[p]);
            o += counts[p];
        }
        Arrays.sort(all);
        var kinds = new java.util.TreeMap<String, Long>();
        ERROR_KINDS.forEach((k, v) -> kinds.put(k, v.sum()));
        System.out.printf("replicated path=%s mode=%s producers=%d ops=%d ops/s=%.0f p50=%.1fus p99=%.1fus errors=%d errkinds=%s%n",
                path, mode.name().toLowerCase(), producers, n, n / took, pct(all, 0.50), pct(all, 0.99), errors,
                kinds.toString().replace(" ", ""));
    }

    static void run(Replica lead, List<byte[]> rec, Durability mode, int producers, double secs, boolean report,
            java.lang.reflect.Method dedup, long idBase) throws Exception {
        long end = System.nanoTime() + (long) (secs * 1e9);
        var lats = new long[producers][];
        var counts = new int[producers];
        var errors = new AtomicLong();
        var ts = new ArrayList<Thread>();
        long t0 = System.nanoTime();
        if (report) ERROR_KINDS.clear(); // the warm-up's failures are not the measured run's
        for (int p = 0; p < producers; p++) {
            int id = p;
            lats[id] = new long[1 << 16];
            ts.add(Thread.ofPlatform().start(() -> {
                long seq = 0;
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    try {
                        if (dedup == null) lead.append(rec, mode).get(5, TimeUnit.SECONDS);
                        else ((java.util.concurrent.CompletableFuture<?>) dedup.invoke(lead, rec, mode, 0, idBase + id, seq + 1)).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        Throwable c = e;
                        while ((c instanceof java.util.concurrent.ExecutionException
                                || c instanceof java.lang.reflect.InvocationTargetException) && c.getCause() != null) c = c.getCause();
                        ERROR_KINDS.computeIfAbsent(c.getClass().getSimpleName(), k -> new java.util.concurrent.atomic.LongAdder()).increment();
                        continue; // a retry resends the same seq
                    }
                    seq++;
                    if (counts[id] == lats[id].length) lats[id] = Arrays.copyOf(lats[id], counts[id] * 2);
                    lats[id][counts[id]++] = System.nanoTime() - s;
                }
            }));
        }
        for (var t : ts) t.join();
        if (report) report(dedup == null ? "replica" : "replica-dedup", mode, producers, lats, counts, errors.get(), (System.nanoTime() - t0) / 1e9);
    }

    static double pct(long[] sorted, double q) {
        return sorted.length == 0 ? Double.NaN : sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))] / 1000.0;
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
