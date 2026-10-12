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
package sjws_nettask.h3;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.QuicTokenHandler;
import java.net.InetSocketAddress;

/**
 * No QUIC Retry and no address-validation tokens: every token a client presents is rejected (the
 * packet is dropped), so quiche keeps the RFC 9000 §8.1 anti-amplification limit (3x the bytes
 * received) until the handshake itself validates the client's address.
 * <p>
 * Replaces Netty's {@code InsecureQuicTokenHandler}, whose tokens ("netty" + client IP + dcid, no MAC)
 * could be forged for any spoofed source address (security review M3). Same behaviour as Netty's
 * package-private {@code NoQuicTokenHandler}, made explicit here. If Retry is ever wanted under load,
 * it needs an HMAC'd, timestamped token instead.
 */
final class NoRetryTokenHandler implements QuicTokenHandler {

    static final NoRetryTokenHandler INSTANCE = new NoRetryTokenHandler();

    private NoRetryTokenHandler() {
    }

    /** @return false: never send a Retry */
    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        return false;
    }

    /** @return -1: no token is ever valid */
    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        return -1;
    }

    @Override
    public int maxTokenLength() {
        return 0;
    }
}
