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
package sjws_nettask.observe;

/**
 * Merges the fixed log2 latency histogram by index. The result is still
 * {@link ServerMetrics#LATENCY_BOUNDS_NANOS}; buckets are never re-cut.
 * A percentile is the inclusive upper bound of the bucket that first reaches that rank,
 * which is what a control plane can compute after adding node {@code i} to node {@code i}.
 */
public final class HistogramMerge {

    private HistogramMerge() {
    }

    /** @return a new array, {@code left[i] + right[i]} */
    public static long[] sum(long[] left, long[] right) {
        requireWidth(left);
        requireWidth(right);
        var out = new long[left.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = left[i] + right[i];
        }
        return out;
    }

    /**
     * @param percentile 1..100
     * @return the bucket's upper bound in nanoseconds, or 0 when {@code counts} is empty
     */
    public static long percentileUpperNanos(long[] counts, int percentile) {
        requireWidth(counts);
        if (percentile < 1 || percentile > 100) {
            throw new IllegalArgumentException("percentile out of range: " + percentile);
        }
        long total = 0;
        for (long n : counts) {
            total += n;
        }
        if (total <= 0) {
            return 0;
        }
        long accumulated = 0;
        for (int i = 0; i < counts.length; i++) {
            accumulated += counts[i];
            if (reached(accumulated, total, percentile)) {
                return ServerMetrics.LATENCY_BOUNDS_NANOS[i];
            }
        }
        return ServerMetrics.LATENCY_BOUNDS_NANOS[counts.length - 1];
    }

    /** {@code accumulated / total >= percentile / 100}, without overflowing the product. */
    static boolean reached(long accumulated, long total, int percentile) {
        if (accumulated >= total) {
            return true;
        }
        long whole = total / 100;
        if (whole > Long.MAX_VALUE / percentile) {
            return (double) accumulated / (double) total >= percentile / 100.0;
        }
        long threshold = whole * percentile + (total % 100 * percentile + 99) / 100;
        return accumulated >= threshold;
    }

    private static void requireWidth(long[] counts) {
        if (counts == null || counts.length != ServerMetrics.LATENCY_BOUNDS_NANOS.length) {
            throw new IllegalArgumentException("histogram width must be " + ServerMetrics.LATENCY_BOUNDS_NANOS.length);
        }
    }
}
