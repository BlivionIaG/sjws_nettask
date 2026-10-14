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
package sjws_nettask.swarm.trace;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C Trace Context propagation for {@code /swarm/v1} (docs/design/agent-swarm.md §9, phase S2b).
 * <p>
 * Loaded only when the swarm is on <b>and</b> {@code -Dsjws.swarm.trace.enabled=true} (or
 * {@code SJWS_SWARM_TRACE_ENABLED=true}); with tracing off no class of this package is loaded and the swarm
 * handler allocates nothing for tracing. It never touches the core request path: only swarm endpoints call it.
 * <p>
 * This is propagation only: no spans are recorded or exported here (export is S4, swarm §9). A "span" is the
 * span id carried in a {@code traceparent}: ingress keeps the caller's, each claim mints a child span id under
 * the same trace id, and the reply carries that child.
 */
public final class SwarmTrace {

    /** The request header (lower case; header lookup is case-insensitive). */
    public static final String HEADER = "traceparent";
    /** Response header of a claim: one child traceparent per LEASE frame, in frame order, {@code -} for none. */
    public static final String LEASE_HEADER = "sjws-lease-traceparent";

    private static final int LEN = 55; // "00-" + 32 + "-" + 16 + "-" + 2

    /**
     * The normalized (lower-case) traceparent, or {@code ""} when {@code h} is absent or invalid. Invalid
     * values are dropped, never forwarded (W3C Trace Context §3.2: restart the trace). Accepts future versions
     * by their first 55 characters, as the spec requires, but always emits version {@code 00}.
     */
    public static String parse(String h) {
        if (h == null) {
            return "";
        }
        var s = h.trim();
        if (s.length() < LEN || (s.length() > LEN && s.charAt(LEN) != '-')) {
            return "";
        }
        if (s.charAt(2) != '-' || s.charAt(35) != '-' || s.charAt(52) != '-') {
            return "";
        }
        if (!hex(s, 0, 2) || !hex(s, 3, 35) || !hex(s, 36, 52) || !hex(s, 53, 55)) {
            return "";
        }
        var v = s.substring(0, 2).toLowerCase(java.util.Locale.ROOT);
        if ("ff".equals(v) || ("00".equals(v) && s.length() != LEN)) {
            return "";
        }
        if (zero(s, 3, 35) || zero(s, 36, 52)) {
            return "";
        }
        return ("00" + s.substring(2, LEN)).toLowerCase(java.util.Locale.ROOT);
    }

    /** A child of {@code parent}: same trace id and flags, a fresh non-zero span id; {@code ""} if no parent. */
    public static String child(String parent) {
        if (parent == null || parent.length() != LEN) {
            return "";
        }
        long span;
        do {
            span = ThreadLocalRandom.current().nextLong();
        } while (span == 0);
        var id = Long.toHexString(span);
        var sb = new StringBuilder(LEN).append(parent, 0, 36);
        for (int i = id.length(); i < 16; i++) {
            sb.append('0');
        }
        return sb.append(id).append(parent, 52, LEN).toString();
    }

    /** The 32-hex trace id of a valid traceparent. */
    public static String traceId(String tp) {
        return tp.substring(3, 35);
    }

    /** The 16-hex span (parent) id of a valid traceparent. */
    public static String spanId(String tp) {
        return tp.substring(36, 52);
    }

    private static boolean hex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return true;
    }

    private static boolean zero(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /** Startup log line; also loads this class eagerly when tracing is on (the class-load test's positive control). */
    public static String describe() {
        return "W3C trace propagation on (" + HEADER + ", claim child spans in " + LEASE_HEADER + ")";
    }

    private SwarmTrace() {
    }
}
