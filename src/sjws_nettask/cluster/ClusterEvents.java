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

/**
 * Off-path cluster signals. The observability workstream can adapt these to its exporter.
 * Never invoked from an HTTP request thread.
 */
public interface ClusterEvents {

    void memberState(NodeId id, MemberState from, MemberState to);

    void heartbeatRtt(NodeId peer, long nanos);

    ClusterEvents NONE = new ClusterEvents() {
        @Override
        public void memberState(NodeId id, MemberState from, MemberState to) {
        }

        @Override
        public void heartbeatRtt(NodeId peer, long nanos) {
        }
    };
}
