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
package sjws_nettask.core;

/**
 * Path pattern for a per-route filter. Matching uses the request path (query and fragment cut off)
 * after percent-decoding and dot-segment normalisation, the way the file handler and upstreams
 * read it: {@code /%61dmin}, {@code //admin} and {@code /x/../admin} all match {@code /admin}.
 * A path whose meaning differs between readers (an encoded {@code /} or {@code \}, a backslash, a
 * {@code ;} path parameter, a {@code %} left after decoding, i.e. double encoding, a malformed
 * escape, or {@code ..} above the root) is ambiguous and matches every non-{@code *} pattern, so a
 * filter fails closed instead of being skipped (the same spellings {@code routing.Router} answers
 * 400).
 * <ul>
 * <li>{@code null}, blank or {@code *} — every request</li>
 * <li>{@code /exact} — the path equals {@code /exact}</li>
 * <li>{@code /prefix/*} — the path equals {@code /prefix} or starts with {@code /prefix/}</li>
 * <li>{@code /*} — every path that starts with {@code /}</li>
 * </ul>
 * A {@code *} anywhere except that trailing form is rejected. A plain path (no {@code %}, {@code \},
 * {@code ;}, empty or dot segment) is matched in place without allocating.
 */
public final class RoutePattern {

    private enum Kind {
        ALL, EXACT, PREFIX
    }

    private static final RoutePattern ALL = new RoutePattern(Kind.ALL, "");

    private final Kind kind;
    /** Exact path, or the prefix with the trailing {@code /*} removed. Empty for {@link Kind#ALL}. */
    private final String path;

    private RoutePattern(Kind kind, String path) {
        this.kind = kind;
        this.path = path;
    }

    public static RoutePattern all() {
        return ALL;
    }

    /**
     * @param route raw route from configuration; null, blank and {@code *} match everything
     * @throws IllegalArgumentException when the pattern is not one of the forms above
     */
    public static RoutePattern parse(String route) {
        if (route == null) {
            return ALL;
        }
        var r = route.trim();
        if (r.isEmpty() || "*".equals(r)) {
            return ALL;
        }
        int star = r.indexOf('*');
        if (star >= 0) {
            // The only wildcard is a trailing "/*" (one star, nothing after it).
            if (star != r.length() - 1 || !r.endsWith("/*")) {
                throw new IllegalArgumentException("bad route: " + route);
            }
            var prefix = r.substring(0, r.length() - 2);
            if (prefix.isEmpty()) {
                prefix = "/"; // "/*" is every origin-form path
            }
            if (!prefix.startsWith("/")) {
                throw new IllegalArgumentException("bad route: " + route);
            }
            return new RoutePattern(Kind.PREFIX, prefix);
        }
        if (!r.startsWith("/")) {
            throw new IllegalArgumentException("bad route: " + route);
        }
        return new RoutePattern(Kind.EXACT, r);
    }

    public boolean isAll() {
        return this.kind == Kind.ALL;
    }

    /** @param target origin-form request target (path, optional query, optional fragment) */
    public boolean matches(String target) {
        if (this.kind == Kind.ALL) {
            return true;
        }
        int end = pathEnd(target);
        if (end == 0 || target.charAt(0) != '/') {
            return false; // not origin-form ("*", empty)
        }
        if (isPlain(target, end)) {
            return matchesPath(target, end);
        }
        var canonical = canonical(target, end);
        return canonical == null || matchesPath(canonical, canonical.length());
    }

    private boolean matchesPath(String p, int end) {
        if (this.kind == Kind.EXACT) {
            return end == this.path.length() && p.regionMatches(0, this.path, 0, end);
        }
        int n = this.path.length();
        if (end < n || !p.regionMatches(0, this.path, 0, n)) {
            return false;
        }
        // "/*" is stored as "/": every path that starts with "/" matches, including "/x".
        if (n == 1) {
            return true;
        }
        return end == n || p.charAt(n) == '/';
    }

    /** True if {@code target[0, end)} needs no decoding or normalisation. */
    static boolean isPlain(String target, int end) {
        if (end == 0 || target.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < end; i++) {
            char c = target.charAt(i);
            if (c == '%' || c == '\\' || c == ';') {
                return false;
            }
            if (c == '/' && i + 1 < end) {
                char d = target.charAt(i + 1);
                if (d == '/') {
                    return false;
                }
                if (d == '.') {
                    int k = i + 2;
                    if (k < end && target.charAt(k) == '.') {
                        k++;
                    }
                    if (k == end || target.charAt(k) == '/') {
                        return false; // "/." or "/.." segment
                    }
                }
            }
        }
        return true;
    }

    /**
     * Decoded, normalised path of {@code target[0, end)}: one percent-decoding pass, empty and
     * {@code .} segments dropped, {@code ..} resolved.
     *
     * @return the path (starts with "/"), or null if ambiguous (see the class comment)
     */
    static String canonical(String target, int end) {
        if (end == 0 || target.charAt(0) != '/') {
            return null;
        }
        // 1. one percent-decoding pass into bytes
        var buf = new byte[end];
        int n = 0;
        boolean nonAscii = false;
        for (int i = 0; i < end; i++) {
            char c = target.charAt(i);
            int b;
            if (c == '%') {
                if (i + 2 >= end) {
                    return null;
                }
                int hi = Character.digit(target.charAt(i + 1), 16);
                int lo = Character.digit(target.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                b = hi << 4 | lo;
                if (b == '/' || b == '%') {
                    return null; // encoded separator, double encoding
                }
                i += 2;
            } else if (c >= 0x80) {
                return null; // targets are ASCII on the wire
            } else {
                b = c;
            }
            if (b == '\\' || b == ';' || b == 0) {
                return null; // backslash, path parameter, NUL (raw or encoded)
            }
            nonAscii |= b >= 0x80;
            buf[n++] = (byte) b;
        }
        // 2. dot segments, in place: out[0, w) is the normalised prefix, always starting with '/'
        int w = 0;
        int i = 0;
        boolean trailing = false;
        while (i < n) {
            int j = i + 1; // buf[i] == '/'
            int segEnd = j;
            while (segEnd < n && buf[segEnd] != '/') {
                segEnd++;
            }
            int len = segEnd - j;
            if (len == 0 || len == 1 && buf[j] == '.') {
                trailing = true;
            } else if (len == 2 && buf[j] == '.' && buf[j + 1] == '.') {
                if (w == 0) {
                    return null; // above the root
                }
                do {
                    w--;
                } while (buf[w] != '/');
                trailing = true;
            } else {
                buf[w++] = '/';
                System.arraycopy(buf, j, buf, w, len);
                w += len;
                trailing = false;
            }
            i = segEnd;
        }
        if (w == 0 || trailing) {
            buf[w++] = '/';
        }
        if (!nonAscii) {
            return new String(buf, 0, w, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(buf, 0, w)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    static int pathEnd(String target) {
        int n = target.length();
        for (int i = 0; i < n; i++) {
            char c = target.charAt(i);
            if (c == '?' || c == '#') {
                return i;
            }
        }
        return n;
    }
}
