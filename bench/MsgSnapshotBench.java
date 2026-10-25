import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import sjws_nettask.msgrepl.SnapshotTesting;

/**
 * #270 produce latency with snapshots (2 data + witness, loopback TCP, rolling file consensus logs, 256 B records,
 * closed loop through the leader's replica, as {@code ReplicatedProduceBench}). Needs the test classes for the
 * snapshot runtime ({@code SnapshotTesting}):
 *
 * <pre>
 * java -cp target/classes:target/test-classes bench/MsgSnapshotBench.java DIR produce async|group PRODUCERS SECONDS snap|nosnap
 *     snap: a background thread snapshots both data replicas every EVERY (10 000) applied entries and truncates
 *     (tail 1 000), as the replicator's timer does. Prints ops/s, p50/p99/p99.9, snapshots taken, disk use.
 * java -cp ... bench/MsgSnapshotBench.java DIR install async PRODUCERS PREFILL_SECONDS
 *     n1 + witness only; prefill, snapshot, then start n2 empty: it installs from n1 (64 MiB/s) while producers
 *     keep producing. Prints produce p99 during the install window, install time and bytes.
 * </pre>
 */
public class MsgSnapshotBench {

    static final long EVERY = 10_000, TAIL = 1_000;
    static final Duration ELECTION = Duration.ofMillis(300), HEARTBEAT = Duration.ofMillis(50);

    record Data(ReplicatedNode node, TcpTransport transport, FileLogStorage storage, SegmentedLog log, LedgerWriter writer,
            SnapshotTesting snaps, Replica replica) {
        void close() throws Exception {
            replica.close();
            node.close();
            transport.close();
            writer.close();
            log.close();
            snaps.close();
            storage.close();
        }
    }

    static List<Member> members;
    static VoterSet voters;
    static ClusterKey key;
    static Map<String, InetSocketAddress> peers = new LinkedHashMap<>();

    static Data openData(Path dir, int i) throws Exception {
        var m = members.get(i);
        var t = TcpTransport.open(m.id(), members, key, Clock.SYSTEM, 4096);
        var storage = new FileLogStorage(dir.resolve(m.id().name() + "/raft"), 16L << 20);
        var node = ReplicatedNode.open(m.id(), voters, Clock.SYSTEM, storage, t);
        var log = SegmentedLog.open(dir.resolve(m.id().name() + "/ledger"), 64L << 20);
        var writer = new LedgerWriter(log, Durability.ASYNC);
        var state = dir.resolve(m.id().name() + "/msgrepl");
        var snaps = SnapshotTesting.create(state, peers, m.id().name(), EVERY, TAIL, System::currentTimeMillis);
        var replica = snaps.replicator(node, Set.of("t"), Map.of(), state.resolve("apply"), ELECTION, HEARTBEAT, System::nanoTime)
                .open("t", writer, Durability.ASYNC, r -> { });
        node.begin(Duration.ofMillis(1));
        return new Data(node, t, storage, log, writer, snaps, replica);
    }

