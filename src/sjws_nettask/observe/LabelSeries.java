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

import java.util.Objects;

/**
 * One bounded label (a configured route or API-key id) inside a {@link NodeReport}.
 * Counts are cumulative. {@code latencyCounts} uses {@link ServerMetrics#LATENCY_BOUNDS_NANOS}
 * so a control plane merges routes by adding bucket {@code i} to bucket {@code i}.
 *
 * @param name configured id, never the raw secret
 * @param requests responses charged to this label
 * @param errors responses with status {@code >= 500}
 * @param latencyCounts non-cumulative histogram, same length and bounds as the node histogram
 */
public record LabelSeries(String name, long requests, long errors, long[] latencyCounts) {

    public LabelSeries {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("label name is empty");
        }
        if (latencyCounts == null || latencyCounts.length != ServerMetrics.LATENCY_BOUNDS_NANOS.length) {
            throw new IllegalArgumentException("latency histogram must have "
                    + ServerMetrics.LATENCY_BOUNDS_NANOS.length + " buckets");
        }
        latencyCounts = latencyCounts.clone();
    }

    /** Empty histogram of the stable log2 width. */
    public static long[] emptyBuckets() {
        return new long[ServerMetrics.LATENCY_BOUNDS_NANOS.length];
    }
}
