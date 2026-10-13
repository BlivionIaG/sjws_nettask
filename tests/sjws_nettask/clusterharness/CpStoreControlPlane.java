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
package sjws_nettask.clusterharness;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import sjws_nettask.SjwsServer;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.cp.AdminServer;
import sjws_nettask.cp.CpStore;
import sjws_nettask.cp.DataWatch;
import sjws_nettask.cp.JoinTokens;
import sjws_nettask.cp.KeyRing;
import sjws_nettask.cp.LastGoodFiles;
import sjws_nettask.cp.SignedSnapshot;
import sjws_nettask.cp.WatchServer;

/**
 * {@link ControlPlane} on real {@link CpStore} voters of {@code cp-config}: 3 or 5 stores on one
 * in-process {@link SimTransport}, each with its own log directory, {@link WatchServer} and admin
 * listener. Data nodes are real data-mode {@link SjwsServer}s whose {@link DataWatch} long-polls a
 * voter through a {@link WatchRelay}; partition and per-node delay act on those sockets.
 *
 * <p>Raft time is {@link ClusterClock} plus one election timeout, so the clock's zero is the
 * moment voter 1's first election deadline is due and {@code start} needs no advance. After that
 * only {@link ClusterClock#advance} moves elections, heartbeats and leases. One driver thread
 * ticks the voters (current leader first) and delivers frames; {@link #leader()} drives once more
 * before it answers.
 */
final class CpStoreControlPlane implements ControlPlane {

    /** CpStore's election timeout. */
    static final Duration ELECTION = Duration.ofMillis(100);
    private static final long TTL_MILLIS = 15 * 60_000L;
    private static final String SECRET = "harness-join-secret-0123456789";

    private final Path dir;
    private final ReentrantLock driving = new ReentrantLock();
    private final List<Voter> voters = new ArrayList<>();
    private final Map<String, Joined> data = new LinkedHashMap<>();
    private SimTransport transport;
    private ClusterClock clock;
    private KeyRing keys;
    private Thread driver;
    private volatile boolean running;

    CpStoreControlPlane(Path dir) {
        this.dir = dir;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<ControlNode> start(int count, ClusterClock clusterClock) throws IOException {
        if (count != 3 && count != 5) {
            throw new IllegalArgumentException("voters must be 3 or 5");
        }
        if (this.transport != null) {
            throw new IllegalStateException("already started");
        }
        this.clock = clusterClock;
        this.transport = new SimTransport();
        long now = System.currentTimeMillis();
        this.keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var members = new ArrayList<Member>();
        var ids = new HashSet<NodeId>();
        for (int i = 1; i <= count; i++) {
            var id = new NodeId(i, "cp-" + i);
            members.add(new Member(id, new SiteId("local"), new InetSocketAddress("127.0.0.1", 21000 + i), 1L,
                    MemberState.ALIVE, false));
            ids.add(id);
        }
        var set = VoterSet.majority(members, ids);
        Clock raft = new RaftClock(clusterClock);
        for (var member : members) {
            var store = CpStore.open(this.dir.resolve(member.id().name()), this.keys, raft, "urn:sjws:admin:root",
                    member.id(), set, this.transport);
            var voter = new Voter(member.id(), store);
            this.voters.add(voter);
            voter.watch = WatchServer.start(store, 0, System::currentTimeMillis);
            voter.admin = AdminServer.startPlainForTests(store, now, TTL_MILLIS);
        }
        this.running = true;
        this.driver = Thread.ofPlatform().daemon().name("harness-cp-driver").start(this::drive);
        var lead = leader().orElseThrow(() -> new IOException("cp-config did not elect a leader"));
        if (!lead.leaderReady()) {
            throw new IOException("cp-config leader is not ready");
        }
        return nodes();
    }

    /**
     * Joins one data-mode server to this control plane through a fresh one-shot join token and
     * the real watch. Returns once the watch has answered once ({@code connected}).
     */
    DataNode join(String id, Map<String, String> labels, Path documentRoot) throws IOException {
        var lead = leaderStore();
        var tokenId = "token-" + id;
        var issued = lead.issueJoinToken(tokenId, SECRET.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis(),
                JoinTokens.DEFAULT_TTL_MILLIS);
        if (issued.status() != 200) {
            throw new IOException("join token: " + issued.status() + " " + issued.body());
        }
        var home = Files.createDirectories(this.dir.resolve(id));
        var pin = home.resolve("root.pub");
        LastGoodFiles.writePin(pin, this.keys.rootPublic(), this.keys.versionFloor());
        var yaml = home.resolve("sjws.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + home.resolve("config-state")
                + "\nserver:\n  port: 0\n  root: " + documentRoot.toAbsolutePath() + "\n");
        var relay = new WatchRelay(this.clock, this::liveWatchPorts);
        SjwsServer server = null;
        DataWatch watch = null;
        try {
            server = SjwsServer.start(ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of()));
            watch = DataWatch.open("127.0.0.1:" + relay.port(), id, tokenId, SECRET.getBytes(StandardCharsets.UTF_8),
                    pin, home.resolve("state"), server, null, System::currentTimeMillis);
            var joined = new Joined(id, labels, server, watch, relay);
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (watch.binding() == null || !"connected".equals(watch.binding().connectionState())) {
                if (System.nanoTime() > end) {
                    throw new IOException("data node " + id + " did not connect");
                }
                pause();
            }
            this.data.put(id, joined);
            return joined;
        } catch (IOException | RuntimeException e) {
            if (watch != null) {
                watch.close();
            }
            if (server != null) {
                server.close();
            }
            relay.close();
            throw e;
        }
    }

