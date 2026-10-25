package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #250: {@code transferLeadership} gave up leadership without failing pending writes. The futures stayed pending,
 * and once another leader's entry was committed at their index, the old leader (by then a follower) completed them
 * as committed: the caller was told a discarded write had committed. Single-threaded, on a fake clock.
 */
@Timeout(60)
class TransferLeadershipPendingTest {

    private static final GroupId GROUP = new GroupId("msg/transfer-pending");
    private static final GroupOptions OPTS = new GroupOptions(Durability.RELAXED, 1 << 20, Duration.ofMillis(100),
            Duration.ofMillis(40), Long.MAX_VALUE);

    private final FakeClock clock = new FakeClock(0L);
    private final SimTransport net = new SimTransport();
    private final List<ReplicatedNode> nodes = new ArrayList<>();
    private final List<ReplicationGroup> groups = new ArrayList<>();
    private final List<List<CommittedEntry>> applied = new ArrayList<>();

    @BeforeEach
    void start() {
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (var m : members) {
            var seen = new CopyOnWriteArrayList<CommittedEntry>();
            var node = ReplicatedNode.open(m.id(), voters, clock, new MemoryLogStorage(), net);
            groups.add(node.openGroup(GROUP, OPTS, seen::add));
            nodes.add(node);
            applied.add(seen);
        }
        for (int k = 0; k < 40 && leader() < 0; k++) {
            step();
        }
        assertTrue(leader() >= 0, "no leader elected");
    }

    @Test
    void appendPendingAtTransferFailsAndNeverCompletesWithAnotherLeadersEntry() throws Exception {
        int a = leader();
        int b = 1 - a;
        long term = groups.get(a).leaderTerm();
        isolate(a);
        var pending = nodes.get(a).append(GROUP, List.of(text("lost")), null, term);
        assertFalse(pending.isDone(), "no quorum can ack the append while the leader is cut off");

        groups.get(a).transferLeadership(nodes.get(b).self()); // the TIMEOUT_NOW is lost too
        assertOutcomeUnknown(pending);

        // The rest of the original repro: b takes over and overwrites the index; a must not report it committed.
        for (int k = 0; k < 60 && !groups.get(b).isLeader(); k++) {
            step();
        }
        assertTrue(groups.get(b).isLeader(), "b never took over");
        assertTrue(groups.get(b).leaderTerm() > term);
        heal(a);
        for (int k = 0; k < 10; k++) {
            step();
        }
        groups.get(b).transferLeadership(nodes.get(a).self());
        for (int k = 0; k < 60 && !groups.get(a).isLeader(); k++) {
            step();
        }
        assertTrue(groups.get(a).isLeader(), "a never took leadership back");
        var after = nodes.get(a).append(GROUP, List.of(text("after")), null, groups.get(a).leaderTerm());
        for (int k = 0; k < 10 && !after.isDone(); k++) {
            step();
        }
        assertTrue(after.get(10, TimeUnit.SECONDS).term() > term);
        assertOutcomeUnknown(pending);
        for (int k = 0; k < 10; k++) {
            step();
        }
        for (int i = 0; i < 2; i++) {
            assertFalse(appliedTexts(i).contains("lost"), "node " + i + " applied the discarded entry");
        }
        assertTrue(appliedTexts(a).contains("after"), "the new leader did not apply its own committed entry");
    }

