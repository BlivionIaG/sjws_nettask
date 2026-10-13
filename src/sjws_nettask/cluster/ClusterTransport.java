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

import java.util.function.BiConsumer;

/**
 * Carries {@link SimTransport.Frame}s between nodes. {@link SimTransport} is the in-process one;
 * {@link TcpTransport} crosses processes. Handlers run only inside {@link #deliver}, on the
 * caller's thread, so a Raft group sees one thread.
 */
public interface ClusterTransport {

    ClusterRpc attach(NodeId self, BiConsumer<NodeId, SimTransport.Frame> handler);

    /** Run up to {@code max} queued inbound handlers. Returns how many ran. */
    int deliver(int max);

    /** Drop {@code self}'s handler and queued frames so it can attach again. No-op by default. */
    default void detach(NodeId self) {}
}
