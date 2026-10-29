package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            try {
                replica = new RaftReplicator(node, Set.of("t"), Map.of(), dir.resolve("state"), ELECTION, HEARTBEAT, clock::nanoTime)
                        .open("t", writer, Durability.ASYNC, r -> { });
            } catch (RuntimeException e) {
                writer.close();
                log.close();
                node.close();
                throw e;
            }
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

    /**
     * #254: the replica keeps "refused" and "unknown" apart. Not leader / term moved: LeaderUnavailable (HTTP may
     * redirect). Other refusals before the log: WriteRefused (nothing appended). In the log, fate unknown:
     * WriteOutcomeUnknown with the hint, never LeaderUnavailable. Unclassified errors pass through as they are.
     */
    @Test
    void consensusErrorsMapToRefusedOrOutcomeUnknown() throws Exception {
        try (var n = new Node()) {
            var r = (RaftReplica) n.replica;
            var g = RaftReplicator.group("t");
            var n2 = new NodeId(2, "n2");
            assertInstanceOf(sjws_nettask.messaging.ledger.LeaderUnavailableException.class,
                    r.map(new sjws_nettask.cluster.NotLeaderException(g, java.util.Optional.of(n2)), true));
            assertInstanceOf(sjws_nettask.messaging.ledger.LeaderUnavailableException.class,
                    r.map(new java.util.concurrent.CompletionException(sjws_nettask.cluster.ClusterErrors.termChanged(g)), true));

            var unknown = r.map(new java.util.concurrent.CompletionException(new sjws_nettask.cluster.OutcomeUnknownException(
                    g, "stopped leading", java.util.Optional.of(n2), null)), true);
            var u = assertInstanceOf(sjws_nettask.messaging.ledger.WriteOutcomeUnknownException.class, unknown);
            assertEquals("n2", u.leaderName(), "the hint from consensus wins");
            assertFalse(unknown instanceof sjws_nettask.messaging.ledger.LeaderUnavailableException);
            assertInstanceOf(sjws_nettask.messaging.ledger.WriteOutcomeUnknownException.class,
                    r.map(new sjws_nettask.cluster.CommittedNotAppliedException(g.value(), 7), true));

            var lag = assertInstanceOf(sjws_nettask.messaging.ledger.WriteRefusedException.class,
                    r.map(sjws_nettask.cluster.ClusterErrors.applyLag(g), true));
            assertTrue(lag.retryable());
            assertTrue(assertInstanceOf(sjws_nettask.messaging.ledger.WriteRefusedException.class,
                    r.map(sjws_nettask.cluster.ClusterErrors.durabilityUnavailable(g), true)).retryable());
            assertTrue(assertInstanceOf(sjws_nettask.messaging.ledger.WriteRefusedException.class,
                    r.map(sjws_nettask.cluster.ClusterErrors.rolledBack(g), true)).retryable(), "rolled back: not appended");
            assertTrue(assertInstanceOf(sjws_nettask.messaging.ledger.WriteRefusedException.class,
                    r.map(sjws_nettask.cluster.ClusterErrors.fenced(g), true)).retryable(), "fenced: not appended");
            var otherIo = new java.io.UncheckedIOException(new java.io.IOException("unclassified storage error"));
            assertSame(otherIo, r.map(otherIo, true), "only the dedicated storage refusals count as not appended");

            // A leader read is never classified as a write outcome (Messaging Core C2): the fenced error passes through.
            var fenced = sjws_nettask.cluster.ClusterErrors.fenced(g);
            assertSame(fenced, r.map(fenced, false));
            assertInstanceOf(sjws_nettask.messaging.ledger.LeaderUnavailableException.class,
                    r.map(new sjws_nettask.cluster.NotLeaderException(g, java.util.Optional.empty()), false));
            assertFalse(assertInstanceOf(sjws_nettask.messaging.ledger.WriteRefusedException.class,
                    r.map(new IllegalArgumentException("batch exceeds maxBatchBytes"), true)).retryable());

            assertTrue(assertInstanceOf(sjws_nettask.messaging.ledger.WriteRefusedException.class,
                    r.map(new sjws_nettask.cluster.GroupClosedException(g), true)).retryable(), "closed: not appended");
            var other = new IllegalStateException("unclassified");
            assertSame(other, r.map(other, true), "unclassified errors stay as they are (HTTP: outcome unknown)");
        }
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

    /** #274 MC-B1: ledger and replication state must line up at open; a crash-shaped gap still recovers above. */
    @Test
    void aLedgerThatDoesNotLineUpIsRefusedAtOpen() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.await(n.replica.appendNoWait(recs("a", "b"), Durability.GROUP)));
            n.settle();
        }
        // A local write after a clean close (replication off): refused, naming the topic and both ends.
        try (var log = SegmentedLog.open(dir.resolve("ledger")); var w = new LedgerWriter(log, Durability.ASYNC)) {
            w.append(recs("local"), Durability.ASYNC).get(5, TimeUnit.SECONDS);
        }
        var moved = assertThrows(IllegalArgumentException.class, Node::new);
        assertTrue(moved.getMessage().contains("replicated topic t ledger end is 3 but replication stopped cleanly at 2"),
                moved.getMessage());
        // The ledger wiped but its replication state kept: the first-start check (non-empty) does not apply, the
        // stamps do: an empty ledger below a header that started at 2 is refused too.
        try (var ch = FileChannel.open(dir.resolve("state/t.applied"), StandardOpenOption.WRITE)) {
            ch.truncate(0);
            ch.write(ByteBuffer.allocate(28).putLong(0).putLong(0).putLong(2).putInt(0).flip());
        }
        try (var walk = java.nio.file.Files.walk(dir.resolve("ledger"))) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
        }
        var wiped = assertThrows(IllegalArgumentException.class, Node::new);
        assertTrue(wiped.getMessage().contains("ledger end is 0 but replication started at offset 2"), wiped.getMessage());
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
