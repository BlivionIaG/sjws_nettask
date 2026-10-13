package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A witness records the last acked {@code (index, term)} before the ack is sent and before it
 * grants a vote. A restart loads that pair and refuses a candidate whose log is behind it.
 */
@Timeout(15)
class WitnessVoteTest {

    private static final SiteId SITE = new SiteId("local");
    private static final GroupId GROUP = new GroupId("ha-control");

    @Test
    void cursorStaysUnpublishedUntilForceAndSurvivesRestart() throws Exception {
        var memory = new MemoryLogStorage();
        assertEquals(0L, memory.witnessIndex(GROUP));
        assertEquals(0L, memory.witnessTerm(GROUP));
        memory.noteWitness(GROUP, 0, 4);
        memory.noteWitness(GROUP, 3, -1);
        memory.noteWitness(GROUP, 4, 2);
        assertEquals(0L, memory.witnessIndex(GROUP));
        memory.noteWitness(GROUP, 3, 2);
        memory.noteWitness(GROUP, 8, 1);
        memory.force(GROUP);
        assertEquals(4L, memory.witnessIndex(GROUP));
        assertEquals(2L, memory.witnessTerm(GROUP));
        memory.force(GROUP);
        memory.noteWitness(GROUP, 6, 2);
        memory.noteWitness(GROUP, 5, 2);
        memory.noteWitness(GROUP, 1, 5);
        memory.noteWitness(GROUP, 2, 4);
        memory.force(GROUP);
        assertEquals(1L, memory.witnessIndex(GROUP));
        assertEquals(5L, memory.witnessTerm(GROUP));

        var root = Files.createTempDirectory("witness-cursor");
        var group = new GroupId("ha-control");
        var store = new FileLogStorage(root);
        assertEquals(0L, store.witnessIndex(group));
        assertEquals(0L, store.witnessTerm(group));
        assertFalse(Files.exists(root.resolve("ha-control.witness")));
        store.noteWitness(group, 4, 2);
        store.noteWitness(group, 6, 2);
        store.noteWitness(group, 5, 2);
        store.noteWitness(group, 9, 1);
        assertEquals(0L, store.witnessIndex(group));
        store.close();
        var crashed = new FileLogStorage(root);
        assertEquals(0L, crashed.witnessIndex(group));
        assertEquals(0L, crashed.witnessTerm(group));
        crashed.close();

        var again = new FileLogStorage(root);
        again.noteWitness(group, 4, 2);
        again.force(group);
        assertEquals(4L, again.witnessIndex(group));
        assertEquals(2L, again.witnessTerm(group));
        assertTrue(Files.exists(root.resolve("ha-control.witness")));
        again.noteWitness(group, 1, 5);
        again.force(group);
        again.force(group);
        again.close();
        var restarted = new FileLogStorage(root);
        assertEquals(1L, restarted.witnessIndex(group));
        assertEquals(5L, restarted.witnessTerm(group));
        assertEquals(0L, restarted.lastIndex(group));
        restarted.force(group);
        restarted.close();

        Files.write(root.resolve("ha-control.witness"), new byte[8]);
        var torn = new FileLogStorage(root);
        assertEquals(0L, torn.witnessIndex(group));
        assertEquals(0L, torn.witnessTerm(group));
        torn.close();
    }

