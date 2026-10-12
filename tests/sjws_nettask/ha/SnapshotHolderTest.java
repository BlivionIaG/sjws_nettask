package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SnapshotHolderTest {

    @Test
    void publishSwapsAtomicallyAndRejectsAnOlderGeneration() {
        var holder = new SnapshotHolder<String>();
        assertNull(holder.current());
        var first = new Snapshot<>(1, 10L, "a");
        assertNull(holder.publish(first));
        assertSame(first, holder.current());
        var second = new Snapshot<>(2, 11L, "b");
        assertSame(first, holder.publish(second));
        assertSame(second, holder.current());
        assertSame(second, holder.publish(new Snapshot<>(2, 12L, "c")));
        assertEquals(2, holder.current().generation());
        assertThrows(IllegalArgumentException.class, () -> holder.publish(new Snapshot<>(1, 1L, "old")));
        assertEquals("c", holder.current().value());
        assertThrows(IllegalArgumentException.class, () -> new Snapshot<>(0, 1L, "no"));
        assertThrows(NullPointerException.class, () -> new Snapshot<>(1, 1L, null));
        assertThrows(NullPointerException.class, () -> holder.publish(null));
    }

    @Test
    void readersNeverObserveAGenerationGoingBackwards() throws Exception {
        var holder = new SnapshotHolder<Integer>();
        holder.publish(new Snapshot<>(1, 1L, 1));
        var start = new CountDownLatch(1);
        var failures = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < 4; i++) {
            var t = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long seen = 0;
                for (int n = 0; n < 10_000; n++) {
                    var cur = holder.current();
                    if (cur.generation() < seen) {
                        failures.incrementAndGet();
                    }
                    seen = cur.generation();
                }
            });
            threads.add(t);
        }
        var writer = Thread.ofVirtual().start(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int g = 2; g <= 500; g++) {
                holder.publish(new Snapshot<>(g, g, g));
            }
        });
        start.countDown();
        writer.join();
        for (var t : threads) {
            t.join();
        }
        assertEquals(0, failures.get());
        assertEquals(500, holder.current().generation());
    }
}
