package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** Payloads settled by every group leave the cache early; a lagging group still gets its payloads. */
class SettledPayloadEvictionTest {

    @TempDir Path dir;

    @Test
    void settledPayloadsAreStillReadableByANewGroup() throws Exception {
        var now = new AtomicLong(1_000);
        try (var p = Partition.open(dir, "t", RetryPolicy.DEFAULT, now::get)) {
            p.subscribe("a", true);
            p.subscribe("c", true); // lags: its payloads must stay readable after "a" settles them
            for (int i = 0; i < 200; i++) p.produce(0, 0, new byte[] {(byte) i, 7});
            for (var d : p.poll("a", "w", 200, 60_000)) assertEquals(LeaseResult.OK, p.ack("a", d.offset(), d.attempt()));
            p.produce(0, 0, new byte[] {1}, Durability.GROUP).durable().get();
            var got = p.poll("c", "w", 201, 60_000);
            assertEquals(201, got.size());
            for (int i = 0; i < 200; i++) assertArrayEquals(new byte[] {(byte) i, 7}, got.get(i).payload());
            for (var d : got) p.ack("c", d.offset(), d.attempt()); // now settled by every group: evicted
            assertArrayEquals(new byte[] {3, 7}, got.get(3).payload()); // a delivery keeps its payload after eviction
            p.produce(0, 0, new byte[] {9, 9}, Durability.GROUP).durable().get();
            assertArrayEquals(new byte[] {9, 9}, p.poll("a", "w", 10, 60_000).get(1).payload());
        }
    }
}
