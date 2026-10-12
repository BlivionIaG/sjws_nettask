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

import java.time.InstantSource;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entity tags (RFC 9110 §8.8.3) for static files, and {@code If-None-Match} evaluation.
 * <p>
 * The opaque tag is {@code "<file key hash>-<mtime ns>-<size>"} (hex). It is <b>strong</b>, except
 * for a file version first seen while it was modified less than one second ago: on file systems with
 * a coarse mtime a second write within that second could keep mtime and size, so such a version gets
 * a weak tag ({@code W/}, as Apache does). That decision is remembered per version ({@link Policy}),
 * so the same version keeps the same tag while it is unchanged (both transports, cache or not).
 */
public final class EntityTag {

    private EntityTag() {
    }

    /** @return the strong tag (quoted) of a file version */
    public static String strong(Validator v) {
        return "\"" + Integer.toHexString(Objects.hashCode(v.fileKey())) + "-" + Long.toHexString(v.mtimeNanos())
                + "-" + Long.toHexString(v.size()) + "\"";
    }

    /**
     * Weak comparison of {@code If-None-Match} with the current tag (RFC 9110 §13.1.2): {@code *}
     * matches any current representation; otherwise any listed tag whose opaque part equals the
     * current one, with or without {@code W/} on either side. A malformed element ends the list
     * (what was parsed so far still counts).
     *
     * @param etag the current tag, e.g. {@code "abc"} or {@code W/"abc"}
     */
    public static boolean noneMatchHits(String ifNoneMatch, String etag) {
        var current = opaque(etag);
        var s = ifNoneMatch;
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == ',') {
                i++;
                continue;
            }
            if (c == '*') {
                return true;
            }
            if (c == 'W' && i + 1 < n && s.charAt(i + 1) == '/') {
                i += 2;
            }
            if (i >= n || s.charAt(i) != '"') {
                return false;
            }
            int end = s.indexOf('"', i + 1);
            if (end < 0) {
                return false;
            }
            if (s.regionMatches(i, current, 0, current.length()) && end + 1 - i == current.length()) {
                return true;
            }
            i = end + 1;
        }
        return false;
    }

    /** @return the quoted opaque part ({@code W/} removed) */
    static String opaque(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }

    /**
     * Decides strong or weak per file version, remembering versions that were young when first
     * seen (bounded; when full it is cleared, which can only turn weak tags strong for old files).
     */
    public static final class Policy {

        static final long YOUNG_MILLIS = 1000;
        static final int MAX_REMEMBERED = 4096;

        private final InstantSource clock;
        private final ConcurrentHashMap<Validator, Boolean> young = new ConcurrentHashMap<>();

        public Policy(InstantSource clock) {
            this.clock = clock;
        }

        /** @return the tag for this version, {@code W/}-prefixed if it was first seen while young */
        public String tag(Validator v) {
            var strong = strong(v);
            return weak(v) ? "W/" + strong : strong;
        }

        boolean weak(Validator v) {
            if (this.young.containsKey(v)) {
                return true;
            }
            long ageMillis = this.clock.millis() - Math.floorDiv(v.mtimeNanos(), 1_000_000L);
            if (ageMillis >= YOUNG_MILLIS) {
                return false; // old stays old: no need to remember it
            }
            if (this.young.size() >= MAX_REMEMBERED) {
                this.young.clear();
            }
            this.young.put(v, Boolean.TRUE);
            return true;
        }

        int remembered() {
            return this.young.size();
        }
    }
}
