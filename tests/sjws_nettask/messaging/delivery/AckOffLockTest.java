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
package sjws_nettask.messaging.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/**
 * Ledger appends are applied under the partition lock but handed to the writer after it is released (staged in
 * apply order). The ledger order must still equal the apply order, a durable future must mean the record is in the
 * log, and recovery must rebuild exactly the state the live partition had.
 */
class AckOffLockTest {

    @TempDir Path dir;
    final AtomicLong now = new AtomicLong(1_000_000);

    Partition open() throws IOException {
        return Partition.open(dir, "q", new RetryPolicy(5, 10, 100), now::get, Durability.ASYNC, 64 << 10);
    }

    @Test
    void aDurableAckIsInTheLogAndSurvivesRecovery() throws Exception {
        long acked;
        try (var p = open()) {
            p.subscribe("g", true);
            for (int i = 0; i < 10; i++) p.produce(0, 0, ("m" + i).getBytes(StandardCharsets.UTF_8));
            var d = p.poll("g", "c", 1, 60_000).get(0);
            acked = d.offset();
            var r = p.ack("g", acked, d.attempt(), Durability.GROUP);
            assertEquals(LeaseResult.OK, r.result());
            long at = r.durable().get(10, TimeUnit.SECONDS);
            // durable means written: the writer has passed the ack record
            try (var log = sjws_nettask.messaging.ledger.SegmentedLog.open(dir.resolve("q"), 64 << 10)) {
                assertTrue(log.nextOffset() > at, "ack record " + at + " in the log (next " + log.nextOffset() + ")");
            }
        }
        try (var p = open()) { // the acked message is never redelivered; the rest are, once their lease expires
            now.addAndGet(120_000);
            var got = new ArrayList<Long>();
            for (var d : p.poll("g", "c", 100, 60_000)) got.add(d.offset());
            assertFalse(got.contains(acked), "acked " + acked + " redelivered: " + got);
            assertEquals(9, got.size());
        }
    }

    @RepeatedTest(5)
    void concurrentClaimAckKeepsLogOrderAndRecoversTheSameState() throws Exception {
        int threads = 16, perThread = 400;
        String live;
        var futures = new java.util.concurrent.ConcurrentLinkedQueue<CompletableFuture<Long>>();
        try (var p = open()) {
            p.subscribe("g", true);
            var start = new CountDownLatch(1);
            var ts = new ArrayList<Thread>();
            for (int t = 0; t < threads; t++) {
                int id = t;
                ts.add(Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            futures.add(p.produce(id, i + 1, ("t" + id + "-" + i).getBytes(StandardCharsets.UTF_8)).durable());
                            for (var d : p.poll("g", "w" + id, 2, 60_000)) {
                                if ((d.offset() & 7) == 0) assertTrue(p.nack("g", d.offset(), d.attempt(), 0).ok());
                                else futures.add(p.ack("g", d.offset(), d.attempt(), (Durability) null).durable());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (var t : ts) t.join();
            // every future completes with its own offset: a mismatch (out-of-order handoff) fails it with "drift"
            long prev = -1;
            var offs = new java.util.TreeSet<Long>();
            for (var f : futures) assertTrue(offs.add(f.get(10, TimeUnit.SECONDS)), "duplicate ledger offset");
            for (long o : offs) { assertTrue(o > prev); prev = o; }
            p.snapshotNow().get(10, TimeUnit.SECONDS);
            live = p.canonicalState();
        }
        try (var p = open()) {
            assertEquals(live, p.canonicalState(), "recovery replays to the live state");
        }
    }

    @Test
    void appendsAfterTheWriterFailedFailFast() throws Exception {
        var p = open();
        p.subscribe("g", true);
        p.produce(0, 0, "a".getBytes(StandardCharsets.UTF_8)).durable().get();
        var d = p.poll("g", "c", 1, 60_000).get(0);
        p.close(); // writer closed: the handoff fails and the ack's durability future says so
        var r = p.ack("g", d.offset(), d.attempt(), Durability.GROUP);
        assertEquals(LeaseResult.OK, r.result());
        assertTrue(r.durable().isCompletedExceptionally(), "never reported durable");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> p.produce(0, 0, "b".getBytes(StandardCharsets.UTF_8)), "later appends fail fast");
    }
}
