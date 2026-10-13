package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
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

class FailoverTest {

    private static final SiteId SITE = new SiteId("local");
    private static final GroupId GROUP = new GroupId("ha-control");

    @TempDir
    Path root;

    @Test
    void passiveReleasesBeforeTheLeaseExpiresAndActiveNeverClaims() throws Exception {
        var clock = new FakeClock(0L);
        var net = new SimTransport();
        var members = new ArrayList<Member>();
        var voters = new java.util.LinkedHashSet<NodeId>();
        for (int wire = 1; wire <= 3; wire++) {
            var member = new Member(new NodeId(wire, "n" + wire), SITE, new InetSocketAddress("127.0.0.1", 21000 + wire),
                    1L, MemberState.ALIVE, false);
            members.add(member);
            voters.add(member.id());
        }
        var options = new GroupOptions(Durability.STRICT, 1024, Duration.ofMillis(100), Duration.ofMillis(40), 1_000_000);
        var nodes = new ArrayList<ReplicatedNode>();
        for (var member : members) {
            var node = ReplicatedNode.open(member.id(), members, voters, QuorumPolicy.majority(), clock,
                    new MemoryLogStorage(), net);
            node.openGroup(GROUP, options, null);
            nodes.add(node);
        }
        var claims = new AtomicInteger();
        var releases = new AtomicInteger();
        var role = new AtomicReference<Double>();
        var hook = new VipHook() {
            @Override
            public void claim(NodeId owner) {
                claims.incrementAndGet();
            }

            @Override
            public void release(NodeId owner) {
                releases.incrementAndGet();
            }
        };
        var metrics = new MetricsSink() {
            @Override
            public void gauge(String name, double value) {
                if ("ha_role".equals(name)) {
                    role.set(value);
                }
            }

            @Override
            public void counter(String name, long delta) {
            }
        };
        var fence = new VipFence(FailoverPolicy.ACTIVE_PASSIVE, members.get(0).id(), hook, metrics, Duration.ofMillis(5));
        fence.onLeader(null);
        fence.poll(false, 0L, 0L);
        assertFalse(fence.claimed());
        nodes.get(0).onLeaderChange(GROUP, (term, leader) -> fence.onLeader(leader));
        clock.advance(Duration.ofMillis(100));
        ReplicatedNode.driveAll(nodes);
        var leader = nodes.get(0).openGroup(GROUP, options, null);
        assertTrue(leader.isLeader());
        assertTrue(fence.claimed());
        assertEquals(1, claims.get());
        assertEquals(1.0, role.get());
        assertEquals(0, FailoverPolicy.ACTIVE_PASSIVE.acceptPause().getAsInt());
        assertNull(FailoverPolicy.ACTIVE_ACTIVE.acceptPause());
        assertTrue(FailoverPolicy.ACTIVE_PASSIVE.claimsVip());
        assertFalse(FailoverPolicy.ACTIVE_ACTIVE.claimsVip());

        clock.advance(Duration.ofMillis(40));
        ReplicatedNode.driveAll(nodes);
        fence.poll(leader.isLeader(), clock.nanoTime(), leader.leaseExpiresAt());
        assertTrue(fence.claimed());
        assertEquals(0, releases.get());

        net.cut(members.get(0).id(), members.get(1).id());
        net.cut(members.get(0).id(), members.get(2).id());
        long expires = leader.leaseExpiresAt();
        clock.advance(Duration.ofNanos(expires - clock.nanoTime() - Duration.ofMillis(5).toNanos()));
        ReplicatedNode.driveAll(nodes);
        assertTrue(leader.isLeader());
        assertTrue(clock.nanoTime() < leader.leaseExpiresAt());
        fence.poll(leader.isLeader(), clock.nanoTime(), leader.leaseExpiresAt());
        assertFalse(fence.claimed());
        assertEquals(1, releases.get());
        assertEquals(0.0, role.get());
        assertFalse(nodes.get(1).openGroup(GROUP, options, null).isLeader());
        assertFalse(nodes.get(2).openGroup(GROUP, options, null).isLeader());

        var active = VipFence.active(members.get(1).id());
        active.onLeader(Optional.of(members.get(1).id()));
        active.poll(true, 0L, 1L);
        assertFalse(active.claimed());
        fence.onLeader(Optional.empty());
        assertFalse(fence.claimed());
        assertThrows(IllegalArgumentException.class,
                () -> VipFence.passive(members.get(0).id(), hook, Duration.ofMillis(-1)));
        assertThrows(NullPointerException.class, () -> new VipFence(null, members.get(0).id(), hook, metrics, Duration.ZERO));
    }

    @Test
    void rollingRestartWaitsForReadyz(@TempDir Path other) throws Exception {
        Files.writeString(root.resolve("index.html"), "a");
        Files.writeString(other.resolve("index.html"), "b");
        var readySettings = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(200)).build();
        var held = HaSettings.builder().requireQuorum(true).drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(200))
                .build();
        try (var up = SjwsServer.start(ServerConfig.defaults(root).withPort(0), readySettings);
                var down = SjwsServer.start(ServerConfig.defaults(other).withPort(0), held)) {
            down.ha().health().setQuorumOk(false);
            var restart = new RollingRestart();
            var peer = new InetSocketAddress("127.0.0.1", up.tcpPort());
            assertEquals(200, restart.status(peer));
            restart.requirePeersReady(List.of());
            restart.requirePeersReady(List.of(peer));
            var blocked = new InetSocketAddress("127.0.0.1", down.tcpPort());
            assertEquals(503, restart.status(blocked));
            assertThrows(IllegalStateException.class, () -> restart.requirePeersReady(List.of(peer, blocked)));
            assertThrows(IllegalArgumentException.class, () -> restart.status(new InetSocketAddress("127.0.0.1", 0)));
            assertThrows(IllegalArgumentException.class, () -> new RollingRestart(java.net.http.HttpClient.newHttpClient(), Duration.ZERO));
        }
    }

    @Test
    void reusePortHandsOverOrReportsUnavailable() throws Exception {
        assertFalse(ReusePort.enable(null));
        assertFalse(ReusePort.setsockopt(-1));
        assertFalse(ReusePort.enable(new ForeignChannel()));
        try (var first = ServerSocketChannel.open(); var second = ServerSocketChannel.open()) {
            first.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            second.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            assertTrue(ReusePort.enable(first));
            assertTrue(ReusePort.enable(second));
            first.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = ((InetSocketAddress) first.getLocalAddress()).getPort();
            second.bind(new InetSocketAddress("127.0.0.1", port));
            assertEquals(port, ((InetSocketAddress) second.getLocalAddress()).getPort());
        }
    }

    private static final class ForeignChannel extends ServerSocketChannel {
        ForeignChannel() {
            super(SelectorProvider.provider());
        }

        @Override
        public ServerSocketChannel bind(SocketAddress local, int backlog) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> ServerSocketChannel setOption(java.net.SocketOption<T> name, T value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.net.ServerSocket socket() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SocketChannel accept() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public <T> T getOption(java.net.SocketOption<T> name) {
            return null;
        }

        @Override
        public java.util.Set<java.net.SocketOption<?>> supportedOptions() {
            return Set.of();
        }

        @Override
        protected void implCloseSelectableChannel() {
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
        }
    }
}
