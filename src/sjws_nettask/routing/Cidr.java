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
package sjws_nettask.routing;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * An address block ("10.0.0.0/8", "::1/128", or a bare address) for {@code trusted_proxies}.
 * Parsed once; {@link #contains} does not allocate.
 */
public record Cidr(byte[] network, int bits) {

    private static final java.util.regex.Pattern IPV4 = java.util.regex.Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final java.util.regex.Pattern IPV6 = java.util.regex.Pattern.compile("[0-9a-fA-F:.]*:[0-9a-fA-F:.]*");

    public static Cidr parse(String text) {
        var t = text.trim();
        int slash = t.indexOf('/');
        var addr = slash < 0 ? t : t.substring(0, slash);
        if (!IPV4.matcher(addr).matches() && !IPV6.matcher(addr).matches()) {
            throw new IllegalArgumentException("not an IP address: " + text); // never a DNS lookup
        }
        byte[] net;
        try {
            net = IPV4.matcher(addr).matches() ? v4(addr, text) : InetAddress.getByName(addr).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad address: " + text, e);
        }
        int bits = net.length * 8;
        if (slash >= 0) {
            try {
                bits = Integer.parseInt(t.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad prefix length: " + text, e);
            }
            if (bits < 0 || bits > net.length * 8) {
                throw new IllegalArgumentException("bad prefix length: " + text);
            }
        }
        return new Cidr(net, bits);
    }

    /** Dotted quad without any resolver call (InetAddress would treat "999.1.1.1" as a host name). */
    private static byte[] v4(String addr, String text) {
        var parts = addr.split("\\.");
        var out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v = Integer.parseInt(parts[i]);
            if (v > 255) {
                throw new IllegalArgumentException("bad address: " + text);
            }
            out[i] = (byte) v;
        }
        return out;
    }

    public boolean contains(InetSocketAddress peer) {
        if (peer == null || peer.getAddress() == null) {
            return false;
        }
        var a = peer.getAddress().getAddress();
        if (a.length != this.network.length) {
            return false;
        }
        int full = this.bits / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != this.network[i]) {
                return false;
            }
        }
        int rest = this.bits % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xff << (8 - rest);
        return (a[full] & mask) == (this.network[full] & mask);
    }
}
