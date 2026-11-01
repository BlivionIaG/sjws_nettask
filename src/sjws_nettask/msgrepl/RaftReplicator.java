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
import java.util.regex.Pattern;

import sjws_nettask.cluster.ClusterNode;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.LedgerWriter;
import sjws_nettask.messaging.ledger.ReplicaHooks;
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
 * <p>Snapshots (#270): with a {@link Snapshots.Runtime} each replica snapshots its own ledger (the consensus image is
 * a small {@link MsgSnapshot} manifest; the data stays in the topic's segments), truncates the consensus log behind
 * it, and installs a peer's segments when it falls behind the log. Without one (tests, or the pre-#270 constructors)
 * the consensus log is never truncated. The group's own snapshot taker stays off ({@code snapshotEveryEntries =
 * Long.MAX_VALUE}): the replica records snapshots itself, at points its ledger has made durable. Producer dedup state
 * (M2, {@link ProducerTable}) is carried as snapshot section {@link MsgSnapshot#PRODUCERS}: a snapshot at index
 * {@code i} holds {@link ProducerTable#write} as applied through {@code i}, and install calls
 * {@link RaftReplica#restoreProducers} before applying {@code i + 1}.
 */
public final class RaftReplicator implements PartitionReplicator {

    /** Same rule as {@code LogTopics.NAME} / {@code HaMessagingSettings} topic names. */
    private static final Pattern TOPIC_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");


    /**
     * Feature bits this build advertises on every msg group it opens (#278): it applies producer entries and fails
     * closed on entries it cannot apply. A leader proposes the first producer entry only once every voter has them.
     */
    public static final int FEATURES = RaftReplica.FEATURE_PRODUCER_ENTRIES;

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
    private final java.util.function.LongSupplier nanoClock;
    private final Snapshots.Runtime snapshots;

    /**
     * @param topics   replicated topics ({@code __offsets} is always added)
     * @param urls     node name to its HTTP base URL, for 307 redirects
     * @param stateDir per-topic apply state ({@code <topic>.applied}: term stamp and consensus index per applied batch)
     */
    public RaftReplicator(ClusterNode node, Set<String> topics, Map<String, String> urls, Path stateDir,
            Duration electionTimeout, Duration heartbeat) throws IOException {
        this(node, topics, urls, stateDir, electionTimeout, heartbeat, System::nanoTime);
    }

    /** {@code nanoClock} must be the node's clock: a leader whose lease ran out stops accepting appends. */
    public RaftReplicator(ClusterNode node, Set<String> topics, Map<String, String> urls, Path stateDir,
            Duration electionTimeout, Duration heartbeat, java.util.function.LongSupplier nanoClock) throws IOException {
        this(node, topics, urls, stateDir, electionTimeout, heartbeat, nanoClock, null);
    }

    /** With {@code snapshots} (owned by the caller), replicas snapshot, truncate and install (see class doc). */
    RaftReplicator(ClusterNode node, Set<String> topics, Map<String, String> urls, Path stateDir, Duration electionTimeout,
            Duration heartbeat, java.util.function.LongSupplier nanoClock, Snapshots.Runtime snapshots) throws IOException {
        this.snapshots = snapshots;
        this.nanoClock = Objects.requireNonNull(nanoClock);
        this.node = Objects.requireNonNull(node);
        this.topics = Set.copyOf(topics);
        this.urls = Map.copyOf(urls);
        this.stateDir = Files.createDirectories(stateDir);
        // Group default RELAXED; group/each appends ask STRICT per append (sketch §2; RELAXED is crash-safe since #202).
        this.options = options(electionTimeout, heartbeat);
    }

    /**
     * The producer dedup table of {@code replica} ({@link ProducerTable#write} encoding), or null when it is not a
     * Raft replica. For tests and diagnostics on a quiescent group: the table belongs to the delivery thread. A #270
     * snapshot takes the same bytes on the delivery thread, at the snapshot's index.
     */
    public static byte[] producerTable(sjws_nettask.messaging.ledger.Replica replica) {
        if (!(replica instanceof RaftReplica r)) return null;
        var out = new java.io.ByteArrayOutputStream();
        try (var d = new java.io.DataOutputStream(out)) {
            var t = r.producers();
            (t != null ? t : new ProducerTable()).write(d); // null: no producer entry applied yet, the empty table
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Group options for msg groups: RELAXED default, the group\'s own snapshot taker off (see class doc). */
    public static GroupOptions options(Duration electionTimeout, Duration heartbeat) {
        return new GroupOptions(sjws_nettask.cluster.Durability.RELAXED, MAX_ENTRY_BYTES, electionTimeout, heartbeat,
                Long.MAX_VALUE);
    }

    /**
     * Opens every {@code msg/<topic>} group plus {@code msg/__offsets} on the witness so it can vote: no listener,
     * nothing applied (#217). Callers must pass the same topic set and election/heartbeat timings the data nodes use
     * ({@code ha.topics} / {@code HaMessaging}); a missing topic on the witness leaves that group with two data voters
     * and no tie-breaker. Refuses a non-witness node (a data node would join with a null listener and apply nothing)
     * and refuses topic names that fail the messaging NAME pattern or start with {@code __} (those collide with internal groups such as {@code __offsets}).
     */
    public static void openForWitness(ClusterNode node, Set<String> topics, Duration electionTimeout, Duration heartbeat) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(topics, "topics");
        if (!node.witness()) {
            throw new IllegalArgumentException(
                    "openForWitness requires a witness node (self is not the voter-without-log)");
        }
        for (var t : topics) {
            // Full NAME pattern (defense in depth; production also goes through HaMessagingSettings).
            if (t == null || !TOPIC_NAME.matcher(t).matches() || t.equals(".") || t.equals("..") || t.startsWith("__")) {
                throw new IllegalArgumentException("openForWitness: invalid topic name " + t
                        + " (must match [A-Za-z0-9._-]{1,128} and must not start with __)");
            }
        }
        var opts = options(electionTimeout, heartbeat);
        // The witness stores no entries, but it is a voter: it advertises the build's msg-group features like the data
        // nodes, so the leader's every-voter check (RaftReplica.FEATURE_PRODUCER_ENTRIES) covers the whole group.
        for (var t : topics) {
            node.openGroup(group(t), opts, null).advertiseFeatures(FEATURES);
        }
        node.openGroup(group("__offsets"), opts, null).advertiseFeatures(FEATURES);
    }

    @Override
    public Set<String> topics() {
        return this.topics;
    }

    @Override
    public Replica open(String topic, LedgerWriter local, Durability defaultMode, Consumer<List<byte[]>> onApplied) throws IOException {
        return new RaftReplica(this.node, group(topic), this.options, local, defaultMode, onApplied, this.urls,
                this.stateDir.resolve(topic + ".applied"), this.nanoClock);
    }

    @Override
    public Replica open(String topic, LedgerWriter local, Durability defaultMode, ReplicaHooks hooks) throws IOException {
        return new RaftReplica(this.node, group(topic), this.options, local, defaultMode, hooks, hooks.onApplied(), this.urls,
                this.stateDir.resolve(topic + ".applied"), this.nanoClock, this.snapshots);
    }

    @Override
    public void close() {
        // Replicas are closed with their topics; the node belongs to the HA bootstrap.
    }
}
