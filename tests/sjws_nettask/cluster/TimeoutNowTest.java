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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * #255: a TIMEOUT_NOW is acted on only while it can still belong to a live leadership transfer, and a leader that
 * cannot persist a later term it has seen stops leading. One RaftGroup (n1, data) with a data peer n2 and a witness
 * n3, driven frame by frame on a fake clock; every assertion is on what the group reports, sends or completes.
 */
@Timeout(30)
class TimeoutNowTest {

    private static final GroupId GROUP = new GroupId("msg/timeout-now");
    private static final NodeId N1 = new NodeId(1, "n1");
    private static final NodeId N2 = new NodeId(2, "n2");
    private static final NodeId N3 = new NodeId(3, "n3");
    private static final GroupOptions OPTS = new GroupOptions(Durability.RELAXED, 1024, Duration.ofMillis(100),
            Duration.ofMillis(40), 1_000_000);

    private final FakeClock clock = new FakeClock(0L);
    private final List<SimTransport.Frame> sent = new CopyOnWriteArrayList<>();
    private final List<Optional<NodeId>> leaderEvents = new CopyOnWriteArrayList<>();
    private boolean failHardState;
    private RaftGroup group;

    @AfterEach
    void closeDeliveries() {
        if (group != null) {
            group.closeDeliveries(Duration.ofSeconds(1));
        }
    }

    // --- stale or wrong-term TIMEOUT_NOW at a sitting leader -------------------------------------------------

    @Test
    void aStaleTimeoutNowDoesNotMakeASittingLeaderCampaign() throws Exception {
        leaderAtTerm(2);
        var pending = group.append(List.of(text("x")));
        sent.clear();

        group.handle(N2, timeoutNow(1, N2), clock.nanoTime()); // left over from a transfer in term 1
        group.handle(N2, timeoutNow(2, N2), clock.nanoTime()); // our own term: only we could have sent it
        group.handle(N3, timeoutNow(2, N3), clock.nanoTime());

        assertTrue(group.facade().isLeader(), "a stale TIMEOUT_NOW must not end a sitting leader's term");
        assertEquals(2L, group.facade().leaderTerm());
        assertEquals(Optional.of(N1), group.facade().leader());
        assertTrue(typesSent().isEmpty(), "ignored without a VOTE or any reply: " + typesSent());
        assertFalse(pending.isDone(), "the write is still the leader's to commit");

        commitThroughPeers(2, 2);
        assertEquals(2L, pending.get(5, TimeUnit.SECONDS).index(), "and it still commits normally");
    }

    @Test
    void aStaleTimeoutNowIsIgnoredEvenWhenTheDiskWouldFailTheElection() throws Exception {
        leaderAtTerm(2);
        var pending = group.append(List.of(text("x")));
        failHardState = true;
        group.handle(N2, timeoutNow(1, N2), clock.nanoTime());
        assertTrue(group.facade().isLeader());
        assertEquals(2L, group.facade().term());
        assertFalse(pending.isDone(), "not even a demotion: the frame never reaches startElection");
    }

    // --- TIMEOUT_NOW at a follower or candidate --------------------------------------------------------------

    @Test
    void aFollowerActsOnlyOnItsLeadersTimeoutNowAtItsTerm() throws Exception {
        group = node(new MemoryLogStorage());
        group.handle(N2, append(3, N2), clock.nanoTime()); // follower of n2 in term 3
        assertEquals(Optional.of(N2), group.facade().leader());
        sent.clear();

        group.handle(N2, timeoutNow(2, N2), clock.nanoTime()); // older term
        group.handle(N3, timeoutNow(3, N3), clock.nanoTime()); // not the leader we follow
        group.handle(N2, timeoutNow(3, new NodeId(9, "n9")), clock.nanoTime()); // not a voter
        group.handle(N2, timeoutNow(3, N1), clock.nanoTime()); // claims to come from us
        group.handle(N3, timeoutNow(3, N2), clock.nanoTime()); // from n3, claiming to be n2: the body lies
        assertEquals(3L, group.facade().term());
        assertFalse(group.campaigning());
        assertTrue(typesSent().isEmpty(), "every one of them ignored: " + typesSent());

        group.handle(N2, timeoutNow(3, N2), clock.nanoTime()); // the real transfer
        assertTrue(group.campaigning());
        assertEquals(4L, group.facade().term());
        assertEquals(List.of(RaftGroup.VOTE, RaftGroup.VOTE), typesSent(), "a real election, no pre-vote");
        assertEquals(4L, ByteBuffer.wrap(sent.get(0).body()).getLong());
    }

