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

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sjws_nettask.cp.ControlLeadership;

/** #212: a demoted leader must stop reporting leaderReady() before its leader-change notice is delivered. */
class ControlLeadershipDemotionTest {

    private static final GroupId GROUP = new GroupId("cp-demote");

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void demotedLeaderIsNotReadyWhileDeliveriesAreBackedUp() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = new ArrayList<Member>();
        var voters = new LinkedHashSet<NodeId>();
        for (int wire = 1; wire <= 3; wire++) {
            var m = new Member(new NodeId(wire, "n" + wire), new SiteId("local"),
                    new InetSocketAddress("127.0.0.1", 24_000 + wire), 1L, MemberState.ALIVE, false);
            members.add(m);
            voters.add(m.id());
        }
        var options = new GroupOptions(Durability.STRICT, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var block = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        GroupListener stalling = entry -> {
            if (block.get()) {
                entered.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        var nodes = new ArrayList<ReplicatedNode>();
        var groups = new ArrayList<ReplicationGroup>();
        for (var m : members) {
            var node = ReplicatedNode.open(m.id(), members, voters, QuorumPolicy.majority(), clock,
                    new MemoryLogStorage(), net);
            groups.add(node.openGroup(GROUP, options, nodes.isEmpty() ? stalling : null));
            nodes.add(node);
        }
        var n1 = nodes.get(0);
        var lead = ControlLeadership.attach(n1, GROUP, groups.get(0), n1.self());
        try {
            clock.advance(Duration.ofMillis(100));
            ReplicatedNode.driveAll(nodes);
            assertTrue(groups.get(0).isLeader());
            assertTrue(lead.leaderReady(), "ready once the election no-op is applied");

            // Stall n1's delivery thread inside a committed entry, so later notices queue behind it.
            block.set(true);
            n1.append(GROUP, List.of(ByteBuffer.wrap(new byte[] {1})));
            for (int i = 0; i < 32 && entered.getCount() > 0; i++) {
                stepAll(nodes);
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "delivery thread is stalled");

            groups.get(0).transferLeadership(nodes.get(1).self());
            for (int i = 0; i < 64 && groups.get(0).isLeader(); i++) {
                stepAll(nodes);
            }
            assertFalse(groups.get(0).isLeader(), "n1 is demoted");
            assertFalse(lead.leaderReady(), "leaderReady() must follow the live role, not the queued notice");
            assertTrue(lead.leaderId().map("n1"::equals).orElse(false), "the stale notice has not drained yet");
        } finally {
            release.countDown();
            for (var node : nodes) {
                node.close();
            }
        }
    }

    private static void stepAll(List<ReplicatedNode> nodes) {
        for (var node : nodes) {
            node.step();
        }
    }
}
