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
package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.messaging.ledger.Durability;

class LogRetentionTest {

    @TempDir Path dir;

    static final long SEG = 512;

    static void fill(MessagingHttp h, String topic, int n) {
        for (int i = 0; i < n; i++) assertEquals(200, MessagingHttpTest.post(h, topic, MessagingHttpTest.recs("x".repeat(60) + i), "group").status());
    }

    @Test
    void sizeLimitDeletesOldestSealedSegmentsOnly() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC, SEG, new LogRetention(2 * SEG, 0), System::currentTimeMillis)) {
            var h = new MessagingHttp(topics);
            fill(h, "t", 60);
            var log = topics.get("t", false).log();
            assertTrue(log.segmentCount() > 4, "segments " + log.segmentCount());
            int deleted = topics.enforceRetention();
            assertTrue(deleted > 0);
            assertTrue(log.totalBytes() <= 2 * SEG + SEG, "bytes " + log.totalBytes()); // at most one segment over
            long start = log.startOffset();
            assertTrue(start > 0);
            assertEquals(416, h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0")).status());
            var r = h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=" + start));
            assertEquals(200, r.status());
            assertEquals(start, MessagingHttpTest.batches(MessagingHttpTest.bytes(r)).get(0).baseOffset());
            assertEquals(0, topics.enforceRetention()); // idempotent
            fill(h, "t", 1); // produce continues after deletion
        }
    }

    @Test
    void ageLimitDeletesOldSealedSegmentsButNeverTheActiveOne() throws Exception {
        var now = new AtomicLong(System.currentTimeMillis());
        try (var topics = new LogTopics(dir, Durability.ASYNC, SEG, new LogRetention(0, 60_000), now::get)) {
            var h = new MessagingHttp(topics);
            fill(h, "a", 30);
            var log = topics.get("a", false).log();
            int before = log.segmentCount();
            assertEquals(0, topics.enforceRetention()); // nothing old yet
            now.addAndGet(3_600_000);
            assertEquals(before - 1, topics.enforceRetention());
            assertEquals(1, log.segmentCount());
            assertTrue(log.segments().get(0).active());
        }
    }

    @Test
    void unlimitedDeletesNothing() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC, SEG, LogRetention.NONE, System::currentTimeMillis)) {
            fill(new MessagingHttp(topics), "n", 30);
            assertEquals(0, topics.enforceRetention());
            assertEquals(LogRetention.NONE, topics.retention());
        }
    }

    @Test
    void policyRules() {
        assertThrows(IllegalArgumentException.class, () -> new LogRetention(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LogRetention(0, -1));
        var p = new LogRetention(100, 1_000);
        assertTrue(p.expired(101, 0, 0));
        assertFalse(p.expired(100, 0, 999));
        assertTrue(p.expired(0, 0, 1_000));
        assertFalse(new LogRetention(0, 0).expired(Long.MAX_VALUE, 0, Long.MAX_VALUE));
    }

    @Test
    void moduleSchedulesRetentionOnlyWhenALimitIsSet() throws Exception {
        var off = new MessagingModule(dir.resolve("off"));
        assertFalse(off.retentionScheduled());
        off.close();
        var on = new MessagingModule(dir.resolve("on"), SEG, new LogRetention(SEG, 0), 10);
        try {
            assertTrue(on.retentionScheduled());
            on.retain();
        } finally {
            on.close();
        }
        assertEquals(5, MessagingModule.number("5", 1));
        assertEquals(1, MessagingModule.number("", 1));
        assertEquals(1, MessagingModule.number("-3", 1));
        assertEquals(1, MessagingModule.number("x", 1));
    }

    @Test
    void scheduledRetentionRuns() throws Exception {
        var m = new MessagingModule(dir, SEG, new LogRetention(SEG, 0), 20);
        try {
            var h = sjws_nettask.core.HandlerMounts.install(java.util.List.of(m), r -> null);
            for (int i = 0; i < 40; i++) {
                var body = MessagingHttpTest.recs("y".repeat(60) + i);
                assertEquals(200, h.handle(new Request("POST", "/messaging/v1/topics/s/records").withBody(MessagingHttpTest.body(body))).status());
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (h.handle(new Request("GET", "/messaging/v1/topics/s/records?offset=0")).status() != 416) {
                assertTrue(System.nanoTime() < deadline, "scheduled retention never ran");
                Thread.sleep(20);
            }
        } finally {
            m.close();
        }
    }

    @Test
    void fetchesShareTheReadLockWhileRetentionWaitsForThem() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC, SEG, new LogRetention(2 * SEG, 0), System::currentTimeMillis)) {
            var h = new MessagingHttp(topics);
            fill(h, "t", 60);
            var reads = topics.get("t", false).reads();
            var held = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var reader = Thread.ofPlatform().start(() -> {
                reads.readLock().lock();
                try { held.countDown(); release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { reads.readLock().unlock(); }
            });
            held.await();
            // another fetch proceeds while a fetch is in flight (an exclusive lock would block it forever)
            var r = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                    () -> h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0")));
            assertEquals(200, r.status());
            MessagingHttpTest.bytes(r);
            // retention must wait for in-flight fetches before deleting segments
            var deleted = new java.util.concurrent.CompletableFuture<Integer>();
            var gc = Thread.ofPlatform().start(() -> {
                try { deleted.complete(topics.enforceRetention()); } catch (Throwable e) { deleted.completeExceptionally(e); }
            });
            while (!reads.hasQueuedThreads()) Thread.onSpinWait();
            assertFalse(deleted.isDone());
            assertEquals(1, reads.getReadLockCount());
            release.countDown();
            assertTrue(deleted.get(10, java.util.concurrent.TimeUnit.SECONDS) > 0);
            reader.join();
            gc.join();
        }
    }
}
