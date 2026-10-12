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
package sjws_nettask.config;

/**
 * One route, in declaration order. Maps onto {@code Route(host, prefix, rewrite, handler)} from
 * the proxy workstream: {@code host} is null when the route matches every host, {@code rewrite}
 * is null when the path is left unchanged, and {@link Action} is the handler kind. Matching is
 * allocation-free and is not called from a transport until that workstream builds a router.
 *
 * @param host lower-case exact host, or the lower-case pattern including {@code *.} when
 *        {@code wildcard} is true; null matches every host
 * @param hostSuffix for a wildcard, the dotted suffix ({@code *.example.com} stores
 *        {@code .example.com}); null otherwise
 */
public record RouteSpec(String host, boolean wildcard, String hostSuffix, String prefix, String rewrite,
        RouteSpec.Action action) {

    /** Exactly one of proxy, redirect or static. */
    public sealed interface Action permits Action.Proxy, Action.Redirect, Action.Static {

        /** {@code proxy: <upstream name>} resolved to the upstream record. */
        record Proxy(Upstream upstream) implements Action {
        }

        /**
         * @param status one of 301, 302, 303, 307, 308
         * @param keepPath append the request path after {@code location}
         */
        record Redirect(int status, String location, boolean keepPath) implements Action {
        }

        /** {@code static: true}: the document root. */
        record Static() implements Action {
        }
    }

    /** Segment-boundary prefix match on the raw (still percent-encoded) request target. */
    public boolean matchesPath(String target) {
        if (target == null || target.isEmpty() || target.charAt(0) != '/') {
            return false;
        }
        if (this.prefix.equals("/")) {
            return true;
        }
        int length = this.prefix.length();
        if (target.length() < length || !target.regionMatches(0, this.prefix, 0, length)) {
            return false;
        }
        if (target.length() == length) {
            return true;
        }
        char next = target.charAt(length);
        return next == '/' || next == '?';
    }

    /** Exact or {@code *.suffix} host. A port in the header is ignored. Null pattern matches all. */
    public boolean matchesHost(String hostHeader) {
        if (this.host == null) {
            return true;
        }
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }
        int end = hostEnd(hostHeader);
        if (end <= 0) {
            return false;
        }
        if (this.wildcard) {
            int suffix = this.hostSuffix.length();
            return end > suffix && hostHeader.regionMatches(true, end - suffix, this.hostSuffix, 0, suffix);
        }
        int start = 0;
        if (hostHeader.charAt(0) == '[') {
            if (hostHeader.charAt(end - 1) != ']') {
                return false;
            }
            start = 1;
            end--;
        }
        int length = end - start;
        return length == this.host.length() && hostHeader.regionMatches(true, start, this.host, 0, length);
    }

    private static int hostEnd(String header) {
        if (header.charAt(0) == '[') {
            int close = header.indexOf(']');
            return close < 0 ? -1 : close + 1;
        }
        int colon = header.indexOf(':');
        return colon < 0 ? header.length() : colon;
    }
}
