package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Issue #237: a leader batch is all-or-nothing. A storage failure after entry k of n rolls the batch back
 * (nothing of it is replicated, committed or left on disk); a failed rollback fails the group closed.
 */
@Timeout(60)
class BatchAtomicityTest {

    private static final GroupId GROUP = new GroupId("msg/atomic");

    @TempDir
    Path tmp;

    private final FakeClock clock = new FakeClock(0L);
    private final SimTransport net = new SimTransport();
    private final List<ReplicatedNode> nodes = new ArrayList<>();
    private final List<ReplicationGroup> groups = new ArrayList<>();
    private final List<Faulty> storages = new ArrayList<>();
    private final List<List<String>> applied = new ArrayList<>();
    private GroupOptions opts;

    /** Delegating storage that fails on command: the k-th append from now, a force, or a truncate. */
    static final class Faulty implements LogStorage {
        final LogStorage d;
        final Path segment; // for torn-tail injection on FileLogStorage, else null
        volatile int failAppendAfter = -1; // appends allowed before the injected failure; -1 = never
        volatile boolean failForce;
        volatile boolean failTruncate;
        int appends;
        /** Every storage call that writes, counted whether or not it then fails. */
        final java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger snapshots = new java.util.concurrent.atomic.AtomicInteger();

        Faulty(LogStorage d, Path segment) {
            this.d = d;
            this.segment = segment;
        }

        void armAppend(int okBefore) {
            appends = 0;
            failAppendAfter = okBefore;
        }

        void disarm() {
            failAppendAfter = -1;
            failForce = false;
            failTruncate = false;
        }

        @Override
        public void append(GroupId g, long index, long term, byte[] data) throws IOException {
            writes.incrementAndGet();
            if (failAppendAfter >= 0 && appends++ >= failAppendAfter) {
                failAppendAfter = -1;
                if (segment != null) {
                    // A torn record: a header that promises more bytes than follow.
                    Files.write(segment, new byte[] {0, 0, 0, 0, 0, 0, 0, 9, 7, 7}, StandardOpenOption.APPEND);
                }
                throw new IOException("injected append failure at index " + index);
            }
            d.append(g, index, term, data);
        }

        @Override
        public byte[] read(GroupId g, long index) throws IOException {
            return d.read(g, index);
        }

        @Override
        public long termAt(GroupId g, long index) throws IOException {
            return d.termAt(g, index);
        }

        @Override
        public long lastIndex(GroupId g) {
            return d.lastIndex(g);
        }

        @Override
        public void truncateSuffix(GroupId g, long fromIndex) throws IOException {
            writes.incrementAndGet();
            if (failTruncate) {
                throw new IOException("injected truncate failure");
            }
            d.truncateSuffix(g, fromIndex);
        }

        @Override
        public void truncatePrefix(GroupId g, long throughIndex) throws IOException {
            writes.incrementAndGet();
            d.truncatePrefix(g, throughIndex);
        }

        @Override
        public void force(GroupId g) throws IOException {
            writes.incrementAndGet();
            if (failForce) {
                failForce = false;
                throw new IOException("injected fsync failure");
            }
            d.force(g);
        }

        @Override
        public int forces(GroupId g) {
            return d.forces(g);
        }

        @Override
        public boolean survivesRestart() {
            return d.survivesRestart();
        }

        @Override
        public long witnessIndex(GroupId g) throws IOException {
            return d.witnessIndex(g);
        }

        @Override
        public long witnessTerm(GroupId g) throws IOException {
            return d.witnessTerm(g);
        }

        @Override
        public void noteWitness(GroupId g, long index, long term) throws IOException {
            writes.incrementAndGet();
            d.noteWitness(g, index, term);
        }

        @Override
        public void saveHardState(GroupId g, long term, int votedFor) throws IOException {
            writes.incrementAndGet();
            d.saveHardState(g, term, votedFor);
        }

