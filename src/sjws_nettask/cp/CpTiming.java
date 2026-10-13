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
package sjws_nettask.cp;

import java.time.Duration;
import sjws_nettask.cluster.GroupOptions;

/**
 * Fail-closed lease/election startup check (control-plane-hosting.md §6.4, HA): leases run on each node's
 * monotonic clock, so only the clock rate error over one lease matters. A control process refuses to start
 * unless {@code lease * (1 + maxClockDriftRatio) + leaseSafetyMargin < electionTimeout}.
 */
public final class CpTiming {

    /** Default {@code cp.maxClockDriftRatio}: 1%. */
    public static final double DEFAULT_DRIFT_RATIO = 0.01;
    /** Default {@code cp.leaseSafetyMargin}. */
    public static final Duration DEFAULT_MARGIN = Duration.ofMillis(10);

    private CpTiming() {
    }

    /** @throws IllegalStateException when the lease could outlive an election on a drifting clock */
    public static void check(GroupOptions options, double driftRatio, Duration margin) {
        check(options.leaseNanos(), options.electionTimeout().toNanos(), driftRatio, margin.toNanos());
    }

    static void check(long leaseNanos, long electionNanos, double driftRatio, long marginNanos) {
        if (!(driftRatio >= 0.0 && driftRatio < 1.0) || marginNanos < 0 || leaseNanos <= 0 || electionNanos <= 0) {
            throw new IllegalStateException("cp timing: invalid inputs");
        }
        // Integer arithmetic on the left side would round the ratio away; doubles are exact enough at ns scale
        // for the values allowed here (lease and election well below 2^53 ns).
        double needed = leaseNanos * (1.0 + driftRatio) + marginNanos;
        if (!(needed < electionNanos)) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "cp timing: lease %.3f ms * (1 + %s) + margin %.3f ms = %.3f ms is not below election timeout"
                            + " %.3f ms; refusing to start",
                    leaseNanos / 1e6, driftRatio, marginNanos / 1e6, needed / 1e6, electionNanos / 1e6));
        }
    }
}
