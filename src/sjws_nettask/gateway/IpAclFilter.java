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
package sjws_nettask.gateway;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.routing.Cidr;

/**
 * Client-address allow/deny list. Lists are compiled to {@link Cidr} when the filter is built.
 * The request path uses {@link Request#peer()} and does not allocate for a known address.
 * The peer address is normalized before matching, so an IPv4-mapped IPv6 peer is compared
 * as IPv4. A missing peer is refused. When both lists are set, a deny match wins.
 * <p>
 * Params: {@code allow} and/or {@code deny}, each a list of addresses or CIDRs (one per line,
 * or comma-separated when there is no newline). At least one list is required.
 */
public final class IpAclFilter implements RequestFilter {

    static final int MAX_ENTRIES = 4_096;

    private final Cidr[] allow;
    private final Cidr[] deny;

    private IpAclFilter(Cidr[] allow, Cidr[] deny) {
        this.allow = allow;
        this.deny = deny;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        var allowRaw = spec.option("allow");
        var denyRaw = spec.option("deny");
        var allow = parseList(allowRaw, "allow");
        var deny = parseList(denyRaw, "deny");
        // An explicit empty allow list is not the same as an omitted one: omitted
        // means deny-only, empty would otherwise silently admit every non-denied peer.
        if (allowRaw != null && allow.length == 0) {
            throw new IllegalArgumentException("ip-acl allow is empty");
        }
        if (allow.length == 0 && deny.length == 0) {
            throw new IllegalArgumentException("ip-acl needs allow or deny");
        }
        return new IpAclFilter(allow, deny);
    }

    @Override
    public Response filter(Request request, Chain next) {
        if (allowed(request.peer())) {
            return next.proceed(request);
        }
        return StaticFileHandler.error(403, request.headOnly());
    }

    boolean allowed(InetSocketAddress peer) {
        if (peer == null || peer.getAddress() == null) {
            return false;
        }
        var addr = Cidr.addressBytes(peer.getAddress());
        if (matches(this.deny, addr)) {
            return false;
        }
        return this.allow.length == 0 || matches(this.allow, addr);
    }

    private static boolean matches(Cidr[] list, byte[] addr) {
        for (var cidr : list) {
            if (cidr.containsAddress(addr)) {
                return true;
            }
        }
        return false;
    }

    static Cidr[] parseList(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return new Cidr[0];
        }
        var parts = raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0
                ? raw.split("\\r?\\n", -1)
                : raw.split(",", -1);
        var out = new ArrayList<Cidr>();
        for (var part : parts) {
            var text = part.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            try {
                out.add(Cidr.parse(text));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("ip-acl " + name + ": " + e.getMessage(), e);
            }
            if (out.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("ip-acl " + name + " has more than " + MAX_ENTRIES + " entries");
            }
        }
        return out.toArray(Cidr[]::new);
    }
}
