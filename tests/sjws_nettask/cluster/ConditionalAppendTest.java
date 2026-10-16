package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Conditional append on term (review-29 item 3a): a leader appends only in the term the caller expected,
 * so a deposed-then-re-elected leader cannot accept a produce that was checked against its old term.
 */
@Timeout(30)
class ConditionalAppendTest {

    private static final GroupId GROUP = new GroupId("msg/cond");
    private static final GroupOptions OPTS = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100),
            Duration.ofMillis(40), Long.MAX_VALUE);

    private final FakeClock clock = new FakeClock(0L);
    private final SimTransport net = new SimTransport();
    private final List<ReplicatedNode> nodes = new ArrayList<>();

    private void start() {
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        for (var m : members) {
            var node = ReplicatedNode.open(m.id(), voters, clock, new MemoryLogStorage(), net);
            node.openGroup(GROUP, OPTS, null);
            nodes.add(node);
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
        return nodes.get(i).openGroup(GROUP, OPTS, null);
    }

    private int leader() {
        for (int i = 0; i < 2; i++) {
            if (group(i).isLeader()) {
                return i;
            }
        }
        return -1;
    }

    private CommittedIndex commit(CompletableFuture<CommittedIndex> future) throws Exception {
        for (int k = 0; k < 20 && !future.isDone(); k++) {
            step();
        }
        return future.get();
    }

    @Test
    void appendsInTheExpectedTermAndRefusesAnyOther() throws Exception {
        start();
        int l = leader();
        var g = group(l);
        long term = g.leaderTerm();
        var ok = commit(nodes.get(l).append(GROUP, List.of(payload("a")), null, term));
        assertEquals(term, ok.term());
        long commit = g.commitIndex();

        for (long wrong : new long[] {term - 1, term + 1}) {
            if (wrong <= 0) {
                continue;
            }
            var refused = nodes.get(l).append(GROUP, List.of(payload("b")), null, wrong);
            var error = assertThrows(ExecutionException.class, refused::get);
            var changed = assertInstanceOf(TermChangedException.class, error.getCause());
            assertEquals(wrong, changed.expectedTerm());
            assertEquals(term, changed.currentTerm());
        }
        step();
        assertEquals(commit, g.commitIndex(), "a refused conditional append must not enter the log");
    }

    @Test
    void followerFailsWithNotLeader() throws Exception {
        start();
        int follower = 1 - leader();
        long term = group(leader()).leaderTerm();
        var refused = nodes.get(follower).append(GROUP, List.of(payload("a")), null, term);
        var error = assertThrows(ExecutionException.class, refused::get);
        assertInstanceOf(NotLeaderException.class, error.getCause());
    }

    @Test
    void deposedThenReElectedLeaderRefusesTheOldTerm() throws Exception {
        start();
        int first = leader();
        int other = 1 - first;
        long oldTerm = group(first).leaderTerm();
        // Depose: isolate the leader until the other data node wins with the witness, then heal.
        net.cut(nodes.get(first).self(), nodes.get(other).self());
        net.cut(nodes.get(first).self(), nodes.get(2).self());
        for (int k = 0; k < 40 && !group(other).isLeader(); k++) {
            step();
        }
        assertTrue(group(other).isLeader(), "other data node never took over");
        net.heal(nodes.get(first).self(), nodes.get(other).self());
        net.heal(nodes.get(first).self(), nodes.get(2).self());
        for (int k = 0; k < 10 && group(first).isLeader(); k++) {
            step();
        }
        // Re-elect the first node in a later term.
        group(other).transferLeadership(nodes.get(first).self());
        for (int k = 0; k < 40 && !group(first).isLeader(); k++) {
            step();
        }
        assertTrue(group(first).isLeader(), "first node was not re-elected");
        long newTerm = group(first).leaderTerm();
        assertNotEquals(oldTerm, newTerm);

        var stale = nodes.get(first).append(GROUP, List.of(payload("stale")), null, oldTerm);
        var error = assertThrows(ExecutionException.class, stale::get);
        assertInstanceOf(TermChangedException.class, error.getCause());

        assertEquals(newTerm, commit(nodes.get(first).append(GROUP, List.of(payload("fresh")), null, newTerm)).term());
    }

    @Test
    void rejectsANonPositiveTerm() {
        start();
        var node = nodes.get(leader());
        assertThrows(IllegalArgumentException.class, () -> node.append(GROUP, List.of(payload("a")), null, 0L));
        assertThrows(IllegalArgumentException.class, () -> node.append(GROUP, List.of(payload("a")), null, -5L));
    }

    @Test
    void defaultsRefuseTheConditionalAppendAndNoopListener() {
        ClusterNode node = new ClusterNode() {
            @Override
            public NodeId self() {
                return new NodeId(1, "n1");
            }

            @Override
            public MembershipView membership() {
                return null;
            }

            @Override
            public void onMembershipChange(Consumer<MembershipView> listener) {
            }

            @Override
            public ReplicationGroup openGroup(GroupId groupId, GroupOptions options, GroupListener listener) {
                return null;
            }

            @Override
            public CompletableFuture<CommittedIndex> append(GroupId groupId, List<ByteBuffer> batch) {
                return CompletableFuture.completedFuture(new CommittedIndex(groupId, 1, 1));
            }

            @Override
            public void onLeaderChange(GroupId groupId, BiConsumer<Long, Optional<NodeId>> listener) {
            }

            @Override
            public SnapshotControl snapshots(GroupId groupId) {
                return null;
            }

            @Override
            public void close() {
            }
        };
        var refused = node.append(GROUP, List.of(payload("a")), null, 1L);
        var error = assertThrows(ExecutionException.class, refused::get);
        assertInstanceOf(UnsupportedOperationException.class, error.getCause());
        assertThrows(UnsupportedOperationException.class, () -> node.onNoopApplied(GROUP, (t, i) -> {
        }));
    }

    private static ByteBuffer payload(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 25_100 + wire), 1L,
                MemberState.ALIVE, witness);
    }
}
