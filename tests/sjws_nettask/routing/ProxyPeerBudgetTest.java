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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

/**
 * #78: a per-peer share of the in-flight body budget. Meant to fail on master (no per-peer
 * constructor, no peer stripes, one client can hold the whole global budget).
 *
 * <p>Waits on {@link ByteBudget#setChangeObserver} rather than sleeping / spinning on budget
 * state (#298 follow-up).
 */
class ProxyPeerBudgetTest {

    static final int SIZE = 1 << 20;
    static final long GLOBAL = 2L << 20;
    static final long PER_PEER = 256 * 1024L;

    @Test
    @Timeout(30)
    void onePeerAtItsShareDoesNotBlockAnotherPeer() throws Exception {
        var reply = "HTTP/1.1 200 OK\r\nContent-Length: " + SIZE + "\r\n\r\n" + "x".repeat(SIZE);
        try (var upstream = script(reply, reply, reply)) {
            var budget = new ByteBudget(GLOBAL, PER_PEER);
            var d = Duration.ofSeconds(10);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(
                    URI.create("http://127.0.0.1:" + upstream.getLocalPort()), d, d, d, 1 << 20, null, List.of(),
                    false, 8), budget)) {
                var peerA = addr(10, 0, 0, 1);
                var peerB = addr(10, 0, 0, 2);
                assertNotEquals(budget.peerStripe(peerA), budget.peerStripe(peerB));

                int stripeA = budget.peerStripe(peerA);
                var held = new CountDownLatch(1);
                budget.setChangeObserver((stripe, peer, global) -> {
                    if (stripe == stripeA && (budget.exhausted(stripeA) || peer >= PER_PEER / 2)) {
                        held.countDown();
                    }
                });
                try {
                    var slow = (Body.Stream) p.handle(request("GET", "/a", peerA)).body();
                    var src = slow.source();
                    assertNotNull(src.next());
                    if (budget.exhausted(stripeA) || budget.peerInFlight(stripeA) >= PER_PEER / 2) {
                        held.countDown();
                    }
                    assertTrue(held.await(10, TimeUnit.SECONDS),
                            "peer A held=" + budget.peerInFlight(stripeA));
                    assertTrue(budget.exhausted(stripeA)
                                    || budget.peerInFlight(stripeA) >= PER_PEER / 2,
                            "peer A held=" + budget.peerInFlight(stripeA));

                    long got = 0;
                    var fast = (Body.Stream) p.handle(request("GET", "/b", peerB)).body();
                    try (var sb = fast.source()) {
                        for (var buf = sb.next(); buf != null; buf = sb.next()) {
                            got += buf.remaining();
                        }
                    }
                    assertEquals(SIZE, got, "peer B must complete while A holds its share");
                    src.close();
                } finally {
                    budget.setChangeObserver(null);
                }
                awaitZero(budget);
            }
        }
    }

    @Test
    @Timeout(30)
    void peerShareIsEnforcedAndNewRequestFailsFastWith503() throws Exception {
        var reply = "HTTP/1.1 200 OK\r\nContent-Length: " + (2 << 20) + "\r\n\r\n" + "y".repeat(2 << 20);
        try (var upstream = script(reply, reply)) {
            var budget = new ByteBudget(GLOBAL, PER_PEER);
            var d = Duration.ofSeconds(10);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(
                    URI.create("http://127.0.0.1:" + upstream.getLocalPort()), d, d, d, 1 << 20, null, List.of(),
                    false, 4), budget)) {
                var peer = addr(10, 1, 2, 3);
                var stripe = budget.peerStripe(peer);
                var exhausted = new CountDownLatch(1);
                budget.setChangeObserver((s, peerBytes, global) -> {
                    if (s == stripe && budget.exhausted(stripe)) {
                        exhausted.countDown();
                    }
                });
                try {
                    var stream = (Body.Stream) p.handle(request("GET", "/hold", peer)).body();
                    var src = stream.source();
                    // Do not take from the source first: next() releases bytes and races the fill.
                    if (budget.exhausted(stripe)) {
                        exhausted.countDown();
                    }
                    assertTrue(exhausted.await(10, TimeUnit.SECONDS),
                            "peer must hit its share; held=" + budget.peerInFlight(stripe)
                                    + " limit=" + PER_PEER);
                    assertTrue(budget.exhausted(stripe),
                            "peer must hit its share; held=" + budget.peerInFlight(stripe)
                                    + " limit=" + PER_PEER);
                    assertEquals(503, p.handle(request("GET", "/again", peer)).status());
                    assertNotNull(src.next());
                    src.close();
                } finally {
                    budget.setChangeObserver(null);
                }
                awaitZero(budget);
                assertEquals(0, budget.peerInFlight(stripe));
            }
        }
    }

    @Test
    @Timeout(30)
    void abortReleasesPeerAndGlobalWithNoLeak() throws Exception {
        var reply = "HTTP/1.1 200 OK\r\nContent-Length: " + (2 << 20) + "\r\n\r\n" + "z".repeat(2 << 20);
        try (var upstream = script(reply)) {
            var budget = new ByteBudget(GLOBAL, PER_PEER);
            var d = Duration.ofSeconds(10);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(
                    URI.create("http://127.0.0.1:" + upstream.getLocalPort()), d, d, d, 1 << 20, null, List.of(),
                    false, 4), budget)) {
                var peer = addr(10, 9, 8, 7);
                var stripe = budget.peerStripe(peer);
                var charged = new CountDownLatch(1);
                budget.setChangeObserver((s, peerBytes, global) -> {
                    if (s == stripe && peerBytes > 0 && global > 0) {
                        charged.countDown();
                    }
                });
                try {
                    var stream = (Body.Stream) p.handle(request("GET", "/drop", peer)).body();
                    var src = stream.source();
                    // Do not take from the source first: next()/take() releases the chunk and can
                    // drain peerInFlight to 0 before the assert (latch already counted down on the
                    // earlier charge; async refill has not yet restored it).
                    if (budget.peerInFlight(stripe) > 0 && budget.inFlight() > 0) {
                        charged.countDown();
                    }
                    assertTrue(charged.await(10, TimeUnit.SECONDS),
                            "peer never charged; held=" + budget.peerInFlight(stripe)
                                    + " global=" + budget.inFlight());
                    assertTrue(budget.peerInFlight(stripe) > 0,
                            "peer must still hold after charge; held=" + budget.peerInFlight(stripe));
                    assertTrue(budget.inFlight() > 0,
                            "global must still hold after charge; inFlight=" + budget.inFlight());
                    src.close();
                } finally {
                    budget.setChangeObserver(null);
                }
                awaitZero(budget);
                assertEquals(0, budget.peerInFlight(stripe));
            }
        }
    }

    @Test
    void unitChargesTrackPerPeerAndDisabledMeansNoSecondCharge() {
        var budget = new ByteBudget(1 << 20, 64 * 1024);
        var a = budget.peerStripe(addr(1, 2, 3, 4));
        var b = budget.peerStripe(addr(5, 6, 7, 8));
        assertNotEquals(a, b);
        budget.add(10_000, a);
        budget.add(20_000, b);
        assertEquals(10_000, budget.peerInFlight(a));
        assertEquals(20_000, budget.peerInFlight(b));
        assertEquals(30_000, budget.inFlight());
        assertTrue(budget.exhausted(a) || budget.peerInFlight(a) < budget.peerLimit());
        budget.release(10_000, a);
        budget.release(20_000, b);
        assertEquals(0, budget.inFlight());
        assertEquals(0, budget.peerInFlight(a));

        var off = new ByteBudget(1 << 20);
        assertEquals(0, off.peerLimit());
        off.add(1000, 99);
        assertEquals(1000, off.inFlight());
        assertEquals(0, off.peerInFlight(99));
        off.release(1000, 99);
        assertEquals(0, off.inFlight());
        assertThrows(IllegalArgumentException.class, () -> new ByteBudget(1 << 20, -1));
    }

    /** Wait for the global counter to return to zero via the change observer (no sleep loop). */
    private static void awaitZero(ByteBudget budget) throws InterruptedException {
        var done = new CountDownLatch(1);
        budget.setChangeObserver((stripe, peer, global) -> {
            if (global == 0) {
                done.countDown();
            }
        });
        try {
            // Check after arming so a release that raced the install still counts:
            // either inFlight is already 0, or the observer will see the transition.
            if (budget.inFlight() != 0) {
                assertTrue(done.await(10, TimeUnit.SECONDS),
                        "budget not drained; inFlight=" + budget.inFlight());
            }
        } finally {
            budget.setChangeObserver(null);
        }
        assertEquals(0, budget.inFlight());
    }

    private static Request request(String method, String target, InetSocketAddress peer) {
        return new Request(method, target, RequestHeaders.of(java.util.Map.of()), peer, false);
    }

    private static InetSocketAddress addr(int a, int b, int c, int d) {
        try {
            return new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d}),
                    0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ServerSocket script(String... replies) throws IOException {
        var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            for (var reply : replies) {
                java.net.Socket accepted;
                try {
                    accepted = server.accept();
                } catch (IOException e) {
                    return;
                }
                Thread.ofVirtual().start(() -> {
                    try (var s = accepted) {
                        readHead(s.getInputStream());
                        s.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1));
                        s.getOutputStream().flush();
                        s.shutdownOutput();
                        s.getInputStream().read();
                    } catch (IOException e) {
                        // proxy closed first
                    }
                });
            }
        });
        return server;
    }

    private static void readHead(InputStream in) throws IOException {
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) {
                throw new IOException("eof");
            }
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1
                    : 0;
        }
    }
}
