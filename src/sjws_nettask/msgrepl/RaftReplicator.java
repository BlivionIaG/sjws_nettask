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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import sjws_nettask.cluster.ClusterNode;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.PartitionReplicator;
import sjws_nettask.messaging.ledger.Replica;

/**
 * Messaging replication phase A (msg-replication-sketch): one consensus group {@code msg/<topic>} per partition plus
 * {@code msg/__offsets}, on the node's {@link ClusterNode}. The consensus log has its own storage (the node's
 * {@code LogStorage}) and wraps the messaging ledger: committed entries are applied in order into the local segmented
 * log, which fetches read.
 *
 * <p>Lives outside the messaging packages: the HA bootstrap constructs it and hands it to
 * {@code MessagingModule.replication}; with HA off this class never loads.
 *
 * <p>Known limitation while HA PR-3/4/5 (snapshot taker, truncation bound, chunked install) are open: msg groups never
 * snapshot or truncate their consensus log ({@code snapshotEveryEntries = Long.MAX_VALUE}), so it grows with the
 * produced data and a node that falls behind catches up by log replay only.
 */
public final class RaftReplicator implements PartitionReplicator {

    /** Group of a topic (and {@code msg/__offsets}). */
    public static GroupId group(String topic) {
        return new GroupId("msg/" + topic);
    }

    /** Batches are one consensus entry each; leave room above the produce body cap. */
    static final int MAX_ENTRY_BYTES = 64 << 20;

    private final ClusterNode node;
    private final Set<String> topics;
    private final Map<String, String> urls;
    private final Path stateDir;
    private final GroupOptions options;

    /**
     * @param topics   replicated topics ({@code __offsets} is always added)
     * @param urls     node name to its HTTP base URL, for 307 redirects
     * @param stateDir per-topic apply state ({@code <topic>.applied}: term stamp and consensus index per applied batch)
     */
    public RaftReplicator(ClusterNode node, Set<String> topics, Map<String, String> urls, Path stateDir,
            Duration electionTimeout, Duration heartbeat) throws IOException {
        this.node = Objects.requireNonNull(node);
        this.topics = Set.copyOf(topics);
        this.urls = Map.copyOf(urls);
        this.stateDir = Files.createDirectories(stateDir);
        // Group default RELAXED; group/each appends ask STRICT per append (sketch §2; RELAXED is crash-safe since #202).
        this.options = options(electionTimeout, heartbeat);
    }

    /** Group options for msg groups: RELAXED default, no snapshot or truncation (see class doc). */
    public static GroupOptions options(Duration electionTimeout, Duration heartbeat) {
        return new GroupOptions(sjws_nettask.cluster.Durability.RELAXED, MAX_ENTRY_BYTES, electionTimeout, heartbeat,
                Long.MAX_VALUE);
    }

    /** A witness (or any node without messaging) must still open the groups to vote: no listener, nothing applied. */
    public static void openForWitness(ClusterNode node, Set<String> topics, Duration electionTimeout, Duration heartbeat) {
        var opts = options(electionTimeout, heartbeat);
        for (var t : topics) node.openGroup(group(t), opts, null);
        node.openGroup(group("__offsets"), opts, null);
    }

    @Override
    public Set<String> topics() {
        return this.topics;
    }

    @Override
    public Replica open(String topic, LedgerWriter local, Durability defaultMode, Consumer<List<byte[]>> onApplied) throws IOException {
        return new RaftReplica(this.node, group(topic), this.options, local, defaultMode, onApplied, this.urls,
                this.stateDir.resolve(topic + ".applied"));
    }

    @Override
    public void close() {
        // Replicas are closed with their topics; the node belongs to the HA bootstrap.
    }
}
