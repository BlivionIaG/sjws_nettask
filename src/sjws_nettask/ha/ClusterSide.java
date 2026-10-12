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
import java.util.List;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.LocalCluster;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.PacketLink;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.UdpPacketLink;

/**
 * Second node plus the UDP heartbeat thread used by cluster-mode benchmarks. Serving does not
 * wait on this; it only keeps the failure detector on the box while wrk runs.
 */
final class ClusterSide implements AutoCloseable {

    private final LocalCluster left;
    private final LocalCluster right;
    private final PacketLink leftLink;
    private final PacketLink rightLink;

    private ClusterSide(LocalCluster left, LocalCluster right, PacketLink leftLink, PacketLink rightLink) {
        this.left = left;
        this.right = right;
        this.leftLink = leftLink;
        this.rightLink = rightLink;
    }

    static ClusterSide start() throws IOException {
        return start(Duration.ofMillis(200));
    }

    static ClusterSide start(Duration interval) throws IOException {
        UdpPacketLink leftLink = null;
        UdpPacketLink rightLink = null;
        LocalCluster left = null;
        LocalCluster right = null;
        try {
            leftLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0));
            rightLink = new UdpPacketLink(new InetSocketAddress("127.0.0.1", 0));
            var site = new SiteId("local");
            var a = new NodeId(1, "bench-a");
            var b = new NodeId(2, "bench-b");
            var members = List.of(
                    new Member(a, site, leftLink.localAddress(), 1L, MemberState.ALIVE, false),
                    new Member(b, site, rightLink.localAddress(), 1L, MemberState.ALIVE, false));
            left = LocalCluster.start(a, members, leftLink, Clock.SYSTEM, interval);
            right = LocalCluster.start(b, members, rightLink, Clock.SYSTEM, interval);
            return new ClusterSide(left, right, leftLink, rightLink);
        } catch (RuntimeException | IOException failed) {
            if (left != null) {
                left.close();
            }
            if (right != null) {
                right.close();
            }
            closeQuiet(leftLink);
            closeQuiet(rightLink);
            throw failed;
        }
    }

    int received() {
        return left.received() + right.received();
    }

    @Override
    public void close() {
        left.close();
        right.close();
        closeQuiet(leftLink);
        closeQuiet(rightLink);
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
