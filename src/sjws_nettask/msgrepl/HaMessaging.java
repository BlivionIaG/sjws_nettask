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

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.config.HaMessagingSettings;
import sjws_nettask.messaging.http.MessagingModule;

/**
 * The HA messaging bootstrap (proposal option B, gate G1): opens this node's Raft side for messaging and installs it.
 * Reached by class name from the composition root only when {@link HaMessagingSettings#replicate()} is true, so a
 * node with {@code ha.messaging: off} or no member list loads neither this package nor {@code sjws_nettask.cluster}.
 *
 * <p>One {@link ReplicatedNode} on one {@link TcpTransport} (HMAC per frame under {@code ha.cluster_key}, HKDF info
 * {@code sjws-raft-frame-v1}) and one {@link FileLogStorage} under {@code <state_dir>/raft}. A data node hands a
 * {@link RaftReplicator} to {@link MessagingModule#replication}; the witness opens the same groups, from the same
 * {@code ha.topics} with the same timings, through {@link RaftReplicator#openForWitness} (#217 item 1).
 */
public final class HaMessaging implements sjws_nettask.OptionalModule {

    /** Raft tick; the heartbeat and election timeouts are multiples of it. */
    static final Duration TICK = Duration.ofMillis(10);
    /** Frames buffered per peer before a drop is counted (as the control plane's voter links). */
    static final int PEER_QUEUE = 1024;

    private final ReplicatedNode node;
    private final TcpTransport transport;
    private final FileLogStorage storage;
    private final String describe;
    /** Data nodes only (#270): the snapshot scheduler and pins, and the install listener. Null on the witness. */
    private final Snapshots.Runtime snapshots;
    private final SnapshotTransfer.Server snapshotServer;

    private HaMessaging(ReplicatedNode node, TcpTransport transport, FileLogStorage storage, String describe,
            Snapshots.Runtime snapshots, SnapshotTransfer.Server snapshotServer) {
        this.node = node;
        this.transport = transport;
        this.storage = storage;
        this.describe = describe;
        this.snapshots = snapshots;
        this.snapshotServer = snapshotServer;
    }

