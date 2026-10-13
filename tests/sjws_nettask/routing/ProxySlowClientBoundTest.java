package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/**
 * Security finding F5, end to end over real sockets: many slow clients fetching a large proxied
 * body keep the proxy's in-memory bytes within the global budget (plus at most one socket read per
 * exchange), a fast client still completes meanwhile, and every byte returns to the budget when
 * the slow clients disconnect mid-stream.
 */
class ProxySlowClientBoundTest {

    static final int CLIENTS = 50;
    static final int SIZE = 8 << 20;
    static final long LIMIT = 4L << 20;
    /** Documented overshoot: one socket read (adaptive allocator max 128 KiB) per active exchange. */
    static final long PER_EXCHANGE_READ = 128 * 1024;

    @TempDir
    Path dir;

    @Test
    void slowClientsAreBoundedAndReleaseEverythingOnDisconnect() throws Exception {
        var body = new byte[SIZE];
        java.util.Arrays.fill(body, (byte) 'z');
        var upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/", ex -> {
            ex.sendResponseHeaders(200, SIZE);
            try (var out = ex.getResponseBody()) {
                out.write(body);
            } catch (IOException e) {
                // the proxy dropped the exchange
            }
        });
        upstream.start();
        var budget = new ByteBudget(LIMIT);
        var d = Duration.ofSeconds(30);
        var settings = new ProxyHandler.Settings(URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                d, d, d, 1 << 20, null, List.of(), false, CLIENTS + 4);
        Files.writeString(this.dir.resolve("x"), "x");
        var config = ServerConfig.builder(this.dir).port(0).build();
        var sampling = new AtomicBoolean(true);
        var peak = new AtomicLong();
        List<Socket> slow = new ArrayList<>();
        try (var proxy = new ProxyHandler(settings, budget);
                var server = SjwsServer.start(config, null, files -> proxy)) {
            int port = server.tcpPort();
            var sampler = Thread.ofPlatform().daemon().start(() -> {
                while (sampling.get()) {
                    peak.accumulateAndGet(budget.inFlight(), Math::max);
                    Thread.onSpinWait();
                }
            });
            try {
                // 50 slow clients: tiny receive window, a few bytes now and then
                for (int i = 0; i < CLIENTS; i++) {
                    var s = new Socket();
                    s.setReceiveBufferSize(4096);
                    s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                    s.setSoTimeout(10_000);
                    s.getOutputStream().write(("GET /big/" + i + " HTTP/1.1\r\nHost: t\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    slow.add(s);
                }
                var tiny = new byte[64];
                for (int round = 0; round < 20; round++) {
                    for (var s : slow) {
                        assertTrue(s.getInputStream().read(tiny) > 0);
                    }
                    Thread.sleep(50);
                }
                // all 50 exchanges are open and their clients barely read: the budget is the bound
                assertTrue(budget.inFlight() > 0, "the slow exchanges should hold bytes");

                // a fast client is not starved while the budget is exhausted
                long got = fetchAll(port, "/fast");
                assertEquals(SIZE, got);

                long bound = LIMIT + (CLIENTS + 1) * PER_EXCHANGE_READ;
                assertTrue(peak.get() <= bound, "in flight peaked at " + peak.get() + " > " + bound);
                // far below what buffering whole responses would hold (50 x 8 MiB = 400 MiB)
                assertTrue(peak.get() < (long) CLIENTS * SIZE / 10, "peak " + peak.get());
            } finally {
                // the slow clients go away mid-stream (RST: unread data)
                for (var s : slow) {
                    s.setSoLinger(true, 0);
                    s.close();
                }
            }
            long until = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (budget.inFlight() != 0 && System.nanoTime() < until) {
                Thread.sleep(20);
            }
            assertEquals(0, budget.inFlight(), "bytes leaked after the clients disconnected");
            sampling.set(false);
            sampler.join(1000);
        } finally {
            upstream.stop(0);
        }
    }

    /** Reads the whole response body as fast as possible; returns its length. */
    private static long fetchAll(int port, String path) throws IOException {
        try (var s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(30_000);
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            InputStream in = s.getInputStream();
            // skip the head
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("no head");
                }
                state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))
                        ? state + 1 : 0;
            }
            var buf = new byte[64 * 1024];
            long total = 0;
            for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
                total += n;
            }
            return total;
        }
    }
}
