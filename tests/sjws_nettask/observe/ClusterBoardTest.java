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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Control-plane aggregate: rate, merged p99, errors, and config drift. */
class ClusterBoardTest {

    @Test
    void emptyBoardAndRejectedStatus() {
        var board = new ClusterBoard();
        var empty = board.status();
        assertEquals(0, empty.desiredVersion());
        assertEquals(0, empty.requestsPerSecond());
        assertEquals(0, empty.p99Nanos());
        assertEquals(0, empty.requests());
        assertEquals(0, empty.errors());
        assertEquals(0, empty.driftedNodes());
        assertEquals(0, empty.nodes().length);
        assertEquals(0, empty.routes().length);
        assertEquals(0, empty.apiKeys().length);
        board.remove(null);
        board.remove("missing");
        assertEquals(0, board.status().nodes().length);

        assertThrows(NullPointerException.class, () -> board.ingest(null));
        assertThrows(IllegalArgumentException.class, () -> new ClusterStatus(0, 0, 0, 0, 0, 0,
                new ClusterStatus.Node[] {null}, new ClusterStatus.Series[0], new ClusterStatus.Series[0]));
        assertThrows(IllegalArgumentException.class, () -> new ClusterStatus(0, 0, 0, 0, 0, 0, new ClusterStatus.Node[0],
                new ClusterStatus.Series[] {null}, new ClusterStatus.Series[0]));
        assertThrows(IllegalArgumentException.class, () -> new ClusterStatus(0, 0, 0, 0, 0, 0, new ClusterStatus.Node[0],
                new ClusterStatus.Series[0], new ClusterStatus.Series[] {null}));
        var withNulls = new ClusterStatus(1, 0, 0, 0, 0, 0, null, null, null);
        assertEquals(0, withNulls.nodes().length);
        assertThrows(IllegalArgumentException.class, () -> new ClusterStatus.Node("", true, true, false, 0, false, 0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new ClusterStatus.Node(null, true, true, false, 0, false, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClusterStatus.Series("", 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new ClusterStatus.Series(null, 0, 0, 0));
    }

    @Test
    void rateIsADeltaAndARestartZerosTheInterval() {
        var board = new ClusterBoard();
        board.ingest(report("a", 1_000, 7, 0, buckets(0, 0)));
        assertEquals(0, board.status().requestsPerSecond());
        assertEquals(0, board.status().nodes()[0].requestsPerSecond());

        board.ingest(report("a", 2_000, 7, 1_000, buckets(100, 0)));
        assertEquals(1_000, board.status().requestsPerSecond());

        board.ingest(report("a", 2_000, 7, 1_500, buckets(100, 0)));
        assertEquals(1_000, board.status().requestsPerSecond(), "no wall-clock gap keeps the previous rate");

        board.ingest(report("a", 3_000, 7, 40, buckets(0, 0)));
        assertEquals(0, board.status().requestsPerSecond(), "a dropped counter is a restart");

        board.ingest(report("a", 4_000, 7, 60, buckets(0, 0)));
        assertEquals(20, board.status().requestsPerSecond());
    }

    @Test
    void mergesHistogramsErrorsRoutesAndDrift() {
        var board = new ClusterBoard();
        board.desiredVersion(7);
        var routeA = new LabelSeries("/api", 99, 1, buckets(99, 0));
        var routeB = new LabelSeries("/api", 1, 1, buckets(0, 1));
        var other = new LabelSeries("/static", 4, 0, buckets(4, 0));
        var key = new LabelSeries("key-id", 10, 2, buckets(9, 1));
        board.ingest(node("a", 1_000, 7, true, true, false, 99, 1, buckets(99, 0), new LabelSeries[] {routeA},
                new LabelSeries[] {key}));
        board.ingest(node("b", 2_000, 6, false, false, true, 5, 3, buckets(0, 5), new LabelSeries[] {routeB, other},
                new LabelSeries[0]));

        var secondA = node("a", 2_000, 7, true, true, false, 199, 1, buckets(99, 0), new LabelSeries[] {routeA},
                new LabelSeries[] {key});
        board.ingest(secondA);

        var status = board.status();
        assertEquals(7, status.desiredVersion());
        assertEquals(100, status.requestsPerSecond());
        assertEquals(204, status.requests());
        assertEquals(4, status.errors());
        assertEquals(1, status.driftedNodes());
        assertEquals(ServerMetrics.LATENCY_BOUNDS_NANOS[1], status.p99Nanos());

        assertEquals("a", status.nodes()[0].nodeId());
        assertFalse(status.nodes()[0].drifted());
        assertTrue(status.nodes()[0].alive());
        assertEquals(100, status.nodes()[0].requestsPerSecond());
        assertEquals(ServerMetrics.LATENCY_FIRST_BOUND_NANOS, status.nodes()[0].p99Nanos());

        assertEquals("b", status.nodes()[1].nodeId());
        assertTrue(status.nodes()[1].drifted());
        assertFalse(status.nodes()[1].alive());
        assertFalse(status.nodes()[1].ready());
        assertTrue(status.nodes()[1].draining());
        assertEquals(0, status.nodes()[1].requestsPerSecond());
        assertEquals(3, status.nodes()[1].errors());

        assertEquals("/api", status.routes()[0].name());
        assertEquals(100, status.routes()[0].requests());
        assertEquals(2, status.routes()[0].errors());
        assertEquals(ServerMetrics.LATENCY_FIRST_BOUND_NANOS, status.routes()[0].p99Nanos());
        assertEquals("/static", status.routes()[1].name());
        assertEquals(4, status.routes()[1].requests());
        assertEquals("key-id", status.apiKeys()[0].name());
        assertEquals(10, status.apiKeys()[0].requests());
        assertEquals(2, status.apiKeys()[0].errors());

        board.desiredVersion(0);
        assertEquals(0, board.status().driftedNodes());
        assertFalse(board.status().nodes()[1].drifted());

        board.remove("b");
        assertEquals(1, board.status().nodes().length);
        assertEquals(199, board.status().requests());
    }

    @Test
    void extraLabelNamesFoldIntoOther() {
        var routes = new LabelSeries[ClusterBoard.MAX_MERGED_SERIES];
        for (int i = 0; i < routes.length - 1; i++) {
            routes[i] = new LabelSeries("r" + i, 1, 0, LabelSeries.emptyBuckets());
        }
        routes[routes.length - 1] = new LabelSeries("other", 1, 0, LabelSeries.emptyBuckets());
        var extra = new LabelSeries("r-extra", 5, 2, buckets(0, 5));
        var all = new LabelSeries[routes.length + 1];
        System.arraycopy(routes, 0, all, 0, routes.length);
        all[routes.length] = extra;

        var board = new ClusterBoard();
        board.ingest(report("a", 1, 1, 1, LabelSeries.emptyBuckets(), all));
        var status = board.status();
        assertEquals(ClusterBoard.MAX_MERGED_SERIES, status.routes().length);
        var other = status.routes()[status.routes().length - 1];
        assertEquals("other", other.name());
        assertEquals(6, other.requests());
        assertEquals(2, other.errors());
        assertEquals(ServerMetrics.LATENCY_BOUNDS_NANOS[1], other.p99Nanos());
    }

    private static long[] buckets(long first, long second) {
        var counts = LabelSeries.emptyBuckets();
        counts[0] = first;
        counts[1] = second;
        return counts;
    }

    private static NodeReport report(String id, long wall, long applied, long requests, long[] counts) {
        return report(id, wall, applied, requests, counts, new LabelSeries[0]);
    }

    private static NodeReport report(String id, long wall, long applied, long requests, long[] counts,
            LabelSeries[] routes) {
        return node(id, wall, applied, true, true, false, requests, 0, counts, routes, new LabelSeries[0]);
    }

    private static NodeReport node(String id, long wall, long applied, boolean alive, boolean ready, boolean draining,
            long requests, long errors, long[] counts, LabelSeries[] routes, LabelSeries[] apiKeys) {
        return new NodeReport(id, wall, wall, alive, ready, draining, applied, 0, requests, errors, 0, 0, 0, counts,
                routes, apiKeys);
    }
}
