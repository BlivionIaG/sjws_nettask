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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable membership snapshot. Listeners and the request path share it without locking. */
public record MembershipView(NodeId self, List<Member> members) {

    public MembershipView {
        Objects.requireNonNull(self, "self");
        members = List.copyOf(members);
    }

    public Optional<Member> member(NodeId id) {
        for (var member : members) {
            if (member.id().equals(id)) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public Optional<Member> memberByWire(int wire) {
        for (var member : members) {
            if (member.id().wire() == wire) {
                return Optional.of(member);
            }
        }
        return Optional.empty();
    }

    public List<Member> others() {
        var out = new ArrayList<Member>(members.size());
        for (var member : members) {
            if (!member.id().equals(self)) {
                out.add(member);
            }
        }
        return List.copyOf(out);
    }

    public int count(MemberState state) {
        int n = 0;
        for (var member : members) {
            if (member.state() == state) {
                n++;
            }
        }
        return n;
    }
}
