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
package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.MemoryLogStorage;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;

/**
 * #217 openForWitness follow-ups: witness-only guard and {@code __}-prefixed topic refusal.
 */
@Timeout(30)
class OpenForWitnessTest {

    static final Duration ELECTION = Duration.ofMillis(100), HEARTBEAT = Duration.ofMillis(40);

    SimTransport net;
    List<ReplicatedNode> nodes = new ArrayList<>();

    @AfterEach
    void close() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            try {
                nodes.get(i).close();
            } catch (Exception ignored) {
            }
        }
        nodes.clear();
        net = null;
    }

    private void openCluster() throws Exception {
        var clock = new FakeClock(0L);
        net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        nodes.add(ReplicatedNode.open(members.get(0).id(), voters, clock, new MemoryLogStorage(), net));
        nodes.add(ReplicatedNode.open(members.get(1).id(), voters, clock, new MemoryLogStorage(), net));
        nodes.add(ReplicatedNode.open(members.get(2).id(), voters, clock, new MemoryLogStorage(), net));
        assertFalse(nodes.get(0).witness());
        assertTrue(nodes.get(2).witness());
    }

    private static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("msg"),
                new InetSocketAddress("127.0.0.1", 10_000 + wire), 1L, MemberState.ALIVE, witness);
    }

    @Test
    void dataNodeRefused() throws Exception {
        openCluster();
        var err = assertThrows(IllegalArgumentException.class,
                () -> RaftReplicator.openForWitness(nodes.get(0), Set.of("t"), ELECTION, HEARTBEAT));
        assertTrue(err.getMessage().contains("witness"), err.getMessage());
    }

    @Test
    void internalTopicNameRefused() throws Exception {
        openCluster();
        for (var bad : List.of("__offsets", "__offsets_local", "__evil")) {
            var err = assertThrows(IllegalArgumentException.class,
                    () -> RaftReplicator.openForWitness(nodes.get(2), Set.of("t", bad), ELECTION, HEARTBEAT), bad);
            assertTrue(err.getMessage().contains("__"), err.getMessage());
        }
    }

    @Test
    void witnessOpensTopicsAndOffsets() throws Exception {
        openCluster();
        RaftReplicator.openForWitness(nodes.get(2), Set.of("t", "u"), ELECTION, HEARTBEAT);
        RaftReplicator.openForWitness(nodes.get(2), Set.of("t", "u"), ELECTION, HEARTBEAT);
    }

    /** N4: full NAME pattern, not only the {@code __} prefix. */
    @Test
    void invalidTopicNamePatternRefused() throws Exception {
        openCluster();
        for (var bad : List.of(".", "..", "bad/name", "has space", "x".repeat(129), "")) {
            var err = assertThrows(IllegalArgumentException.class,
                    () -> RaftReplicator.openForWitness(nodes.get(2), Set.of(bad), ELECTION, HEARTBEAT), bad);
            assertTrue(err.getMessage().contains("invalid topic name"), err.getMessage());
        }
    }
}
