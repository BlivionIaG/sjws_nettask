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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.*;
import static sjws_nettask.h3.SecurityReviewTest.outcomes;
import static sjws_nettask.h3.SecurityReviewTest.waitFor;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.core.VerificationRegressionTest;
import sjws_nettask.tls.TlsMaterial;

/**
 * The security verification's HTTP/3 repros (V1, V2, V3/V4 over HTTP/3, V5), inverted to assert the
 * fixes.
 */
@Tag("h3")
class VerificationTest {

    @TempDir
    Path base;
    Path root;
    StaticFileHandler handler;
    TlsMaterial tls;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable());
        this.root = H3Fixture.webRoot(this.base).toRealPath();
        this.handler = new StaticFileHandler(this.root);
        this.tls = TlsMaterial.selfSigned("localhost");
    }

    ServerConfig.Builder config() {
        return ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0);
    }

    /** Forwards client datagrams to the server and drops every answer, like a spoofed source address. */
    static final class OneWayRelay implements AutoCloseable {
        final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());

        OneWayRelay(InetSocketAddress target) throws Exception {
            Thread.ofPlatform().daemon().start(() -> {
                var buf = new byte[2048];
                try {
                    while (true) {
                        var p = new DatagramPacket(buf, buf.length);
                        this.socket.receive(p);
                        if (p.getPort() != target.getPort()) {
                            this.socket.send(new DatagramPacket(p.getData(), p.getLength(), target));
                        }
                    }
                } catch (Exception e) {
                    // closed
                }
            });
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), this.socket.getLocalPort());
        }

        /** Starts {@code n} handshakes through the relay; none of them can complete. */
        void attempt(H3TestClient client, int n) {
            for (int i = 0; i < n; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        client.connect(address());
                    } catch (Exception e) {
                        // never completes
                    }
                });
            }
        }

        @Override
        public void close() {
            this.socket.close();
        }
    }

    /**
     * V2 / B2 inverted: eight handshakes that can never complete (one-way relay) hold no
     * {@code h3MaxConnections} slot (8 here); a real client is served.
     */
    @Test
    void v2HalfOpenHandshakesHoldNoConnectionSlot() throws Exception {
        var cfg = config().h3MaxConnections(8).idleTimeout(Duration.ofSeconds(20)).build();
        try (var server = new Http3Server(cfg, this.handler, this.tls).start();
                var relay = new OneWayRelay(server.localAddress());
                var blackholed = new H3TestClient(this.tls.certificate());
                var victim = new H3TestClient(this.tls.certificate())) {
            relay.attempt(blackholed, 8);
            waitFor(() -> server.halfOpen() == 8, 5, "eight half-open handshakes");
            assertEquals(0, server.connections(), "half-open ones are not connections");
            var q = victim.connect(server.localAddress());
            var r = H3TestClient.get(q, "/hello.txt");
            assertEquals(200, r.status());
            assertEquals(1, server.connections());
        }
    }

    /** V2: a handshake not completed within the handshake timeout is dropped and frees its half-open slot. */
    @Test
    void v2UnfinishedHandshakesAreDroppedAfterTheTimeout() throws Exception {
        var cfg = config().idleTimeout(Duration.ofSeconds(20)).build();
        try (var server = new Http3Server(cfg, this.handler, this.tls)
                .limits(Limits.of(cfg).withHandshake(Duration.ofMillis(500), 256)).start();
                var relay = new OneWayRelay(server.localAddress());
                var blackholed = new H3TestClient(this.tls.certificate())) {
            relay.attempt(blackholed, 8);
            waitFor(() -> server.droppedHandshakes() >= 8, 5, "the handshake deadline to drop all eight");
            relay.close(); // no retransmitted Initials from here on
            waitFor(() -> server.halfOpen() == 0, 5, "no half-open handshake left");
            assertEquals(0, server.connections());
        }
    }

    /**
     * V2: half-open handshakes have their own, smaller cap; the excess is dropped at once. Known gap:
     * while it is full, new (real) clients are dropped too, until the timeout frees it; established
     * connections are not affected.
     */
    @Test
    void v2HalfOpenCapDropsTheExcess() throws Exception {
        var cfg = config().idleTimeout(Duration.ofSeconds(20)).build();
        try (var server = new Http3Server(cfg, this.handler, this.tls)
                .limits(Limits.of(cfg).withHandshake(Duration.ofMillis(1500), 4)).start();
                var relay = new OneWayRelay(server.localAddress());
                var blackholed = new H3TestClient(this.tls.certificate());
                var established = new H3TestClient(this.tls.certificate());
                var late = new H3TestClient(this.tls.certificate())) {
            var q = established.connect(server.localAddress());
            relay.attempt(blackholed, 8);
            waitFor(() -> server.halfOpen() == 4 && server.droppedHandshakes() >= 4, 5, "cap of 4 reached");
            assertTrue(server.halfOpen() <= 4);
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status(), "established connections keep working");
            relay.close();
            waitFor(() -> server.halfOpen() == 0, 5, "the timeout to free the cap");
            assertEquals(200, H3TestClient.get(late.connect(server.localAddress()), "/hello.txt").status());
        }
    }

    /** V1: one address gets at most {@code h3MaxConnectionsPerIp} connections; the next is refused. */
    @Test
    void v1ConnectionsPerAddressAreCapped() throws Exception {
        var cfg = config().build();
        try (var server = new Http3Server(cfg, this.handler, this.tls)
                .limits(Limits.of(cfg).withConnectionsPerIp(2)).start();
                var c1 = new H3TestClient(this.tls.certificate());
                var c2 = new H3TestClient(this.tls.certificate());
                var c3 = new H3TestClient(this.tls.certificate())) {
            var q1 = c1.connect(server.localAddress());
            var q2 = c2.connect(server.localAddress());
            assertEquals(200, H3TestClient.get(q1, "/hello.txt").status());
            assertEquals(200, H3TestClient.get(q2, "/hello.txt").status());
            Http3ServerTest.assertRefused(c3, server);
            q1.close().sync();
            waitFor(() -> server.connections() == 1, 5, "the first connection to go");
            assertEquals(200, H3TestClient.get(c3.connect(server.localAddress()), "/hello.txt").status(),
                    "a closed connection frees its address slot");
        }
        assertEquals(ServerConfig.DEFAULT_H3_MAX_CONNECTIONS_PER_IP, Limits.of(cfg).connectionsPerIp());
    }

    /** A client with a small per-stream receive window: a paused stream costs the server almost nothing. */
    final class SmallWindowClient implements AutoCloseable {
        final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        final io.netty.channel.Channel datagram;

        SmallWindowClient(long streamWindow) throws Exception {
            var ssl = QuicSslContextBuilder.forClient().trustManager(VerificationTest.this.tls.certificate())
                    .applicationProtocols(Http3.supportedApplicationProtocols()).build();
            var codec = Http3.newQuicClientCodecBuilder().sslContext(ssl)
                    .sslEngineProvider(q -> ssl.newEngine(q.alloc(), "localhost", 443))
                    .maxIdleTimeout(60_000, TimeUnit.MILLISECONDS).initialMaxData(64L * 1024 * 1024)
                    .initialMaxStreamDataBidirectionalLocal(streamWindow).build();
            this.datagram = new Bootstrap().group(this.group).channel(NioDatagramChannel.class).handler(codec)
                    .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        }

        QuicChannel connect(InetSocketAddress server) throws Exception {
            return QuicChannel.newBootstrap(this.datagram).handler(new Http3ClientConnectionHandler())
                    .remoteAddress(server).localAddress(this.datagram.localAddress()).connect().get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            this.datagram.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
            this.group.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    /** Keeps a never-read GET open on {@code q}, re-issuing it (after 50 ms) whenever the server ends it. */
    static void holdForever(QuicChannel q, AtomicBoolean stop) {
        if (stop.get() || !q.isActive()) {
            return;
        }
        Http3.newRequestStream(q, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                ReferenceCountUtil.release(msg);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ctx.close(); // the server refused or reset the stream: expected here
            }
        }).addListener(f -> {
            if (!f.isSuccess()) {
                return;
            }
            var s = (QuicStreamChannel) f.getNow();
            s.config().setAutoRead(false);
            s.closeFuture().addListener(c -> q.eventLoop().schedule(() -> holdForever(q, stop), 50, TimeUnit.MILLISECONDS));
            var h = new DefaultHttp3HeadersFrame();
            h.headers().method("GET").path("/large.bin").scheme("https").authority("localhost");
            s.writeAndFlush(h).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        });
    }

    /**
     * V1 / B1 inverted: four connections from one address that each open 64 never-read downloads can't
     * fill the server-wide in-flight cap (256 here): under contention each connection keeps a fair share,
     * so another client's requests are all answered.
     */
    @Test
    void v1FewConnectionsCannotFillTheInflightCap() throws Exception {
        var stop = new AtomicBoolean();
        var cfg = config().maxConnections(256).headerTimeout(Duration.ofSeconds(10)).build();
        try (var server = new Http3Server(cfg, this.handler, this.tls).start();
                var attacker = new SmallWindowClient(1024);
                var victim = new H3TestClient(this.tls.certificate())) {
            for (int c = 0; c < 4; c++) {
                var q = attacker.connect(server.localAddress());
                for (int i = 0; i < Http3Server.MAX_STREAMS; i++) {
                    holdForever(q, stop);
                }
            }
            waitFor(() -> server.inflight() >= 128, 10, "the attacker to hold half of the in-flight cap");
            var vq = victim.connect(server.localAddress());
            for (int i = 0; i < 10; i++) {
                var r = H3TestClient.request(vq, "GET", "/nope", Map.of()).get(10, TimeUnit.SECONDS);
                assertFalse(r.reset(), "victim request " + i + " refused; inflight=" + server.inflight());
                assertEquals(404, r.status());
            }
            assertTrue(server.inflight() < 256, "in-flight cap never filled: " + server.inflight());
            assertTrue(server.responses(Http3Writer.Outcome.REJECTED) > 0, "the attacker's excess was refused");
            stop.set(true);
        }
    }

    /**
     * V5 / B4 inverted: twenty slow streams on one connection, each a drip far below the minimum rate,
     * are not cut while file slots are free; with the slots contended (20 of 24 in use) the same readers
     * are cut (SLOW).
     */
    @ParameterizedTest(name = "contended={0}")
    @ValueSource(booleans = {false, true})
    void v5SlowStreamsAreCutOnlyUnderContention(boolean contended) throws Exception {
        var cfg = config().build();
        var limits = new Limits(1000, contended ? 24 : 1000, 32, 64, Duration.ofSeconds(5), Duration.ofSeconds(1),
                Long.MAX_VALUE / 4, Duration.ofSeconds(2), Limits.HANDSHAKE_TIMEOUT, Limits.MAX_HALF_OPEN, 16);
        try (var server = new Http3Server(cfg, this.handler, this.tls).limits(limits).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var drips = new java.util.concurrent.CopyOnWriteArrayList<ScheduledFuture<?>>();
            for (int i = 0; i < 20; i++) {
                H3TestClient.request(q, "GET", "/large.bin", Map.of(), null, s -> {
                    s.config().setAutoRead(false);
                    drips.add(s.eventLoop().scheduleAtFixedRate(() -> { // read for 10 ms every 300 ms
                        s.config().setAutoRead(true);
                        s.eventLoop().schedule(() -> s.config().setAutoRead(false), 10, TimeUnit.MILLISECONDS);
                    }, 300, 300, TimeUnit.MILLISECONDS));
                });
            }
            if (contended) {
                waitFor(() -> server.responses(Http3Writer.Outcome.SLOW) >= 1, 10, "a slow stream to be cut");
            } else {
                Thread.sleep(4000); // four rate windows
                assertEquals(0, server.responses(Http3Writer.Outcome.SLOW), outcomes(server));
                assertEquals(0, server.responses(Http3Writer.Outcome.STALLED), outcomes(server));
            }
            drips.forEach(d -> d.cancel(false));
            q.close().sync();
        }
    }

    /**
     * V3 + V7 over HTTP/3: below an execute-only (711) directory and a 0100 root, 403 by default; served
     * with {@code --allow-unlistable-dirs}.
     */
    @ParameterizedTest(name = "allowUnlistable={0}")
    @ValueSource(booleans = {false, true})
    void v3ExecuteOnlyDirectories(boolean allowUnlistable) throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root ignores permissions");
        Assumptions.assumeTrue(sjws_nettask.core.SafeOpen.SECURE);
        var xo = Files.createDirectories(this.root.resolve("xonly"));
        Files.writeString(xo.resolve("f.txt"), "in x-only dir");
        var changed = new ArrayList<Path>();
        var config = config().allowUnlistableDirs(allowUnlistable).build();
        var handler = new StaticFileHandler(this.root, config.allowUnlistableDirs());
        try (var server = new Http3Server(config, handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            changed.add(xo);
            Files.setPosixFilePermissions(xo, PosixFilePermissions.fromString("rwx--x--x"));
            changed.add(this.root);
            Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("--x------"));
            var r = H3TestClient.get(q, "/xonly/f.txt");
            assertFalse(r.reset(), "answered, not reset");
            assertEquals(allowUnlistable ? 200 : 403, r.status());
            assertEquals(allowUnlistable, H3Fixture.text(r).equals("in x-only dir"));
            assertEquals(allowUnlistable ? 200 : 403, H3TestClient.get(q, "/hello.txt").status());
        } finally {
            for (var p : changed.reversed()) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
            }
        }
    }

    /** V7 inverted over HTTP/3: the attacker's own 311 directory can't force the by-path fallback. */
    @Test
    void v7SearchOnlyDirectoryCannotForceTheFallback() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root ignores permissions");
        Assumptions.assumeTrue(sjws_nettask.core.SafeOpen.SECURE);
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var result = VerificationRegressionTest.searchOnlyRace(this.base, this.root, Duration.ofSeconds(3), path -> {
                try {
                    var r = H3TestClient.get(q, path);
                    return Map.entry(r.reset() ? 0 : r.status(), H3Fixture.text(r));
                } catch (Exception e) {
                    return Map.entry(0, "");
                }
            });
            assertEquals(0, result.leaked(), result.toString());
            assertEquals(0, result.served(), "no 200 below a search-only directory: " + result);
            assertTrue(result.denied() > 0, result.toString());
        }
    }

    /** V4 over HTTP/3: a file swapped for a FIFO after the handler's decision is answered 404, at once. */
    @Test
    void v4FileSwappedForAFifoIsAnswered404() throws Exception {
        var file = this.root.resolve("swap.txt");
        Function<Request, Response> seam = req -> {
            try {
                Files.writeString(file, "regular");
                var response = this.handler.handle(req);
                Files.delete(file);
                VerificationRegressionTest.mkfifo(file);
                return response;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Assumptions.assumeTrue(probeMkfifo());
        try (var server = new Http3Server(config().build(), seam, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var r = H3TestClient.request(q, "GET", "/swap.txt", Map.of()).get(3, TimeUnit.SECONDS);
            assertFalse(r.reset());
            assertEquals(404, r.status());
            assertEquals(1, server.responses(Http3Writer.Outcome.SWAPPED));
        } finally {
            VerificationRegressionTest.unblock(file);
        }
    }

    /** V4 over HTTP/3: a FIFO in the web root is not served, and nothing blocks on it. */
    @Test
    void v4FifoInTheRootIsNotServed() throws Exception {
        Assumptions.assumeTrue(probeMkfifo());
        var fifo = this.root.resolve("pipe");
        VerificationRegressionTest.mkfifo(fifo);
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var r = H3TestClient.request(q, "GET", "/pipe", Map.of()).get(3, TimeUnit.SECONDS);
            assertNotEquals(200, r.status());
        } finally {
            VerificationRegressionTest.unblock(fifo);
        }
    }

    boolean probeMkfifo() {
        try {
            return new ProcessBuilder("mkfifo", "--version").start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