    @Test
    void aTimeoutNowWhoseBodyNamesAnotherPeerThanItsSenderIsIgnored() throws Exception {
        group = node(new MemoryLogStorage());
        group.handle(N2, append(3, N2), clock.nanoTime()); // follower of n2 in term 3
        sent.clear();
        group.handle(N3, timeoutNow(3, N2), clock.nanoTime()); // arrives from n3, claims to be the leader n2
        group.handle(N3, timeoutNow(4, N2), clock.nanoTime()); // nor can a spoofed one raise our term
        assertEquals(3L, group.facade().term());
        assertFalse(group.campaigning());
        assertEquals(Optional.of(N2), group.facade().leader());
        assertTrue(typesSent().isEmpty(), typesSent().toString());
    }

    @Test
    void aFollowerThatKnowsNoLeaderYetActsOnATimeoutNowAtItsTerm() throws Exception {
        group = node(new MemoryLogStorage());
        group.handle(N2, vote(3, N2), clock.nanoTime()); // term 3, voted for n2, no APPEND yet
        assertTrue(group.facade().leader().isEmpty());
        group.handle(N2, timeoutNow(3, N2), clock.nanoTime());
        assertTrue(group.campaigning());
        assertEquals(4L, group.facade().term());
    }

    @Test
    void aHigherTermTimeoutNowIsAdoptedThenActedOn() throws Exception {
        group = node(new MemoryLogStorage());
        group.handle(N2, append(3, N2), clock.nanoTime());
        sent.clear();
        group.handle(N2, timeoutNow(5, N2), clock.nanoTime());
        assertEquals(6L, group.facade().term());
        assertTrue(group.campaigning());
        assertTrue(typesSent().contains(RaftGroup.VOTE));
    }

    @Test
    void aCandidateIgnoresATimeoutNowAtItsTerm() throws Exception {
        group = node(new MemoryLogStorage());
        clock.advance(Duration.ofSeconds(1));
        group.tick(clock.nanoTime());
        group.handle(N3, grant(RaftGroup.PRE_VOTE_RESP, 0, N3), clock.nanoTime());
        assertTrue(group.campaigning());
        assertEquals(1L, group.facade().term());
        sent.clear();
        group.handle(N2, timeoutNow(1, N2), clock.nanoTime());
        assertEquals(1L, group.facade().term(), "no second election from a TIMEOUT_NOW at the candidate's term");
        assertTrue(typesSent().isEmpty());
    }

    @Test
    void aWitnessNeverCampaignsOnTimeoutNow() throws Exception {
        group = new RaftGroup(GROUP, N3, true, Set.of(N3), Set.of(N1, N2, N3), List.of(N1, N2), TimeoutNowTest::byWire,
                QuorumPolicy.majority(), OPTS, new MemoryLogStorage(), (to, frame) -> sent.add(frame), clock, null,
                1024);
        group.handle(N1, timeoutNow(4, N1), clock.nanoTime());
        assertFalse(group.campaigning());
        assertTrue(typesSent().isEmpty());
    }

    // --- persist failure at a leader ------------------------------------------------------------------------

