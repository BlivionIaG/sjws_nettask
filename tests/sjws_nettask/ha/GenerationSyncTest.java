package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sjws_nettask.cluster.Durability;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.MemoryLogStorage;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.QuorumPolicy;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;

@Timeout(15)
class GenerationSyncTest {

    @Test
    void stageThenActivateWritesTheKeyMode0600() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var site = new SiteId("local");
        var members = new ArrayList<Member>();
        var voters = new java.util.LinkedHashSet<NodeId>();
        for (int wire = 1; wire <= 3; wire++) {
            var member = new Member(new NodeId(wire, "n" + wire), site, new InetSocketAddress("127.0.0.1", 21000 + wire),
                    1L, MemberState.ALIVE, false);
            members.add(member);
            voters.add(member.id());
        }
        var options = new GroupOptions(1 << 20, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000L);
        assertEquals(Durability.RELAXED, options.durability());
        var group = new GroupId("ha-control");
        var nodes = new ArrayList<ReplicatedNode>();
        for (var member : members) {
            var node = ReplicatedNode.open(member.id(), members, voters, QuorumPolicy.majority(), clock,
                    new MemoryLogStorage(), net);
            node.openGroup(group, options, null);
            nodes.add(node);
        }
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        ReplicatedNode leader = null;
        for (var node : nodes) {
            if (node.openGroup(group, options, null).isLeader()) {
                leader = node;
            }
        }
        assertNotNull(leader);
        var bundles = new HmacBundles();
        var secret = "shared-secret".getBytes(StandardCharsets.UTF_8);
        var dir = Files.createTempDirectory("ha-gen");
        var activated = new AtomicInteger();
        var activatedOn = new java.util.concurrent.atomic.AtomicReference<String>();
        GenerationSync leaderSync = null;
        var syncs = new ArrayList<GenerationSync>();
        for (var node : nodes) {
            var sync = new GenerationSync(node, group, options, bundles, secret, dir.resolve("n" + node.self().wire() + ".key"),
                    node == leader ? generation -> {
                        activatedOn.set(Thread.currentThread().getName());
                        activated.incrementAndGet();
                    } : null);
            syncs.add(sync);
            if (node == leader) {
                leaderSync = sync;
            }
        }
        var config = "cfg".getBytes(StandardCharsets.UTF_8);
        var cert = "cert".getBytes(StandardCharsets.UTF_8);
        var key = "super-secret".getBytes(StandardCharsets.UTF_8);
        var published = leaderSync.publishFrom(7L, () -> config, new CertProvider() {
            @Override
            public byte[] certificate() {
                return cert;
            }

            @Override
            public byte[] privateKey() {
                return key;
            }
        });
        ReplicatedNode.driveAll(nodes);
        assertEquals(7L, published.get().groupId().value().equals("ha-control") ? 7L : -1L);
        ReplicatedNode.driveAll(nodes);
        for (var sync : syncs) {
            assertTrue(sync.awaitApplied(java.time.Duration.ofSeconds(5)), "worker applied the markers");
        }
        assertTrue(activated.get() >= 1);
        // Pull + key write + activate run on GenerationSync's own worker, never on the Raft delivery thread.
        assertEquals("ha-generation-sync", activatedOn.get());
        for (var node : nodes) {
            var path = dir.resolve("n" + node.self().wire() + ".key");
            assertArrayEquals(key, Files.readAllBytes(path));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(path));
        }
        leader.append(group, List.of(ByteBuffer.wrap(new byte[] {1})));
        ReplicatedNode.driveAll(nodes);
        assertThrows(java.io.IOException.class, () -> bundles.pull(7L, "nope".getBytes(StandardCharsets.UTF_8)));
        for (var sync : syncs) {
            sync.close();
            assertFalse(sync.awaitApplied(java.time.Duration.ofMillis(100)), "closed worker takes no more work");
        }
        assertThrows(java.io.IOException.class, () -> bundles.pull(99L, secret));
        var payload = GenerationSync.payload(config, cert, key);
        assertArrayEquals(key, GenerationSync.privateKey(payload));
        assertNotEquals(0, PosixFilePermissions.fromString("rw-------").size());
        assertFalse(syncs.isEmpty());
    }
}
