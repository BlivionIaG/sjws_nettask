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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Voters for one group, opened with {@link QuorumPolicy#majority()}. Two data nodes plus a
 * witness is the HA shape. Two voters and no witness is rejected: that pair cannot lose one
 * node and still commit.
 */
public final class VoterSet {

    private final List<Member> members;
    private final Set<NodeId> voters;
    private final QuorumPolicy policy;

    private VoterSet(List<Member> members, Set<NodeId> voters, QuorumPolicy policy) {
        this.members = members;
        this.voters = voters;
        this.policy = policy;
    }

    /**
     * Two data nodes and one witness. The policy is a majority of those three, so any one
     * failure still leaves a quorum.
     */
    public static VoterSet dataAndWitness(Member dataA, Member dataB, Member witness) {
        Objects.requireNonNull(dataA, "dataA");
        Objects.requireNonNull(dataB, "dataB");
        Objects.requireNonNull(witness, "witness");
        if (dataA.witness() || dataB.witness() || !witness.witness()) {
            throw new IllegalArgumentException("witness");
        }
        if (dataA.id().equals(dataB.id()) || dataA.id().equals(witness.id()) || dataB.id().equals(witness.id())) {
            throw new IllegalArgumentException("members");
        }
        return majority(List.of(dataA, dataB, witness), Set.of(dataA.id(), dataB.id(), witness.id()));
    }

    /**
     * Majority over {@code voters}. A bare pair with no witness is rejected. One voter and a
     * larger set stay legal.
     */
    public static VoterSet majority(List<Member> members, Set<NodeId> voters) {
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(voters, "voters");
        if (voters.isEmpty()) {
            throw new IllegalArgumentException("voters");
        }
        var byId = new LinkedHashMap<NodeId, Member>();
        for (var member : members) {
            Objects.requireNonNull(member, "member");
            if (byId.put(member.id(), member) != null) {
                throw new IllegalArgumentException("duplicate " + member.id());
            }
        }
        int witnesses = 0;
        for (var id : voters) {
            var member = byId.get(id);
            if (member == null) {
                throw new IllegalArgumentException("voter is not a member");
            }
            if (member.witness()) {
                witnesses++;
            }
        }
        if (voters.size() == 2 && witnesses == 0) {
            throw new IllegalArgumentException("two voters require a witness");
        }
        return new VoterSet(List.copyOf(members), Set.copyOf(voters), QuorumPolicy.majority());
    }

    public List<Member> members() {
        return members;
    }

    public Set<NodeId> voters() {
        return voters;
    }

    public QuorumPolicy policy() {
        return policy;
    }
}
