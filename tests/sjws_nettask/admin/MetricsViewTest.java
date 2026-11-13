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
package sjws_nettask.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.observe.ServerMetrics;
import sjws_nettask.observe.Snapshot;

/** Metrics view (#366): two fake node snapshots merged with Observability's bucket layout. */
class MetricsViewTest {

    @TempDir
    Path root;

    private static final long[] BOUNDS = ServerMetrics.LATENCY_BOUNDS_NANOS;

    /** {@code n} responses with status 200 (or 503 for {@code errors}), all in latency bucket {@code bucket}. */
    private static Snapshot node(long n, long errors, int bucket, int h1, String protocol) {
        var status = new long[1000];
        status[200] = n - errors;
        status[503] = errors;
        var protocols = new long[5];
        protocols[protocol.equals("HTTP/1.1") ? 1 : 3] = n;
        var counts = new long[BOUNDS.length];
        counts[bucket] = n;
        return new Snapshot(n, n * 10, h1, h1, 0, 0, status, protocols,
                new Snapshot.Latency(n, n * BOUNDS[bucket], BOUNDS[bucket], BOUNDS, counts));
    }

    @Test
    void aggregateSumsCountersAndMergesHistogramsByBucket() {
        var fast = node(100, 0, 2, 3, "HTTP/1.1");
        var slow = node(100, 4, 10, 2, "HTTP/3");
        var all = MetricsView.aggregate(List.of(fast, slow));
        assertEquals(200, all.requests());
        assertEquals(2000, all.bytesSent());
        assertEquals(5, all.activeConnections());
        assertEquals(196, all.status(200));
        assertEquals(4, all.status(503));
        assertEquals(100, all.protocol("HTTP/1.1"));
        assertEquals(100, all.protocol("HTTP/3"));
        assertEquals(200, all.latency().count());
        assertEquals(BOUNDS[10], all.latency().maxNanos());
        // percentiles from merged buckets: half the samples are fast, so p50 is the fast bucket
        var text = MetricsView.render(all);
        assertTrue(text.contains("p50 <= " + MetricsView.seconds(BOUNDS[2])), text);
        assertTrue(text.contains("p99 <= " + MetricsView.seconds(BOUNDS[10])), text);
        assertTrue(text.contains("2xx 196") && text.contains("5xx 4"), text);
        assertNull(MetricsView.aggregate(List.of()));
    }

    @Test
    void layoutsMustMatchAndReportsAreConsistent() {
        var odd = new Snapshot(1, 0, 0, 0, 0, 0, new long[10], new long[5],
                new Snapshot.Latency(0, 0, 0, BOUNDS, new long[BOUNDS.length]));
        assertThrows(IllegalArgumentException.class, () -> MetricsView.aggregate(List.of(node(1, 0, 0, 0, "HTTP/1.1"), odd)));
        assertThrows(IllegalArgumentException.class,
                () -> new MetricsView.Report(List.of(new MetricsView.Node("a", odd)), 0));
        assertThrows(NullPointerException.class, () -> new MetricsView.Node(null, odd));
        assertEquals("-", MetricsView.seconds(0));
        assertEquals("+Inf", MetricsView.seconds(Long.MAX_VALUE));
    }

    private static ServerControl reporting(MetricsView.Report report) {
        return new ServerControl() {
            @Override public ServerStatus status() { return ServerStatus.stopped("stopped", Map.of()); }
            @Override public MetricsView.Report metrics() { return report; }
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void reload() { }
            @Override public void close() { }
        };
    }

    @Test
    void viewShowsAggregatePerNodeAndMissingNodes() {
        var report = new MetricsView.Report(List.of(new MetricsView.Node("gw-1", node(100, 0, 2, 3, "HTTP/1.1")),
                new MetricsView.Node("gw-2", node(100, 4, 10, 2, "HTTP/3"))), 3);
        var tui = new AdminTui(reporting(report), new LogBuffer(5), null, false);
        tui.handle("g");
        assertEquals(AdminTui.View.METRICS, tui.view());
        var screen = tui.render();
        assertTrue(screen.contains("G METRICS"), screen);
        assertTrue(screen.contains("Nodes reporting 2/3 (missing nodes are not in the totals)"), screen);
        assertTrue(screen.contains("Aggregate\n  requests 200"), screen);
        assertTrue(screen.contains("Node gw-1\n  requests 100"), screen);
        assertTrue(screen.contains("Node gw-2"), screen);
        tui.handle("5");
        assertEquals(AdminTui.View.METRICS, tui.view());

        var one = new AdminTui(reporting(new MetricsView.Report(List.of(report.nodes().get(0)), 1)),
                new LogBuffer(5), null, false);
        one.handle("g");
        screen = one.render();
        assertTrue(screen.contains("Nodes reporting 1/1\n"), screen);
        assertFalse(screen.contains("Aggregate"), screen);

        var none = new AdminTui(reporting(null), new LogBuffer(5), null, false);
        none.handle("g");
        assertTrue(none.render().contains("metrics are not available for this server"));
        var zero = new AdminTui(reporting(new MetricsView.Report(List.of(), 2)), new LogBuffer(5), null, false);
        zero.handle("g");
        screen = zero.render();
        assertTrue(screen.contains("Nodes reporting 0/2"), screen);
        assertFalse(screen.contains("Node "), screen);
        assertNull(new AdminTuiTest.FakeControl().metrics());
    }

    @Test
    void embeddedControlReportsTheLocalNode() throws Exception {
        try (var control = new EmbeddedServerControl(() -> ServerConfig.builder(this.root).port(0).tlsKeyPassword("secret").build(),
                new LogBuffer(20))) {
            assertEquals(0, control.metrics().nodes().size());
            assertEquals(1, control.metrics().nodesTotal());
            control.start();
            var report = control.metrics();
            assertEquals(1, report.nodes().size());
            assertEquals("local", report.nodes().get(0).nodeId());
            var tui = new AdminTui(control, new LogBuffer(5), null, false);
            tui.handle("g");
            assertTrue(tui.render().contains("Node local\n  requests 0"), tui.render());
        }
    }

    @Test
    void droppedAccessLogLinesAreShown() {
        var s = new Snapshot(1, 0, 0, 0, 0, 7, new long[1000], new long[5],
                new Snapshot.Latency(0, 0, 0, BOUNDS, new long[BOUNDS.length]));
        assertTrue(MetricsView.render(s).contains("access log lines dropped 7"));
    }

    @Test
    void nodeIdsAreShownThroughPrintable() {
        var id = "gw-\u202e1\u001b[31m";
        var report = new MetricsView.Report(List.of(new MetricsView.Node(id, node(1, 0, 0, 0, "HTTP/1.1"))), 1);
        var tui = new AdminTui(reporting(report), new LogBuffer(5), null, false);
        tui.handle("g");
        var screen = tui.render();
        assertTrue(screen.contains("Node " + AdminTui.printable(id)), screen);
        assertFalse(screen.contains("\u202e") || screen.contains("\u001b"), screen);
    }
}
