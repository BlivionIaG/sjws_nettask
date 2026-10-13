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
package sjws_nettask.ha;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.LocalCluster;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.PacketLink;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.UdpPacketLink;
import sjws_nettask.cluster.VoterSet;

/**
 * Two data nodes, a witness, and their UDP heartbeat threads for cluster-mode benchmarks.
 * Serving does not wait on this; it only keeps the failure detector on the box while wrk runs.
 * The voter set is a majority of those three.
 */
final class ClusterSide implements AutoCloseable {

    private final LocalCluster left;
    private final LocalCluster right;
    private final LocalCluster witness;
    private final PacketLink leftLink;
    private final PacketLink rightLink;
    private final PacketLink witnessLink;
    private final VoterSet voters;

    private ClusterSide(LocalCluster left, LocalCluster right, LocalCluster witness, PacketLink leftLink,
            PacketLink rightLink, PacketLink witnessLink, VoterSet voters) {
        this.left = left;
        this.right = right;
        this.witness = witness;
        this.leftLink = leftLink;
        this.rightLink = rightLink;
        this.witnessLink = witnessLink;
        this.voters = voters;
    }

    static ClusterSide start(ClusterKey key) throws IOException {
        return start(Duration.ofMillis(200), key);
    }

    static ClusterSide start(Duration interval, ClusterKey key) throws IOException {
        java.util.Objects.requireNonNull(key, "cluster key");
        UdpPacketLink leftLink = null;
        UdpPacketLink rightLink = null;
        UdpPacketLink witnessLink = null;
        var started = new ArrayList<LocalCluster>();
        try {
            leftLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0));
            rightLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0));
            witnessLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0));
            var site = new SiteId("local");
            var a = new NodeId(1, "bench-a");
            var b = new NodeId(2, "bench-b");
            var w = new NodeId(3, "bench-w");
            var voters = VoterSet.dataAndWitness(
                    new Member(a, site, leftLink.localAddress(), 1L, MemberState.ALIVE, false),
                    new Member(b, site, rightLink.localAddress(), 1L, MemberState.ALIVE, false),
                    new Member(w, site, witnessLink.localAddress(), 1L, MemberState.ALIVE, true));
            var members = voters.members();
            var left = LocalCluster.start(a, members, leftLink, Clock.SYSTEM, interval, key);
            started.add(left);
            var right = LocalCluster.start(b, members, rightLink, Clock.SYSTEM, interval, key);
            started.add(right);
            var witness = LocalCluster.start(w, members, witnessLink, Clock.SYSTEM, interval, key);
            started.add(witness);
            return new ClusterSide(left, right, witness, leftLink, rightLink, witnessLink, voters);
        } catch (RuntimeException | IOException failed) {
            for (var node : started) {
                node.close();
            }
            closeQuiet(leftLink);
            closeQuiet(rightLink);
            closeQuiet(witnessLink);
            throw failed;
        }
    }

    VoterSet voters() {
        return voters;
    }

    int received() {
        return left.received() + right.received() + witness.received();
    }

    @Override
    public void close() {
        left.close();
        right.close();
        witness.close();
        closeQuiet(leftLink);
        closeQuiet(rightLink);
        closeQuiet(witnessLink);
    }

    private static void closeQuiet(PacketLink link) {
        if (link == null) {
            return;
        }
        try {
            link.close();
        } catch (IOException ignored) {
            // The benchmark process is exiting; a second close is not fatal.
        }
    }
}
