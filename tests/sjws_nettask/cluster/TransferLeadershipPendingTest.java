package sjws_nettask.cluster;

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
        assertNotLeader(pending);

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
        assertNotLeader(pending);
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
        assertInstanceOf(NotLeaderException.class, cause);
    }

    private static void assertNotLeader(CompletableFuture<CommittedIndex> future) {
        var cause = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause();
        assertInstanceOf(NotLeaderException.class, cause);
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
