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

import java.util.Objects;
import java.util.Set;

/**
 * Pure function of a voter set and an ack set. Callers pass the votes they already have;
 * the policy does not send RPCs.
 */
@FunctionalInterface
public interface QuorumPolicy {

    boolean hasQuorum(Set<NodeId> voters, Set<NodeId> acks);

    /** Votes required for a majority of {@code voters} (n/2+1). */
    static int majorityCount(int voters) {
        if (voters < 1) {
            throw new IllegalArgumentException("voters");
        }
        return voters / 2 + 1;
    }

    static QuorumPolicy majority() {
        return (voters, acks) -> {
            Objects.requireNonNull(voters, "voters");
            Objects.requireNonNull(acks, "acks");
            if (voters.isEmpty()) {
                return false;
            }
            int got = 0;
            for (var id : acks) {
                if (voters.contains(id)) {
                    got++;
                }
            }
            return got >= majorityCount(voters.size());
        };
    }

    /**
     * Two data nodes plus a witness. False unless the witness acked and at least one data
     * node acked ("no witness → no promotion"). A data node alone is not a quorum.
     */
    static QuorumPolicy witness(NodeId witness) {
        Objects.requireNonNull(witness, "witness");
        return (voters, acks) -> {
            Objects.requireNonNull(voters, "voters");
            Objects.requireNonNull(acks, "acks");
            if (!voters.contains(witness) || !acks.contains(witness)) {
                return false;
            }
            for (var id : acks) {
                if (!id.equals(witness) && voters.contains(id)) {
                    return true;
                }
            }
            return false;
        };
    }
}