    @Override
    public List<ControlNode> nodes() {
        return List.copyOf(this.voters);
    }

    @Override
    public Optional<ControlNode> leader() {
        this.driving.lock();
        try {
            driveOnce();
        } finally {
            this.driving.unlock();
        }
        Voter best = null;
        for (var voter : this.voters) {
            if (voter.leaderReady() && (best == null || voter.term() > best.term())) {
                best = voter;
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public long push(String canonicalYaml) {
        var store = leaderStore();
        var proposed = store.propose(canonicalYaml);
        if (proposed.status() != 200) {
            throw new IllegalStateException("propose: " + proposed.status() + " " + proposed.body());
        }
        var activated = store.activate(System.currentTimeMillis(), TTL_MILLIS);
        if (activated.status() != 200) {
            throw new IllegalStateException("activate: " + activated.status() + " " + activated.body());
        }
        dispatched(activated.version());
        return activated.version();
    }

    /**
     * Waits (real time, bounded) until the envelope has left the voters for every data node: applied
     * there, or queued in its relay. A delay then counts from a clock reading taken before the
     * caller can advance the clock, not from whenever the watch thread happened to be scheduled.
     */
    private void dispatched(long version) {
        long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < end) {
            boolean all = true;
            for (var joined : this.data.values()) {
                all &= joined.watch.appliedVersion() >= version || joined.relay.holding();
            }
            if (all) {
                return;
            }
            pause(1);
        }
    }

    @Override
    public String contentHash(long version) {
        for (SignedSnapshot snapshot : leaderStore().history()) {
            if (snapshot.version() == version) {
                return snapshot.canonicalHashHex();
            }
        }
        throw new IllegalArgumentException("version " + version + " is not retained");
    }

    @Override
    public long rollback(long toVersion) {
        var outcome = leaderStore().rollback(toVersion, System.currentTimeMillis(), TTL_MILLIS);
        if (outcome.status() != 200) {
            throw new IllegalStateException("rollback: " + outcome.status() + " " + outcome.body());
        }
        dispatched(outcome.version());
        return outcome.version();
    }

    @Override
    public void partitionDataFromControl() {
        for (var joined : this.data.values()) {
            joined.relay.pause();
        }
    }

    @Override
    public void heal() {
        for (var joined : this.data.values()) {
            joined.relay.resume();
        }
        for (var a : this.voters) {
            for (var b : this.voters) {
                if (a != b) {
                    this.transport.heal(a.id, b.id);
                }
            }
        }
    }

    @Override
    public void killLeader() {
        var lead = (Voter) leader().orElseThrow(() -> new IllegalStateException("no leader to kill"));
        this.driving.lock();
        try {
            lead.dead = true;
            for (var other : this.voters) {
                if (other != lead) {
                    this.transport.cut(lead.id, other.id);
                }
            }
        } finally {
            this.driving.unlock();
        }
        int watchPort = lead.watch.port();
        lead.admin.close();
        lead.watch.close();
        for (var joined : this.data.values()) {
            joined.relay.dropUpstream(watchPort);
        }
        lead.store.close();
    }

    @Override
    public void delay(String dataNodeId, Duration oneWay) {
        var joined = this.data.get(dataNodeId);
        if (joined == null) {
            throw new IllegalArgumentException("no data node " + dataNodeId);
        }
        joined.relay.delay(oneWay.toNanos());
    }

    @Override
    public void awaitConverged(long version, Duration timeout) {
        long end = System.nanoTime() + timeout.toNanos();
        while (true) {
            boolean all = true;
            for (var joined : this.data.values()) {
                all &= joined.watch.appliedVersion() == version;
            }
            if (all) {
                return;
            }
            if (System.nanoTime() > end) {
                var seen = new StringBuilder();
                for (var joined : this.data.values()) {
                    seen.append(joined.id).append('=').append(joined.watch.appliedVersion()).append(' ');
                }
                throw new IllegalStateException("not converged on " + version + ": " + seen.toString().trim());
            }
            pause();
        }
    }

    @Override
    public void close() {
        for (var joined : this.data.values()) {
            joined.close();
        }
        this.data.clear();
        this.running = false;
        if (this.driver != null) {
            try {
                this.driver.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (var voter : this.voters) {
            if (!voter.dead) {
                voter.dead = true;
                voter.admin.close();
                voter.watch.close();
                voter.store.close();
            }
        }
        try (var walk = Files.walk(this.dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException e) {
            // Temporary directory; best effort.
        }
    }

    private CpStore leaderStore() {
        return ((Voter) leader().orElseThrow(() -> new IllegalStateException("cp-config has no ready leader"))).store;
    }

    private List<Integer> liveWatchPorts() {
        // Leader first: a follower learns the commit index only from the leader's next heartbeat,
        // which needs the clock to advance. Followers stay as fallbacks.
        var ports = new ArrayList<Integer>();
        for (var voter : this.voters) {
            if (!voter.dead) {
                if (voter.store.leadership().isLeader()) {
                    ports.add(0, voter.watch.port());
                } else {
                    ports.add(voter.watch.port());
                }
            }
        }
        return ports;
    }

    private void drive() {
        while (this.running) {
            this.driving.lock();
            try {
                driveOnce();
            } catch (RuntimeException e) {
                // A stopped voter's group can throw; the next round continues with the others.
            } finally {
                this.driving.unlock();
            }
            pause(1);
        }
    }

    /** One round: the current leader ticks first so its heartbeat lands before a follower's deadline. */
    private void driveOnce() {
        var order = new ArrayList<Voter>();
        for (var voter : this.voters) {
            if (!voter.dead) {
                if (voter.store.leadership().isLeader()) {
                    order.add(0, voter);
                } else {
                    order.add(voter);
                }
            }
        }
        for (var voter : order) {
            voter.store.drive();
        }
    }

    private static void pause() {
        pause(5);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** {@link ClusterClock} as Raft's clock, shifted by one election timeout. */
    private record RaftClock(ClusterClock clock) implements Clock {
        @Override
        public long nanoTime() {
            return this.clock.nanoTime() + ELECTION.toNanos();
        }

        @Override
        public void sleep(Duration duration) throws InterruptedException {
            Thread.sleep(duration);
        }
    }

    private static final class Voter implements ControlNode {
        private final NodeId id;
        private final CpStore store;
        private WatchServer watch;
        private AdminServer admin;
        private volatile boolean dead;

        Voter(NodeId id, CpStore store) {
            this.id = id;
            this.store = store;
        }

        @Override
        public String id() {
            return this.id.name();
        }

        @Override
        public int adminPort() {
            return this.admin.port();
        }

        @Override
        public boolean leaderReady() {
            return !this.dead && this.store.leadership().leaderReady();
        }

        @Override
        public long term() {
            return this.store.leadership().term();
        }
    }

    private static final class Joined implements DataNode {
        private final String id;
        private final Map<String, String> labels;
        private final SjwsServer server;
        private final DataWatch watch;
        private final WatchRelay relay;

        Joined(String id, Map<String, String> labels, SjwsServer server, DataWatch watch, WatchRelay relay) {
            this.id = id;
            this.labels = labels;
            this.server = server;
            this.watch = watch;
            this.relay = relay;
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public Map<String, String> labels() {
            return this.labels;
        }

        @Override
        public int httpPort() {
            return this.server.tcpPort();
        }

        @Override
        public long version() {
            return this.watch.appliedVersion();
        }

        @Override
        public String contentHash() {
            return this.watch.appliedHash();
        }

        @Override
        public SjwsServer server() {
            return this.server;
        }

        private boolean closed;

        @Override
        public synchronized void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.watch.close();
            this.relay.close();
            this.server.close();
        }
    }
}