    public static void main(String[] a) throws Exception {
        var dir = Path.of(a[0]);
        var what = a[1];
        var mode = Durability.valueOf(a[2].toUpperCase());
        int producers = Integer.parseInt(a[3]);
        double secs = Double.parseDouble(a[4]);
        boolean snap = a.length > 5 && a[5].equals("snap");
        Files.createDirectories(dir);
        key = ClusterKey.of("raft-bench-key-0123456789abcdef".getBytes(), null);
        members = new ArrayList<>();
        for (int w = 1; w <= 3; w++) {
            members.add(new Member(new NodeId(w, "n" + w), new SiteId("s"), new InetSocketAddress("127.0.0.1", freePort()), 1L,
                    MemberState.ALIVE, w == 3));
        }
        for (int w = 1; w <= 2; w++) peers.put("n" + w, new InetSocketAddress("127.0.0.1", freePort()));
        voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var wt = TcpTransport.open(members.get(2).id(), members, key, Clock.SYSTEM, 4096);
        var wstorage = new FileLogStorage(dir.resolve("n3/raft"), 16L << 20);
        var witness = ReplicatedNode.open(members.get(2).id(), voters, Clock.SYSTEM, wstorage, wt);
        RaftReplicator.openForWitness(witness, Set.of("t"), ELECTION, HEARTBEAT);
        witness.begin(Duration.ofMillis(1));
        var data = new ArrayList<Data>();
        data.add(openData(dir, 0));
        if (what.equals("produce")) data.add(openData(dir, 1));
        Replica leader = awaitLeader(data);
        var rec = List.of(new byte[256]);
        var stop = new AtomicBoolean();
        var taken = new AtomicLong();
        Thread snapper = null;
        if (what.equals("produce") && snap) {
            snapper = Thread.ofPlatform().name("bench-snapshotter").start(() -> {
                long[] last = new long[data.size()];
                while (!stop.get()) {
                    for (int i = 0; i < data.size(); i++) {
                        try {
                            long end = data.get(i).writer.snapshot().nextOffset();
                            if (end - last[i] >= EVERY && SnapshotTesting.snapshotNow(data.get(i).replica)) {
                                last[i] = end;
                                taken.incrementAndGet();
                            }
                        } catch (Exception e) {
                            System.err.println("snapshot: " + e);
                        }
                    }
                    try {
                        Thread.sleep(200); // the replicator's check interval
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }
        if (what.equals("produce")) {
            run(leader, rec, mode, producers, 2.0, null); // warm-up
            var r = run(leader, rec, mode, producers, secs, null);
            stop.set(true);
            if (snapper != null) snapper.join();
            System.out.printf("msgsnap produce mode=%s snap=%s producers=%d ops=%d ops/s=%.0f p50=%.1fus p99=%.1fus p999=%.1fus"
                    + " errors=%d snapshots=%d raft_bytes=%d ledger_bytes=%d snap_bytes=%d%n", mode.name().toLowerCase(), snap,
                    producers, r.n, r.n / r.took, r.p50, r.p99, r.p999, r.errors, taken.get(), du(dir.resolve("n1/raft")),
                    du(dir.resolve("n1/ledger")), du(dir.resolve("n1/msgrepl/snap")));
        } else {
            run(leader, rec, mode, producers, secs, null); // prefill with n2 down
            if (!SnapshotTesting.snapshotNow(leader)) throw new IllegalStateException("no snapshot");
            long ledger = du(dir.resolve("n1/ledger"));
            var installing = new AtomicBoolean(true);
            var started = new AtomicLong();
            var done = new AtomicLong();
            var opener = Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(1000);
                    started.set(System.nanoTime());
                    var d = openData(dir, 1);
                    data.add(d);
                    while (SnapshotTesting.installs(d.replica) == 0) Thread.sleep(1);
                    done.set(System.nanoTime());
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    installing.set(false);
                }
            });
            var before = run(leader, rec, mode, producers, 1.0, null);
            var during = run(leader, rec, mode, producers, 120, installing);
            opener.join();
            System.out.printf("msgsnap install mode=%s producers=%d ledger_bytes=%d install_ms=%.0f sent=%d"
                    + " before_p99=%.1fus during_ops/s=%.0f during_p50=%.1fus during_p99=%.1fus during_p999=%.1fus errors=%d%n",
                    mode.name().toLowerCase(), producers, ledger, (done.get() - started.get()) / 1e6, data.get(0).snaps.bytesSent(),
                    before.p99, during.n / during.took, during.p50, during.p99, during.p999, during.errors);
        }
        for (var d : data) d.close();
        witness.close();
        wt.close();
        wstorage.close();
        System.exit(0);
    }

    static Replica awaitLeader(List<Data> data) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            for (var d : data) if (d.replica.leaderReady()) return d.replica;
            Thread.sleep(10);
        }
        throw new IllegalStateException("no ready leader");
    }

    record Result(int n, double took, double p50, double p99, double p999, long errors) { }

    /** Closed loop for {@code secs}, or while {@code whileTrue} holds when given. */
    static Result run(Replica lead, List<byte[]> rec, Durability mode, int producers, double secs, AtomicBoolean whileTrue)
            throws Exception {
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
                while (System.nanoTime() < end && (whileTrue == null || whileTrue.get())) {
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
        return new Result(n, took, pct(all, 0.50), pct(all, 0.99), pct(all, 0.999), errors.get());
    }

    static double pct(long[] sorted, double q) {
        return sorted.length == 0 ? Double.NaN : sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))] / 1000.0;
    }

    static long du(Path p) throws Exception {
        if (!Files.exists(p)) return 0;
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
