package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Pins Architecture 2026-10-30 18:19: regular consumer-group Deliver/Ack keep the writer default (ASYNC →
 * schedules force); stream-style NONE Deliver/Ack do not. Requires append-mode dirtiness (#342).
 */
class DeliverAckDurabilityTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);
    Partition p;

    @AfterEach
    void close() throws Exception {
        if (p != null) p.close();
    }

    private Partition open() throws Exception {
        p = Partition.open(dir, "orders", new RetryPolicy(3, 100, 1_000), now::get, Durability.ASYNC, 64 * 1024, false);
        p.subscribe("g", false);
        return p;
    }

    /** Wait until durable has caught nextOffset (ASYNC produce force settled). */
    private void awaitFullyDurable() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (p.writerSnapshot().durableOffset() < p.writerSnapshot().nextOffset() && System.nanoTime() < until) {
            Thread.sleep(5);
        }
        assertEquals(p.writerSnapshot().nextOffset(), p.writerSnapshot().durableOffset(), "writer must be fully durable");
    }

    @Test
    void defaultPollAndAckScheduleBackgroundForce() throws Exception {
        open();
        p.produce(1, 1, "a".getBytes(StandardCharsets.UTF_8), Durability.ASYNC).durable().get(5, TimeUnit.SECONDS);
        awaitFullyDurable();
        long f0 = p.writerSnapshot().fsyncs();
        var got = p.poll("g", "c", 1, 60_000); // null deliver durability → writer default ASYNC
        assertEquals(1, got.size());
        assertNotNull(p.ack("g", got.get(0).offset(), got.get(0).attempt(), null).durable());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (p.writerSnapshot().fsyncs() <= f0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertTrue(p.writerSnapshot().fsyncs() > f0,
                "regular consumer-group Deliver/Ack must keep ASYNC force; fsyncs=" + p.writerSnapshot().fsyncs());
    }

    @Test
    void nonePollAndAckDoNotScheduleBackgroundForce() throws Exception {
        open();
        p.produce(1, 1, "a".getBytes(StandardCharsets.UTF_8), Durability.ASYNC).durable().get(5, TimeUnit.SECONDS);
        awaitFullyDurable();
        long f0 = p.writerSnapshot().fsyncs();
        long next0 = p.writerSnapshot().nextOffset();
        var got = p.poll("g", "c", 1, 60_000, Durability.NONE);
        assertEquals(1, got.size());
        var ack = p.ack("g", got.get(0).offset(), got.get(0).attempt(), Durability.NONE);
        assertNotNull(ack.durable());
        ack.durable().get(5, TimeUnit.SECONDS); // NONE completes without waiting for force
        Thread.sleep(200); // well past a typical floor tick; still must not force for NONE-only dirtiness
        assertEquals(f0, p.writerSnapshot().fsyncs(),
                "NONE Deliver/Ack must not schedule background fsyncs; f0=" + f0 + " now=" + p.writerSnapshot().fsyncs());
        assertTrue(p.writerSnapshot().nextOffset() > next0, "Deliver/Ack appended");
        assertEquals(next0, p.writerSnapshot().durableOffset(),
                "NONE Deliver/Ack leave durable behind (read watermark not crash-durable)");
    }

    /**
     * #416: NONE Deliver/Ack are not crash-durable. After a crash that drops the unsynced tail
     * (simulated by truncating frames past the pre-Deliver durableOffset), the message is due again
     * and is redelivered.
     */
    @Test
    void noneDeliverAckLostOnCrashAreRedelivered() throws Exception {
        open();
        p.produce(1, 1, "a".getBytes(StandardCharsets.UTF_8), Durability.ASYNC).durable().get(5, TimeUnit.SECONDS);
        awaitFullyDurable();
        long durableBefore = p.writerSnapshot().durableOffset();
        var got = p.poll("g", "c", 1, 60_000, Durability.NONE);
        assertEquals(1, got.size());
        long msgOff = got.get(0).offset();
        p.ack("g", msgOff, got.get(0).attempt(), Durability.NONE).durable().get(5, TimeUnit.SECONDS);
        assertTrue(p.poll("g", "c", 1, 1_000, Durability.NONE).isEmpty(), "acked in memory before crash");
        assertTrue(p.writerSnapshot().nextOffset() > durableBefore);
        assertEquals(durableBefore, p.writerSnapshot().durableOffset(),
                "NONE Deliver/Ack leave durable behind until a later force");
        // Close forces residual NONE bytes. Truncate those frames to simulate a crash before fsync.
        p.close();
        p = null;
        var file = dir.resolve("orders").resolve(String.format("%020d.log", 0));
        long size = Files.size(file);
        int cut = 0;
        try (var log = SegmentedLog.open(dir.resolve("orders"))) {
            var batches = log.readAll();
            for (int i = batches.size() - 1; i >= 0; i--) {
                var b = batches.get(i);
                if (b.baseOffset() >= durableBefore) {
                    cut += b.encode().remaining();
                } else {
                    break;
                }
            }
        }
        assertTrue(cut > 0, "expected unsynced Deliver/Ack frames to drop");
        try (var ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(size - cut);
        }
        p = open();
        var again = p.poll("g", "c", 1, 60_000);
        assertEquals(1, again.size(), "message redelivered after NONE Deliver/Ack lost in crash");
        assertEquals(msgOff, again.get(0).offset());
        assertEquals(1, again.get(0).attempt(), "fresh delivery after both Deliver and Ack were lost");
    }

}
