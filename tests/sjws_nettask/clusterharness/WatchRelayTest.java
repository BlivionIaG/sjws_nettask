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
package sjws_nettask.clusterharness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Focused coverage of {@link WatchRelay} pause, delay, fallback and close paths. */
@Timeout(10)
class WatchRelayTest {

    private static final byte[] HELLO = new byte[] {'h', 'e', 'l', 'l', 'o'};
    private static final byte[] UP = new byte[] {'u', 'p'};

    @Test
    void pauseHoldsControlToDataUntilResume() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var pair = bindUpstream();
                var relay = new WatchRelay(clock, () -> List.of(pair.port()));
                var session = connect(relay, pair)) {
            relay.pause();
            session.upstream.getOutputStream().write(HELLO);
            session.upstream.getOutputStream().flush();
            await(relay::holding);
            assertEquals(0, session.client.getInputStream().available());
            assertTrue(readTimesOut(session.client, 40));
            relay.resume();
            await(() -> !relay.holding());
            assertArrayEquals(HELLO, readExact(session.client, HELLO.length));
        }
    }

    @Test
    void delayHoldsControlToDataUntilTheClockAdvances() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var pair = bindUpstream();
                var relay = new WatchRelay(clock, () -> List.of(pair.port()));
                var session = connect(relay, pair)) {
            relay.delay(Duration.ofMillis(200).toNanos());
            session.upstream.getOutputStream().write(HELLO);
            session.upstream.getOutputStream().flush();
            await(relay::holding);
            clock.advance(Duration.ofMillis(50));
            assertTrue(readTimesOut(session.client, 40));
            clock.advance(Duration.ofMillis(200));
            await(() -> !relay.holding());
            assertArrayEquals(HELLO, readExact(session.client, HELLO.length));
        }
    }

    @Test
    void delayDoesNotHoldDataToControl() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var pair = bindUpstream();
                var relay = new WatchRelay(clock, () -> List.of(pair.port()));
                var session = connect(relay, pair)) {
            relay.delay(Duration.ofSeconds(30).toNanos());
            session.client.getOutputStream().write(UP);
            session.client.getOutputStream().flush();
            assertArrayEquals(UP, readExact(session.upstream, UP.length));
            assertFalse(relay.holding());
        }
    }

    @Test
    void negativeDelayIsClampedToZero() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var pair = bindUpstream();
                var relay = new WatchRelay(clock, () -> List.of(pair.port()));
                var session = connect(relay, pair)) {
            relay.delay(-5);
            session.upstream.getOutputStream().write(HELLO);
            session.upstream.getOutputStream().flush();
            assertArrayEquals(HELLO, readExact(session.client, HELLO.length));
            assertFalse(relay.holding());
        }
    }

    @Test
    void dropUpstreamResetsThePipe() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var pair = bindUpstream();
                var relay = new WatchRelay(clock, () -> List.of(pair.port()));
                var session = connect(relay, pair)) {
            relay.dropUpstream(pair.port());
            await(() -> {
                try {
                    return session.client.isClosed() || session.upstream.isClosed()
                            || session.client.getInputStream().read() == -1;
                } catch (SocketTimeoutException e) {
                    return false;
                } catch (IOException e) {
                    return true;
                }
            });
        }
    }

    @Test
    void missingUpstreamClosesTheClient() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var relay = new WatchRelay(clock, List::of);
                var client = new Socket()) {
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.port()), 1000);
            client.setSoTimeout(1000);
            await(() -> {
                try {
                    return client.getInputStream().read() == -1 || client.isClosed();
                } catch (SocketTimeoutException e) {
                    return false;
                } catch (IOException e) {
                    return true;
                }
            });
        }
    }

    @Test
    void fallsBackToTheNextLiveUpstream() throws Exception {
        var clock = ClusterClock.Manual.startingAtZero();
        try (var dead = new ServerSocket();
                var live = bindUpstream()) {
            dead.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int deadPort = dead.getLocalPort();
            dead.close();
            try (var relay = new WatchRelay(clock, () -> List.of(deadPort, live.port()));
                    var session = connect(relay, live)) {
                session.upstream.getOutputStream().write(HELLO);
                session.upstream.getOutputStream().flush();
                assertArrayEquals(HELLO, readExact(session.client, HELLO.length));
            }
        }
    }

    private static Upstream bindUpstream() throws IOException {
        var listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        var accepted = new AtomicReference<Socket>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var thread = Thread.ofVirtual().name("watch-relay-test-accept").start(() -> {
            try {
                accepted.set(listener.accept());
            } catch (IOException e) {
                errors.add(e);
            }
        });
        return new Upstream(listener, accepted, errors, thread);
    }

    private static Session connect(WatchRelay relay, Upstream upstream) throws Exception {
        var client = new Socket();
        client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.port()), 1000);
        client.setSoTimeout(1000);
        await(() -> upstream.accepted.get() != null || !upstream.errors.isEmpty());
        assertTrue(upstream.errors.isEmpty(), () -> String.valueOf(upstream.errors));
        var server = upstream.accepted.get();
        server.setSoTimeout(1000);
        return new Session(client, server);
    }

    private static byte[] readExact(Socket socket, int n) throws IOException {
        var out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = socket.getInputStream().read(out, off, n - off);
            if (r < 0) {
                throw new IOException("eof after " + off + " of " + n);
            }
            off += r;
        }
        return out;
    }

    private static boolean readTimesOut(Socket socket, int timeoutMs) throws IOException {
        int previous = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMs);
        try {
            socket.getInputStream().read();
            return false;
        } catch (SocketTimeoutException e) {
            return true;
        } finally {
            socket.setSoTimeout(previous);
        }
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met");
            }
            Thread.sleep(5);
        }
    }

    private record Session(Socket client, Socket upstream) implements AutoCloseable {
        @Override
        public void close() {
            quietly(this.client);
            quietly(this.upstream);
        }
    }

    private record Upstream(ServerSocket listener, AtomicReference<Socket> accepted,
            CopyOnWriteArrayList<Throwable> errors, Thread acceptor) implements AutoCloseable {
        int port() {
            return this.listener.getLocalPort();
        }

        @Override
        public void close() {
            quietly(this.listener);
            var socket = this.accepted.get();
            if (socket != null) {
                quietly(socket);
            }
            this.acceptor.interrupt();
        }
    }

    private static void quietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // Test teardown.
        }
    }
}
