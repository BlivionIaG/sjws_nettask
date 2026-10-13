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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;

/**
 * Weighted / canary backends of one route (design §13). Pins are checked first: a request whose
 * header or cookie matches a backend's pin goes to that backend. Otherwise one backend is picked
 * by weight from a precomputed alias table (Vose): O(1), no allocation. With {@code sticky}, the
 * pick hashes that header or cookie value instead of drawing at random, so a client keeps its
 * backend while the weights stay the same. Only built when a route lists {@code backends}; a plain
 * {@code proxy:} route never sees this class.
 */
public final class WeightedHandler implements Handler {

    /** A header or cookie test. {@code value} null means "present". */
    public record Match(boolean cookie, String name, String value) {
        public Match {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("match name is empty");
            }
            name = cookie ? name : name.toLowerCase(Locale.ROOT);
        }

        /** @return the header or cookie value, or null */
        String find(RequestHeaders headers) {
            var values = headers.values(cookie ? "cookie" : this.name);
            for (int i = 0; i < values.size(); i++) {
                var v = cookie ? cookieValue(values.get(i), this.name) : values.get(i);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }

        boolean test(RequestHeaders headers) {
            var v = find(headers);
            return v != null && (this.value == null || this.value.equals(v.trim()));
        }
    }

    /** @param weight relative share, 0 when only reachable through {@code pin} */
    public record Backend(Handler handler, int weight, Match pin) {
        public Backend {
            Objects.requireNonNull(handler, "handler");
            if (weight < 0) {
                throw new IllegalArgumentException("weight must be >= 0");
            }
        }
    }

    private final Handler[] handlers;
    private final Match[] pins; // null when no backend is pinned
    private final Match sticky;
    private final double[] prob;
    private final int[] alias;

    public WeightedHandler(List<Backend> backends, Match sticky) {
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("no backends");
        }
        int n = backends.size();
        this.handlers = new Handler[n];
        var pins = new Match[n];
        boolean anyPin = false;
        long total = 0;
        for (int i = 0; i < n; i++) {
            var b = backends.get(i);
            this.handlers[i] = b.handler();
            pins[i] = b.pin();
            anyPin |= b.pin() != null;
            total += b.weight();
        }
        if (total <= 0) {
            throw new IllegalArgumentException("total weight must be > 0");
        }
        this.pins = anyPin ? pins : null;
        this.sticky = sticky;
        this.prob = new double[n];
        this.alias = new int[n];
        // Vose: scaled weights, small and large work lists
        var scaled = new double[n];
        var small = new int[n];
        var large = new int[n];
        int s = 0;
        int l = 0;
        for (int i = 0; i < n; i++) {
            scaled[i] = (double) backends.get(i).weight() * n / total;
            if (scaled[i] < 1.0) {
                small[s++] = i;
            } else {
                large[l++] = i;
            }
        }
        while (s > 0 && l > 0) {
            int lo = small[--s];
            int hi = large[--l];
            this.prob[lo] = scaled[lo];
            this.alias[lo] = hi;
            scaled[hi] = scaled[hi] + scaled[lo] - 1.0;
            if (scaled[hi] < 1.0) {
                small[s++] = hi;
            } else {
                large[l++] = hi;
            }
        }
        while (l > 0) {
            int i = large[--l];
            this.prob[i] = 1.0;
            this.alias[i] = i;
        }
        while (s > 0) { // rounding leftovers
            int i = small[--s];
            this.prob[i] = 1.0;
            this.alias[i] = i;
        }
    }

    @Override
    public Response handle(Request request) {
        return this.handlers[select(request.headers())].handle(request);
    }

    /** Index of the backend for these headers. Package-private for the distribution tests. */
    int select(RequestHeaders headers) {
        if (this.pins != null) {
            for (int i = 0; i < this.pins.length; i++) {
                var pin = this.pins[i];
                if (pin != null && pin.test(headers)) {
                    return i;
                }
            }
        }
        if (this.sticky != null) {
            var key = this.sticky.find(headers);
            if (key != null) {
                return pick(mix(key.hashCode() * 0x9E3779B97F4A7C15L ^ key.length()));
            }
        }
        return pick(ThreadLocalRandom.current().nextLong());
    }

    /** High 32 bits choose the column, low 32 bits the coin. */
    int pick(long bits) {
        int n = this.prob.length;
        int column = (int) (((bits >>> 32) * n) >>> 32);
        double coin = (bits & 0xFFFF_FFFFL) * 0x1.0p-32;
        return coin < this.prob[column] ? column : this.alias[column];
    }

    /** Exact probability of backend {@code i} implied by the alias table, for tests. */
    double share(int i) {
        int n = this.prob.length;
        double mass = this.prob[i];
        for (int j = 0; j < n; j++) {
            if (this.alias[j] == i && j != i) {
                mass += 1.0 - this.prob[j];
            }
        }
        return mass / n;
    }

    int size() {
        return this.handlers.length;
    }

    /** SplitMix64 finalizer. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** RFC 6265 {@code name=value; name2=value2}: the value of {@code name}, or null. Allocates only on a hit. */
    static String cookieValue(String header, String name) {
        int i = 0;
        int len = header.length();
        while (i < len) {
            while (i < len && (header.charAt(i) == ' ' || header.charAt(i) == ';')) {
                i++;
            }
            int eq = header.indexOf('=', i);
            int end = header.indexOf(';', i);
            if (end < 0) {
                end = len;
            }
            if (eq > 0 && eq < end && eq - i == name.length() && header.regionMatches(i, name, 0, name.length())) {
                int vs = eq + 1;
                int ve = end;
                while (ve > vs && header.charAt(ve - 1) == ' ') {
                    ve--;
                }
                if (ve - vs >= 2 && header.charAt(vs) == '"' && header.charAt(ve - 1) == '"') {
                    vs++;
                    ve--;
                }
                return header.substring(vs, ve);
            }
            i = end + 1;
        }
        return null;
    }
}
