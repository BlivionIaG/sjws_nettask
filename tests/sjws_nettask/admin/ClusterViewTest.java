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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import sjws_nettask.observe.ClusterStatus;

/** Cluster view (#363): node list with named health checks, degraded and unreachable nodes, slow sources. */
class ClusterViewTest {

    private static ClusterStatus.Node node(String id, boolean alive, boolean ready, boolean draining, long applied,
            boolean drifted) {
        return new ClusterStatus.Node(id, alive, ready, draining, applied, drifted, 120, 3, 4.5, 2_000_000);
    }

    private static ClusterStatus status() {
        return new ClusterStatus(7, 9.0, 2_000_000, 240, 6, 1, new ClusterStatus.Node[] {
                node("gw-1", true, true, false, 7, false),
                node("gw-2", true, true, false, 6, true),
                node("gw-3", false, false, false, 7, false),
                node("gw-4", true, false, true, 7, false) }, null, null);
    }

    private static ServerControl control(Supplier<ClusterStatus> cluster) {
        return new ServerControl() {
            @Override public ServerStatus status() { return ServerStatus.stopped("stopped", Map.of()); }
            @Override public ClusterStatus clusterStatus() { return cluster.get(); }
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void reload() { }
            @Override public void close() { }
        };
    }

    @Test
    void healthRollsUpNamedChecks() {
        var nodes = ClusterView.nodes(status());
        assertEquals(ClusterView.Health.UP, nodes.get(0).health());
        assertEquals("ok", nodes.get(0).summary());
        assertEquals(ClusterView.Health.DEGRADED, nodes.get(1).health());
        assertEquals("config: applied 6, desired 7", nodes.get(1).summary());
        assertEquals(ClusterView.Health.DOWN, nodes.get(2).health());
        assertEquals("alive: unreachable, ready: not ready", nodes.get(2).summary());
        assertEquals(ClusterView.Health.DEGRADED, nodes.get(3).health());
        assertEquals("ready: not ready, serving: draining", nodes.get(3).summary());
        assertEquals(4, nodes.get(0).checks().size());
    }

    @Test
    void clusterViewListsNodesAndOpensOne() {
        var tui = new AdminTui(control(ClusterViewTest::status), new LogBuffer(5), null, false);
        tui.handle("k");
        assertEquals(AdminTui.View.CLUSTER, tui.view());
        var screen = tui.render();
        assertTrue(screen.contains("K CLUSTER"), screen);
        assertTrue(screen.contains("Cluster desired 7: 4 nodes, 1 up, 2 degraded, 1 down"), screen);
        assertTrue(screen.contains("gw-3"), screen);
        assertTrue(screen.contains("DOWN"), screen);
        assertTrue(screen.contains("node <id> shows one node's checks"), screen);

        tui.handle("1");
        tui.handle("node gw-2");
        assertEquals(AdminTui.View.CLUSTER, tui.view());
        screen = tui.render();
        assertTrue(screen.contains("Node gw-2: DEGRADED"), screen);
        assertTrue(screen.contains("  [ok]   alive\n"), screen);
        assertTrue(screen.contains("  [FAIL] config: applied 6, desired 7\n"), screen);
        assertTrue(screen.contains("  requests 120  errors 3"), screen);

        tui.handle("node nope");
        assertTrue(tui.render().contains("node nope is not in the cluster status"));
        tui.handle("node");
        assertTrue(tui.render().contains("node selection cleared"));
        tui.handle("6");
        assertEquals(AdminTui.View.CLUSTER, tui.view());
        tui.handle("h");
        assertTrue(tui.render().contains("node <id> node detail"));
    }

    @Test
    void healthIsColouredWhenAnsiIsOn() {
        var tui = new AdminTui(control(ClusterViewTest::status), new LogBuffer(5), null, true);
        tui.handle("k");
        var screen = tui.render();
        assertTrue(screen.contains(AdminTui.GREEN + "UP"), screen);
        assertTrue(screen.contains(AdminTui.YELLOW + "DEGRADED"), screen);
        assertTrue(screen.contains(AdminTui.RED + "DOWN"), screen);
    }

    @Test
    void missingFailingAndSlowSourcesDoNotStallTheConsole() throws Exception {
        var none = new AdminTui(control(() -> null), new LogBuffer(5), null, false);
        none.handle("k");
        assertTrue(none.render().contains("no cluster status attached to this server"));

        var failing = new AdminTui(control(() -> {
            throw new IllegalStateException("board closed");
        }), new LogBuffer(5), null, false);
        failing.handle("k");
        assertTrue(failing.render().contains("cluster status unavailable: board closed"));

        var release = new CountDownLatch(1);
        var slow = new AdminTui(control(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return status();
        }), new LogBuffer(5), null, false);
        slow.clusterTimeout(Duration.ofMillis(50));
        slow.handle("k");
        long start = System.nanoTime();
        var screen = slow.render();
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos(), "render waited too long");
        assertTrue(screen.contains("cluster status unavailable: no answer within 50 ms"), screen);
        release.countDown();
        assertThrows(TimeoutException.class, () -> ClusterView.fetch(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, Duration.ofMillis(20)));
        assertEquals(AdminTui.CLUSTER_TIMEOUT, Duration.ofSeconds(2));
    }

    @Test
    void statusViewIsUnchanged() {
        var screen = new AdminTui(control(ClusterViewTest::status), new LogBuffer(5), null, false).render();
        assertTrue(screen.contains("node gw-2 applied 6 DRIFT"), screen);
    }
}
