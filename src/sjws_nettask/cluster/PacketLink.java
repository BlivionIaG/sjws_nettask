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

import java.io.IOException;
import java.net.InetSocketAddress;

/** One datagram hop. UDP in production, an in-process network when tests inject loss. */
public interface PacketLink extends AutoCloseable {

    void send(InetSocketAddress to, byte[] packet, int offset, int length) throws IOException;

    /** Next inbound datagram, or null when the socket has nothing waiting. */
    Packet poll() throws IOException;

    InetSocketAddress localAddress();

    @Override
    void close() throws IOException;

    record Packet(InetSocketAddress from, byte[] data) {
    }
}