    @Test
    void aLeaderThatCannotPersistAHigherTermStopsLeadingAndNeverAcks() throws Exception {
        leaderAtTerm(1);
        var pending = group.append(List.of(text("x")));
        long commitBefore = group.facade().commitIndex();
        failHardState = true;
        sent.clear();

        group.handle(N2, appendResp(2, N2, false, 0), clock.nanoTime()); // a later term, which we cannot save

        assertFalse(group.facade().isLeader(), "a leader that knows of a later term must stop leading");
        assertEquals(-1L, group.facade().leaderTerm());
        assertTrue(group.facade().leader().isEmpty(), "and stop reporting itself as leader");
        assertEquals(1L, group.facade().term(), "the unsaved term is not adopted");
        assertOutcomeUnknown(pending);
        awaitLastLeaderEvent(Optional.empty());

        // Late acks for its own term no longer count: nothing commits, nothing is acked.
        group.handle(N2, appendResp(1, N2, true, 2), clock.nanoTime());
        group.handle(N3, appendResp(1, N3, true, 2), clock.nanoTime());
        clock.advance(Duration.ofMillis(50));
        group.tick(clock.nanoTime());
        assertEquals(commitBefore, group.facade().commitIndex());
        assertTrue(typesSent().isEmpty(), "no frame carries the unsaved term, no heartbeat at the old one: "
                + typesSent());

        // Nor does it count itself into a new term: the campaign's own write fails, so no VOTE goes out.
        clock.advance(Duration.ofSeconds(2));
        group.tick(clock.nanoTime());
        assertTrue(group.campaigning(), "pre-vote at the durable term");
        group.handle(N3, grant(RaftGroup.PRE_VOTE_RESP, 1, N3), clock.nanoTime());
        assertFalse(group.campaigning());
        assertFalse(group.facade().isLeader());
        assertTrue(group.facade().leader().isEmpty());
        assertEquals(1L, group.facade().term());
        assertFalse(typesSent().contains(RaftGroup.VOTE), "no VOTE at an unsaved term: " + typesSent());
    }

    @Test
    void aLeaderThatCannotPersistAHigherTermTimeoutNowStopsLeadingWithoutCampaigning() throws Exception {
        leaderAtTerm(1);
        var pending = group.append(List.of(text("x")));
        failHardState = true;
        sent.clear();
        group.handle(N2, timeoutNow(2, N2), clock.nanoTime());
        assertFalse(group.facade().isLeader());
        assertTrue(group.facade().leader().isEmpty());
        assertFalse(group.campaigning());
        assertEquals(1L, group.facade().term());
        assertOutcomeUnknown(pending);
        assertTrue(typesSent().isEmpty(), typesSent().toString());
    }

    @Test
    void aCandidateThatCannotPersistAHigherTermDoesNotWinItsStaleTerm() throws Exception {
        group = node(new MemoryLogStorage());
        clock.advance(Duration.ofSeconds(1));
        group.tick(clock.nanoTime());
        group.handle(N3, grant(RaftGroup.PRE_VOTE_RESP, 0, N3), clock.nanoTime());
        assertTrue(group.campaigning());
        assertEquals(1L, group.facade().term());
        failHardState = true;
        sent.clear();

        group.handle(N2, refusal(RaftGroup.VOTE_RESP, 2, N2), clock.nanoTime()); // a later term, which we cannot save
        assertFalse(group.campaigning(), "a candidate that knows of a later term stops campaigning");
        assertEquals(1L, group.facade().term(), "the unsaved term is not adopted");

        group.handle(N3, grant(RaftGroup.VOTE_RESP, 1, N3), clock.nanoTime()); // the grant that would have won term 1
        assertFalse(group.facade().isLeader(), "no win in the stale term");
        assertTrue(group.facade().leader().isEmpty());
        assertTrue(typesSent().isEmpty(), "no APPEND or no-op from a stale-term leader: " + typesSent());
    }

    // --- same-term demotion --------------------------------------------------------------------------------

    @Test
    void sameTermDemotionFailsPendingWritesAndLateAcksCommitNothing() throws Exception {
        leaderAtTerm(1);
        var pending = group.append(List.of(text("x")));
        long commitBefore = group.facade().commitIndex();

        group.handle(N2, append(1, N2), clock.nanoTime()); // another leader in our own term: demote, no write

        assertFalse(group.facade().isLeader());
        assertEquals(1L, group.facade().term());
        assertEquals(Optional.of(N2), group.facade().leader());
        assertOutcomeUnknown(pending);
        awaitLastLeaderEvent(Optional.of(N2));

        group.handle(N3, appendResp(1, N3, true, 2), clock.nanoTime());
        clock.advance(Duration.ofMillis(50));
        group.tick(clock.nanoTime());
        assertEquals(commitBefore, group.facade().commitIndex(), "a follower counts no acks");
    }

    // --- helpers -------------------------------------------------------------------------------------------

    /** n1, restarted at {@code targetTerm - 1}, elected leader of {@code targetTerm} with the witness's votes. */
    private void leaderAtTerm(long targetTerm) {
        var storage = new MemoryLogStorage();
        storage.saveHardState(GROUP, targetTerm - 1, 0);
        group = node(storage);
        group.addLeaderListener((term, who) -> leaderEvents.add(who));
        clock.advance(Duration.ofSeconds(1)); // past the restart recovery window
        group.tick(clock.nanoTime());
        group.handle(N3, grant(RaftGroup.PRE_VOTE_RESP, targetTerm - 1, N3), clock.nanoTime());
        group.handle(N3, grant(RaftGroup.VOTE_RESP, targetTerm, N3), clock.nanoTime());
        assertTrue(group.facade().isLeader());
        assertEquals(targetTerm, group.facade().leaderTerm());
    }

