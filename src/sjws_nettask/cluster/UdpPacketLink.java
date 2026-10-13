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
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/** Non-blocking UDP. The heartbeat thread is the only caller, so the receive buffer is reused. */
public final class UdpPacketLink implements PacketLink {

    private final DatagramChannel channel;
    private final ByteBuffer receive = ByteBuffer.allocate(HeartbeatAuthenticator.PACKET_BYTES);
    private final InetSocketAddress local;

    public UdpPacketLink(InetSocketAddress bind) throws IOException {
        channel = DatagramChannel.open(StandardProtocolFamily.INET);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.configureBlocking(false);
        channel.bind(bind);
        local = (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    public void send(InetSocketAddress to, byte[] packet, int offset, int length) throws IOException {
        var src = ByteBuffer.wrap(packet, offset, length);
        int n = channel.send(src, to);
        if (n == 0 && length > 0) {
            throw new IOException("udp send blocked");
        }
    }

    @Override
    public Packet poll() throws IOException {
        receive.clear();
        var from = channel.receive(receive);
        if (from == null) {
            return null;
        }
        receive.flip();
        var data = new byte[receive.remaining()];
        receive.get(data);
        return new Packet((InetSocketAddress) from, data);
    }

    @Override
    public InetSocketAddress localAddress() {
        return local;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
