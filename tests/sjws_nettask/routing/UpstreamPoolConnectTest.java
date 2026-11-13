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
package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Connect clean-up in {@link UpstreamPool#acquire(Duration)}: an abandoned connect never leaves a socket open. */
class UpstreamPoolConnectTest {

    /**
     * Codex P2 on #477: a probe interrupted while it waits for a new connection (EndpointBalancer
     * close -> probeWorkers.shutdownNow()) must cancel the connect and close its channel, even though
     * the connect itself goes on to succeed. Before the fix the socket stayed open with nobody owning it.
     */
    @Test
    @Timeout(30)
    void interruptedConnectWaitClosesTheLateConnection() throws Exception {
        try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                var pool = new UpstreamPool(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/"),
                        Duration.ofSeconds(5), 4, new ByteBudget(1 << 20))) {
            // Interrupted before the wait: acquire() starts the connect, then get() throws at once,
            // so the connect completes only after the caller gave up (the "slow connect" case).
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class, () -> pool.acquire(Duration.ofSeconds(2)));
            } finally {
                Thread.interrupted(); // keep the test thread clean whatever acquire() did with the flag
            }
            server.setSoTimeout(2000);
            Socket accepted;
            try {
                accepted = server.accept();
            } catch (SocketTimeoutException e) {
                return; // closed before the SYN went out: nothing leaked
            }
            try (accepted) {
                accepted.setSoTimeout(5000);
                int read;
                try {
                    read = accepted.getInputStream().read();
                } catch (SocketTimeoutException e) {
                    fail("the abandoned upstream connection is still open (orphaned socket)");
                    return;
                } catch (IOException reset) {
                    return; // RST: closed
                }
                assertEquals(-1, read, "the abandoned upstream connection should be closed");
            }
            assertEquals(0, pool.idleCount());
        }
    }

    /**
     * Codex P2 on #475 (Security BLOCKER): interrupting or timing out a connecting acquire
     * must cancel the connect and keep its max_connections slot until the channel closes.
     * Releasing the slot on the request thread lets a second acquire start another socket
     * while the first is still connecting.
     */
    @Test
    @Timeout(30)
    void interruptedConnectHoldsTheSlotUntilTheChannelCloses() throws Exception {
        var admission = Admission.of(0, 1);
        // Gate connect completion before starting the worker (Codex P2 on #475): a
        // listening ServerSocket can finish the handshake into its backlog, and
        // acquire() can succeed, before the main thread reaches parkEventLoops().
        // TEST-NET-1 is unroutable so the connect stays in flight; parking the
        // event loops first also keeps close (and the slot release) queued.
        try (var pool = new UpstreamPool(URI.create("http://192.0.2.1:9/"),
                        Duration.ofSeconds(5), 4, new ByteBudget(1 << 20), admission)) {
            var started = new CountDownLatch(1);
            var interrupted = new CompletableFuture<Void>();
            var resume = parkEventLoops();
            try {
                var first = Thread.ofPlatform().start(() -> {
                    try {
                        started.countDown();
                        pool.acquire();
                        interrupted.completeExceptionally(new AssertionError("acquire should have been interrupted"));
                    } catch (InterruptedException e) {
                        interrupted.complete(null);
                    } catch (Throwable e) {
                        interrupted.completeExceptionally(e);
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 50 && admission.connections() == 0; i++) {
                    Thread.sleep(10);
                }
                assertEquals(1, admission.connections());

                first.interrupt();
                interrupted.get(5, TimeUnit.SECONDS);
                assertEquals(1, admission.connections());
                var stolen = admission.tryConnection();
                if (stolen != null) {
                    stolen.release();
                    fail("the slot was released before the abandoned channel closed");
                }
            } finally {
                resume.run();
            }
            for (int i = 0; i < 50 && admission.connections() > 0; i++) {
                Thread.sleep(20);
            }
            assertEquals(0, admission.connections());
        }
    }

    @Test
    @Timeout(30)
    void timedOutConnectHoldsTheSlotUntilTheChannelCloses() throws Exception {
        var admission = Admission.of(0, 1);
        // A listening ServerSocket would complete the TCP handshake into its backlog and
        // acquire() would succeed; TEST-NET-1 is unroutable so the connect stays in flight.
        try (var pool = new UpstreamPool(URI.create("http://192.0.2.1:9/"),
                        Duration.ofSeconds(5), 4, new ByteBudget(1 << 20), admission)) {
            var started = new CountDownLatch(1);
            var finished = new CompletableFuture<Throwable>();
            Thread.ofPlatform().start(() -> {
                try {
                    started.countDown();
                    pool.acquire(Duration.ofMillis(200));
                    finished.complete(null);
                } catch (Throwable e) {
                    finished.complete(e);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 50 && admission.connections() == 0; i++) {
                Thread.sleep(10);
            }
            assertEquals(1, admission.connections());

            var resume = parkEventLoops();
            try {
                // The connect wait has timed out (or will within 200 ms) and close is queued
                // behind the parked event loops. The slot must still be held.
                for (int i = 0; i < 30 && !finished.isDone(); i++) {
                    Thread.sleep(20);
                }
                assertEquals(1, admission.connections());
                var stolen = admission.tryConnection();
                if (stolen != null) {
                    stolen.release();
                    fail("the slot was released before the timed-out channel closed");
                }
                assertFalse(finished.isDone(), "acquire returned before the abandoned channel closed");
            } finally {
                resume.run();
            }
            var outcome = finished.get(5, TimeUnit.SECONDS);
            assertInstanceOf(UpstreamPool.ConnectTimeout.class, outcome);
            for (int i = 0; i < 50 && admission.connections() > 0; i++) {
                Thread.sleep(20);
            }
            assertEquals(0, admission.connections());
        }
    }

    /**
     * Codex P2 on #475: if the close wait times out because the event loops are still
     * parked, awaitAbandonedSlot must leave the hold on the close-future listener.
     * Releasing then would let a second acquire open another socket while the first
     * is still open, exceeding max_connections.
     */
    @Test
    @Timeout(30)
    void closeWaitTimeoutKeepsTheSlotUntilTheChannelCloses() throws Exception {
        var admission = Admission.of(0, 1);
        // Short connect timeout so the close wait expires while the loops are parked.
        try (var pool = new UpstreamPool(URI.create("http://192.0.2.1:9/"),
                        Duration.ofMillis(200), 4, new ByteBudget(1 << 20), admission)) {
            var started = new CountDownLatch(1);
            var finished = new CompletableFuture<Throwable>();
            var resume = parkEventLoops();
            try {
                Thread.ofPlatform().start(() -> {
                    try {
                        started.countDown();
                        pool.acquire(Duration.ofMillis(50));
                        finished.complete(null);
                    } catch (Throwable e) {
                        finished.complete(e);
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var outcome = finished.get(5, TimeUnit.SECONDS);
                assertInstanceOf(UpstreamPool.ConnectTimeout.class, outcome);
                assertEquals(1, admission.connections());
                var stolen = admission.tryConnection();
                if (stolen != null) {
                    stolen.release();
                    fail("the slot was released before the abandoned channel closed");
                }
                assertThrows(UpstreamPool.ConnectionLimit.class, () -> pool.acquire(Duration.ofMillis(50)));
            } finally {
                resume.run();
            }
            for (int i = 0; i < 50 && admission.connections() > 0; i++) {
                Thread.sleep(20);
            }
            assertEquals(0, admission.connections());
        }
    }

    /**
     * Codex P1 on #475: with no max_connections cap there is no slot to reclaim, so a
     * timed-out acquire must return without waiting for the abandoned channel to close.
     */
    @Test
    @Timeout(30)
    void uncappedTimedOutAcquireDoesNotWaitForClose() throws Exception {
        try (var pool = new UpstreamPool(URI.create("http://192.0.2.1:9/"),
                        Duration.ofSeconds(5), 4, new ByteBudget(1 << 20))) {
            var resume = parkEventLoops();
            try {
                long start = System.nanoTime();
                assertThrows(UpstreamPool.ConnectTimeout.class, () -> pool.acquire(Duration.ofMillis(50)));
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(elapsedMs < 1000,
                        "uncapped acquire waited on close: " + elapsedMs + " ms (connect timeout is 5 s)");
            } finally {
                resume.run();
            }
        }
    }

    /**
     * Queues a parking task on every upstream event loop so close futures cannot complete
     * until the returned runnable runs.
     */
    private static Runnable parkEventLoops() throws Exception {
        var field = UpstreamPool.class.getDeclaredField("loops");
        field.setAccessible(true);
        var loops = (io.netty.channel.EventLoopGroup) field.get(null);
        assertNotNull(loops);
        var evs = new ArrayList<io.netty.util.concurrent.EventExecutor>();
        loops.forEach(evs::add);
        var parked = new CountDownLatch(evs.size());
        var resume = new CountDownLatch(1);
        for (var ev : evs) {
            ev.execute(() -> {
                parked.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        if (!parked.await(5, TimeUnit.SECONDS)) {
            resume.countDown();
            fail("event loops did not park");
        }
        return resume::countDown;
    }
}