    @Test
    void restartedWitnessRefusesALogBehindTheAckedCursor() throws Exception {
        var root = Files.createTempDirectory("witness-vote");
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = members();
        var voters = voters(members);
        var witnessLog = new FileLogStorage(root);
        var nodes = open(members, voters, clock, net, List.of(new MemoryLogStorage(), new MemoryLogStorage(), witnessLog));
        var opts = options();
        for (var node : nodes) {
            node.openGroup(GROUP, opts, null);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        var leader = nodes.get(0);
        assertTrue(leader.openGroup(GROUP, opts, null).isLeader());
        var committed = leader.append(GROUP, List.of(ByteBuffer.wrap("kept".getBytes(StandardCharsets.UTF_8))));
        ReplicatedNode.driveAll(nodes);
        assertTrue(committed.isDone());
        clock.advance(Duration.ofMillis(40));
        ReplicatedNode.driveAll(nodes);
        long index = witnessLog.witnessIndex(GROUP);
        long term = witnessLog.witnessTerm(GROUP);
        assertTrue(index >= committed.get().index());
        assertTrue(term > 0);
        assertEquals(0L, witnessLog.lastIndex(GROUP));
        nodes.get(2).close();
        witnessLog.close();

        var reopened = new FileLogStorage(root);
        assertEquals(index, reopened.witnessIndex(GROUP));
        assertEquals(term, reopened.witnessTerm(GROUP));
        assertEquals(0L, reopened.lastIndex(GROUP));
        var restartedNet = new SimTransport();
        var restarted = ReplicatedNode.open(members.get(2).id(), members, voters, QuorumPolicy.witness(members.get(2).id()),
                clock, reopened, restartedNet);
        restarted.openGroup(GROUP, opts, null);
        var replies = new ArrayList<SimTransport.Frame>();
        BiConsumer<NodeId, SimTransport.Frame> record = (from, frame) -> replies.add(frame);
        var candidate = restartedNet.attach(members.get(0).id(), record);
        restartedNet.attach(members.get(1).id(), record);
        assertEquals(0, ask(restartedNet, candidate, members.get(2).id(), vote(9, 1, index + 20, term - 1), replies));
        assertEquals(0, ask(restartedNet, candidate, members.get(2).id(), vote(9, 1, index - 1, term), replies));
        assertEquals(1, ask(restartedNet, candidate, members.get(2).id(), vote(9, 2, index, term), replies));
        assertEquals(1, ask(restartedNet, candidate, members.get(2).id(), vote(10, 2, 1, term + 1), replies));
        reopened.close();
    }

    @Test
    void witnessWithNothingAckedGrantsACandidate() throws Exception {
        var root = Files.createTempDirectory("witness-empty");
        var store = new FileLogStorage(root);
        var net = new SimTransport();
        var members = members();
        var witness = ReplicatedNode.open(members.get(2).id(), members, voters(members),
                QuorumPolicy.witness(members.get(2).id()), new FakeClock(0L), store, net);
        witness.openGroup(GROUP, options(), null);
        var replies = new ArrayList<SimTransport.Frame>();
        var candidate = net.attach(members.get(0).id(), (from, frame) -> replies.add(frame));
        assertEquals(1, ask(net, candidate, members.get(2).id(), vote(1, 1, 0, 0), replies));
        assertEquals(0L, store.lastIndex(GROUP));
        store.close();
    }

    @Test
    void failedForceDoesNotGrantAndFailedSyncDoesNotAck() throws Exception {
        var voting = new FlakyLog();
        var voteNet = new SimTransport();
        var members = members();
        var witness = ReplicatedNode.open(members.get(2).id(), members, voters(members),
                QuorumPolicy.witness(members.get(2).id()), new FakeClock(0L), voting, voteNet);
        witness.openGroup(GROUP, options(), null);
        var replies = new ArrayList<SimTransport.Frame>();
        BiConsumer<NodeId, SimTransport.Frame> record = (from, frame) -> replies.add(frame);
        voting.failForce(true);
        var candidate = voteNet.attach(members.get(0).id(), record);
        voteNet.attach(members.get(1).id(), record);
        assertEquals(0, ask(voteNet, candidate, members.get(2).id(), vote(4, 1, 3, 1), replies));
        voting.failForce(false);
        assertEquals(1, ask(voteNet, candidate, members.get(2).id(), vote(4, 2, 3, 1), replies));
        assertEquals(0L, voting.witnessIndex(GROUP));

        var flaky = new FlakyLog();
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var voters = voters(members);
        var nodes = open(members, voters, clock, net,
                List.of(new MemoryLogStorage(), new MemoryLogStorage(), flaky));
        var opts = options();
        for (var node : nodes) {
            node.openGroup(GROUP, opts, null);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        assertTrue(nodes.get(0).openGroup(GROUP, opts, null).isLeader());
        long durable = flaky.witnessIndex(GROUP);
        assertTrue(durable > 0);
        flaky.failForce(true);
        var stuck = nodes.get(0).append(GROUP, List.of(ByteBuffer.wrap("disk".getBytes(StandardCharsets.UTF_8))));
        ReplicatedNode.driveAll(nodes);
        assertFalse(stuck.isDone());
        assertEquals(durable, flaky.witnessIndex(GROUP));
        assertEquals(0L, flaky.lastIndex(GROUP));
        flaky.failForce(false);
        clock.advance(Duration.ofMillis(40));
        ReplicatedNode.driveAll(nodes);
        assertTrue(stuck.isDone());
        assertTrue(flaky.witnessIndex(GROUP) >= stuck.get().index());

        long held = flaky.witnessIndex(GROUP);
        flaky.failNote(true);
        var unnoted = nodes.get(0).append(GROUP, List.of(ByteBuffer.wrap("note".getBytes(StandardCharsets.UTF_8))));
        ReplicatedNode.driveAll(nodes);
        assertFalse(unnoted.isDone());
        assertEquals(held, flaky.witnessIndex(GROUP));
        flaky.failNote(false);
        clock.advance(Duration.ofMillis(40));
        ReplicatedNode.driveAll(nodes);
        assertTrue(unnoted.isDone());
        assertTrue(flaky.witnessIndex(GROUP) >= unnoted.get().index());
    }

    private static int ask(SimTransport net, ClusterRpc candidate, NodeId witness, byte[] body,
            List<SimTransport.Frame> replies) {
        int before = replies.size();
        candidate.send(witness, new SimTransport.Frame(GROUP, RaftGroup.VOTE, body));
        int spins = 0;
        while (replies.size() == before && spins++ < 8) {
            if (net.deliver(8) == 0 && replies.size() == before) {
                break;
            }
        }
        assertEquals(before + 1, replies.size());
        var buf = ByteBuffer.wrap(replies.get(replies.size() - 1).body()).order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.getLong();
        buf.getInt();
        return buf.get() & 0xff;
    }

    private static byte[] vote(long term, int wire, long lastIndex, long lastTerm) {
        var body = ByteBuffer.allocate(8 + 4 + 8 + 8).order(java.nio.ByteOrder.BIG_ENDIAN);
        body.putLong(term);
        body.putInt(wire);
        body.putLong(lastIndex);
        body.putLong(lastTerm);
        return body.array();
    }

    private static List<ReplicatedNode> open(List<Member> members, LinkedHashSet<NodeId> voters, FakeClock clock,
            SimTransport net, List<LogStorage> logs) {
        var policy = QuorumPolicy.witness(members.get(2).id());
        var nodes = new ArrayList<ReplicatedNode>();
        for (int i = 0; i < members.size(); i++) {
            nodes.add(ReplicatedNode.open(members.get(i).id(), members, voters, policy, clock, logs.get(i), net));
        }
        return nodes;
    }

    private static List<Member> members() {
        return List.of(member(1, false), member(2, false), member(3, true));
    }

    private static LinkedHashSet<NodeId> voters(List<Member> members) {
        var voters = new LinkedHashSet<NodeId>();
        for (var member : members) {
            voters.add(member.id());
        }
        return voters;
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", 20_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }

    private static GroupOptions options() {
        return new GroupOptions(Durability.STRICT, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
    }

    private static final class FlakyLog implements LogStorage {
        private final MemoryLogStorage memory = new MemoryLogStorage();
        private boolean failForce;
        private boolean failNote;

        void failForce(boolean fail) {
            this.failForce = fail;
        }

        void failNote(boolean fail) {
            this.failNote = fail;
        }

        @Override
        public void append(GroupId group, long index, long term, byte[] data) throws IOException {
            memory.append(group, index, term, data);
        }

        @Override
        public byte[] read(GroupId group, long index) throws IOException {
            return memory.read(group, index);
        }

        @Override
        public long termAt(GroupId group, long index) throws IOException {
            return memory.termAt(group, index);
        }

        @Override
        public long lastIndex(GroupId group) {
            return memory.lastIndex(group);
        }

        @Override
        public void truncateSuffix(GroupId group, long fromIndex) throws IOException {
            memory.truncateSuffix(group, fromIndex);
        }

        @Override
        public void truncatePrefix(GroupId group, long throughIndex) throws IOException {
            memory.truncatePrefix(group, throughIndex);
        }

        @Override
        public void force(GroupId group) throws IOException {
            if (failForce) {
                throw new IOException("force");
            }
            memory.force(group);
        }

        @Override
        public int forces(GroupId group) {
            return memory.forces(group);
        }

        @Override
        public long witnessIndex(GroupId group) throws IOException {
            return memory.witnessIndex(group);
        }

        @Override
        public long witnessTerm(GroupId group) throws IOException {
            return memory.witnessTerm(group);
        }

        @Override
        public void noteWitness(GroupId group, long index, long term) throws IOException {
            if (failNote) {
                throw new IOException("note");
            }
            memory.noteWitness(group, index, term);
        }
    }
}
