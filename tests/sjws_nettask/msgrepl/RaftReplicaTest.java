package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.QuorumPolicy;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.Replica;
import sjws_nettask.messaging.ledger.SegmentedLog;

/** One-node group over a file log: restart replay never duplicates, a stale term stamp is never applied. */
@Timeout(60)
class RaftReplicaTest {

    static final Duration ELECTION = Duration.ofMillis(100), HEARTBEAT = Duration.ofMillis(40);

    @TempDir Path dir;
    FakeClock clock = new FakeClock(0L);

    final class Node implements AutoCloseable {
        final ReplicatedNode node;
        final SegmentedLog log;
        final LedgerWriter writer;
        final Replica replica;

        Node() throws Exception {
            var id = new NodeId(1, "n1");
            var member = new Member(id, new SiteId("local"), new InetSocketAddress("127.0.0.1", 26_001), 1L, MemberState.ALIVE, false);
            node = ReplicatedNode.open(id, List.of(member), Set.of(id), QuorumPolicy.majority(), clock,
                    new FileLogStorage(dir.resolve("raft")), new SimTransport());
            log = SegmentedLog.open(dir.resolve("ledger"));
            writer = new LedgerWriter(log, Durability.ASYNC);
            replica = new RaftReplicator(node, Set.of("t"), Map.of(), dir.resolve("state"), ELECTION, HEARTBEAT, clock::nanoTime)
                    .open("t", writer, Durability.ASYNC, r -> { });
            for (int k = 0; k < 200 && !replica.leaderReady(); k++) step();
            assertTrue(replica.leaderReady());
        }

        void step() {
            clock.advance(Duration.ofMillis(20));
            node.drive();
        }

        long await(CompletableFuture<Long> f) throws Exception {
            for (int k = 0; k < 2000 && !f.isDone(); k++) {
                step();
                Thread.sleep(1);
            }
            return f.get(5, TimeUnit.SECONDS);
        }

        /** Drives until the ledger stops moving. */
        long settle() throws Exception {
            long last = -1;
            for (int k = 0; k < 50; k++) {
                step();
                Thread.sleep(2);
                long now = writer.snapshot().nextOffset();
                if (now == last && k > 10) break;
                last = now;
            }
            return writer.snapshot().nextOffset();
        }

        List<String> records() throws Exception {
            var out = new ArrayList<String>();
            for (var b : log.readAll()) for (var r : b.records()) out.add(new String(r, StandardCharsets.UTF_8));
            return out;
        }

        @Override
        public void close() throws Exception {
            replica.close();
            writer.close();
            log.close();
            node.close();
        }
    }

    static List<byte[]> recs(String... rs) {
        var out = new ArrayList<byte[]>();
        for (var r : rs) out.add(r.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    @Test
    void restartReplayDoesNotDuplicate() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.await(n.replica.appendNoWait(recs("a", "b"), Durability.GROUP)));
            assertEquals(2, n.await(n.replica.appendNoWait(recs("c"), null)));
            n.settle();
        }
        try (var n = new Node()) {
            assertEquals(3, n.settle());
            assertEquals(3, n.await(n.replica.appendNoWait(recs("d"), Durability.GROUP)));
            assertEquals(List.of("a", "b", "c", "d"), n.records());
        }
        // Lose every stamp but the header (crash before the stamp file reached disk): the ledger end still wins.
        try (var ch = FileChannel.open(dir.resolve("state/t.applied"), StandardOpenOption.WRITE)) {
            ch.truncate(28);
        }
        try (var n = new Node()) {
            assertEquals(4, n.settle());
            assertEquals(List.of("a", "b", "c", "d"), n.records());
        }
    }

    @Test
    void staleTermStampIsNeverApplied() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.await(n.replica.appendNoWait(recs("a"), Durability.GROUP)));
            long term = n.replica.term();
            ByteBuffer stale = RaftReplica.encode(recs("stale"), Durability.ASYNC, term - 1, 1, 999);
            var committed = n.node.append(RaftReplicator.group("t"), List.of(stale));
            for (int k = 0; k < 200 && !committed.isDone(); k++) n.step();
            assertTrue(committed.isDone() && !committed.isCompletedExceptionally(), "stale entry not committed by consensus");
            assertEquals(1, n.settle());
            assertEquals(1, n.await(n.replica.appendNoWait(recs("b"), Durability.GROUP)));
            assertEquals(List.of("a", "b"), n.records());
        }
    }
}
