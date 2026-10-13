package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** poll(…, waitNanos): parks without spinning, woken by appends and by due redeliveries. Real clock. */
class BlockingPollTest {

    @TempDir Path dir;
    static final RetryPolicy POLICY = new RetryPolicy(3, 1, 10);
    static final long MS = 1_000_000L;

    Partition open() throws IOException {
        var p = Partition.open(dir, "t", POLICY, System::currentTimeMillis, Durability.ASYNC, 64 * 1024);
        p.snapshotPolicy(0, 0, 10_000);
        return p;
    }

    @Test
    void returnsImmediatelyWhenSomethingIsDeliverable() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            p.produce(0, 0, new byte[] {1});
            long t = System.nanoTime();
            assertEquals(1, p.poll("g", "c", 10, 1_000, 5_000 * MS).size());
            assertTrue(System.nanoTime() - t < 1_000 * MS);
        }
    }

    @Test
    void timesOutEmptyWithoutSpinning() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            long t = System.nanoTime();
            var cpu0 = cpu();
            assertTrue(p.poll("g", "c", 1, 1_000, 200 * MS).isEmpty());
            long took = System.nanoTime() - t;
            assertTrue(took >= 190 * MS && took < 2_000 * MS, "took " + took);
            assertTrue(cpu() - cpu0 < 100 * MS, "parked, not spinning: cpu " + (cpu() - cpu0));
            assertTrue(p.poll("g", "c", 1, 1_000, 0).isEmpty(), "zero wait = non-blocking");
            assertThrows(IllegalArgumentException.class, () -> p.poll("g", "c", 1, 1_000, -1));
        }
    }

    private static long cpu() {
        return java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    @Test
    void wakesOnProduceFromAnotherThread() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            p.subscribe("h", true);
            var g = CompletableFuture.supplyAsync(() -> p.poll("g", "a", 5, 1_000, 10_000 * MS));
            var h = CompletableFuture.supplyAsync(() -> p.poll("h", "b", 5, 1_000, 10_000 * MS));
            Thread.sleep(100);
            long t = System.nanoTime();
            p.produce(0, 0, new byte[] {7});
            assertEquals(1, g.get(5, TimeUnit.SECONDS).size(), "every group's waiter is woken");
            assertEquals(1, h.get(5, TimeUnit.SECONDS).size());
            assertTrue(System.nanoTime() - t < 1_000 * MS);
        }
    }

    @Test
    void manyWaitersOfOneGroupEachGetOneMessage() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            var fs = new java.util.ArrayList<CompletableFuture<List<Delivery>>>();
            for (int i = 0; i < 4; i++) { int k = i; fs.add(CompletableFuture.supplyAsync(() -> p.poll("g", "w" + k, 1, 1_000, 10_000 * MS))); }
            Thread.sleep(100);
            for (int i = 0; i < 4; i++) p.produce(0, 0, new byte[] {(byte) i});
            var offsets = new java.util.HashSet<Long>();
            for (var f : fs) offsets.add(f.get(5, TimeUnit.SECONDS).getFirst().offset());
            assertEquals(4, offsets.size(), "no lost wakeup, no double delivery");
        }
    }

    @Test
    void wakesWhenALeaseExpiresOrANackBecomesDue() throws Exception {
        try (var p = open()) {
            p.subscribe("g", true);
            p.produce(0, 0, new byte[] {1});
            p.poll("g", "a", 1, 300, 0); // lease 300 ms, nothing else pending
            long t = System.nanoTime();
            var re = p.poll("g", "b", 1, 10_000, 10_000 * MS);
            long took = System.nanoTime() - t;
            assertEquals(2, re.getFirst().attempt(), "redelivered after expiry");
            assertTrue(took >= 250 * MS && took < 3_000 * MS, "woke near the lease deadline: " + took / MS + " ms");
            // a nack by another thread makes it due sooner than the waiter planned: signalled
            var waiter = CompletableFuture.supplyAsync(() -> p.poll("g", "c", 1, 1_000, 10_000 * MS));
            Thread.sleep(100);
            assertEquals(LeaseResult.OK, p.nack("g", re.getFirst().offset(), 2, 0));
            assertEquals(3, waiter.get(5, TimeUnit.SECONDS).getFirst().attempt());
        }
    }

    @Test
    void closeAndInterruptReleaseWaiters() throws Exception {
        var p = open();
        p.subscribe("g", true);
        var t = Thread.ofPlatform().start(() -> assertTrue(p.poll("g", "x", 1, 1_000, 60_000 * MS).isEmpty()));
        Thread.sleep(100);
        t.interrupt();
        t.join(5_000);
        assertFalse(t.isAlive());
        var f = CompletableFuture.supplyAsync(() -> p.poll("g", "y", 1, 1_000, 60_000 * MS));
        Thread.sleep(100);
        p.close();
        assertTrue(f.get(5, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void dueRedeliveriesAreReadLazilyAndDeadLetterInOrder() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        try (var p = Partition.open(dir, "lazy", POLICY, clock::get, Durability.ASYNC, 64 * 1024)) {
            p.subscribe("g", true);
            for (int i = 0; i < 200; i++) p.produce(0, 0, new byte[] {1});
            var ds = p.poll("g", "a", 200, 10);
            for (var d : ds) p.nack("g", d.offset(), d.attempt(), 0);
            clock.addAndGet(1_000);
            var two = p.poll("g", "b", 2, 10);
            assertEquals(List.of(ds.get(0).offset(), ds.get(1).offset()), two.stream().map(Delivery::offset).toList());
            clock.addAndGet(1_000); // the 2 leases expire, 198 nacks still due at attempt 1, the 2 at attempt 2
            var rest = p.poll("g", "c", 1_000, 10);
            assertEquals(200, rest.size());
            for (var d : rest) p.nack("g", d.offset(), d.attempt(), 0);
            clock.addAndGet(1_000);
            p.poll("g", "d", 1_000, 10).forEach(d -> p.nack("g", d.offset(), d.attempt(), 0));
            clock.addAndGet(1_000);
            assertTrue(p.poll("g", "e", 1_000, 10).isEmpty(), "max attempts reached: all dead-lettered");
            assertEquals(200, p.counts().groups().get("g").counts().deadLettered());
        }
    }
}
