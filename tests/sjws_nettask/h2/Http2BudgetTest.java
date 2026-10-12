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
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Handler;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * Regression tests for the independent review of PR #25: per-connection stream and file budgets,
 * short file-slot wait, zero-window cancel, per-IP cap, the HTTP/1 fallback's in-flight slot, and
 * GOAWAY + drain on shutdown.
 */
class Http2BudgetTest {

    static final TlsMaterial TLS = TlsMaterial.selfSigned("localhost");
    static final int BIG = 2 * 1024 * 1024;
    static final int REFUSED_STREAM = 7, CANCEL = 8;

    @TempDir
    static Path root;

    private Http2Server server;

    @BeforeAll
    static void files() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello h2\n");
        Files.write(root.resolve("big.bin"), new byte[BIG]);
    }

    private void start(Http2Server.Limits limits, Handler handler) throws Exception {
        var config = ServerConfig.builder(root).h2Enabled(true).h2Port(0).h2cPort(0)
                .writeTimeout(Duration.ofSeconds(30)).build();
        this.server = new Http2Server(config, handler, TLS, limits).start();
    }

    private void start(Http2Server.Limits limits) throws Exception {
        start(limits, new StaticFileHandler(root));
    }

    private static Http2Server.Limits limits(int inflight, int files, int perConnInflight, int perConnFiles,
            Duration zeroWindow, int perIp) {
        return new Http2Server.Limits(100, 16 * 1024, 1000, 10, 16, inflight, files, perConnInflight, perConnFiles,
                Duration.ofMillis(100), zeroWindow, perIp);
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    /** Downloads big.bin on a fresh connection with ample windows; returns elapsed millis. */
    private long victim() throws IOException {
        try (var h2 = new RawH2(this.server.h2cPort(), 16 * 1024 * 1024)) {
            h2.windowUpdate(0, 16 * 1024 * 1024);
            long t0 = System.nanoTime();
            h2.get(1, "/big.bin");
            long got = 0;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.stream() == 1 && f.type() == RawH2.RST_STREAM) {
                    throw new AssertionError("victim stream reset: " + f.errorCode());
                }
                if (f.stream() == 1 && f.type() == RawH2.DATA) {
                    got += f.payload().length;
                    if ((f.flags() & RawH2.END_STREAM) != 0) {
                        break;
                    }
                }
            }
            assertEquals(BIG, got, "victim got the whole file");
            return (System.nanoTime() - t0) / 1_000_000;
        }
    }

    @Test
    void zeroWindowClientsDoNotDelayAnotherClient() throws Exception {
        start(Http2Server.Limits.DEFAULT);
        var hogs = new ArrayList<RawH2>();
        try {
            for (int c = 0; c < 15; c++) { // the per-IP maximum minus the victim, never opening a window
                var h2 = new RawH2(this.server.h2cPort(), 0);
                hogs.add(h2);
                for (int i = 0; i < 100; i++) {
                    h2.get(1 + 2 * i, "/big.bin");
                }
            }
            Thread.sleep(500); // let them grab whatever they can
            long ms = victim();
            assertTrue(ms < 1000, "victim took " + ms + " ms");
        } finally {
            for (var h : hogs) {
                h.close();
            }
        }
    }

    @Test
    void oneConnectionGetsOnlyItsFileBudget() throws Exception {
        start(limits(4096, 512, 32, 8, Duration.ofSeconds(30), 16));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) {
            for (int i = 0; i < 20; i++) {
                h2.get(1 + 2 * i, "/big.bin");
            }
            int refused = 0;
            long end = System.nanoTime() + 3_000_000_000L;
            h2.socket.setSoTimeout(500);
            for (RawH2.Frame f; System.nanoTime() < end && (f = h2.read()) != null;) {
                if (f.type() == RawH2.RST_STREAM && f.errorCode() == REFUSED_STREAM) {
                    refused++;
                }
            }
            assertEquals(12, refused, "8 file slots, the other 12 refused after the short wait");
        }
    }

    /**
     * The PR #38 CI flake: small files used to queue for the connection's file budget behind
     * large ones and were refused after the 100 ms wait on a slow machine. A file that fits in one
     * DATA frame now holds a slot only while it is read, outside the connection's budget.
     */
    @Test
    void smallFilesAreNotQueuedBehindTheConnectionsLargeFiles() throws Exception {
        start(limits(4096, 512, 100, 2, Duration.ofSeconds(30), 16));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) { // window 0: the large files never finish
            h2.get(1, "/big.bin");
            h2.get(3, "/big.bin"); // the connection's whole file budget (2) is now held
            Thread.sleep(300);
            h2.windowUpdate(0, 1 << 20);
            for (int i = 0; i < 10; i++) {
                int id = 5 + 2 * i;
                h2.get(id, "/hello.txt");
                h2.windowUpdate(id, 1 << 16);
            }
            int done = 0;
            h2.socket.setSoTimeout(5000);
            for (RawH2.Frame f; done < 10 && (f = h2.read()) != null;) {
                if (f.stream() >= 5 && f.type() == RawH2.RST_STREAM) {
                    throw new AssertionError("small file refused on stream " + f.stream() + ": " + f.errorCode());
                }
                if (f.stream() >= 5 && f.type() == RawH2.DATA && (f.flags() & RawH2.END_STREAM) != 0) {
                    done++;
                }
            }
            assertEquals(10, done, "every small file answered while the budget is held");
        }
    }

    @Test
    void streamsBeyondTheConnectionsInflightBudgetAreRefused() throws Exception {
        start(limits(4096, 512, 2, 8, Duration.ofSeconds(30), 16));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) {
            for (int i = 0; i < 5; i++) {
                h2.get(1 + 2 * i, "/big.bin");
            }
            int refused = 0;
            h2.socket.setSoTimeout(1000);
            for (RawH2.Frame f; refused < 3 && (f = h2.read()) != null;) {
                if (f.type() == RawH2.RST_STREAM && f.errorCode() == REFUSED_STREAM) {
                    refused++;
                }
            }
            assertEquals(3, refused);
            assertTrue(this.server.refusedStreams() >= 3);
        }
    }

    @Test
    void aStreamWhoseWindowStaysClosedIsCancelled() throws Exception {
        start(limits(4096, 512, 32, 8, Duration.ofMillis(500), 16));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) {
            long t0 = System.nanoTime();
            h2.get(1, "/big.bin");
            RawH2.Frame rst = null;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.stream() == 1 && f.type() == RawH2.RST_STREAM) {
                    rst = f;
                    break;
                }
            }
            assertNotNull(rst);
            assertEquals(CANCEL, rst.errorCode());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms >= 400 && ms < 5000, "cancelled after about the zero-window timeout: " + ms);
        }
    }

    @Test
    void connectionsPerAddressAreCapped() throws Exception {
        start(limits(4096, 512, 32, 8, Duration.ofSeconds(5), 2));
        try (var a = new RawH2(this.server.h2cPort(), 65535); var b = new RawH2(this.server.h2cPort(), 65535)) {
            a.get(1, "/hello.txt");
            b.get(1, "/hello.txt");
            assertNotNull(a.read());
            assertNotNull(b.read());
            try (var c = new Socket(InetAddress.getLoopbackAddress(), this.server.h2cPort())) {
                c.setSoTimeout(5000);
                assertEquals(-1, c.getInputStream().read(), "third connection from the address closed");
            }
        }
    }

    @Test
    void theHttp1FallbackTakesAnInflightSlotAndAnswers503WhenNoneIsFree() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var files = new StaticFileHandler(root);
        start(limits(1, 512, 32, 8, Duration.ofSeconds(5), 16), request -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return files.handle(request);
        });
        var req = "GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        try (var first = new Socket(InetAddress.getLoopbackAddress(), this.server.h2cPort());
                var second = new Socket(InetAddress.getLoopbackAddress(), this.server.h2cPort())) {
            first.setSoTimeout(10_000);
            second.setSoTimeout(10_000);
            first.getOutputStream().write(req);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, this.server.inflight(), "the fallback request holds the slot");
            second.getOutputStream().write(req);
            var answer = new String(second.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(answer.startsWith("HTTP/1.1 503"), answer);
            assertTrue(answer.toLowerCase().contains("retry-after: 1"));
            release.countDown();
            var head = new String(first.getInputStream().readNBytes(12), StandardCharsets.ISO_8859_1);
            assertEquals("HTTP/1.1 200", head);
        }
        long end = System.nanoTime() + 2_000_000_000L;
        while (this.server.inflight() > 0 && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals(0, this.server.inflight(), "slot released");
    }

    @Test
    void stopAcceptingSendsGoAwayNoError() throws Exception {
        start(Http2Server.Limits.DEFAULT);
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.get(1, "/hello.txt");
            assertNotNull(h2.read());
            this.server.stopAccepting();
            RawH2.Frame goAway = null;
            for (RawH2.Frame f; goAway == null && (f = h2.read()) != null;) {
                if (f.type() == RawH2.GOAWAY) {
                    goAway = f;
                }
            }
            assertNotNull(goAway, "GOAWAY on stopAccepting");
            assertEquals(0, goAway.errorCode(), "NO_ERROR");
        }
    }

    @Test
    void closeDrainsAStreamInProgress() throws Exception {
        start(limits(4096, 512, 32, 8, Duration.ofSeconds(30), 16));
        this.server.shutdownTimeout(Duration.ofSeconds(10));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) {
            h2.get(1, "/big.bin");
            long end = System.nanoTime() + 3_000_000_000L;
            while (this.server.inflight() == 0 && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            var closing = CompletableFuture.runAsync(this.server::close);
            Thread.sleep(300);
            assertTrue(!closing.isDone(), "close waits for the stream");
            h2.windowUpdate(0, BIG);
            h2.windowUpdate(1, BIG);
            long got = 0;
            boolean goAway = false;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.GOAWAY) {
                    goAway = true;
                }
                if (f.stream() == 1 && f.type() == RawH2.DATA) {
                    got += f.payload().length;
                    if ((f.flags() & RawH2.END_STREAM) != 0) {
                        break;
                    }
                }
            }
            assertTrue(goAway, "GOAWAY first");
            assertEquals(BIG, got, "the response finished during the drain");
            closing.get(10, TimeUnit.SECONDS);
        }
    }
}
