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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Control-plane heartbeat payload: cadence, cumulative counters, histogram merge. */
class NodeReporterTest {

    @Test
    void cadenceReusesTheReportAndSequenceAdvancesOnlyOnAFreshTake() {
        var metrics = new ServerMetrics();
        metrics.record(200, 1, 10, 500_000);
        var reporter = NodeReporter.builder("node-a", metrics).intervalNanos(NodeReporter.INTERVAL_NANOS)
                .wallClockMillis(() -> 1_000L).build();

        var first = reporter.report(0);
        assertEquals(1, first.sequence());
        assertEquals(1_000L, first.wallMillis());
        assertEquals(1, first.requests());
        assertSame(first, reporter.report(NodeReporter.INTERVAL_NANOS - 1));
        assertEquals(1, reporter.report(NodeReporter.INTERVAL_NANOS - 1).sequence());

        metrics.record(200, 1, 10, 500_000);
        var second = reporter.report(NodeReporter.INTERVAL_NANOS);
        assertEquals(2, second.sequence());
        assertEquals(2, second.requests());
        assertEquals(20, second.bytesSent());
    }

    @Test
    void zeroIntervalTakesEveryCallAndReportUsesTheClock() {
        var metrics = new ServerMetrics();
        var reporter = NodeReporter.builder("node-a", metrics).intervalNanos(0).build();
        var a = reporter.report(0);
        var b = reporter.report(0);
        assertEquals(1, a.sequence());
        assertEquals(2, b.sequence());
        assertTrue(a.alive());
        assertTrue(a.ready());
        assertFalse(a.draining());
        assertEquals(0, a.appliedVersion());
        assertEquals(0, a.contentHash());
        assertEquals(0, a.routes().length);
        assertEquals(0, a.apiKeys().length);
        assertEquals(3, reporter.report().sequence());
    }

    @Test
    void cumulativeErrorsHealthAndConfigVersion() {
        var metrics = new ServerMetrics();
        metrics.record(200, 1, 4, ServerMetrics.LATENCY_FIRST_BOUND_NANOS);
        metrics.record(404, 1, 0, ServerMetrics.LATENCY_FIRST_BOUND_NANOS);
        metrics.record(499, 1, 0, ServerMetrics.LATENCY_FIRST_BOUND_NANOS);
        metrics.record(500, 1, 0, ServerMetrics.LATENCY_BOUNDS_NANOS[1]);
        metrics.record(599, 1, 0, ServerMetrics.LATENCY_BOUNDS_NANOS[14] + 1);
        boolean[] health = {false, false, true};
        long[] version = {7, 0xabc};
        var buckets = LabelSeries.emptyBuckets();
        buckets[0] = 3;
        var route = new LabelSeries("/api", 3, 1, buckets);
        var key = new LabelSeries("key-id", 3, 1, buckets);
        var reporter = NodeReporter.builder("node-b", metrics).intervalNanos(0).alive(() -> health[0]).ready(() -> health[1])
                .draining(() -> health[2]).appliedVersion(() -> version[0]).contentHash(() -> version[1])
                .labels(new LabelSource() {
                    @Override
                    public LabelSeries[] routes() {
                        return new LabelSeries[] {route};
                    }

                    @Override
                    public LabelSeries[] apiKeys() {
                        return new LabelSeries[] {key};
                    }
                }).build();

        var report = reporter.report(1);
        assertFalse(report.alive());
        assertFalse(report.ready());
        assertTrue(report.draining());
        assertEquals(7, report.appliedVersion());
        assertEquals(0xabc, report.contentHash());
        assertEquals(5, report.requests());
        assertEquals(2, report.errors());
        assertEquals(2, report.clientErrors());
        assertEquals(4, report.bytesSent());
        assertEquals(3, report.latencyCounts()[0]);
        assertEquals(1, report.latencyCounts()[1]);
        assertEquals(1, report.latencyCounts()[report.latencyCounts().length - 1]);
        assertEquals("/api", report.routes()[0].name());
        assertEquals("key-id", report.apiKeys()[0].name());
        assertEquals(ServerMetrics.LATENCY_FIRST_BOUND_NANOS * 3 + ServerMetrics.LATENCY_BOUNDS_NANOS[1]
                + ServerMetrics.LATENCY_BOUNDS_NANOS[14] + 1, report.latencySumNanos());

        metrics.record(500, 1, 1, 1);
        var later = reporter.report(2);
        assertEquals(3, later.errors());
        assertEquals(6, later.requests());
    }

    @Test
    void nullLabelsBecomeEmptyAndInputsAreCopied() {
        var metrics = new ServerMetrics();
        var reporter = NodeReporter.builder("node-a", metrics).labels(null).intervalNanos(0).build();
        assertEquals(0, reporter.report(1).routes().length);

        var counts = LabelSeries.emptyBuckets();
        counts[0] = 4;
        var series = new LabelSeries("r", 4, 0, counts);
        counts[0] = 99;
        assertEquals(4, series.latencyCounts()[0]);
        series.latencyCounts()[0] = 7;
        assertEquals(4, series.requests());
        assertEquals(7, series.latencyCounts()[0]);

        var histogram = LabelSeries.emptyBuckets();
        histogram[2] = 1;
        var report = new NodeReport("n", 1, 2, true, true, false, 3, 4, 1, 0, 0, 0, 9, histogram, null, null);
        histogram[2] = 50;
        assertEquals(1, report.latencyCounts()[2]);
        assertEquals(0, report.routes().length);
        assertEquals(0, report.apiKeys().length);
    }

