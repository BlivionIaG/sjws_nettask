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
import sjws_nettask.msgrepl.RaftReplicator;

/**
 * HA-on produce latency (messaging replication phase A): two data nodes and a witness on loopback TCP, file-backed
 * consensus logs, one {@code msg/t} group. Closed-loop producers append 1-record batches of 256 B through the leader's
 * replica; the ack waits for commit (RELAXED for async, STRICT for group) and the local apply.
 *
 * <pre>java -cp target/classes bench/ReplicatedProduceBench.java DIR async|group PRODUCERS SECONDS</pre>
 */
public class ReplicatedProduceBench {

    public static void main(String[] a) throws Exception {
        var dir = Path.of(a[0]);
        var mode = Durability.valueOf(a[1].toUpperCase());
        int producers = Integer.parseInt(a[2]);
        double secs = Double.parseDouble(a[3]);
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
            replicas.add(new RaftReplicator(node, Set.of("t"), Map.of(), dir.resolve(m.id().name() + "/state"), election, heartbeat)
                    .open("t", writer, Durability.ASYNC, r -> { }));
        }
        for (var node : nodes) node.begin(Duration.ofMillis(1));
        Replica leader = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (leader == null && System.nanoTime() < deadline) {
            for (var r : replicas) if (r.leaderReady()) leader = r;
            Thread.sleep(10);
        }
        if (leader == null) throw new IllegalStateException("no ready leader");
        var lead = leader;
        var rec = List.of(new byte[256]);
        run(lead, rec, mode, producers, 2.0, false); // warm-up
        run(lead, rec, mode, producers, secs, true);
        for (var r : replicas) r.close();
        for (var node : nodes) node.close();
        for (var t : transports) t.close();
        for (var w : writers) w.close();
        for (var l : logs) l.close();
        System.exit(0);
    }

    static void run(Replica lead, List<byte[]> rec, Durability mode, int producers, double secs, boolean report) throws Exception {
        long end = System.nanoTime() + (long) (secs * 1e9);
        var lats = new long[producers][];
        var counts = new int[producers];
        var errors = new AtomicLong();
        var ts = new ArrayList<Thread>();
        long t0 = System.nanoTime();
        for (int p = 0; p < producers; p++) {
            int id = p;
            lats[id] = new long[1 << 16];
            ts.add(Thread.ofPlatform().start(() -> {
                while (System.nanoTime() < end) {
                    long s = System.nanoTime();
                    try {
                        lead.append(rec, mode).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        continue;
                    }
                    if (counts[id] == lats[id].length) lats[id] = Arrays.copyOf(lats[id], counts[id] * 2);
                    lats[id][counts[id]++] = System.nanoTime() - s;
                }
            }));
        }
        for (var t : ts) t.join();
        if (!report) return;
        double took = (System.nanoTime() - t0) / 1e9;
        int n = 0;
        for (int c : counts) n += c;
        var all = new long[n];
        int o = 0;
        for (int p = 0; p < producers; p++) {
            System.arraycopy(lats[p], 0, all, o, counts[p]);
            o += counts[p];
        }
        Arrays.sort(all);
        System.out.printf("replicated mode=%s producers=%d ops=%d ops/s=%.0f p50=%.1fus p99=%.1fus errors=%d%n",
                mode.name().toLowerCase(), producers, n, n / took, pct(all, 0.50), pct(all, 0.99), errors.get());
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
