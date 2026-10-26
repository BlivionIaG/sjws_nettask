package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import sjws_nettask.messaging.ledger.ProducerAppend;
import sjws_nettask.messaging.ledger.ProducerSequenceException;
import sjws_nettask.messaging.ledger.WriteOutcomeUnknownException;
import sjws_nettask.messaging.ledger.WriteRefusedException;
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

        <T> T await(CompletableFuture<T> f) throws Exception {
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

        /** The refusal of producer batch {@code f}, decided when it is applied. */
        ProducerSequenceException refused(CompletableFuture<?> f) throws Exception {
            for (int k = 0; k < 2000 && !f.isDone(); k++) {
                step();
                Thread.sleep(1);
            }
            var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
            return assertInstanceOf(ProducerSequenceException.class, e.getCause());
        }

        long produce(long pid, long seq, String... rs) throws Exception {
            return produceAck(pid, seq, rs).baseOffset();
        }

        ProducerAppend produceAck(long pid, long seq, String... rs) throws Exception {
            return await(replica.appendDeduplicated(recs(rs), Durability.GROUP, 0, pid, seq));
        }

        /** Proposes raw entry bytes as the leader, as another build (or a corrupt proposer) would. */
        void proposeRaw(ByteBuffer entry) throws Exception {
            await(node.append(RaftReplicator.group("t"), List.of(entry), null, replica.term()));
            settle();
        }

        String fenced() {
            return ((RaftReplica) replica).fenced();
        }

        byte[] table() {
            return RaftReplicator.producerTable(replica);
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

    /**
     * M2: a retried producer batch is acknowledged with its original offset and appended once; a gap or a reused seq
     * is refused with the seq to send next; a seq older than the window is a duplicate it can no longer place.
     * Producer id 0 is no dedup at all.
     */
    @Test
    void producerRetryIsAppendedOnceAndSequenceErrorsAreRefused() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.produce(7, 1, "a", "b"));
            assertEquals(0, n.produce(7, 1, "a", "b"), "retry: the original offset");
            var gap = n.refused(n.replica.appendDeduplicated(recs("z"), Durability.GROUP, 0, 7, 3));
            assertEquals(ProducerSequenceException.Kind.OUT_OF_ORDER, gap.kind());
            assertEquals(2, gap.expectedSeq());
            var reused = n.refused(n.replica.appendDeduplicated(recs("z"), Durability.GROUP, 0, 7, 1));
            assertEquals(ProducerSequenceException.Kind.SEQ_REUSED, reused.kind());
            assertEquals(2, n.produce(7, 2, "c"));
            assertEquals(ProducerSequenceException.Kind.UNKNOWN_PRODUCER,
                    n.refused(n.replica.appendDeduplicated(recs("z"), null, 0, 8, 2)).kind(), "a new producer starts at 1");
            assertEquals(3, n.produce(8, 1, "d"), "producers are independent");

            // Producer id 0: at-least-once, every send appended.
            assertEquals(4, n.produce(0, 1, "x"));
            assertEquals(5, n.produce(0, 1, "x"));
            assertThrows(IllegalArgumentException.class, () -> n.replica.appendDeduplicated(recs("z"), null, 0, 7, 0));

            for (int s = 3; s <= 2 + ProducerTable.WINDOW; s++) n.produce(7, s, "w" + s);
            var old = n.refused(n.replica.appendDeduplicated(recs("c"), Durability.GROUP, 0, 7, 2));
            assertEquals(ProducerSequenceException.Kind.DUPLICATE_OUT_OF_WINDOW, old.kind());
            assertEquals(3 + ProducerTable.WINDOW, old.expectedSeq());
            var rs = n.records();
            assertEquals(List.of("a", "b", "c", "d", "x", "x"), rs.subList(0, 6));
            assertEquals(6 + ProducerTable.WINDOW, rs.size(), "refused and duplicate batches take no offsets");
        }
    }

    /** M2: restart replay rebuilds the producer table exactly, with the stamp file and without it. */
    @Test
    void restartRebuildsTheProducerTable() throws Exception {
        byte[] before;
        try (var n = new Node()) {
            assertEquals(0, n.produce(7, 1, "a"));
            assertEquals(1, n.produce(0, 0, "plain"));
            assertEquals(2, n.produce(7, 2, "b", "c"));
            n.refused(n.replica.appendDeduplicated(recs("z"), null, 0, 7, 9));
            assertEquals(4, n.produce(9, 1, "d"));
            n.settle();
            before = n.table();
        }
        try (var n = new Node()) {
            assertEquals(5, n.settle());
            assertArrayEquals(before, n.table(), "replay rebuilt the same table");
            assertEquals(2, n.produce(7, 2, "b", "c"), "a retry after a restart still dedups");
            assertEquals(5, n.produce(7, 3, "e"));
            n.settle();
            before = n.table();
        }
        try (var ch = FileChannel.open(dir.resolve("state/t.applied"), StandardOpenOption.WRITE)) {
            ch.truncate(28); // stamps lost: only the header is left
        }
        try (var n = new Node()) {
            assertEquals(6, n.settle());
            assertArrayEquals(before, n.table());
            assertEquals(5, n.produce(7, 3, "e"));
            assertEquals(4, n.produce(9, 1, "d"));
            assertEquals(List.of("a", "plain", "b", "c", "d", "e"), n.records());
        }
    }

    /**
     * #278 Security B1 + B2: a retry with the same records is acknowledged as a duplicate; the same producer, seq and
     * record count with other records is refused as seq_reused and appends nothing. At 4627d8f the second case was
     * acknowledged with the first batch's offset and never stored.
     */
    @Test
    void duplicateIsMarkedAndAnotherBatchUnderTheSameSeqIsRefused() throws Exception {
        try (var n = new Node()) {
            var first = n.produceAck(7, 1, "a", "b");
            assertEquals(new ProducerAppend(0, false), first);
            assertEquals(new ProducerAppend(0, true), n.produceAck(7, 1, "a", "b"), "a retry is marked duplicate");
            var other = n.refused(n.replica.appendDeduplicated(recs("x", "y"), Durability.GROUP, 0, 7, 1));
            assertEquals(ProducerSequenceException.Kind.SEQ_REUSED, other.kind(), "same id, seq and count, other records");
            assertEquals(2, other.expectedSeq());
            assertEquals(new ProducerAppend(2, false), n.produceAck(7, 2, "c"));
            assertEquals(List.of("a", "b", "c"), n.records());
        }
    }

    /**
     * #278 HA B2: a seq above 1 from a producer the table does not hold (never seen, or evicted) cannot be decided: an
     * earlier attempt may be in the log. It is UNKNOWN_PRODUCER, never OUT_OF_ORDER (not-appended); a known producer's
     * gap stays OUT_OF_ORDER.
     */
    @Test
    void anUnknownProducerAboveSeqOneIsUnknownNotOutOfOrder() throws Exception {
        try (var n = new Node()) {
            var unknown = n.refused(n.replica.appendDeduplicated(recs("z"), Durability.GROUP, 0, 9, 3));
            assertEquals(ProducerSequenceException.Kind.UNKNOWN_PRODUCER, unknown.kind());
            assertEquals(1, unknown.expectedSeq());
            assertEquals(0, n.produce(9, 1, "a"));
            assertEquals(ProducerSequenceException.Kind.OUT_OF_ORDER,
                    n.refused(n.replica.appendDeduplicated(recs("z"), Durability.GROUP, 0, 9, 3)).kind(), "a known producer's gap");
            assertEquals(List.of("a"), n.records());
        }
    }

    static ByteBuffer header(byte magic, int mode, long term, int origin, long seq, int count, int extra) {
        return ByteBuffer.allocate(1 + 1 + 8 + 4 + 8 + 4 + extra).put(magic).put((byte) mode).putLong(term).putInt(origin)
                .putLong(seq).putInt(count);
    }

    /** #278 HA B1: an entry of an unknown format fails the replica closed; it is never skipped. */
    @Test
    void anUnknownEntryMagicFailsClosed() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.produce(7, 1, "a"));
            n.proposeRaw(header((byte) 'Q', 0, n.replica.term(), 2, 1, 0, 0).flip());
            assertTrue(n.fenced() != null && n.fenced().contains("unknown entry magic 0x51"), String.valueOf(n.fenced()));
            var refused = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> n.replica.appendNoWait(recs("b"), null).get(5, TimeUnit.SECONDS));
            assertInstanceOf(WriteRefusedException.class, refused.getCause(), "a failed-closed replica refuses appends");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> n.replica.appendDeduplicated(recs("b"), null, 0, 7, 2).get(5, TimeUnit.SECONDS));
            assertEquals(List.of("a"), n.records(), "nothing applied after the fence");
        }
        try (var n = new Node()) { // the replay meets the same entry: fails closed again, never skips past it
            n.settle();
            assertTrue(n.fenced() != null && n.fenced().contains("unknown entry magic"), String.valueOf(n.fenced()));
        }
    }

    /**
     * #278 HA B1: a short producer entry, or a records region that does not match its count, fails closed, and the
     * proposer's own pending batch completes exceptionally instead of hanging.
     */
    @Test
    void malformedEntriesFailClosedAndFailTheirProposer() throws Exception {
        try (var n = new Node()) {
            @SuppressWarnings("unchecked")
            var pending = (Map<Long, CompletableFuture<Long>>) field(n.replica, "pending");
            var mine = new CompletableFuture<Long>();
            long seq = 1_000_000;
            pending.put(seq, mine);
            var shortP = header((byte) 'P', 0, n.replica.term(), 1, seq, 1, 4).putInt(0).flip(); // 4 of 20 producer bytes
            n.proposeRaw(shortP);
            assertTrue(n.fenced() != null && n.fenced().contains("shorter than its header"), String.valueOf(n.fenced()));
            var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> mine.get(5, TimeUnit.SECONDS));
            assertInstanceOf(WriteOutcomeUnknownException.class, e.getCause());
            assertEquals(List.of(), n.records());
        }
    }

    @Test
    void aRecordsRegionThatDoesNotMatchItsCountFailsClosed() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.produce(7, 1, "a"));
            // count 2, one record of 1 byte: the old code read past the end mid-apply
            n.proposeRaw(header((byte) 'M', 0, n.replica.term(), 2, 1, 2, 5).putInt(1).put((byte) 'x').flip());
            assertTrue(n.fenced() != null && n.fenced().contains("does not hold 2 records"), String.valueOf(n.fenced()));
            assertEquals(List.of("a"), n.records());
        }
    }

    /** #278 HA B3 / Security N4: a replay whose offsets disagree with the stamp file fails closed, never patched over. */
    @Test
    void aReplayThatDisagreesWithTheStampFileFailsClosed() throws Exception {
        try (var n = new Node()) {
            assertEquals(0, n.produce(7, 1, "a"));
            assertEquals(1, n.produce(7, 2, "b", "c"));
            n.settle();
        }
        var stamps = dir.resolve("state/t.applied");
        try (var ch = FileChannel.open(stamps, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long last = ch.size() - 28;
            var count = ByteBuffer.allocate(4);
            ch.read(count, last + 24);
            assertEquals(2, count.flip().getInt(), "the last stamp is the two-record batch");
            ch.write(ByteBuffer.allocate(4).putInt(1).flip(), last + 24); // resume would expect offset 2, replay reaches 3
        }
        try (var n = new Node()) {
            n.settle();
            assertTrue(n.fenced() != null && n.fenced().contains("stamp file says 2"), String.valueOf(n.fenced()));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> n.replica.appendDeduplicated(recs("b", "c"), null, 0, 7, 2).get(5, TimeUnit.SECONDS),
                    "no dedup answer from a table built on the wrong offsets");
            assertEquals(List.of("a", "b", "c"), n.records());
        }
    }

    /** #278 Security B4: the producer table exists only once a producer entry is applied. */
    @Test
    void theProducerTableIsCreatedByTheFirstProducerEntry() throws Exception {
        try (var n = new Node()) {
            n.await(n.replica.appendNoWait(recs("plain"), null));
            n.settle();
            assertEquals(null, ((RaftReplica) n.replica).producers());
            assertEquals(1, n.produce(7, 1, "a"));
            assertTrue(((RaftReplica) n.replica).producers() != null);
        }
    }

    static Object field(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
