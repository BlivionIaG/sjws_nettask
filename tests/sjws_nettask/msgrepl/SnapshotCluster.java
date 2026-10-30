package sjws_nettask.msgrepl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.ReplicaHooks;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Two data nodes and a witness (2+1) over {@link SimTransport}, each data node with a rolling {@link FileLogStorage},
 * a segmented ledger, a {@link Snapshots.Runtime} and a real {@link SnapshotTransfer.Server} on loopback. Snapshots are
 * taken by the test ({@link DataNode#snapshot()}), never by the timer, so every scenario is deterministic.
 */
final class SnapshotCluster implements AutoCloseable {

    static final Duration ELECTION = Duration.ofMillis(100), HEARTBEAT = Duration.ofMillis(40);
    static final GroupId GROUP = RaftReplicator.group("t");
    static final byte[] KEY = SnapshotTransfer.deriveKey("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    final Path dir;
    final Snapshots settings;
    final long raftRollBytes;
    final long ledgerSegmentBytes;
    final FakeClock clock = new FakeClock(0L);
    final SimTransport net = new SimTransport();
    final List<Member> members;
    final VoterSet voters;
    final Map<String, InetSocketAddress> peers = new LinkedHashMap<>();
    /** Held until each data node's {@link SnapshotTransfer.Server} takes over (#288 MC-N3). */
    final Map<String, ServerSocketChannel> reserved = new LinkedHashMap<>();
    final DataNode[] data = new DataNode[2];
    ReplicatedNode witness;
    FileLogStorage witnessStorage;

    SnapshotCluster(Path dir, Snapshots settings, long raftRollBytes, long ledgerSegmentBytes) throws Exception {
        this.dir = dir;
        this.settings = settings;
        this.raftRollBytes = raftRollBytes;
        this.ledgerSegmentBytes = ledgerSegmentBytes;
        this.members = List.of(member(1, false), member(2, false), member(3, true));
        this.voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (int i = 0; i < 2; i++) {
            var name = "n" + (i + 1);
            var ch = reservePort();
            reserved.put(name, ch);
            peers.put(name, (InetSocketAddress) ch.getLocalAddress());
        }
        for (int i = 0; i < 2; i++) data[i] = new DataNode(i);
        openWitness();
        for (int k = 0; k < 400 && leader() < 0; k++) step();
        if (leader() < 0) throw new AssertionError("no ready leader");
    }

    static Snapshots settings(long every, long tail) {
        return new Snapshots(every, tail, MsgSnapshot.MIN_CHUNK, 64L << 20, Duration.ofHours(1), 0);
    }

    static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("msg"), new InetSocketAddress("127.0.0.1", 27_000 + wire),
                1L, MemberState.ALIVE, witness);
    }

    /** Binds loopback:0 and returns the open channel (caller owns it). */
    static ServerSocketChannel reservePort() throws IOException {
        var ch = ServerSocketChannel.open();
        try {
            ch.bind(new InetSocketAddress("127.0.0.1", 0), 16);
            return ch;
        } catch (IOException e) {
            ch.close();
            throw e;
        }
    }

    /** A free loopback port; prefers {@link #reservePort} when the socket must stay held. */
    static int freePort() throws IOException {
        try (var s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    void openWitness() throws IOException {
        witnessStorage = new FileLogStorage(dir.resolve("w3").resolve("raft"), raftRollBytes);
        witness = ReplicatedNode.open(members.get(2).id(), voters, clock, witnessStorage, net);
        RaftReplicator.openForWitness(witness, Set.of("t"), ELECTION, HEARTBEAT);
    }

    /** A tiny producer-dedup table (stand-in for M2's): producer id to the last sequence applied. */
    static final class Dedup implements ReplicaHooks.ReplicatedState {
        final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();
        volatile int restores;

        void applied(List<byte[]> records) {
            for (var r : records) {
                var p = new String(r, StandardCharsets.UTF_8).split(":", 3);
                if (p.length == 3 && p[0].startsWith("pid")) last.merge(p[0], Long.parseLong(p[1]), Math::max);
            }
        }

        boolean duplicate(String pid, long seq) {
            return last.getOrDefault(pid, -1L) >= seq;
        }

        @Override
        public byte[] snapshotState() {
            var sb = new StringBuilder();
            new java.util.TreeMap<>(last).forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void restoreState(byte[] image) {
            restores++;
            last.clear();
            for (var line : new String(image, StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                last.put(line.substring(0, eq), Long.parseLong(line.substring(eq + 1)));
            }
        }
    }

    final class DataNode implements AutoCloseable {
        final int i;
        final Path root;
        FileLogStorage storage;
        ReplicatedNode node;
        SegmentedLog log;
        LedgerWriter writer;
        Snapshots.Runtime runtime;
        SnapshotTransfer.Server server;
        RaftReplica replica;
        final ReentrantReadWriteLock reads = new ReentrantReadWriteLock();
        Dedup dedup = new Dedup();
        volatile long dropped = -1;
        boolean open;

        DataNode(int i) throws IOException {
            this.i = i;
            this.root = dir.resolve("n" + (i + 1));
            open();
        }

        String name() {
            return "n" + (i + 1);
        }

        NodeId id() {
            return members.get(i).id();
        }

        void open() throws IOException {
            dedup = new Dedup(); // a restart loses the in-memory table: only the snapshot and replay rebuild it
            storage = new FileLogStorage(root.resolve("raft"), raftRollBytes);
            node = ReplicatedNode.open(id(), voters, clock, storage, net);
            log = SegmentedLog.open(root.resolve("ledger"), ledgerSegmentBytes);
            writer = new LedgerWriter(log, Durability.ASYNC);
            runtime = new Snapshots.Runtime(settings, root.resolve("msgrepl"), KEY, peers, clock::currentTimeMillis);
            var listen = reserved.remove(name());
            if (listen == null) { // reopen after close: re-reserve
                listen = reservePort();
                peers.put(name(), (InetSocketAddress) listen.getLocalAddress());
            }
            server = new SnapshotTransfer.Server(listen, KEY, null, runtime.store, settings.bytesPerSecond(), clock::currentTimeMillis);
            peers.put(name(), server.address()); // publish the bound address (same port)
            var hooks = new ReplicaHooks(dedup::applied, this::dropBelow, reads.writeLock(), () -> { }, dedup);
            var urls = Map.of("n1", "http://n1.test", "n2", "http://n2.test");
            replica = (RaftReplica) new RaftReplicator(node, Set.of("t"), urls, root.resolve("msgrepl").resolve("apply"),
                    ELECTION, HEARTBEAT, clock::nanoTime, runtime).open("t", writer, Durability.ASYNC, hooks);
            open = true;
        }

        void dropBelow(long x) {
            reads.writeLock().lock();
            try {
                for (var seg : log.segments()) {
                    if (seg.active() || seg.nextOffset() > x) break;
                    log.deleteSegment(seg.baseOffset());
                }
                dropped = Math.max(dropped, x);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                reads.writeLock().unlock();
            }
        }

        /** Snapshot at the latest applied entry now (what the timer does once {@code everyEntries} are due). */
        boolean snapshot() throws IOException {
            var a = replica.lastAppliedForTest();
            return a != null && replica.snapshotAt(a);
        }

        long end() {
            return writer.snapshot().nextOffset();
        }

        @Override
        public void close() throws Exception {
            if (!open) return;
            open = false;
            replica.close();
            writer.close();
            server.close();
            runtime.close();
            node.close();
            log.close();
            storage.close();
        }
    }

    List<ReplicatedNode> live() {
        var out = new ArrayList<ReplicatedNode>();
        for (var d : data) if (d.open) out.add(d.node);
        if (witness != null) out.add(witness);
        return out;
    }

    void step() {
        clock.advance(Duration.ofMillis(10));
        ReplicatedNode.driveAll(live());
    }

    int leader() {
        for (int i = 0; i < 2; i++) if (data[i].open && data[i].replica.leaderReady()) return i;
        return -1;
    }

    <T> T await(CompletableFuture<T> f) throws Exception {
        for (int k = 0; k < 4000 && !f.isDone(); k++) {
            step();
            if (!f.isDone()) Thread.sleep(0, 200_000);
        }
        return f.get(10, TimeUnit.SECONDS);
    }

    /** Produces one batch on the leader as {@code pid:seq:payload}, waits for the ack; returns its base offset. */
    long produce(String pid, long seq, String payload) throws Exception {
        int l = leader();
        for (int k = 0; k < 400 && l < 0; k++) {
            step();
            l = leader();
        }
        var rec = (pid + ":" + seq + ":" + payload).getBytes(StandardCharsets.UTF_8);
        return await(data[l].replica.appendNoWait(List.of(rec), Durability.ASYNC));
    }

    /** Waits until node {@code i}'s ledger end reaches {@code end}. */
    void awaitEnd(int i, long end) throws Exception {
        for (int k = 0; k < 20_000; k++) {
            if (data[i].end() >= end) return;
            step();
            Thread.sleep(0, 100_000);
        }
        throw new AssertionError(data[i].name() + " stuck at " + data[i].end() + " < " + end);
    }

    /** Every record of node {@code i}'s ledger from {@code from}, in offset order. */
    List<String> records(int i, long from) throws IOException {
        var out = new ArrayList<String>();
        data[i].log.scanFrom(from, b -> {
            for (var r : b.records()) out.add(new String(r, StandardCharsets.UTF_8));
        });
        return out;
    }

    /** SHA-256 of node {@code i}'s ledger contents (offsets and records) from {@code from}: equal across replicas. */
    String checksum(int i, long from) throws IOException {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            data[i].log.scanFrom(from, b -> {
                long off = b.baseOffset();
                for (var r : b.records()) {
                    md.update(ByteBuffer.allocate(8).putLong(off++).array());
                    md.update(r);
                }
            });
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void cut(int i) {
        for (var m : members) if (!m.id().equals(data[i].id())) net.cut(data[i].id(), m.id());
    }

    void heal(int i) {
        for (var m : members) if (!m.id().equals(data[i].id())) net.heal(data[i].id(), m.id());
    }

    static long dirBytes(Path p) throws IOException {
        if (!Files.exists(p)) return 0;
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    @Override
    public void close() throws Exception {
        for (var d : data) if (d != null) d.close();
        if (witness != null) witness.close();
        if (witnessStorage != null) witnessStorage.close();
    }
}
