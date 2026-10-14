/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a voter does after a crash and restart. RELAXED acks from memory, so the restarted log
 * can be shorter than what this node acked. Term and vote are durable, a restarted data voter
 * abstains until it has caught up to a leader's commit index (or the recovery window passes),
 * and a leader never commits on its own memory plus a witness that stores no entry.
 */
@Timeout(30)
class RelaxedRestartVoteTest {

    private static final GroupId GROUP = new GroupId("msg/0");
    private static final NodeId N1 = new NodeId(1, "n1");
    private static final NodeId N2 = new NodeId(2, "n2");
    private static final NodeId N3 = new NodeId(3, "n3");
    private static final GroupOptions OPTS = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100),
            Duration.ofMillis(40), 1_000_000);

    /** Captures what one RaftGroup sends. */
    private static final class Outbox implements ClusterRpc {
        final List<SimTransport.Frame> sent = new ArrayList<>();

        @Override
        public void send(NodeId to, SimTransport.Frame frame) {
            sent.add(frame);
        }

        Boolean lastGrant(int type) {
            for (int i = sent.size() - 1; i >= 0; i--) {
                if (sent.get(i).type() == type) {
                    return sent.get(i).body()[12] == 1;
                }
            }
            return null;
        }
    }

    private static RaftGroup node3(LogStorage storage, Outbox out, FakeClock clock) {
        return new RaftGroup(GROUP, N3, false, Set.of(), Set.of(N1, N2, N3), List.of(N1, N2),
                wire -> switch (wire) {
                    case 1 -> N1;
                    case 2 -> N2;
                    case 3 -> N3;
                    default -> null;
                }, QuorumPolicy.majority(), OPTS, storage, out, clock, null, 1024);
    }

    private static SimTransport.Frame vote(int type, long term, NodeId candidate, long lastIndex, long lastTerm) {
        var body = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN);
        body.putLong(term).putInt(candidate.wire()).putLong(lastIndex).putLong(lastTerm);
        return new SimTransport.Frame(GROUP, type, body.array());
    }

    private static SimTransport.Frame append(long term, NodeId leader, long prevIndex, long prevTerm, long commit,
            String... entries) {
        int size = 8 + 4 + 8 + 8 + 8 + 4;
        var datas = new ArrayList<byte[]>();
        for (var entry : entries) {
            var raw = entry.getBytes(StandardCharsets.UTF_8);
            var data = new byte[raw.length + 1];
            data[0] = RaftGroup.USER;
            System.arraycopy(raw, 0, data, 1, raw.length);
            datas.add(data);
            size += 12 + data.length;
        }
        var body = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        body.putLong(term).putInt(leader.wire()).putLong(prevIndex).putLong(prevTerm).putLong(commit)
                .putInt(datas.size());
        for (var data : datas) {
            body.putLong(term).putInt(data.length).put(data);
        }
        return new SimTransport.Frame(GROUP, RaftGroup.APPEND, body.array());
    }

    @Test
    void restartedVoterDoesNotVoteTwiceInOneTerm() throws Exception {
        for (var storage : List.<LogStorage>of(new MemoryLogStorage(),
                new FileLogStorage(Files.createTempDirectory("hard-state")))) {
            var clock = new FakeClock(0L);
            var out = new Outbox();
            var first = node3(storage, out, clock);
            first.handle(N1, vote(RaftGroup.VOTE, 5, N1, 0, 0), 0);
            assertTrue(out.lastGrant(RaftGroup.VOTE_RESP));
            assertEquals(5L, storage.hardStateTerm(GROUP));
            assertEquals(1, storage.hardStateVote(GROUP));

            var restarted = node3(storage, out, clock);
            assertEquals(5L, restarted.facade().term());
            // Catch up first, so only the durable vote can refuse the second candidate.
            restarted.handle(N1, append(5, N1, 0, 0, 0), 0);
            restarted.handle(N2, vote(RaftGroup.VOTE, 5, N2, 0, 0), 0);
            assertFalse(out.lastGrant(RaftGroup.VOTE_RESP), storage.getClass().getSimpleName());
            restarted.handle(N2, vote(RaftGroup.VOTE, 6, N2, 0, 0), 0);
            assertTrue(out.lastGrant(RaftGroup.VOTE_RESP));
            assertEquals(2, storage.hardStateVote(GROUP));
        }
    }

    /**
     * Node 3 acked entries 1..2 from memory and helped commit them (n1 + n3 of three). It crashes
     * before the batched fsync. A candidate that never received them must not get its vote.
     */
    @Test
    void restartedVoterThatLostAckedEntriesAbstainsUntilCaughtUp() throws Exception {
        var clock = new FakeClock(0L);
        var out = new Outbox();
        var before = new MemoryLogStorage();
        var live = node3(before, out, clock);
        live.handle(N1, append(4, N1, 0, 0, 0, "a", "b"), 0);
        assertEquals(2L, before.lastIndex(GROUP));
        assertEquals(0, before.forces(GROUP), "acked from memory, never forced");

        // Crash: the unforced entries are gone, the durable term and vote survive.
        var after = new MemoryLogStorage();
        after.saveHardState(GROUP, before.hardStateTerm(GROUP), before.hardStateVote(GROUP));
        var restarted = node3(after, out, clock);
        assertEquals(0L, after.lastIndex(GROUP));

        // n2 never got 1..2. Its log looks as long as ours, so the up-to-date check alone grants.
        restarted.handle(N2, vote(RaftGroup.PRE_VOTE, 4, N2, 0, 0), 0);
        assertFalse(out.lastGrant(RaftGroup.PRE_VOTE_RESP));
        restarted.handle(N2, vote(RaftGroup.VOTE, 5, N2, 0, 0), 0);
        assertFalse(out.lastGrant(RaftGroup.VOTE_RESP));
        clock.advance(Duration.ofMillis(200)); // past the election deadline, inside the recovery window
        restarted.tick(clock.nanoTime());
        assertFalse(restarted.campaigning(), "a recovering node does not campaign either");

        // The leader's next append carries commit 2; once we hold it the abstention ends.
        restarted.handle(N1, append(5, N1, 0, 0, 2, "a", "b"), 0);
        assertEquals(2L, restarted.facade().commitIndex());
        restarted.handle(N2, vote(RaftGroup.VOTE, 6, N2, 2, 5), 0);
        assertTrue(out.lastGrant(RaftGroup.VOTE_RESP));
    }

    @Test
    void recoveryWindowRestoresLivenessAfterAWholeClusterRestart() throws Exception {
        var clock = new FakeClock(0L);
        var out = new Outbox();
        var storage = new MemoryLogStorage();
        storage.saveHardState(GROUP, 3, 0);
        var restarted = node3(storage, out, clock);
        restarted.handle(N2, vote(RaftGroup.VOTE, 4, N2, 0, 0), clock.nanoTime());
        assertFalse(out.lastGrant(RaftGroup.VOTE_RESP));
        clock.advance(OPTS.electionTimeout().multipliedBy(2).plusNanos(OPTS.leaseNanos()));
        restarted.tick(clock.nanoTime());
        restarted.handle(N2, vote(RaftGroup.VOTE, 4, N2, 0, 0), clock.nanoTime());
        assertTrue(out.lastGrant(RaftGroup.VOTE_RESP));
    }

    @Test
    void singleVoterRestartIsNotBlocked() throws Exception {
        var clock = new FakeClock(0L);
        var storage = new MemoryLogStorage();
        storage.append(GROUP, 1, 2, new byte[] {RaftGroup.NOOP});
        var group = new RaftGroup(GROUP, N1, false, Set.of(), Set.of(N1), List.of(), w -> w == 1 ? N1 : null,
                QuorumPolicy.majority(), OPTS, storage, new Outbox(), clock, null, 1024);
        clock.advance(Duration.ofMillis(200));
        group.tick(clock.nanoTime());
        assertTrue(group.facade().isLeader());
        group.closeDeliveries(Duration.ofSeconds(1));
    }

    @Test
    void fileLogReportsItsLastIndexAfterReopenWithoutAnyRead() throws Exception {
        var root = Files.createTempDirectory("reopen");
        var log = new FileLogStorage(root);
        log.append(GROUP, 1, 1, new byte[] {0, 1});
        log.append(GROUP, 2, 1, new byte[] {0, 2});
        log.force(GROUP);
        log.close();
        var reopened = new FileLogStorage(root);
        assertEquals(2L, reopened.lastIndex(GROUP), "used to read 0 until termAt/read loaded the file");
        assertEquals(0L, new FileLogStorage(root).hardStateTerm(GROUP));
        reopened.close();
    }

    /** With the other data node cut, leader memory plus a witness ack is not a commit until the leader forces. */
    @Test
    void witnessAckAloneWaitsForTheLeadersFsync() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var nodes = new ArrayList<ReplicatedNode>();
        var logs = new ArrayList<MemoryLogStorage>();
        for (var member : members) {
            var log = new MemoryLogStorage();
            logs.add(log);
            var node = ReplicatedNode.open(member.id(), voters, clock, log, net);
            node.openGroup(GROUP, OPTS, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        var leader = nodes.get(0);
        assertTrue(leader.openGroup(GROUP, OPTS, null).isLeader());
        net.cut(N1, N2);
        net.cut(N2, new NodeId(3, "n3"));
        int forcesBefore = logs.get(0).forces(GROUP);
        var pending = leader.append(GROUP, List.of(ByteBuffer.wrap("x".getBytes(StandardCharsets.UTF_8))));
        while (net.deliver(64) > 0) {
            // Witness ack arrives; the leader has not forced yet.
        }
        assertEquals(forcesBefore, logs.get(0).forces(GROUP));
        // Completion runs on the delivery thread, so wait rather than poll isDone().
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> pending.get(300, java.util.concurrent.TimeUnit.MILLISECONDS),
                "committed on leader memory + witness cursor");
        leader.drive();
        pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(logs.get(0).forces(GROUP) > forcesBefore);
        for (var node : nodes) {
            node.close();
        }
    }

    /** Memory log whose hard-state write can be made to fail. */
    private static final class FailingVotes implements LogStorage {
        final MemoryLogStorage inner = new MemoryLogStorage();
        boolean fail;

        @Override
        public void append(GroupId group, long index, long term, byte[] data) {
            inner.append(group, index, term, data);
        }

        @Override
        public byte[] read(GroupId group, long index) {
            return inner.read(group, index);
        }

        @Override
        public long termAt(GroupId group, long index) {
            return inner.termAt(group, index);
        }

        @Override
        public long lastIndex(GroupId group) {
            return inner.lastIndex(group);
        }

        @Override
        public void truncateSuffix(GroupId group, long fromIndex) {
            inner.truncateSuffix(group, fromIndex);
        }

        @Override
        public void truncatePrefix(GroupId group, long throughIndex) {
            inner.truncatePrefix(group, throughIndex);
        }

        @Override
        public void force(GroupId group) {
            inner.force(group);
        }

        @Override
        public int forces(GroupId group) {
            return inner.forces(group);
        }

        @Override
        public long witnessIndex(GroupId group) {
            return inner.witnessIndex(group);
        }

        @Override
        public long witnessTerm(GroupId group) {
            return inner.witnessTerm(group);
        }

        @Override
        public void noteWitness(GroupId group, long index, long term) {
            inner.noteWitness(group, index, term);
        }

        @Override
        public void saveHardState(GroupId group, long term, int votedFor) throws java.io.IOException {
            if (fail) {
                throw new java.io.IOException("disk full");
            }
            inner.saveHardState(group, term, votedFor);
        }

        @Override
        public boolean hasHardState(GroupId group) {
            return inner.hasHardState(group);
        }

        @Override
        public long hardStateTerm(GroupId group) {
            return inner.hardStateTerm(group);
        }

        @Override
        public int hardStateVote(GroupId group) {
            return inner.hardStateVote(group);
        }
    }

    @Test
    void failedVoteWriteRefusesTheVote() throws Exception {
        var storage = new FailingVotes();
        var out = new Outbox();
        var group = node3(storage, out, new FakeClock(0L));
        group.handle(N1, append(5, N1, 0, 0, 0), 0); // term 5 is durable, no vote yet
        storage.fail = true;
        group.handle(N2, vote(RaftGroup.VOTE, 5, N2, 0, 0), 0);
        assertFalse(out.lastGrant(RaftGroup.VOTE_RESP));
        assertEquals(0, storage.hardStateVote(GROUP));
        storage.fail = false;
        group.handle(N2, vote(RaftGroup.VOTE, 5, N2, 0, 0), 0);
        assertTrue(out.lastGrant(RaftGroup.VOTE_RESP), "the vote was not recorded in memory either");
    }

    @Test
    void failedWriteOnAHigherTermChangesNothingAndSendsNothing() throws Exception {
        var storage = new FailingVotes();
        var out = new Outbox();
        var group = node3(storage, out, new FakeClock(0L));
        storage.fail = true;
        for (var frame : List.of(append(7, N1, 0, 0, 0), vote(RaftGroup.VOTE, 7, N2, 0, 0))) {
            group.handle(N1, frame, 0);
            assertEquals(0L, group.facade().term());
            assertTrue(out.sent.isEmpty(), "nothing carrying the unsaved term goes out");
            assertTrue(group.facade().leader().isEmpty());
        }
    }

    @Test
    void failedWriteInStartElectionChangesNothingAndSendsNoVote() throws Exception {
        var storage = new FailingVotes();
        var out = new Outbox();
        var clock = new FakeClock(0L);
        var group = node3(storage, out, clock);
        clock.advance(Duration.ofMillis(300));
        group.tick(clock.nanoTime());
        assertTrue(group.campaigning(), "pre-vote sent");
        out.sent.clear();
        storage.fail = true;
        var grant = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putLong(0).putInt(1).put((byte) 1);
        group.handle(N1, new SimTransport.Frame(GROUP, RaftGroup.PRE_VOTE_RESP, grant.array()), clock.nanoTime());
        assertEquals(0L, group.facade().term());
        assertFalse(group.campaigning());
        assertTrue(out.sent.isEmpty(), "no VOTE at an unsaved term");
        assertFalse(storage.hasHardState(GROUP));
    }

    @Test
    void voteFileFormatRefusesBadInputAndUpgradesFromTheLog() throws Exception {
        var root = Files.createTempDirectory("vote-file");
        var log = new FileLogStorage(root);
        var clock = new FakeClock(0L);
        assertEquals(0L, node3(log, new Outbox(), clock).facade().term(), "no file, empty log: term 0");
        log.append(GROUP, 1, 3, new byte[] {RaftGroup.NOOP});
        log.append(GROUP, 2, 4, new byte[] {RaftGroup.NOOP});
        var upgraded = node3(log, new Outbox(), clock);
        assertEquals(4L, upgraded.facade().term(), "no file, log present: last log term");
        assertFalse(log.hasHardState(GROUP));

        log.saveHardState(GROUP, 9, 2);
        var vote = root.resolve("msg_0.vote");
        assertEquals(FileLogStorage.VOTE_FILE_BYTES, Files.size(vote));
        assertEquals(9L, node3(log, new Outbox(), clock).facade().term());
        var good = Files.readAllBytes(vote);
        for (int corrupt = 0; corrupt < 4; corrupt++) {
            var bytes = good.clone();
            switch (corrupt) {
                case 0 -> bytes = java.util.Arrays.copyOf(good, 12); // short (the old 12-byte layout)
                case 1 -> bytes[0] ^= 1; // magic
                case 2 -> bytes[7] = 2; // version
                default -> bytes[10] ^= 1; // term bit flip: CRC
            }
            Files.write(vote, bytes);
            var bad = new FileLogStorage(root);
            var error = org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
                    () -> node3(bad, new Outbox(), clock), "case " + corrupt);
            assertTrue(error.getCause().getMessage().contains("refusing to start"), error.getCause().getMessage());
            bad.close();
        }
        log.close();
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"),
                new InetSocketAddress("127.0.0.1", 23_100 + wire), 1L, MemberState.ALIVE, witness);
    }
}
