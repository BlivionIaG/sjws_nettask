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

/**
 * Vose alias table over integer weights: {@link #pick} is O(1) and does not allocate. Built off the
 * request path (route compile, endpoint-set swap). Shared by {@link WeightedHandler} and
 * {@link EndpointBalancer}.
 */
final class AliasTable {

    private final double[] prob;
    private final int[] alias;

    /** @param weights at least one, each >= 0, total > 0 */
    AliasTable(long[] weights) {
        int n = weights.length;
        if (n == 0) {
            throw new IllegalArgumentException("no weights");
        }
        long total = 0;
        for (long w : weights) {
            if (w < 0) {
                throw new IllegalArgumentException("weight must be >= 0");
            }
            total += w;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("total weight must be > 0");
        }
        this.prob = new double[n];
        this.alias = new int[n];
        var scaled = new double[n];
        var small = new int[n];
        var large = new int[n];
        int s = 0;
        int l = 0;
        for (int i = 0; i < n; i++) {
            scaled[i] = (double) weights[i] * n / total;
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

    /** High 32 bits choose the column, low 32 bits the coin. */
    int pick(long bits) {
        int n = this.prob.length;
        int column = (int) (((bits >>> 32) * n) >>> 32);
        double coin = (bits & 0xFFFF_FFFFL) * 0x1.0p-32;
        return coin < this.prob[column] ? column : this.alias[column];
    }

    /** Exact probability of {@code i} implied by the table, for tests. */
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
        return this.prob.length;
    }

    /** SplitMix64 finalizer. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
