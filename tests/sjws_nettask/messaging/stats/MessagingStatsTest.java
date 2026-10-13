package sjws_nettask.messaging.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;

class MessagingStatsTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    void noneIsEmptyAndAllocationFree() {
        assertSame(MessagingStats.StatsSnapshot.EMPTY, MessagingStats.NONE.snapshot());
        assertSame(MessagingStats.NONE.snapshot(), MessagingStats.NONE.snapshot());
        assertTrue(MessagingStats.NONE.snapshot().partitions().isEmpty());
        assertEquals("", PartitionStatsRegistry.prometheus(MessagingStats.StatsSnapshot.EMPTY).lines()
                .filter(l -> !l.startsWith("#")).reduce("", String::concat));
    }

    @Test
    void lagInFlightDlqDepthAndAge() throws Exception {
        try (var p = Partition.open(dir, "orders", new RetryPolicy(1, 10, 10), now::get)) {
            var reg = new PartitionStatsRegistry(now::get);
            reg.register(3, p);
            p.subscribe("billing", true);
            for (int i = 0; i < 5; i++) p.produce(0, 0, ("m" + i).getBytes(StandardCharsets.UTF_8));
            now.addAndGet(2_000);
            var d = p.poll("billing", "c", 3, 60_000);
            p.ack("billing", d.get(1).offset());
            p.reject("billing", d.get(2).offset(), "bad");
            var s = reg.snapshot();
            assertEquals(2, s.partitions().size());
            var main = s.partitions().get(0);
            assertEquals("orders", main.topic());
            assertEquals(3, main.partition());
            assertFalse(main.deadLetterQueue());
            var g = main.groups().getFirst();
            assertEquals("billing", g.group());
            assertEquals(1, g.inFlight());
            assertEquals(2, g.enqueued());
            assertEquals(3, g.lagMessages());
            assertEquals(1, g.acked());
            assertEquals(1, g.deadLettered());
            assertEquals(1, g.dlqDepth());
            assertEquals(main.logEndOffset() - g.committedOffset(), g.lagOffsets());
            assertTrue(g.lagOffsets() > 0);
            assertEquals(2_000, g.oldestPendingAgeMs());
            var dlq = s.partitions().get(1);
            assertEquals("orders.dlq", dlq.topic());
            assertTrue(dlq.deadLetterQueue());
            assertEquals(1, dlq.enqueuedTotal());
            var prom = PartitionStatsRegistry.prometheus(s);
            assertTrue(prom.contains("sjws_messaging_lag_messages{topic=\"orders\",partition=\"3\",group=\"billing\"} 3"), prom);
            assertTrue(prom.contains("sjws_messaging_dlq_depth{topic=\"orders\",partition=\"3\",group=\"billing\"} 1"));
            // drain: lag goes to zero, age to 0
            p.ack("billing", d.get(0).offset());
            for (var x : p.poll("billing", "c", 10, 60_000)) p.ack("billing", x.offset());
            var g2 = reg.snapshot().partitions().get(0).groups().getFirst();
            assertEquals(0, g2.lagMessages());
            assertEquals(0, g2.oldestPendingAgeMs());
            assertEquals(0, g2.lagOffsets(), "committed reaches log end once everything is resolved");
            assertTrue(reg.unregister("orders", 3));
            assertFalse(reg.unregister("orders", 3));
            assertTrue(reg.snapshot().partitions().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> reg.register(-1, p));
            reg.register(0, p);
            reg.register(0, p);
            assertEquals(1, reg.registeredTopics().size());
        }
    }

    @Test
    void snapshotsDoNotBlockWhileThePartitionIsBusy() throws Exception {
        try (var p = Partition.open(dir, "t", RetryPolicy.DEFAULT, now::get)) {
            var reg = new PartitionStatsRegistry();
            reg.register(0, p);
            p.subscribe("g", true);
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            var w = Thread.ofPlatform().start(() -> { while (!stop.get()) p.produce(0, 0, new byte[8]); });
            long last = -1;
            for (int i = 0; i < 20_000; i++) {
                var g = reg.snapshot().partitions().getFirst().groups().getFirst();
                assertTrue(g.enqueued() >= last, "monotonic while only producing");
                last = g.enqueued();
            }
            stop.set(true);
            w.join();
            assertTrue(last > 0);
            assertEquals("a\\\"b", PartitionStatsRegistry.prometheus(new MessagingStats.StatsSnapshot(0, java.util.List.of(
                    new MessagingStats.PartitionStats("a\"b", 0, false, 0, 0, 0, java.util.List.of(
                            new MessagingStats.GroupStats("g", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))))).lines()
                    .filter(l -> l.startsWith("sjws_messaging_lag_offsets")).findFirst().orElseThrow()
                    .replaceAll(".*topic=\"([^,]*)\",.*", "$1"));
        }
    }
}
