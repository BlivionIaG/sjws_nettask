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
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.Partition;
import sjws_nettask.messaging.delivery.RetryPolicy;

/**
 * {@link Topics#close(String)} racing {@link Topics#partition} on one topic (the reply.&lt;node&gt; close vs.
 * TaskService.reply / produceCancel): no second open of the same directory, and a caller that hits a closed
 * partition gets a fresh, open one on retry.
 */
class TopicsCloseRaceTest {

    @TempDir
    Path dir;

    @Test
    void closeRacingPartitionNeverDoubleOpensAndRetriesGetAnOpenPartition() throws Exception {
        runRace(false);
    }

    @Test
    void closeRacingPartitionWithoutDlqNeverDoubleOpens() throws Exception {
        runRace(true);
    }

    private void runRace(boolean withoutDlq) throws Exception {
        var topics = new Topics(this.dir, new RetryPolicy(5, 0, 0), System::currentTimeMillis);
        String topic = "reply.nodeA";
        int producers = 4;
        var stop = new AtomicBoolean();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var offsetsByInstance = new ConcurrentHashMap<Partition, java.util.Set<Long>>();
        var produced = new AtomicLong();
        var retries = new AtomicLong();
        var start = new CountDownLatch(1);
        var threads = new Thread[producers + 1];
        for (int t = 0; t < producers; t++) {
            long pid = t + 1;
            threads[t] = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    long seq = 0;
                    while (!stop.get()) {
                        seq++;
                        int attempt = 0;
                        while (true) {
                            var p = withoutDlq ? topics.partitionWithoutDlq(topic) : topics.partition(topic);
                            try {
                                var r = p.produce(pid, seq, new byte[] {1});
                                // An offset counts only once durable: a produce that slipped in after Partition.close
                                // set closed but before writer.close() gets an offset whose durable future then fails
                                // with "closed", and the reopened instance reuses that offset. The retry keeps
                                // (pid, seq), so if the record did reach the log the reopen dedups it to its offset
                                // (counted here, once).
                                awaitDurable(r, p, () -> withoutDlq ? topics.partitionWithoutDlq(topic) : topics.partition(topic));
                                offsetsByInstance.computeIfAbsent(p, k -> ConcurrentHashMap.newKeySet()).add(r.offset());
                                produced.incrementAndGet();
                                break;
                            } catch (RuntimeException e) {
                                if (!closedUnderUs(e)) {
                                    throw e; // an unrelated failure must fail the test, not be retried
                                }
                                // got it just before a close started: the retry must get a fresh one
                                retries.incrementAndGet();
                                var again = withoutDlq ? topics.partitionWithoutDlq(topic) : topics.partition(topic);
                                if (again == p && topics.isOpen(topic)) {
                                    // the same instance handed out again after its close finished: a bug
                                    if (++attempt > 1000) {
                                        throw new AssertionError("partition() keeps returning a closed partition");
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        threads[producers] = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                while (!stop.get()) {
                    topics.close(topic);
                    Thread.onSpinWait();
                }
            } catch (Throwable e) {
                errors.add(e);
            }
        });
        start.countDown();
        Thread.sleep(1500);
        stop.set(true);
        for (var th : threads) {
            th.join(30_000);
            assertFalse(th.isAlive(), "a racing thread hung");
        }
        if (!errors.isEmpty()) {
            var e = new AssertionError("racing threads failed: " + errors.peek());
            errors.forEach(e::addSuppressed);
            throw e;
        }
        assertTrue(produced.get() > 0);
        assertTrue(offsetsByInstance.size() > 1, "the closer never closed a partition the producers used");
        // Two Partitions open on one directory at once would both append from the same nextOffset:
        // across every instance, each offset must have been handed out exactly once.
        var all = new java.util.HashSet<Long>();
        long total = 0;
        for (var s : offsetsByInstance.values()) {
            total += s.size();
            all.addAll(s);
        }
        assertEquals(total, all.size(), "an offset was appended twice: two partitions shared a directory");
        assertEquals(produced.get(), total);
        // The ledger agrees after a clean reopen.
        topics.close(topic);
        var fresh = withoutDlq ? topics.partitionWithoutDlq(topic) : topics.partition(topic);
        assertSame(fresh, withoutDlq ? topics.partitionWithoutDlq(topic) : topics.partition(topic));
        assertEquals(produced.get(), fresh.produce(99, 1, new byte[] {2}).offset());
        topics.close();
    }

    /**
     * Waits for {@code r} to be durable. A produce that passed the writer's closed check just as
     * {@code LedgerWriter.close()} drained its queue can be left with a durable future that never completes
     * (Partition.produce does not check {@code closed} under its lock yet: the Messaging Core follow-up from the #240
     * review). That can only happen on an instance {@link Topics#close(String)} already removed, so a wait that stalls
     * while {@code p} is no longer the current instance counts as closed under us. Retrying is safe either way: the retry
     * keeps (pid, seq), so a record that did reach the log is deduplicated to its offset.
     */
    private static void awaitDurable(Partition.ProduceResult r, Partition p, java.util.function.Supplier<Partition> current) {
        while (true) {
            try {
                r.durable().get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new java.util.concurrent.CompletionException(e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                if (current.get() != p) {
                    throw new StrandedOnClose();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
    }

    /** A durable wait that stalled on an instance {@link Topics#close(String)} had already removed. */
    private static final class StrandedOnClose extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StrandedOnClose() {
            super("durable wait stalled on a closed partition", null, false, false);
        }
    }

    /** The failure of a produce on a partition closed under the caller: the writer's "closed", possibly wrapped. */
    private static boolean closedUnderUs(Throwable e) {
        while ((e instanceof java.util.concurrent.CompletionException
                || e instanceof java.util.concurrent.ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        return e instanceof StrandedOnClose || e instanceof IllegalStateException && "closed".equals(e.getMessage());
    }

    @Test
    void closedTopicsRefuseAndCloseOfUnknownTopicIsANoOp() {
        var topics = new Topics(this.dir, new RetryPolicy(5, 0, 0), System::currentTimeMillis);
        topics.close("never-opened");
        var a = topics.partition("t");
        topics.close("t");
        assertFalse(topics.isOpen("t"));
        var b = topics.partition("t");
        assertNotSame(a, b);
        topics.close();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> topics.partition("t"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> topics.partitionWithoutDlq("t"));
    }
}