        @Override
        public boolean hasHardState(GroupId g) throws IOException {
            return d.hasHardState(g);
        }

        @Override
        public long hardStateTerm(GroupId g) throws IOException {
            return d.hardStateTerm(g);
        }

        @Override
        public int hardStateVote(GroupId g) throws IOException {
            return d.hardStateVote(g);
        }

        @Override
        public boolean persistsSnapshots() {
            return d.persistsSnapshots();
        }

        @Override
        public void saveSnapshot(GroupId g, LogSnapshot snapshot) throws IOException {
            writes.incrementAndGet();
            snapshots.incrementAndGet();
            d.saveSnapshot(g, snapshot);
        }

        @Override
        public LogSnapshot loadSnapshot(GroupId g, int maxBytes) throws IOException {
            return d.loadSnapshot(g, maxBytes);
        }
    }

    private void start(Durability durability, boolean files) throws IOException {
        opts = new GroupOptions(durability, 1 << 20, Duration.ofMillis(100), Duration.ofMillis(40), Long.MAX_VALUE);
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (var m : members) {
            Faulty storage;
            if (files) {
                var root = Files.createDirectories(tmp.resolve("n" + m.id().wire()));
                storage = new Faulty(new FileLogStorage(root), root.resolve("msg_atomic.log"));
            } else {
                storage = new Faulty(new MemoryLogStorage(), null);
            }
            var seen = new CopyOnWriteArrayList<String>();
            var node = ReplicatedNode.open(m.id(), voters, clock, storage, net);
            groups.add(node.openGroup(GROUP, opts, entry -> {
                var copy = new byte[entry.entry().remaining()];
                entry.entry().duplicate().get(copy);
                seen.add(new String(copy, StandardCharsets.UTF_8));
            }));
            nodes.add(node);
            storages.add(storage);
            applied.add(seen);
        }
        for (int k = 0; k < 40 && leader() < 0; k++) {
            step();
        }
        assertTrue(leader() >= 0, "no leader elected");
    }

    private void step() {
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
    }

    private ReplicationGroup group(int i) {
        return groups.get(i); // openGroup again would replace the listener
    }

    private int leader() {
        for (int i = 0; i < 2; i++) {
            if (group(i).isLeader()) {
                return i;
            }
        }
        return -1;
    }