    /**
     * Opens the node and installs replication. Called reflectively by the composition root before the server mounts
     * the messaging module.
     *
     * @param settings  a resolved config ({@link HaMessagingSettings#replicate()} true)
     * @param stateDir  {@code ha.state_dir}, or its default
     * @param key       {@code ha.cluster_key}; {@code previous} the rotation key or null
     * @param messaging the {@link MessagingModule} on a data node; null on the witness
     * @throws IllegalArgumentException when the module does not match the role, or the voters are refused
     * @throws IOException when the transport cannot bind or the state cannot be opened
     */
    public static HaMessaging start(HaMessagingSettings settings, Path stateDir, byte[] key, byte[] previous,
            Object messaging, PrintStream err) throws IOException {
        Objects.requireNonNull(settings, "settings");
        if (!settings.replicate()) {
            throw new IllegalArgumentException("ha.messaging does not resolve to replication");
        }
        boolean witness = settings.selfIsWitness();
        if (witness != (messaging == null)) {
            throw new IllegalArgumentException(witness
                    ? "this node is the ha.members witness: it stores no messages, so turn messaging off on it"
                    : "ha.messaging needs messaging enabled on a data node (sjws.messaging.enabled=true)");
        }
        var voters = voters(settings);
        var self = nodeId(settings.self());
        var clusterKey = ClusterKey.of(key, previous);
        var election = settings.electionTimeout();
        var heartbeat = settings.heartbeat();
        Files.createDirectories(stateDir);
        var transport = TcpTransport.open(self, voters.members(), clusterKey, Clock.SYSTEM, PEER_QUEUE);
        var snap = settings.snapshot();
        FileLogStorage storage = null;
        ReplicatedNode node = null;
        Snapshots.Runtime runtime = null;
        SnapshotTransfer.Server server = null;
        try {
            // Rolling files: truncation behind a snapshot frees whole files (#270).
            storage = new FileLogStorage(stateDir.resolve("raft"), snap.logSegmentBytes());
            node = ReplicatedNode.open(self, voters, Clock.SYSTEM, storage, transport);
            if (witness) {
                RaftReplicator.openForWitness(node, settings.topics(), election, heartbeat);
            } else {
                var urls = new LinkedHashMap<String, String>();
                var peers = new LinkedHashMap<String, InetSocketAddress>();
                for (var m : settings.members()) {
                    if (!m.witness()) {
                        urls.put(m.id(), m.httpUrl());
                        peers.put(m.id(), new InetSocketAddress(m.host(), m.port() + snap.portOffset()));
                    }
                }
                var tKey = SnapshotTransfer.deriveKey(key);
                var tPrevious = previous == null ? null : SnapshotTransfer.deriveKey(previous);
                var settingsS = new Snapshots(snap.everyEntries(), snap.logTailEntries(), snap.chunkBytes(),
                        snap.installRate(), Snapshots.DEFAULT_CHECK);
                runtime = new Snapshots.Runtime(settingsS, stateDir, tKey, peers, System::currentTimeMillis);
                var me = settings.self();
                server = new SnapshotTransfer.Server(new InetSocketAddress(me.host(), me.port() + snap.portOffset()),
                        tKey, tPrevious, runtime.store, snap.installRate(), System::currentTimeMillis);
                ((MessagingModule) messaging).replication(new RaftReplicator(node, settings.topics(), urls,
                        stateDir.resolve("apply"), election, heartbeat, System::nanoTime, runtime));
            }
            node.begin(TICK);
        } catch (IOException | RuntimeException e) {
            closeSnapshots(runtime, server);
            closeQuietly(node, transport, storage);
            throw e;
        }
        var line = "ha messaging: replication on, node=" + settings.nodeId() + (witness ? " (witness)" : "")
                + ", voters=" + settings.members().size() + ", topics=" + settings.topics().size()
                + ", election_timeout=" + election.toMillis() + "ms, heartbeat=" + heartbeat.toMillis() + "ms";
        err.println(line);
        for (var m : voters.members()) {
            var host = m.address().getAddress();
            if (host == null || !host.isLoopbackAddress()) {
                err.println("WARN: ha messaging: Raft frames to " + m.id().name() + " (" + m.address()
                        + ") are authenticated (HMAC) but not encrypted; keep the member network private (#175)");
                break;
            }
        }
        if (!witness) {
            err.println("ha messaging: snapshots every " + snap.everyEntries() + " entries, " + snap.logTailEntries()
                    + " kept behind; install listener on port +" + snap.portOffset() + " at " + snap.installRate() + " B/s");
        }
        return new HaMessaging(node, transport, storage, line, runtime, server);
    }

    /** {@code VoterSet.dataAndWitness} with a witness, else {@code VoterSet.majority} (which refuses a bare pair). */
    static VoterSet voters(HaMessagingSettings settings) {
        var members = new ArrayList<Member>();
        var ids = new HashSet<NodeId>();
        Member witness = null;
        var data = new ArrayList<Member>();
        for (var m : settings.members()) {
            var member = new Member(nodeId(m), new SiteId("msg"), new InetSocketAddress(m.host(), m.port()), 1L,
                    MemberState.ALIVE, m.witness());
            members.add(member);
            ids.add(member.id());
            if (m.witness()) {
                witness = member;
            } else {
                data.add(member);
            }
        }
        if (witness != null && data.size() == 2) {
            return VoterSet.dataAndWitness(data.get(0), data.get(1), witness);
        }
        return VoterSet.majority(List.copyOf(members), ids);
    }

    static NodeId nodeId(HaMessagingSettings.Member m) {
        return new NodeId(m.wire(), m.id());
    }

    ReplicatedNode node() {
        return this.node;
    }

    @Override
    public String toString() {
        return this.describe;
    }

    /** Stops the node, then the transport and the log storage. The messaging module closes its replicas first. */
    @Override
    public void close() {
        closeSnapshots(this.snapshots, this.snapshotServer);
        closeQuietly(this.node, this.transport, this.storage);
    }

    private static void closeSnapshots(Snapshots.Runtime runtime, SnapshotTransfer.Server server) {
        if (server != null) {
            server.close();
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private static void closeQuietly(ReplicatedNode node, TcpTransport transport, FileLogStorage storage) {
        if (node != null) {
            node.close();
        }
        transport.close();
        if (storage != null) {
            try {
                storage.close();
            } catch (IOException e) {
                System.err.println("WARN: ha messaging: closing the Raft log: " + e);
            }
        }
    }
}
