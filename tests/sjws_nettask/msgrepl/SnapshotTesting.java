package sjws_nettask.msgrepl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import sjws_nettask.cluster.ClusterNode;
import sjws_nettask.messaging.ledger.PartitionReplicator;
import sjws_nettask.messaging.ledger.Replica;

/** Test access to the #270 snapshot runtime for tests outside this package (the messaging HTTP layer). */
public final class SnapshotTesting implements AutoCloseable {

    final Snapshots.Runtime runtime;
    final SnapshotTransfer.Server server;

    private SnapshotTesting(Snapshots.Runtime runtime, SnapshotTransfer.Server server) {
        this.runtime = runtime;
        this.server = server;
    }

    /** A runtime (timer off: snapshots are taken by the test) and its transfer server on {@code peers.get(self)}. */
    public static SnapshotTesting create(Path stateDir, Map<String, InetSocketAddress> peers, String self, long every, long tail,
            LongSupplier clockMs) throws IOException {
        var settings = new Snapshots(every, tail, MsgSnapshot.MIN_CHUNK, 64L << 20, Duration.ofHours(1), 0);
        var rt = new Snapshots.Runtime(settings, stateDir, SnapshotCluster.KEY, peers, clockMs);
        var server = new SnapshotTransfer.Server(peers.get(self), SnapshotCluster.KEY, null, rt.store, settings.bytesPerSecond(), clockMs);
        return new SnapshotTesting(rt, server);
    }

    public static int freePort() throws IOException {
        return SnapshotCluster.freePort();
    }

    public PartitionReplicator replicator(ClusterNode node, Set<String> topics, Map<String, String> urls, Path stateDir,
            Duration election, Duration heartbeat, LongSupplier nanoClock) throws IOException {
        return new RaftReplicator(node, topics, urls, stateDir, election, heartbeat, nanoClock, this.runtime);
    }

    /** Snapshots {@code r} at its latest applied entry now; false when there is nothing to snapshot. */
    public static boolean snapshotNow(Replica r) throws IOException {
        var rr = (RaftReplica) r;
        var a = rr.lastAppliedForTest();
        return a != null && rr.snapshotAt(a);
    }

    public static long installs(Replica r) {
        return ((RaftReplica) r).installs();
    }

    public long bytesSent() {
        return this.server.bytesSent.get();
    }

    @Override
    public void close() {
        this.server.close();
        this.runtime.close();
    }
}