    private static List<ByteBuffer> batch(String tag, int n) {
        var out = new ArrayList<ByteBuffer>();
        for (int i = 1; i <= n; i++) {
            out.add(ByteBuffer.wrap((tag + "-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private <T> T settle(CompletableFuture<T> future) throws Exception {
        for (int k = 0; k < 30 && !future.isDone(); k++) {
            step();
        }
        return future.get();
    }

    private static boolean contains(List<String> seen, String tag) {
        return seen.stream().anyMatch(s -> s.contains(tag + "-"));
    }

    @ParameterizedTest(name = "{0}: fail after entry {1} of 4")
    @CsvSource({"RELAXED,0", "RELAXED,1", "RELAXED,2", "RELAXED,3", "STRICT,0", "STRICT,1", "STRICT,2", "STRICT,3"})
    void appendFailureAfterEntryKRollsTheWholeBatchBack(Durability durability, int k) throws Exception {
        start(durability, false);
        int l = leader();
        settle(nodes.get(l).append(GROUP, batch("before", 2)));
        long last = storages.get(l).lastIndex(GROUP);

        storages.get(l).armAppend(k);
        var failed = nodes.get(l).append(GROUP, batch("bad", 4));
        var error = assertThrows(ExecutionException.class, failed::get);
        assertInstanceOf(UncheckedIOException.class, error.getCause());
        assertEquals(last, storages.get(l).lastIndex(GROUP), "no entry of the failed batch may stay in the log");
        assertTrue(group(l).isLeader(), "a rolled-back batch keeps the leader serving");

        var ok = settle(nodes.get(l).append(GROUP, batch("after", 3)));
        assertEquals(last + 3, ok.index());
        for (int i = 0; i < 2; i++) {
            int node = i;
            for (int s = 0; s < 10 && !contains(applied.get(node), "after"); s++) {
                step();
            }
            assertFalse(contains(applied.get(i), "bad"), "node " + i + " applied part of the failed batch");
            assertTrue(contains(applied.get(i), "after"), "node " + i + " missed the next batch");
            assertEquals(last + 3, storages.get(i).lastIndex(GROUP));
        }
    }

    @Test
    void fsyncFailureOfAStrictBatchRollsItBack() throws Exception {
        start(Durability.STRICT, false);
        int l = leader();
        settle(nodes.get(l).append(GROUP, batch("before", 1)));
        long last = storages.get(l).lastIndex(GROUP);
        storages.get(l).failForce = true;
        var failed = nodes.get(l).append(GROUP, batch("bad", 3));
        assertInstanceOf(UncheckedIOException.class, assertThrows(ExecutionException.class, failed::get).getCause());
        assertEquals(last, storages.get(l).lastIndex(GROUP));
        settle(nodes.get(l).append(GROUP, batch("after", 1)));
        assertFalse(contains(applied.get(l), "bad"));
    }

    @Test
    void failedRollbackFailsTheGroupClosedAndAnotherNodeTakesOver() throws Exception {
        start(Durability.RELAXED, true); // file log: snapshots and prefix deletes are real
        int l = leader();
        var faulty = storages.get(l);
        settle(nodes.get(l).append(GROUP, batch("before", 3)));
        faulty.failTruncate = true;
        faulty.armAppend(2);
        var failed = nodes.get(l).append(GROUP, batch("bad", 4));
        var cause = assertThrows(ExecutionException.class, failed::get).getCause();
        assertInstanceOf(UncheckedIOException.class, cause);
        assertTrue(cause.getMessage().contains("outcome unknown"), cause.getMessage());
        assertFalse(group(l).isLeader(), "a group that cannot roll back must step down");
        assertTrue(nodes.get(l).failedClosed(GROUP), "failed-closed state must be visible for health");
        assertFalse(nodes.get(1 - l).failedClosed(GROUP));

        faulty.disarm(); // even with a healthy disk again, it stays closed until restart
        int writes = faulty.writes.get();
        int snapshots = faulty.snapshots.get();
        long commit = group(l).commitIndex();
        long last = faulty.lastIndex(GROUP);
        long term = group(l).term();

        var refused = nodes.get(l).append(GROUP, batch("later", 1));
        assertFenced(assertThrows(ExecutionException.class, refused::get).getCause());
        var control = nodes.get(l).snapshots(GROUP);
        assertFenced(assertThrows(UncheckedIOException.class, () -> control.take(commit)));
        assertFenced(assertThrows(UncheckedIOException.class,
                () -> control.install(new LogSnapshot(GROUP, last + 10, term, new byte[] {1}))));
        var compacted = control.truncateAndCompact(commit, snap -> new byte[] {2});
        assertFenced(assertThrows(ExecutionException.class, compacted::get).getCause());
        control.setTruncationBound((g, snapshotIndex) -> Long.MAX_VALUE);
        var bounded = control.truncateToBound(snap -> new byte[] {3});
        assertFenced(assertThrows(ExecutionException.class, bounded::get).getCause());

        int other = 1 - l;
        for (int k = 0; k < 60 && !group(other).isLeader(); k++) {
            step();
        }
        assertTrue(group(other).isLeader(), "the healthy data node never took over");
        settle(nodes.get(other).append(GROUP, batch("next", 1)));
        assertFalse(contains(applied.get(other), "bad"), "the failed batch was never replicated");
        for (int k = 0; k < 20; k++) {
            step();
        }
        assertFalse(group(l).isLeader(), "the failed-closed node must not campaign");
        assertEquals(writes, faulty.writes.get(), "a failed-closed group must not touch its storage");
        assertEquals(snapshots, faulty.snapshots.get(), "a failed-closed group must not write a snapshot");
        assertEquals(last, faulty.lastIndex(GROUP), "nothing appended or truncated after failing closed");
        assertEquals(commit, group(l).commitIndex(), "commit must not advance after failing closed");
        assertFalse(contains(applied.get(l), "next"), "a failed-closed group must not apply new entries");
    }

    @Test
    void failingClosedFailsEarlierPendingWritesAsOutcomeUnknownNotNotLeader() throws Exception {
        start(Durability.RELAXED, false);
        int l = leader();
        var faulty = storages.get(l);
        settle(nodes.get(l).append(GROUP, batch("before", 1)));
        // Keep the next batch pending: in the log, but no quorum can ack it.
        net.cut(nodes.get(l).self(), nodes.get(1 - l).self());
        net.cut(nodes.get(l).self(), nodes.get(2).self());
        var inFlight = nodes.get(l).append(GROUP, batch("inflight", 2));
        assertFalse(inFlight.isDone(), "the in-flight batch must still be pending");
        long pendingLast = faulty.lastIndex(GROUP);

        faulty.failTruncate = true;
        faulty.armAppend(1);
        var failed = nodes.get(l).append(GROUP, batch("bad", 3));
        assertTrue(assertThrows(ExecutionException.class, failed::get).getCause().getMessage()
                .contains("outcome unknown"));
        assertTrue(nodes.get(l).failedClosed(GROUP));

        var cause = assertThrows(ExecutionException.class,
                () -> inFlight.get(10, java.util.concurrent.TimeUnit.SECONDS)).getCause();
        assertFalse(cause instanceof NotLeaderException, "an in-log write may still commit; not-leader says it won't");
        assertInstanceOf(UncheckedIOException.class, cause);
        assertTrue(cause.getMessage().contains("outcome unknown"), cause.getMessage());
        assertTrue(cause.getMessage().contains("index " + pendingLast), cause.getMessage());
    }

    /** Cut the leader off from both peers, so nothing it appends or asks can reach a quorum. */
    private void isolate(int l) {
        net.cut(nodes.get(l).self(), nodes.get(1 - l).self());
        net.cut(nodes.get(l).self(), nodes.get(2).self());
    }

    private Set<NodeId> allMembers() {
        return Set.of(nodes.get(0).self(), nodes.get(1).self(), nodes.get(2).self());
    }

    @Test
    void readBarrierWaitersFailWithNotLeaderOnStepDown() throws Exception {
        start(Durability.RELAXED, false);
        int l = leader();
        int other = 1 - l;
        settle(nodes.get(l).append(GROUP, batch("before", 1)));
        isolate(l);
        clock.advance(Duration.ofSeconds(10)); // past the lease, before any tick: still leader, so the read waits
        var read = group(l).readBarrier();
        assertFalse(read.isDone(), "a barrier past the lease must wait for a quorum round");
        for (int k = 0; k < 60 && !group(other).isLeader(); k++) {
            step();
        }
        assertTrue(group(other).isLeader(), "the other data node never took over");
        net.heal(nodes.get(l).self(), nodes.get(other).self());
        net.heal(nodes.get(l).self(), nodes.get(2).self());
        for (int k = 0; k < 20 && !read.isDone(); k++) {
            step();
        }
        assertFalse(group(l).isLeader());
        var cause = assertThrows(ExecutionException.class, () -> read.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(NotLeaderException.class, cause);
    }

    @Test
    void failingClosedFailsAPendingMembershipChangeAsOutcomeUnknownAndRefusesNewOnes() throws Exception {
        start(Durability.RELAXED, false);
        int l = leader();
        var faulty = storages.get(l);
        settle(nodes.get(l).append(GROUP, batch("before", 1)));
        isolate(l);
        var change = group(l).changeMembership(allMembers(), Set.of());
        assertFalse(change.isDone(), "the joint config cannot commit without a quorum");
        clock.advance(Duration.ofSeconds(10));
        var read = group(l).readBarrier();
        assertFalse(read.isDone());

        faulty.failTruncate = true;
        faulty.armAppend(1);
        var failed = nodes.get(l).append(GROUP, batch("bad", 3));
        var batchError = assertThrows(ExecutionException.class, failed::get).getCause();
        assertTrue(batchError.getMessage().contains("outcome unknown"), batchError.getMessage());
        assertTrue(nodes.get(l).failedClosed(GROUP));

        // (b) the pending change was already in the log: outcome unknown, same root cause as the batch.
        var changeError = assertThrows(ExecutionException.class, () -> change.get(10, TimeUnit.SECONDS)).getCause();
        assertFalse(changeError instanceof NotLeaderException, "an in-log config change may still commit");
        assertInstanceOf(UncheckedIOException.class, changeError);
        assertTrue(changeError.getMessage().contains("membership change"), changeError.getMessage());
        assertTrue(changeError.getMessage().contains("outcome unknown"), changeError.getMessage());
        assertSame(batchError.getCause(), changeError.getCause(), "the original storage error is the cause");
        assertSame(batchError, changeError.getSuppressed()[0], "the fail-closed error rides along as suppressed");

        // A waiting read barrier fails with the fenced error.
        assertFenced(assertThrows(ExecutionException.class, () -> read.get(10, TimeUnit.SECONDS)).getCause());

        // (d) new calls after failing closed are refused as fenced, not as not-leader.
        var again = group(l).changeMembership(allMembers(), Set.of());
        assertFenced(assertThrows(ExecutionException.class, () -> again.get(10, TimeUnit.SECONDS)).getCause());
        var barrier = group(l).readBarrier();
        assertFenced(assertThrows(ExecutionException.class, () -> barrier.get(10, TimeUnit.SECONDS)).getCause());
    }

    private static void assertFenced(Throwable error) {
        assertInstanceOf(UncheckedIOException.class, error);
        assertTrue(error.getMessage().contains("failed closed"), error.getMessage());
    }

    @ParameterizedTest(name = "file log, fail after entry {0} of 3")
    @CsvSource({"0", "1", "2"})
    void fileLogDropsThePartialBatchAndTheTornTailAcrossARestart(int k) throws Exception {
        start(Durability.STRICT, true);
        int l = leader();
        settle(nodes.get(l).append(GROUP, batch("before", 2)));
        storages.get(l).armAppend(k);
        var failed = nodes.get(l).append(GROUP, batch("bad", 3));
        assertThrows(ExecutionException.class, failed::get);
        var ok = settle(nodes.get(l).append(GROUP, batch("after", 2)));

        var reopened = new FileLogStorage(tmp.resolve("n" + nodes.get(l).self().wire()));
        assertEquals(ok.index(), reopened.lastIndex(GROUP));
        boolean sawAfter = false;
        for (long i = 1; i <= ok.index(); i++) {
            var data = reopened.read(GROUP, i);
            var text = data == null ? "" : new String(data, StandardCharsets.UTF_8);
            assertFalse(text.contains("bad-"), "index " + i + " holds part of the failed batch after restart");
            sawAfter |= text.contains("after-2");
        }
        assertTrue(sawAfter, "the batch after the failure did not survive the restart");
    }

    @Test
    void argumentErrorsFailTheFutureInsteadOfThrowing() throws Exception {
        start(Durability.RELAXED, false);
        var node = nodes.get(leader());
        var list = new ArrayList<ByteBuffer>();
        list.add(null);
        for (var refused : List.of(node.append(GROUP, List.of()), node.append(GROUP, null), node.append(GROUP, list),
                node.append(GROUP, batch("x", 1), null, 0L))) {
            assertInstanceOf(IllegalArgumentException.class,
                    assertThrows(ExecutionException.class, refused::get).getCause());
        }
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 25_200 + wire),
                1L, MemberState.ALIVE, witness);
    }
}
