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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LedgerInspector;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;

/** Queues view (#384): counts and dead letters come from LedgerInspector snapshots, never payloads. */
class QueueViewTest {

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private Partition orders() throws Exception {
        var p = Partition.open(this.dir.resolve("orders"), "orders", RetryPolicy.DEFAULT, this.now::get);
        p.snapshotPolicy(0, 0, 1_000); // rebuild on every read so the test sees each step
        p.subscribe("billing", true);
        for (int i = 0; i < 4; i++) {
            p.produce(0, 0, ("secret-payload-" + i).getBytes());
        }
        var leased = p.poll("billing", "worker-1", 2, 60_000);
        assertEquals(2, leased.size());
        assertTrue(p.reject("billing", leased.get(0).offset(), "bad \u001b[2J input"));
        return p;
    }

    @Test
    void readsCountsOldestAgeAndDeadLettersFromSnapshots() throws Exception {
        try (var p = orders()) {
            this.now.addAndGet(5_000);
            var inspector = new LedgerInspector(p);
            var views = QueueView.read(Map.of("orders", inspector), this.now.get());
            assertEquals(1, views.size());
            var q = views.get(0);
            assertEquals("orders", q.topic());
            assertEquals("billing", q.group());
            assertEquals(2, q.pending());
            assertEquals(1, q.inFlight());
            assertEquals(1, q.deadLettered());
            assertEquals(5_000, q.oldestPendingAgeMs(), q.toString());

            var dead = QueueView.deadLetters(inspector, "billing", 0, 10);
            assertEquals(1, dead.size());
            assertEquals("rejected:bad \u001b[2J input", dead.get(0).reason());
            assertTrue(dead.get(0).size() > 0);
            assertEquals(List.of(), QueueView.deadLetters(inspector, "billing", dead.get(0).offset() + 1, 10));
            assertEquals(List.of(), QueueView.deadLetters(inspector, "billing", 0, -3));
            assertThrows(IllegalArgumentException.class, () -> QueueView.deadLetters(inspector, "nope", 0, 10));
        }
    }

    @Test
    void embeddedControlServesQueuesAndRejectsUnknownNames() throws Exception {
        var log = new LogBuffer(20);
        try (var p = orders(); var control = new EmbeddedServerControl(() -> null, log)) {
            assertEquals(List.of(), control.queues());
            assertThrows(ServerControl.ControlException.class, () -> control.deadLetters("orders", "billing", 0, 5));
            var topics = new LinkedHashMap<String, LedgerInspector>();
            topics.put("orders", new LedgerInspector(p));
            control.attachQueues(() -> topics);
            assertEquals(1, control.queues().size());
            assertEquals(1, control.deadLetters("orders", "billing", 0, 5).size());
            assertThrows(ServerControl.ControlException.class, () -> control.deadLetters("missing", "billing", 0, 5));
            assertThrows(ServerControl.ControlException.class, () -> control.deadLetters("orders", "missing", 0, 5));
            control.attachQueues(() -> {
                throw new IllegalStateException("topic closed");
            });
            assertEquals(List.of(), control.queues());
            assertThrows(NullPointerException.class, () -> control.attachQueues(() -> topics, null));
            assertTrue(log.tail(1).get(0).contains("queue snapshot failed: topic closed"));
            control.attachQueues(null);
            assertEquals(List.of(), control.queues());
        }
    }