    @Test
    void membershipChangePendingAtTransferFails() throws Exception {
        int a = leader();
        isolate(a);
        Set<NodeId> voters = Set.of(nodes.get(0).self(), nodes.get(1).self(), nodes.get(2).self());
        CompletableFuture<Void> change = groups.get(a).changeMembership(voters, Set.of());
        assertFalse(change.isDone(), "no quorum can commit the joint config while the leader is cut off");

        groups.get(a).transferLeadership(nodes.get(1 - a).self());
        var cause = assertThrows(ExecutionException.class, () -> change.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(OutcomeUnknownException.class, cause, "the joint config is in the log: it may still commit");
        assertEquals(nodes.get(1 - a).self(), ((OutcomeUnknownException) cause).hint().orElseThrow());
    }

    /**
     * #254: OutcomeUnknown means "may still commit", and it does. The entry reaches b, a hands leadership to b
     * before b's ack arrives, so a fails the write as outcome unknown; b then commits it (with its no-op), and every
     * node applies it. NotLeaderException here would have told the client to resend: a duplicate.
     */
    @Test
    void entryOnTheNewLeaderCommitsDespiteTheOldLeadersOutcomeUnknown() throws Exception {
        int a = leader();
        int b = 1 - a;
        net.cut(nodes.get(a).self(), nodes.get(2).self()); // only b can ack, so the ack is the one thing to hold back
        drain();
        var pending = nodes.get(a).append(GROUP, List.of(text("maybe")), null, groups.get(a).leaderTerm());
        assertEquals(1, net.deliver(1), "the append must reach b");
        assertFalse(pending.isDone(), "b's ack is still queued");

        groups.get(a).transferLeadership(nodes.get(b).self());
        var cause = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(OutcomeUnknownException.class, cause);
        assertFalse(cause instanceof NotLeaderException);

        for (int k = 0; k < 60 && !groups.get(b).isLeader(); k++) {
            step();
        }
        assertTrue(groups.get(b).isLeader(), "b never took over");
        net.heal(nodes.get(a).self(), nodes.get(2).self());
        for (int k = 0; k < 20 && !(appliedTexts(a).contains("maybe") && appliedTexts(b).contains("maybe")); k++) {
            step();
        }
        assertTrue(appliedTexts(b).contains("maybe"), "the new leader must commit the entry it already had");
        assertTrue(appliedTexts(a).contains("maybe"), "and the old leader applies it too");
        assertTrue(pending.isCompletedExceptionally(), "the old future stays failed");
    }

    /** #254: close() resolves every future it holds; nothing parks a caller (CpStore, RaftReplica) forever. */
    @Test
    void closeResolvesPendingAppendsMembershipChangesAndReads() throws Exception {
        int a = leader();
        isolate(a);
        var append = nodes.get(a).append(GROUP, List.of(text("open")), null, groups.get(a).leaderTerm());
        Set<NodeId> voters = Set.of(nodes.get(0).self(), nodes.get(1).self(), nodes.get(2).self());
        CompletableFuture<Void> change = groups.get(a).changeMembership(voters, Set.of());
        clock.advance(Duration.ofSeconds(10));
        CompletableFuture<Void> read = groups.get(a).readBarrier();
        assertFalse(append.isDone() || change.isDone() || read.isDone());

        nodes.get(a).close();
        assertInstanceOf(OutcomeUnknownException.class,
                assertThrows(ExecutionException.class, () -> append.get(10, TimeUnit.SECONDS)).getCause());
        assertInstanceOf(OutcomeUnknownException.class,
                assertThrows(ExecutionException.class, () -> change.get(10, TimeUnit.SECONDS)).getCause());
        assertInstanceOf(NotLeaderException.class,
                assertThrows(ExecutionException.class, () -> read.get(10, TimeUnit.SECONDS)).getCause(),
                "a read has no outcome to lose");
        nodes.get(a).close(); // idempotent
        var later = groups.get(a).readBarrier();
        assertInstanceOf(NotLeaderException.class,
                assertThrows(ExecutionException.class, () -> later.get(10, TimeUnit.SECONDS)).getCause());
    }

    /** #254 (3): after close() new appends are refused (nothing appended), not outcome unknown and not not-leader. */
    @Test
    void appendsAfterCloseAreRefusedAsNotAppended() throws Exception {
        int a = leader();
        nodes.get(a).close();
        var later = nodes.get(a).append(GROUP, List.of(text("late")));
        var cause = assertThrows(ExecutionException.class, () -> later.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(GroupClosedException.class, cause);
        assertFalse(cause instanceof OutcomeUnknownException || cause instanceof NotLeaderException);
        for (int k = 0; k < 10; k++) {
            step();
        }
        assertFalse(appliedTexts(1 - a).contains("late"), "a refused append must never reach the log");
    }

    /** #254 (3): close() is a permanent step-down: the closed node never campaigns, so it is never elected again. */
    @Test
    void aClosedNodeNeverCampaignsOrLeadsAgain() throws Exception {
        int a = leader();
        int b = 1 - a;
        long termAtClose = groups.get(a).term();
        nodes.get(a).close();
        assertFalse(groups.get(a).isLeader());
        for (int k = 0; k < 100 && !groups.get(b).isLeader(); k++) {
            step(); // drives the closed node too: its ticks are ignored
        }
        assertTrue(groups.get(b).isLeader(), "the survivors elect b");
        // Hand leadership to the closed node and cut b from the witness, so nobody else could win: still never a.
        groups.get(b).transferLeadership(nodes.get(a).self());
        net.cut(nodes.get(b).self(), nodes.get(2).self());
        for (int k = 0; k < 200; k++) {
            step();
            assertFalse(groups.get(a).isLeader(), "a closed node was elected at step " + k);
        }
        assertEquals(termAtClose, groups.get(a).term(), "a closed node must not even start an election");
    }

    /**
     * #254 (Security): close() marks every group closed before it waits (1 s at most) for the Raft thread. A tick or
     * a frame that thread still runs afterwards, even a TIMEOUT_NOW, can no longer start an election.
     */
    @Test
    void aRaftThreadStillRunningAfterCloseCannotCampaign() throws Exception {
        int a = leader();
        int b = 1 - a;
        var follower = nodes.get(b).group(GROUP);
        long term = groups.get(b).term();
        follower.markClosed(); // what ReplicatedNode.close() does first
        for (int k = 1; k <= 50; k++) {
            follower.tick(clock.nanoTime() + k * 1_000_000_000L); // far past every election deadline
        }
        assertEquals(term, groups.get(b).term(), "a closed group started an election from a late tick");
        groups.get(a).transferLeadership(nodes.get(b).self()); // a TIMEOUT_NOW to the closed group
        for (int k = 0; k < 50; k++) {
            step();
            assertFalse(groups.get(b).isLeader(), "a closed group was elected at step " + k);
        }
        assertEquals(term, groups.get(b).term(), "a closed group acted on a frame");
        var refused = nodes.get(b).append(GROUP, List.of(text("x")));
        assertInstanceOf(GroupClosedException.class,
                assertThrows(ExecutionException.class, () -> refused.get(10, TimeUnit.SECONDS)).getCause());
    }

    /**
     * #254 (4): a leader that loses its term and leads again in a later term never acks the old future, even when that
     * very entry commits under it. A future cannot stay pending across the term change (every exit from LEADER fails
     * it as outcome unknown, failPendingWrites); this pins that the later commit of the same index does not flip it.
     * The overwrite variant (another leader's entry lands at the index) is
     * {@link #appendPendingAtTransferFailsAndNeverCompletesWithAnotherLeadersEntry}.
     */
    @Test
    void aLeaderThatRegainsLeadershipNeverAcksItsOldTermFuture() throws Exception {
        int a = leader();
        int b = 1 - a;
        long term = groups.get(a).leaderTerm();
        isolate(a);
        var pending = nodes.get(a).append(GROUP, List.of(text("comeback")), null, term);
        assertFalse(pending.isDone());
        groups.get(a).transferLeadership(nodes.get(b).self()); // TIMEOUT_NOW is lost: a only steps down
        assertOutcomeUnknown(pending);
        assertFalse(groups.get(a).isLeader());

        // b stays cut off, a reaches the witness again: a is the only one who can win, in a later term.
        net.cut(nodes.get(b).self(), nodes.get(2).self());
        net.heal(nodes.get(a).self(), nodes.get(2).self());
        for (int k = 0; k < 300 && !(groups.get(a).isLeader() && appliedTexts(a).contains("comeback")); k++) {
            step();
        }
        assertTrue(groups.get(a).isLeader(), "a never led again");
        assertTrue(groups.get(a).leaderTerm() > term, "a leads in a later term");
        assertTrue(appliedTexts(a).contains("comeback"), "the old-term entry committed under the new term");
        assertTrue(pending.isCompletedExceptionally(), "yet the old future was never acked as committed");
        assertOutcomeUnknown(pending);
    }

    private void drain() {
        while (net.deliver(1_000) > 0) {
            // flush heartbeats and their replies so the next frame queued is the one under test
        }
    }

    @Test
    void readBarrierWaitingAtTransferFailsWithNotLeader() throws Exception {
        int a = leader();
        isolate(a);
        clock.advance(Duration.ofSeconds(10)); // past the lease, before any tick: still leader, so the read waits
        CompletableFuture<Void> read = groups.get(a).readBarrier();
        assertFalse(read.isDone(), "a barrier past the lease must wait for a quorum round");

        groups.get(a).transferLeadership(nodes.get(1 - a).self()); // #246 (a): through the shared step-down helper
        var cause = assertThrows(ExecutionException.class, () -> read.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(NotLeaderException.class, cause);
        heal(a);
        for (int k = 0; k < 60; k++) {
            step();
        }
        assertInstanceOf(NotLeaderException.class,
                assertThrows(ExecutionException.class, () -> read.get(10, TimeUnit.SECONDS)).getCause(),
                "a failed barrier stays failed whoever leads next");
    }

    /** #254: the write was in the log, so not-leader (a refusal) would be a lie; it may still commit. */
    private static void assertOutcomeUnknown(CompletableFuture<CommittedIndex> future) {
        var cause = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(OutcomeUnknownException.class, cause);
        assertFalse(cause instanceof NotLeaderException);
    }

    private void isolate(int i) {
        net.cut(nodes.get(i).self(), nodes.get(1 - i).self());
        net.cut(nodes.get(i).self(), nodes.get(2).self());
    }

    private void heal(int i) {
        net.heal(nodes.get(i).self(), nodes.get(1 - i).self());
        net.heal(nodes.get(i).self(), nodes.get(2).self());
    }

    private List<String> appliedTexts(int i) {
        var out = new ArrayList<String>();
        for (var entry : applied.get(i)) {
            var copy = new byte[entry.entry().remaining()];
            entry.entry().duplicate().get(copy);
            out.add(new String(copy, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static ByteBuffer text(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    private void step() {
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
    }

    private int leader() {
        for (int i = 0; i < 2; i++) {
            if (groups.get(i).isLeader()) {
                return i;
            }
        }
        return -1;
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 25_350 + wire),
                1L, MemberState.ALIVE, witness);
    }
}
