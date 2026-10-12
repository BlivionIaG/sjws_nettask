package sjws_nettask.ha;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.ServerSocketChannel;
import sjws_nettask.ClientManager;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.Transport;
import sjws_nettask.core.Body;
import sjws_nettask.core.FileCache;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

class HaLifecycleTest {

    @TempDir
    Path root;

    @Test
    void probesAndFilesAndConnectionClose() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var events = new AtomicInteger();
        var gauges = new AtomicInteger();
        var settings = HaSettings.builder()
                .drainDelay(Duration.ZERO)
                .drainTimeout(Duration.ofSeconds(2))
                .events(new HaEvents() {
                    @Override
                    public void drainStarted() {
                        events.incrementAndGet();
                    }

                    @Override
                    public void drainFinished() {
                        events.addAndGet(10);
                    }
                })
                .metrics(new MetricsSink() {
                    @Override
                    public void gauge(String name, double value) {
                        if ("ha_ready".equals(name)) {
                            gauges.set((int) value);
                        }
                    }

                    @Override
                    public void counter(String name, long delta) {
                        gauges.addAndGet(100);
                    }
                })
                .build();
        try (var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings)) {
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/healthz", 2000).statusCode());
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/readyz", 2000).statusCode());
            assertEquals("hello", HttpTestSupport.get(server.tcpPort(), "/hello.txt", 2000).bodyText());
            assertEquals("keep-alive", keepAliveHeader(server.tcpPort()));
            server.ha().lifecycle().beginClosingConnections();
            assertEquals("close", keepAliveHeader(server.tcpPort()));
            assertEquals(1, gauges.get(), "ready gauge");
            int port = server.tcpPort();
            server.drain();
            assertEquals(11, events.get());
            assertThrows(IOException.class, () -> HttpTestSupport.get(port, "/healthz", 500));
            assertFalse(server.ha().health().alive());
        }
    }

    @Test
    void drainWaitsOutTheDelayThenLetsAnInFlightRequestFinish() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slept = new CountDownLatch(1);
        var handlerCalls = new AtomicInteger();
        var settings = HaSettings.builder()
                .drainDelay(Duration.ofMillis(50))
                .drainTimeout(Duration.ofSeconds(3))
                .sleeper(delay -> {
                    slept.countDown();
                    if (!delay.isZero()) {
                        Thread.sleep(delay.toMillis());
                    }
                })
                .handler(request -> {
                    handlerCalls.incrementAndGet();
                    if ("/block".equals(request.target())) {
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return text("old");
                    }
                    return text("file");
                })
                .build();
        var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings);
        try {
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/readyz", 2000).statusCode());
            var worker = Thread.ofVirtual().start(() -> {
                try {
                    var response = HttpTestSupport.get(server.tcpPort(), "/block", 4000);
                    assertEquals("old", response.bodyText());
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var draining = Thread.ofVirtual().start(() -> {
                try {
                    server.drain();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(slept.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(503, HttpTestSupport.get(server.tcpPort(), "/readyz", 2000).statusCode());
            server.ha().snapshots().publish(new Snapshot<>(2, 2L, request -> {
                handlerCalls.incrementAndGet();
                return text("new");
            }));
            release.countDown();
            worker.join(3000);
            draining.join(3000);
            assertFalse(draining.isAlive());
            assertFalse(server.ha().health().alive());
        } finally {
            server.close();
        }
    }

    @Test
    void acceptPauseHoldsTheLoopThenReleasesIt() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var pause = new AtomicInteger(300);
        var settings = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(200))
                .acceptPause(pause::get).build();
        try (var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings)) {
            assertThrows(java.net.SocketTimeoutException.class,
                    () -> HttpTestSupport.get(server.tcpPort(), "/hello.txt", 80));
            pause.set(0);
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/hello.txt", 2000).statusCode());
        }
    }

    @Test
    void drainWithoutHaJustCloses() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0));
        assertNull(server.ha());
        int port = server.tcpPort();
        server.drain();
        assertThrows(IOException.class, () -> HttpTestSupport.get(port, "/hello.txt", 500));
    }

    @Test
    void secondDrainWaitsForTheFirst() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "x");
        var gate = new CountDownLatch(1);
        var settings = HaSettings.builder().drainDelay(Duration.ofMillis(20)).drainTimeout(Duration.ofMillis(500))
                .sleeper(delay -> {
                    gate.await();
                    if (!delay.isZero()) {
                        Thread.sleep(delay.toMillis());
                    }
                }).build();
        var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings);
        var first = new AtomicBoolean();
        var a = Thread.ofVirtual().start(() -> {
            try {
                server.drain();
                first.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(30);
        var bDone = new AtomicBoolean();
        var b = Thread.ofVirtual().start(() -> {
            try {
                server.drain();
                bDone.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(40);
        assertFalse(bDone.get());
        gate.countDown();
        a.join(2000);
        b.join(2000);
        assertTrue(first.get());
        assertTrue(bDone.get());
    }

    @Test
    void drainStopsHttp3AndKeepsAStaticFileSnapshot() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var files = new StaticFileHandler(root, false, new FileCache(0));
        var fake = new FakeH3();
        var settings = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(500))
                .handler(files).build();
        var config = ServerConfig.builder(root).port(0).h3Enabled(true).h3Port(0).build();
        try (var server = SjwsServer.start(config, (c, h) -> fake, settings)) {
            assertSame(files, server.handler());
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/hello.txt", 2000).statusCode());
            server.drain();
            assertTrue(fake.stops >= 1, "HTTP/3 stopped accepting during drain");
        }
        assertNotNull(sjws_nettask.H3Transports.defaultFactory().create(config, files));
    }

    @Test
    void haWrapsTheFilterChainAndProbesSkipIt() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var hits = new AtomicInteger();
        var filters = RequestFilters.create().add((request, next) -> {
            hits.incrementAndGet();
            var response = next.proceed(request);
            response.headers().add("x-filter", "1");
            return response;
        });
        var seenChain = new AtomicReference<FilterChain>();
        var seenTerminal = new AtomicReference<Handler>();
        var settings = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(500)).build();
        var config = ServerConfig.builder(root).port(0).h3Enabled(true).h3Port(0).build();
        try (var server = SjwsServer.start(config, new Transport.Factory() {
            @Override
            public Transport create(ServerConfig c, StaticFileHandler h) {
                throw new AssertionError("server must pass the HA handler, not only the file handler");
            }

            @Override
            public Transport create(ServerConfig c, StaticFileHandler h, FilterChain chain, Handler terminal) {
                seenChain.set(chain);
                seenTerminal.set(terminal);
                return new FakeH3();
            }
        }, filters, null, settings)) {
            assertNotNull(server.filterChain());
            assertNull(seenChain.get(), "HTTP/3 must not run the chain beside the HA handler");
            assertSame(server.requestHandler(), seenTerminal.get());
            assertTrue(server.requestHandler() instanceof HaHandler);
            assertNotSame(server.handler(), server.requestHandler());
            var health = HttpTestSupport.get(server.tcpPort(), "/healthz", 2000);
            assertEquals(200, health.statusCode());
            assertEquals(0, hits.get(), "probes do not enter the filter");
            var file = HttpTestSupport.get(server.tcpPort(), "/hello.txt", 2000);
            assertEquals("hello", file.bodyText());
            assertEquals("1", file.headers().get("x-filter"));
            assertEquals(1, hits.get());
        }
    }

    @Test
    void drainInterruptsAStuckHandlerAtTheTimeout() throws Exception {
        var release = new AtomicBoolean();
        var settings = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(150))
                .handler(request -> {
                    while (!release.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    return text("late");
                }).build();
        var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings);
        try {
            var worker = Thread.ofVirtual().start(() -> {
                try {
                    HttpTestSupport.get(server.tcpPort(), "/block", 3000);
                } catch (IOException ignored) {
                    // the timeout path may reset the socket
                }
            });
            Thread.sleep(40);
            long started = System.nanoTime();
            server.drain();
            long ms = (System.nanoTime() - started) / 1_000_000;
            assertTrue(ms < 2000, "drain bounded by timeout, took " + ms + "ms");
            worker.join(2000);
        } finally {
            release.set(true);
            server.close();
        }
    }

    @Test
    void hooksBeforeTheAcceptLoopRejectBadInput() throws Exception {
        try (var channel = ServerSocketChannel.open()) {
            channel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            var manager = new ClientManager(channel, ServerConfig.defaults(root));
            manager.expireIdleConnections();
            assertThrows(IllegalArgumentException.class, () -> manager.prepareGracefulDrain(0));
        }
    }

    @Test
    void settingsRejectBadDelays() {
        assertThrows(IllegalArgumentException.class,
                () -> HaSettings.builder().drainTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class,
                () -> HaSettings.builder().drainDelay(Duration.ofMillis(-1)).build());
        assertThrows(NullPointerException.class, () -> HaSettings.builder().sleeper(null).build());
        var defaults = HaSettings.defaults();
        assertEquals(Duration.ofSeconds(5), defaults.drainDelay());
        assertFalse(defaults.requireQuorum());
        assertNull(defaults.acceptPause());
        assertSame(HaEvents.NONE, defaults.events());
        HaEvents.NONE.drainStarted();
        HaEvents.NONE.drainFinished();
        MetricsSink.NONE.gauge("x", 1);
        MetricsSink.NONE.counter("x", 1);
    }

    @Test
    void benchOptionsParse() {
        var options = BenchServer.Options.parse(new String[] {"--mode=cluster", "--port=9", "--root=/tmp/html"});
        assertTrue(options.cluster());
        assertEquals(9, options.port());
        assertEquals("cluster", options.mode());
        assertEquals("single", BenchServer.Options.parse(new String[0]).mode());
        assertThrows(IllegalArgumentException.class, () -> BenchServer.Options.parse(new String[] {"--mode=nope"}));
        assertThrows(IllegalArgumentException.class, () -> BenchServer.Options.parse(new String[] {"--wat"}));
        assertThrows(IllegalArgumentException.class, () -> BenchServer.Options.parse(new String[] {"--root=  "}));
    }

    private static String keepAliveHeader(int port) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            socket.setSoTimeout(2000);
            HttpTestSupport.write(socket.getOutputStream(),
                    "GET /hello.txt HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n");
            var response = HttpTestSupport.readResponse(socket.getInputStream());
            return response.headers().get("connection");
        }
    }

    private static final class FakeH3 implements Transport {
        int stops;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public Transport start() {
            return this;
        }

        @Override
        public int port() {
            return 9;
        }

        @Override
        public void stopAccepting() {
            this.stops++;
        }

        @Override
        public void close() {
        }
    }

    private static Response text(String body) {
        return new Response(200, ResponseHeaders.of().add("content-type", "text/plain"),
                Body.Bytes.of(body.getBytes(StandardCharsets.UTF_8)));
    }
}
