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

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hash tables over the routes of one {@link Router}, built once. A lookup returns the lowest
 * declaration index among the routes that match, which is what a first-match linear scan returns,
 * and does not allocate.
 * <p>
 * Routes are grouped by host class: host-less, exact host and {@code *.suffix}. Each group keys its
 * routes by exact prefix string and keeps the lowest index per prefix (a later route with the same
 * host and prefix can never win). A lookup probes the prefixes of the target that end at a length
 * some route uses and that satisfy {@link Router.Route#matchesPath}'s boundary rule, in every host
 * group the request can belong to.
 */
final class CompiledRoutes {

    private final PathTable any;
    private final HostTable exact;
    private final HostTable wild;
    /** Distinct wildcard suffix lengths (".example.com" is 12), ascending. */
    private final int[] wildLens;

    CompiledRoutes(Router.Route[] routes) {
        int seed = ThreadLocalRandom.current().nextInt() | 1;
        var anyB = new PathTable.Builder(seed);
        var exactB = new HostTable.Builder(seed);
        var wildB = new HostTable.Builder(seed);
        var lens = new java.util.TreeSet<Integer>();
        for (int i = 0; i < routes.length; i++) {
            var r = routes[i];
            if (r.host() == null) {
                anyB.add(r.prefix(), i);
                if (r.prefix().equals("/")) {
                    break; // host-less catch-all: no later route can match
                }
            } else if (r.host().startsWith("*.")) {
                var suffix = r.host().substring(1);
                wildB.paths(suffix).add(r.prefix(), i);
                lens.add(suffix.length());
            } else {
                exactB.paths(r.host()).add(r.prefix(), i);
            }
        }
        this.any = anyB.build();
        this.exact = exactB.build();
        this.wild = wildB.build();
        this.wildLens = lens.stream().mapToInt(Integer::intValue).toArray();
    }

    /** @param host the Host header or null; @return lowest matching index, or -1 */
    int match(String host, String target, int pathEnd) {
        int best = this.any.match(target, pathEnd, Integer.MAX_VALUE);
        if (host != null) {
            int end = HostTable.end(host);
            var paths = this.exact.get(host, 0, end);
            if (paths != null) {
                best = paths.match(target, pathEnd, best);
            }
            for (int len : this.wildLens) {
                if (len >= end) {
                    break;
                }
                paths = this.wild.get(host, end - len, end);
                if (paths != null) {
                    best = paths.match(target, pathEnd, best);
                }
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    /** Case-sensitive prefix strings to the lowest route index using each. */
    static final class PathTable {

        static final PathTable EMPTY = new Builder(1).build();

        private final String[] keys;
        private final int[] hashes;
        private final int[] index;
        private final int mask;
        private final int seed;
        /** {@code hasLen[n]}: some key has length n. */
        private final boolean[] hasLen;

        private PathTable(String[] keys, int[] hashes, int[] index, int seed, boolean[] hasLen) {
            this.keys = keys;
            this.hashes = hashes;
            this.index = index;
            this.mask = keys.length - 1;
            this.seed = seed;
            this.hasLen = hasLen;
        }

        /** @return min(best, lowest index of a key that matches target) */
        int match(String target, int pathEnd, int best) {
            int max = Math.min(pathEnd, this.hasLen.length - 1);
            for (int n = 1; n <= max; n++) {
                if (!this.hasLen[n]) {
                    continue;
                }
                // Router.Route.matchesPath: n == pathEnd, the prefix ends in '/', or '/' follows it
                if (n != pathEnd && target.charAt(n - 1) != '/' && target.charAt(n) != '/') {
                    continue;
                }
                int i = find(target, n);
                if (i >= 0 && i < best) {
                    best = i;
                }
            }
            return best;
        }

        private int find(String target, int n) {
            int h = hash(this.seed, target, 0, n);
            for (int slot = h & this.mask;; slot = (slot + 1) & this.mask) {
                var key = this.keys[slot];
                if (key == null) {
                    return -1;
                }
                if (this.hashes[slot] == h && key.length() == n && target.startsWith(key)) {
                    return this.index[slot];
                }
            }
        }

        static int hash(int seed, String s, int from, int to) {
            int h = seed;
            for (int i = from; i < to; i++) {
                h = h * 31 + s.charAt(i);
            }
            return mix(h);
        }

        static final class Builder {
            private final int seed;
            private final java.util.LinkedHashMap<String, Integer> first = new java.util.LinkedHashMap<>();

            Builder(int seed) {
                this.seed = seed;
            }

            void add(String prefix, int i) {
                this.first.putIfAbsent(prefix, i);
            }

            PathTable build() {
                int cap = Integer.highestOneBit(Math.max(2, this.first.size() * 2 - 1)) << 1;
                var keys = new String[cap];
                var hashes = new int[cap];
                var index = new int[cap];
                int maxLen = 0;
                for (var k : this.first.keySet()) {
                    maxLen = Math.max(maxLen, k.length());
                }
                var hasLen = new boolean[maxLen + 1];
                for (var e : this.first.entrySet()) {
                    var k = e.getKey();
                    hasLen[k.length()] = true;
                    int h = hash(this.seed, k, 0, k.length());
                    int slot = h & (cap - 1);
                    while (keys[slot] != null) {
                        slot = (slot + 1) & (cap - 1);
                    }
                    keys[slot] = k;
                    hashes[slot] = h;
                    index[slot] = e.getValue();
                }
                return new PathTable(keys, hashes, index, this.seed, hasLen);
            }
        }
    }

    /** Lower-case host names (or ".suffix") to their {@link PathTable}, matched ignoring case. */
    static final class HostTable {

        private final String[] keys;
        private final int[] hashes;
        private final PathTable[] paths;
        private final int mask;
        private final int seed;

        private HostTable(String[] keys, int[] hashes, PathTable[] paths, int seed) {
            this.keys = keys;
            this.hashes = hashes;
            this.paths = paths;
            this.mask = keys.length - 1;
            this.seed = seed;
        }

        /** Same as {@link Router#hostEnd}. */
        static int end(String host) {
            return Router.hostEnd(host);
        }

        PathTable get(String host, int from, int to) {
            int n = to - from;
            int h = hash(this.seed, host, from, to);
            for (int slot = h & this.mask;; slot = (slot + 1) & this.mask) {
                var key = this.keys[slot];
                if (key == null) {
                    return null;
                }
                if (this.hashes[slot] == h && key.length() == n && host.regionMatches(true, from, key, 0, n)) {
                    return this.paths[slot];
                }
            }
        }

        /** Consistent with {@code regionMatches(true, ...)}: equal ignoring case implies equal fold. */
        static char fold(char c) {
            return Character.toLowerCase(Character.toUpperCase(c));
        }

        static int hash(int seed, String s, int from, int to) {
            int h = seed;
            for (int i = from; i < to; i++) {
                h = h * 31 + fold(s.charAt(i));
            }
            return mix(h);
        }

        static final class Builder {
            private final int seed;
            private final java.util.LinkedHashMap<String, PathTable.Builder> hosts = new java.util.LinkedHashMap<>();

            Builder(int seed) {
                this.seed = seed;
            }

            PathTable.Builder paths(String key) {
                var paths = this.hosts.get(key);
                if (paths == null) {
                    paths = new PathTable.Builder(this.seed);
                    this.hosts.put(key, paths);
                }
                return paths;
            }

            HostTable build() {
                int cap = Integer.highestOneBit(Math.max(2, this.hosts.size() * 2 - 1)) << 1;
                var keys = new String[cap];
                var hashes = new int[cap];
                var paths = new PathTable[cap];
                for (var e : this.hosts.entrySet()) {
                    var k = e.getKey();
                    int h = hash(this.seed, k, 0, k.length());
                    int slot = h & (cap - 1);
                    while (keys[slot] != null) {
                        slot = (slot + 1) & (cap - 1);
                    }
                    keys[slot] = k;
                    hashes[slot] = h;
                    paths[slot] = e.getValue().build();
                }
                return new HostTable(keys, hashes, paths, this.seed);
            }
        }
    }

    static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        return h ^ (h >>> 16);
    }
}
