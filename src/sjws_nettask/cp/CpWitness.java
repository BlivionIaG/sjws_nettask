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
package sjws_nettask.cp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.ReplicationGroup;
import sjws_nettask.cluster.VoterSet;

/**
 * Vote-only witness voter of {@code cp-config} (control-plane-hosting.md §6.1, HA). It joins the group as a
 * witness member: it keeps no log, durably records only the last acked {@code (index, term)}
 * ({@link sjws_nettask.cluster.LogStorage#witnessIndex}), votes, never campaigns or leads, and serves no reads.
 * It never names {@link KeyRing}, {@link CpStore}, {@link WatchServer} or {@link AdminServer}; ClassLoadTest
 * asserts a witness host loads none of them.
 */
public final class CpWitness implements AutoCloseable {

    private final ReplicatedNode node;
    private final ReplicationGroup group;
    private final FileLogStorage storage;

    private CpWitness(ReplicatedNode node, ReplicationGroup group, FileLogStorage storage) {
        this.node = node;
        this.group = group;
        this.storage = storage;
    }

    /** @throws IllegalArgumentException when {@code self} is not the witness of {@code voters} */
    public static CpWitness open(Path stateDir, NodeId self, VoterSet voters, Clock clock, ClusterTransport transport)
            throws IOException {
        boolean isWitness = voters.members().stream().anyMatch(m -> m.id().equals(self) && m.witness());
        if (!isWitness || !voters.voters().contains(self)) {
            throw new IllegalArgumentException("CpWitness needs self to be the witness voter: " + self);
        }
        var storage = new FileLogStorage(stateDir.resolve("cp-config"));
        ReplicatedNode node = null;
        try {
            node = ReplicatedNode.open(self, voters, clock, storage, transport);
            // A witness applies nothing: committed entries are not stored or served here.
            var group = node.openGroup(CpGroup.GROUP, CpGroup.options(), entry -> { });
            return new CpWitness(node, group, storage);
        } catch (RuntimeException e) {
            if (node != null) {
                node.close();
            }
            storage.close();
            throw e;
        }
    }

    /** Ticks on a virtual thread, like {@link CpStore#start()}. */
    public void start() {
        node.begin(Duration.ofMillis(50));
    }

    public void drive() {
        node.drive();
    }

    /** Always false: a witness never leads. */
    public boolean isLeader() {
        return group.isLeader();
    }

    public long term() {
        return group.term();
    }

    /** Last durably acked index (the witness cursor), not a log length. */
    public long ackedIndex() throws IOException {
        return storage.witnessIndex(CpGroup.GROUP);
    }

    public long ackedTerm() throws IOException {
        return storage.witnessTerm(CpGroup.GROUP);
    }

    @Override
    public void close() {
        node.close();
        try {
            storage.close();
        } catch (IOException e) {
            System.getLogger(CpWitness.class.getName()).log(System.Logger.Level.WARNING,
                    "cp witness storage close failed: " + e.getMessage());
        }
    }
}