    @Test
    void rejectsEmptyIdentityBadHistogramsAndANegativeInterval() {
        var metrics = new ServerMetrics();
        assertThrows(IllegalArgumentException.class, () -> NodeReporter.builder("", metrics));
        assertThrows(IllegalArgumentException.class, () -> NodeReporter.builder(null, metrics));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", null));
        assertThrows(IllegalArgumentException.class, () -> NodeReporter.builder("n", metrics).intervalNanos(-1));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", metrics).appliedVersion(null));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", metrics).contentHash(null));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", metrics).alive(null));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", metrics).ready(null));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", metrics).draining(null));
        assertThrows(NullPointerException.class, () -> NodeReporter.builder("n", metrics).wallClockMillis(null));

        var bad = new long[3];
        assertThrows(IllegalArgumentException.class, () -> new NodeReport("", 1, 0, true, true, false, 0, 0, 0, 0, 0, 0, 0,
                LabelSeries.emptyBuckets(), new LabelSeries[0], new LabelSeries[0]));
        assertThrows(NullPointerException.class, () -> new NodeReport(null, 1, 0, true, true, false, 0, 0, 0, 0, 0, 0, 0,
                LabelSeries.emptyBuckets(), new LabelSeries[0], new LabelSeries[0]));
        assertThrows(IllegalArgumentException.class, () -> new NodeReport("n", 1, 0, true, true, false, 0, 0, 0, 0, 0, 0, 0,
                bad, new LabelSeries[0], new LabelSeries[0]));
        assertThrows(IllegalArgumentException.class, () -> new NodeReport("n", 1, 0, true, true, false, 0, 0, 0, 0, 0, 0, 0,
                null, new LabelSeries[0], new LabelSeries[0]));
        var hole = new LabelSeries[] {null};
        assertThrows(IllegalArgumentException.class, () -> new NodeReport("n", 1, 0, true, true, false, 0, 0, 0, 0, 0, 0, 0,
                LabelSeries.emptyBuckets(), hole, new LabelSeries[0]));
        assertThrows(IllegalArgumentException.class, () -> new NodeReport("n", 1, 0, true, true, false, 0, 0, 0, 0, 0, 0, 0,
                LabelSeries.emptyBuckets(), new LabelSeries[0], hole));
        assertThrows(IllegalArgumentException.class, () -> new LabelSeries("", 0, 0, LabelSeries.emptyBuckets()));
        assertThrows(NullPointerException.class, () -> new LabelSeries(null, 0, 0, LabelSeries.emptyBuckets()));
        assertThrows(IllegalArgumentException.class, () -> new LabelSeries("r", 0, 0, bad));
        assertThrows(IllegalArgumentException.class, () -> new LabelSeries("r", 0, 0, null));
    }

    @Test
    void percentileIsTheBucketUpperBoundAndNodesMergeByIndex() {
        var left = LabelSeries.emptyBuckets();
        var right = LabelSeries.emptyBuckets();
        left[0] = 98;
        right[0] = 1;
        right[1] = 1;
        var merged = HistogramMerge.sum(left, right);
        assertEquals(99, merged[0]);
        assertEquals(1, merged[1]);
        assertEquals(ServerMetrics.LATENCY_FIRST_BOUND_NANOS, HistogramMerge.percentileUpperNanos(merged, 99));
        assertEquals(ServerMetrics.LATENCY_BOUNDS_NANOS[1], HistogramMerge.percentileUpperNanos(merged, 100));

        var tail = LabelSeries.emptyBuckets();
        tail[tail.length - 1] = 10;
        assertEquals(Long.MAX_VALUE, HistogramMerge.percentileUpperNanos(tail, 1));
        assertEquals(0, HistogramMerge.percentileUpperNanos(LabelSeries.emptyBuckets(), 99));

        assertThrows(IllegalArgumentException.class, () -> HistogramMerge.percentileUpperNanos(merged, 0));
        assertThrows(IllegalArgumentException.class, () -> HistogramMerge.percentileUpperNanos(merged, 101));
        assertThrows(IllegalArgumentException.class, () -> HistogramMerge.sum(left, new long[1]));
        assertThrows(IllegalArgumentException.class, () -> HistogramMerge.sum(null, right));
        assertThrows(IllegalArgumentException.class, () -> HistogramMerge.percentileUpperNanos(null, 50));

        assertTrue(HistogramMerge.reached(Long.MAX_VALUE, Long.MAX_VALUE, 99));
        assertFalse(HistogramMerge.reached(1, 100, 50));
        assertTrue(HistogramMerge.reached(50, 100, 50));
    }
}