    /** Both peers ack through {@code index}; the leader forces its own copy and commits. */
    private void commitThroughPeers(long term, long index) {
        group.handle(N2, appendResp(term, N2, true, index), clock.nanoTime());
        group.handle(N3, appendResp(term, N3, true, index), clock.nanoTime());
        clock.advance(Duration.ofMillis(50));
        group.tick(clock.nanoTime());
    }

    private RaftGroup node(MemoryLogStorage inner) {
        LogStorage storage = (LogStorage) Proxy.newProxyInstance(LogStorage.class.getClassLoader(),
                new Class<?>[] {LogStorage.class}, (proxy, method, args) -> {
                    if (failHardState && method.getName().equals("saveHardState")) {
                        throw new java.io.IOException("disk full");
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new RaftGroup(GROUP, N1, false, Set.of(N3), Set.of(N1, N2, N3), List.of(N2, N3), TimeoutNowTest::byWire,
                QuorumPolicy.majority(), OPTS, storage, (to, frame) -> sent.add(frame), clock, null, 1024);
    }

    private static NodeId byWire(int wire) {
        return switch (wire) {
            case 1 -> N1;
            case 2 -> N2;
            case 3 -> N3;
            default -> null;
        };
    }

    private List<Integer> typesSent() {
        var types = new ArrayList<Integer>();
        for (var frame : sent) {
            types.add(frame.type());
        }
        return types;
    }

    private void awaitLastLeaderEvent(Optional<NodeId> expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline
                && (leaderEvents.isEmpty() || !leaderEvents.get(leaderEvents.size() - 1).equals(expected))) {
            Thread.sleep(5);
        }
        assertEquals(expected, leaderEvents.get(leaderEvents.size() - 1), "listeners hear the leadership change: "
                + leaderEvents);
    }

    private static OutcomeUnknownException assertOutcomeUnknown(CompletableFuture<CommittedIndex> future) {
        var cause = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause();
        assertFalse(cause instanceof NotLeaderException, "the write is in the log: not a refusal");
        return assertInstanceOf(OutcomeUnknownException.class, cause);
    }

    private static SimTransport.Frame timeoutNow(long term, NodeId sender) {
        var body = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putLong(term).putInt(sender.wire());
        return new SimTransport.Frame(GROUP, RaftGroup.TIMEOUT_NOW, body.array());
    }

    private static SimTransport.Frame grant(int type, long term, NodeId voter) {
        var body = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putLong(term).putInt(voter.wire()).put((byte) 1);
        return new SimTransport.Frame(GROUP, type, body.array());
    }

    private static SimTransport.Frame refusal(int type, long term, NodeId voter) {
        var body = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putLong(term).putInt(voter.wire()).put((byte) 0);
        return new SimTransport.Frame(GROUP, type, body.array());
    }

    private static SimTransport.Frame appendResp(long term, NodeId follower, boolean success, long match) {
        var body = ByteBuffer.allocate(21).order(ByteOrder.BIG_ENDIAN).putLong(term).putInt(follower.wire())
                .put((byte) (success ? 1 : 0)).putLong(match);
        return new SimTransport.Frame(GROUP, RaftGroup.APPEND_RESP, body.array());
    }

    private static SimTransport.Frame vote(long term, NodeId candidate) {
        var body = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).putLong(term).putInt(candidate.wire())
                .putLong(0).putLong(0);
        return new SimTransport.Frame(GROUP, RaftGroup.VOTE, body.array());
    }

    /** An empty APPEND (heartbeat) from {@code leader} at {@code term}, prev (0, 0), commit 0. */
    private static SimTransport.Frame append(long term, NodeId leader) {
        var body = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN).putLong(term).putInt(leader.wire())
                .putLong(0).putLong(0).putLong(0).putInt(0);
        return new SimTransport.Frame(GROUP, RaftGroup.APPEND, body.array());
    }

    private static ByteBuffer text(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }
}