    @Test
    void tuiShowsQueuesPagesDeadLettersAndNeverPrintsPayloadsOrEscapes() throws Exception {
        try (var p = orders(); var control = new EmbeddedServerControl(() -> null, new LogBuffer(20))) {
            this.now.addAndGet(5_000);
            control.attachQueues(() -> Map.of("orders", new LedgerInspector(p)), this.now::get);
            var tui = new AdminTui(control, new LogBuffer(5), null, false);
            tui.handle("m");
            assertEquals(AdminTui.View.QUEUES, tui.view());
            var screen = tui.render();
            assertTrue(screen.contains("m queues".toUpperCase()), screen);
            assertTrue(screen.matches("(?s).*orders\\s+billing\\s+2\\s+1\\s+1.*"), screen);

            tui.handle("d orders billing");
            assertTrue(tui.message().startsWith("dead letters: 1"), tui.message());
            screen = tui.render();
            assertTrue(screen.contains("Dead letters orders / billing from 0 (payloads not shown)"), screen);
            assertTrue(screen.contains("consumer - reason rejected:bad ?[2J input"), screen);
            assertTrue(screen.contains("00:00:05"), screen);
            assertFalse(screen.contains("\u001b[2J"), screen);
            assertFalse(screen.contains("secret-payload"), screen);

            tui.handle("d orders");
            assertEquals("usage: d <topic> <group> [fromOffset]", tui.message());
            tui.handle("d orders billing x");
            assertEquals("fromOffset must be a number", tui.message());
            tui.handle("d missing billing 0");
            assertTrue(tui.message().contains("unknown topic missing"), tui.message());
            tui.handle("4");
            assertEquals(AdminTui.View.QUEUES, tui.view());
        }
    }

    @Test
    void fullPageOffersTheNextCursor() {
        var entries = new java.util.ArrayList<QueueView.Entry>();
        for (int i = 0; i < AdminTui.QUEUE_PAGE; i++) {
            entries.add(new QueueView.Entry(10 + i, 3, null, 0, 1, null));
        }
        entries.set(1, new QueueView.Entry(11, 2, "worker-\u00e9", 0, 1, " "));
        var control = new ServerControl() {
            @Override public ServerStatus status() { return ServerStatus.stopped("stopped", Map.of()); }
            @Override public void start() { }
            @Override public void stop() { }
            @Override public void reload() { }
            @Override public void close() { }
            @Override
            public List<QueueView.Entry> deadLetters(String topic, String group, long fromOffset, int limit) {
                return entries;
            }
        };
        var tui = new AdminTui(control, new LogBuffer(5), null, false);
        tui.handle("d t g 10");
        assertEquals("next page: d t g " + (10 + AdminTui.QUEUE_PAGE), tui.message());
        var screen = tui.render();
        assertTrue(screen.contains("messaging is not enabled or has no consumer groups"), screen);
        assertTrue(screen.contains("consumer - reason -"), screen);
        assertTrue(screen.contains("@11 attempts 2 size 1 consumer worker-\u00e9 reason -"), screen);
        tui.handle("d t g 1 2");
        assertEquals("usage: d <topic> <group> [fromOffset]", tui.message());
    }

    @Test
    void defaultControlHasNoMessaging() {
        var control = new AdminTuiTest.FakeControl();
        assertEquals(List.of(), control.queues());
        var tui = new AdminTui(control, new LogBuffer(5), null, false);
        tui.handle("d t g");
        assertTrue(tui.message().contains("messaging is not enabled"), tui.message());
        assertEquals("a?b", AdminTui.printable("a\u009bb"));
        assertEquals("x".repeat(120) + "...", AdminTui.printable("x".repeat(130)));
    }

    @Test
    void idleGroupHasNoAgeAndCappedListsAreFlagged() throws Exception {
        try (var p = orders(); var control = new EmbeddedServerControl(() -> null, new LogBuffer(5))) {
            p.subscribe("audit", false); // joins at the tail: nothing pending
            p.snapshotPolicy(0, 0, 1); // one item per list: billing's two pending entries are cut
            control.attachQueues(() -> Map.of("orders", new LedgerInspector(p)), this.now::get);
            var tui = new AdminTui(control, new LogBuffer(5), null, false);
            var views = QueueView.read(Map.of("orders", new LedgerInspector(p)), this.now.get());
            var audit = views.stream().filter(v -> v.group().equals("audit")).findFirst().orElseThrow();
            assertEquals(-1, audit.oldestPendingAgeMs());
            assertEquals(0, audit.pending());
            var billing = views.stream().filter(v -> v.group().equals("billing")).findFirst().orElseThrow();
            assertTrue(billing.truncated(), billing.toString());
            tui.handle("m");
            var screen = tui.render();
            assertTrue(screen.contains("(lists truncated)"), screen);
            assertTrue(screen.matches("(?s).*audit\\s+0\\s+0\\s+0\\s+-.*"), screen);
        }
    }
}
