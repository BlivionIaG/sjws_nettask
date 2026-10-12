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

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.SafeOpenTest;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * The security review's repros (F1-F6, {@code sjws_nettask-h3-review-security.md}), inverted: each
 * one asserts the fix.
 */
@Tag("h3")
class SecurityReviewTest {

    @TempDir
    Path base;
    Path root;
    StaticFileHandler handler;
    TlsMaterial tls;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable());
        this.root = H3Fixture.webRoot(this.base);
        this.handler = new StaticFileHandler(this.root);
        this.tls = TlsMaterial.selfSigned("localhost");
    }

    ServerConfig.Builder config() {
        return ServerConfig.builder(this.root).h3Enabled(true).h3Host("127.0.0.1").h3Port(0);
    }

    /** F6 over HTTP/3: a parent directory flipping to a symlink to outside never leaks the outside file. */
    @Test
    void m4ParentSymlinkRaceNeverLeaksOverHttp3() throws Exception {
        var outside = Files.createDirectories(this.base.resolve("outside"));
        Files.writeString(outside.resolve("a.txt"), "SECRET-OUTSIDE-ROOT-0123456789");
        var d = Files.createDirectories(this.root.resolve("d"));
        Files.writeString(d.resolve("a.txt"), "public-inside-root-abcdefghijk");
        var stop = new AtomicBoolean();
        int leaked = 0;
        int served = 0;
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var flipper = Thread.ofPlatform().start(() -> SafeOpenTest.flip(d, this.root.resolve("d.real"), outside,
                    stop));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline) {
                    var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<
                            H3TestClient.H3Response>>();
                    for (int i = 0; i < 16; i++) {
                        futures.add(H3TestClient.request(q, "GET", "/d/a.txt", Map.of()));
                    }
                    for (var f : futures) {
                        var r = f.get(10, TimeUnit.SECONDS);
                        var text = H3Fixture.text(r);
                        if (text.contains("SECRET-OUTSIDE")) {
                            leaked++;
                        } else if (r.status() == 200 && !r.reset()) {
                            served++;
                        }
                    }
                }
            } finally {
                stop.set(true);
                flipper.join();
            }
            assertTrue(server.responses(Http3Writer.Outcome.SWAPPED) > 0, "the race was hit");
        }
        assertEquals(0, leaked, "served " + served);
        assertTrue(served > 0);
    }

    /** F5, inverted: the server's token handler accepts no token, so a forged one can't skip address validation. */
    @Test
    void m3ForgedRetryTokenIsRejected() throws Exception {
        var victim = new java.net.InetSocketAddress(java.net.InetAddress.getByName("203.0.113.7"), 443);
        var forged = io.netty.buffer.Unpooled.buffer()
                .writeBytes("netty".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .writeBytes(victim.getAddress().getAddress()).writeBytes(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        try {
            assertTrue(io.netty.handler.codec.quic.InsecureQuicTokenHandler.INSTANCE.validateToken(forged.slice(),
                    victim) > 0, "the repro: Netty's insecure handler accepts it");
            assertEquals(-1, Http3Server.TOKENS.validateToken(forged.slice(), victim));
            assertEquals(0, Http3Server.TOKENS.maxTokenLength());
            var out = io.netty.buffer.Unpooled.buffer();
            assertFalse(Http3Server.TOKENS.writeToken(out, io.netty.buffer.Unpooled.wrappedBuffer(new byte[8]), victim),
                    "no Retry packets");
            assertEquals(0, out.readableBytes());
            out.release();
        } finally {
            forged.release();
        }
        // and clients still connect (no Retry round trip needed)
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            assertEquals(200, H3TestClient.get(client.connect(server.localAddress()), "/hello.txt").status());
        }
    }

    /** S2: small receive windows (bodies are discarded), so out-of-order data can't pin megabytes per connection. */
    @Test
    void s2ReceiveWindowsAreSmall() throws Exception {
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var params = q.peerTransportParameters();
            assertEquals(1024 * 1024, params.initialMaxData());
            assertEquals(16 * 1024, params.initialMaxStreamDataBidiRemote());
            assertEquals(0, params.initialMaxStreamDataBidiLocal(), "the server never opens bidi streams");
            assertEquals(Http3Server.MAX_STREAMS, params.initialMaxStreamsBidi());
            // a request body far above the stream window is still drained and the response unaffected
            var body = new byte[200_000];
            var r = H3TestClient.request(q, "GET", "/hello.txt", Map.of(), body, null).get(10, TimeUnit.SECONDS);
            H3Fixture.assertParity(this.handler, "GET", "/hello.txt", Map.of(), r);
            assertEquals(200, H3TestClient.get(q, "/bin.dat").status());
        }
    }

    static long finished(Http3Server s) {
        long n = 0;
        for (var o : Http3Writer.Outcome.values()) {
            n += s.responses(o);
        }
        return n;
    }

    static String outcomes(Http3Server s) {
        var sb = new StringBuilder();
        for (var o : Http3Writer.Outcome.values()) {
            sb.append(o).append('=').append(s.responses(o)).append(' ');
        }
        return sb.toString();
    }

    static void waitFor(BooleanSupplier condition, long seconds, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    /** Opens {@code n} downloads of /large.bin on {@code q} whose client never reads (flow control fills up). */
    static List<QuicStreamChannel> pausedDownloads(QuicChannel q, int n) {
        var held = new java.util.concurrent.CopyOnWriteArrayList<QuicStreamChannel>();
        for (int i = 0; i < n; i++) {
            H3TestClient.request(q, "GET", "/large.bin", Map.of(), null, s -> {
                s.config().setAutoRead(false);
                held.add(s);
            });
        }
        return held;
    }

    static Limits limits(int maxInflight, int maxOpenFiles, int perConnection, Duration slotWait,
            Duration writeTimeout, long minRate) {
        return limits(maxInflight, maxOpenFiles, perConnection, slotWait, writeTimeout, minRate, Duration.ofSeconds(2));
    }

    static Limits limits(int maxInflight, int maxOpenFiles, int perConnection, Duration slotWait,
            Duration writeTimeout, long minRate, Duration drain) {
        return new Limits(maxInflight, maxOpenFiles, perConnection, slotWait, writeTimeout, minRate, drain);
    }

    /**
     * F1, inverted: three connections that stop reading can't block another client's download. Each
     * connection holds at most 16 file slots; its other streams are refused (H3_REQUEST_REJECTED) after the
     * slot wait instead of queueing forever.
     */
    @Test
    void m1ThreeStalledConnectionsDontStarveOtherClients() throws Exception {
        var config = config().writeTimeout(Duration.ofSeconds(20)).headerTimeout(Duration.ofSeconds(2)).build();
        try (var server = new Http3Server(config, this.handler, this.tls).start();
                var attacker = new H3TestClient(this.tls.certificate());
                var victim = new H3TestClient(this.tls.certificate())) {
            for (int c = 0; c < 3; c++) {
                pausedDownloads(attacker.connect(server.localAddress()), 60);
            }
            waitFor(() -> server.responses(Http3Writer.Outcome.REJECTED) >= 3 * (60 - Limits.FILES_PER_CONNECTION),
                    15, "excess attacker streams refused");
            long t0 = System.nanoTime();
            var vq = victim.connect(server.localAddress());
            var r = H3TestClient.request(vq, "GET", "/hello.txt", Map.of()).get(5, TimeUnit.SECONDS);
            H3Fixture.assertParity(this.handler, "GET", "/hello.txt", Map.of(), r);
            assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(2), "served without waiting for a slot");
            assertEquals(3 * Limits.FILES_PER_CONNECTION, server.inflight(), "only the slot holders are in flight");
        }
    }

    /** M1: with every server-wide file slot taken, a download is refused after the slot wait, never hung. */
    @Test
    void m1ExhaustedFileSlotsRefuseWithRequestRejectedAfterTheSlotWait() throws Exception {
        var config = config().writeTimeout(Duration.ofSeconds(20)).build();
        try (var server = new Http3Server(config, this.handler, this.tls)
                .limits(limits(100, 8, 16, Duration.ofSeconds(1), Duration.ofSeconds(20), 0)).start();
                var attacker = new H3TestClient(this.tls.certificate());
                var victim = new H3TestClient(this.tls.certificate())) {
            var aq = attacker.connect(server.localAddress());
            pausedDownloads(aq, 8);
            waitFor(() -> server.inflight() == 8, 5, "8 stalled downloads");
            Thread.sleep(300);
            var vq = victim.connect(server.localAddress());
            long t0 = System.nanoTime();
            var refused = H3TestClient.request(vq, "GET", "/hello.txt", Map.of()).get(5, TimeUnit.SECONDS);
            long waited = System.nanoTime() - t0;
            assertTrue(refused.reset(), "refused, not served");
            assertEquals(-1, refused.status(), "nothing was sent before the refusal");
            assertTrue(waited >= TimeUnit.MILLISECONDS.toNanos(900) && waited < TimeUnit.SECONDS.toNanos(4),
                    "waited the slot wait: " + waited / 1_000_000 + " ms");
            assertEquals(1, server.responses(Http3Writer.Outcome.REJECTED));
            assertEquals(404, H3TestClient.get(vq, "/nope").status(), "in-memory bodies need no file slot");
            aq.close().sync();
            waitFor(() -> server.inflight() == 0, 5, "attacker's slots returned when its connection closed");
            H3Fixture.assertParity(this.handler, "GET", "/hello.txt", Map.of(), H3TestClient.get(vq, "/hello.txt"));
        }
    }

    /** M1: a reader that keeps every chunk moving but is slower than the minimum rate is cut off. */
    @Test
    void m1DripReaderBelowTheMinimumRateIsReset() throws Exception {
        try (var server = new Http3Server(config().build(), this.handler, this.tls)
                .limits(limits(100, 100, 16, Duration.ofSeconds(5), Duration.ofSeconds(1), Long.MAX_VALUE / 4))
                .start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            var drip = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
            var f = H3TestClient.request(q, "GET", "/large.bin", Map.of(), null, s -> {
                s.config().setAutoRead(false);
                drip.set(s.eventLoop().scheduleAtFixedRate(() -> { // read for 10 ms every 300 ms
                    s.config().setAutoRead(true);
                    s.eventLoop().schedule(() -> s.config().setAutoRead(false), 10, TimeUnit.MILLISECONDS);
                }, 300, 300, TimeUnit.MILLISECONDS));
            });
            waitFor(() -> finished(server) == 1, 10, "the download to end");
            assertEquals(1, server.responses(Http3Writer.Outcome.SLOW), "slow reader cut off: " + outcomes(server));
            assertEquals(0, server.responses(Http3Writer.Outcome.STALLED), "it never stalled: each chunk moved");
            drip.get().cancel(false);
            q.close().sync();
            assertTrue(f.get(5, TimeUnit.SECONDS).body().length < H3Fixture.LARGE);
        }
    }

    /**
     * F2, inverted: requests whose streams the client resets while they wait for a file slot are
     * cancelled with the stream; their virtual threads don't pile up beyond the stream limit.
     */
    @Test
    void m2ResetStreamsDontLeaveParkedThreads() throws Exception {
        var config = config().writeTimeout(Duration.ofSeconds(25)).build();
        var calls = new java.util.concurrent.atomic.AtomicLong();
        java.util.function.Function<sjws_nettask.core.Request, sjws_nettask.core.Response> counting = r -> {
            calls.incrementAndGet();
            return this.handler.handle(r);
        };
        try (var server = new Http3Server(config, counting, this.tls)
                .limits(limits(100_000, 32, 16, Duration.ofSeconds(25), Duration.ofSeconds(25), 0)).start();
                var attacker = new H3TestClient(this.tls.certificate())) {
            for (int c = 0; c < 3; c++) {
                pausedDownloads(attacker.connect(server.localAddress()), 16);
            }
            waitFor(() -> server.inflight() == 48, 10, "48 stalled downloads (32 sending, 16 waiting for a slot)");
            int held = server.inflight();
            long before = calls.get() - finished(server);
            var q = attacker.connect(server.localAddress());
            int sent = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < deadline && sent < 1000) {
                var batch = new ArrayList<io.netty.util.concurrent.Future<?>>();
                for (int i = 0; i < 50; i++) {
                    var p = q.eventLoop().<Void>newPromise();
                    io.netty.handler.codec.http3.Http3.newRequestStream(q,
                            new io.netty.channel.ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                                    io.netty.util.ReferenceCountUtil.release(msg);
                                }
                            }).addListener(fut -> {
                                if (!fut.isSuccess()) {
                                    p.trySuccess(null);
                                    return;
                                }
                                var s = (QuicStreamChannel) fut.getNow();
                                var h = new io.netty.handler.codec.http3.DefaultHttp3HeadersFrame();
                                h.headers().method("GET").path("/hello.txt").scheme("https").authority("localhost");
                                s.writeAndFlush(h).addListener(w -> {
                                    s.eventLoop().schedule(() -> s.shutdown(0x10c).addListener(x -> s.close()), 20,
                                            TimeUnit.MILLISECONDS);
                                    s.closeFuture().addListener(x -> p.trySuccess(null));
                                });
                            });
                    batch.add(p);
                }
                for (var p : batch) {
                    p.await(5, TimeUnit.SECONDS);
                }
                sent += 50;
            }
            assertTrue(sent >= 200, "sent " + sent);
            waitFor(() -> server.inflight() <= held + Http3Server.MAX_STREAMS, 5,
                    "reset requests cancelled (in flight: " + server.inflight() + ")");
            Thread.sleep(500);
            assertTrue(server.inflight() <= held + Http3Server.MAX_STREAMS, "in flight " + server.inflight());
            long parked = calls.get() - finished(server) - before;
            assertTrue(parked <= Http3Server.MAX_STREAMS, "parked request threads: " + parked + " of " + sent
                    + " reset requests (" + calls.get() + " handled)");
        }
    }

    /** S1: a server-wide cap on in-flight requests, covering in-memory responses (listings, errors) too. */
    @Test
    void s1InflightRequestsAreCappedServerWide() throws Exception {
        try (var server = new Http3Server(config().writeTimeout(Duration.ofSeconds(20)).build(), this.handler, this.tls)
                .limits(limits(4, 100, 16, Duration.ofSeconds(5), Duration.ofSeconds(20), 0)).start();
                var attacker = new H3TestClient(this.tls.certificate());
                var victim = new H3TestClient(this.tls.certificate())) {
            var aq = attacker.connect(server.localAddress());
            pausedDownloads(aq, 4);
            waitFor(() -> server.inflight() == 4, 5, "cap reached");
            pausedDownloads(aq, 3);
            waitFor(() -> server.responses(Http3Writer.Outcome.REJECTED) == 3, 5, "over the cap: refused at once");
            var vq = victim.connect(server.localAddress());
            var listing = H3TestClient.request(vq, "GET", "/sub/", Map.of()).get(5, TimeUnit.SECONDS);
            assertTrue(listing.reset(), "listings count too");
            assertEquals(4, server.responses(Http3Writer.Outcome.REJECTED));
            aq.close().sync();
            waitFor(() -> server.inflight() == 0, 5, "slots returned");
            H3Fixture.assertParity(this.handler, "GET", "/sub/", Map.of(), H3TestClient.get(vq, "/sub/"));
        }
    }

    /** A client whose connections record every GOAWAY the server sends on its control stream. */
    static QuicChannel goAwayRecordingConnection(io.netty.channel.EventLoopGroup group, TlsMaterial tls,
            java.net.InetSocketAddress server, List<Long> goaways) throws Exception {
        var ssl = io.netty.handler.codec.quic.QuicSslContextBuilder.forClient().trustManager(tls.certificate())
                .applicationProtocols(io.netty.handler.codec.http3.Http3.supportedApplicationProtocols()).build();
        var codec = io.netty.handler.codec.http3.Http3.newQuicClientCodecBuilder().sslContext(ssl)
                .sslEngineProvider(qc -> ssl.newEngine(qc.alloc(), "localhost", 443))
                .maxIdleTimeout(15000, TimeUnit.MILLISECONDS).initialMaxData(10_000_000)
                .initialMaxStreamDataBidirectionalLocal(1_000_000).build();
        var dg = new io.netty.bootstrap.Bootstrap().group(group)
                .channel(io.netty.channel.socket.nio.NioDatagramChannel.class).handler(codec)
                .bind(new java.net.InetSocketAddress("127.0.0.1", 0)).sync().channel();
        var control = new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof io.netty.handler.codec.http3.Http3GoAwayFrame g) {
                    goaways.add(g.id());
                }
                io.netty.util.ReferenceCountUtil.release(msg);
            }
        };
        return QuicChannel.newBootstrap(dg)
                .handler(new io.netty.handler.codec.http3.Http3ClientConnectionHandler(control, null, null, null, true))
                .remoteAddress(server).localAddress(dg.localAddress()).connect().get(10, TimeUnit.SECONDS);
    }

    /**
     * F4, inverted: close() sends GOAWAY first and lets an in-flight download finish within the drain
     * window, then closes the connection.
     */
    @Test
    void s3CloseSendsGoAwayAndDrainsInFlightDownloads() throws Exception {
        var goaways = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var group = new io.netty.channel.MultiThreadIoEventLoopGroup(1,
                io.netty.channel.nio.NioIoHandler.newFactory());
        try {
            java.util.function.Function<sjws_nettask.core.Request, sjws_nettask.core.Response> slow = r -> {
                if (r.target().equals("/large.bin")) {
                    try {
                        Thread.sleep(800); // still being handled when close() starts
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return this.handler.handle(r);
            };
            var server = new Http3Server(config().build(), slow, this.tls)
                    .limits(limits(100, 100, 16, Duration.ofSeconds(5), Duration.ofSeconds(30), 0, Duration.ofSeconds(10)))
                    .start();
            var q = goAwayRecordingConnection(group, this.tls, server.localAddress(), goaways);
            assertEquals(200, H3TestClient.get(q, "/hello.txt").status()); // stream 0
            var inflight = H3TestClient.request(q, "GET", "/large.bin", Map.of()); // stream 4, handled slowly
            waitFor(() -> server.inflight() == 1, 5, "download in flight");
            long t0 = System.nanoTime();
            var closer = Thread.ofPlatform().start(server::close);
            assertFalse(inflight.isDone(), "the download is still in flight when close() starts");
            var r = inflight.get(15, TimeUnit.SECONDS);
            closer.join(15_000);
            long took = System.nanoTime() - t0;
            assertFalse(r.reset(), "the in-flight download completed: " + r.error() + " " + outcomes(server) + " took " + took / 1_000_000 + " ms, got " + r.body().length + " goaways " + goaways);
            assertEquals(H3Fixture.LARGE, r.body().length);
            assertArrayEquals(Files.readAllBytes(this.root.resolve("large.bin")), r.body());
            assertEquals(List.of(8L), goaways, "GOAWAY names the first unprocessed request stream");
            assertTrue(took > TimeUnit.MILLISECONDS.toNanos(500), "close() waited for the request");
            assertTrue(took < TimeUnit.SECONDS.toNanos(9), "returned once drained, not after the full window");
            assertEquals(1, server.responses(Http3Writer.Outcome.COMPLETE) - 1);
            q.closeFuture().await(5, TimeUnit.SECONDS);
            assertFalse(q.isActive(), "connection closed after the drain");
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
        }
    }

    /** S3: the drain window is bounded: a download that doesn't progress is cut when it ends. */
    @Test
    void s3DrainWindowIsBounded() throws Exception {
        var server = new Http3Server(config().build(), this.handler, this.tls)
                .limits(limits(100, 100, 16, Duration.ofSeconds(5), Duration.ofSeconds(30), 0, Duration.ofMillis(500)))
                .start();
        try (var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            pausedDownloads(q, 1);
            waitFor(() -> server.inflight() == 1, 5, "stalled download");
            long t0 = System.nanoTime();
            server.close();
            long took = System.nanoTime() - t0;
            assertTrue(took >= TimeUnit.MILLISECONDS.toNanos(450) && took < TimeUnit.SECONDS.toNanos(4),
                    "close took " + took / 1_000_000 + " ms");
            waitFor(() -> !q.isActive(), 5, "connection closed");
        }
    }

    /** What the HTTP/1.1 parser answers for this request line (0 = accepted). */
    static int http1Status(String method, String target) throws Exception {
        try {
            sjws_nettask.HTTP.Http1Parser.read(new java.io.ByteArrayInputStream((method + " " + target
                    + " HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)),
                    sjws_nettask.CONSTANTS.MAX_LINE_LENGTH, sjws_nettask.CONSTANTS.MAX_HEADERS);
            return 0;
        } catch (sjws_nettask.HTTP.ParseError e) {
            return e.status();
        }
    }

    /**
     * F3, inverted: request validation matches RFC 9114 §4.2 and the HTTP/1.1 parser; nothing answers 500,
     * and an oversized request is a stream error, not a connection error.
     */
    @Test
    void s4RequestValidationMatchesHttp1() throws Exception {
        try (var server = new Http3Server(config().build(), this.handler, this.tls).start();
                var client = new H3TestClient(this.tls.certificate())) {
            var q = client.connect(server.localAddress());
            for (var name : new String[] {"transfer-encoding", "keep-alive", "upgrade", "proxy-connection"}) {
                var r = H3TestClient.request(q, "GET", "/hello.txt", Map.of(name, "x")).get(10, TimeUnit.SECONDS);
                assertTrue(r.reset(), name + ": malformed, stream reset");
                assertEquals(-1, r.status(), name + ": no response");
            }
            // CR/LF can't even be sent in an HTTP/1.1 request line; a raw space or DEL is 400 there
            var crlf = H3TestClient.request(q, "GET", "/sub?a\r\nset-cookie: pwn=1", Map.of()).get(10, TimeUnit.SECONDS);
            assertEquals(400, crlf.status(), "not 500, and no header injection");
            assertNull(crlf.header("set-cookie"));
            assertNull(crlf.header("location"));
            for (var target : new String[] {"/a b+c.txt", "/hello.txt\u007f", "/caf\u00e9"}) {
                var r = H3TestClient.request(q, "GET", target, Map.of()).get(10, TimeUnit.SECONDS);
                assertEquals(400, r.status(), target);
                if (target.indexOf('\u00e9') < 0) {
                    assertEquals(400, http1Status("GET", target), "HTTP/1: " + target);
                }
                assertArrayEquals(H3Fixture.http1Body(StaticFileHandler.error(400, false)), r.body());
            }
            for (var method : new String[] {"FOO", "get"}) {
                var r = H3TestClient.request(q, method, "/hello.txt", Map.of()).get(10, TimeUnit.SECONDS);
                assertEquals(501, r.status(), method);
                assertEquals(501, http1Status(method, "/hello.txt"), "HTTP/1: " + method);
            }
            assertEquals(405, H3TestClient.request(q, "POST", "/hello.txt", Map.of()).get(10, TimeUnit.SECONDS).status());
            var conn = H3TestClient.request(q, "CONNECT", null, Map.of()).get(10, TimeUnit.SECONDS);
            assertTrue(conn.reset(), "CONNECT without :authority/:path stays malformed");
            var huge = H3TestClient.request(q, "GET", "/hello.txt?" + "a".repeat(9000), Map.of())
                    .get(10, TimeUnit.SECONDS);
            assertEquals(414, huge.status());
            var big = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < 20; i++) {
                big.put("x-filler-" + i, "b".repeat(1000));
            }
            assertEquals(431, H3TestClient.request(q, "GET", "/hello.txt", big).get(10, TimeUnit.SECONDS).status());
            assertTrue(q.isActive(), "stream errors only: the connection survives");
            H3Fixture.assertParity(this.handler, "GET", "/hello.txt", Map.of(), H3TestClient.get(q, "/hello.txt"));
        }
    }
}
