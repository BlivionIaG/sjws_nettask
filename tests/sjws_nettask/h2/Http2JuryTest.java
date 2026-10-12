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
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * Regression tests for the security jury's findings on PR #25: connection-slot squatting over
 * HTTP/2 (PING keep-alive, unfinished header block), slowloris on the HTTP/1.x fallback, and the
 * control-frame flood guard.
 */
class Http2JuryTest {

    static final TlsMaterial TLS = TlsMaterial.selfSigned("localhost");

    @TempDir
    static Path root;

    private Http2Server server;

    @BeforeAll
    static void files() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello h2\n");
        Files.write(root.resolve("big.bin"), new byte[1024 * 1024]);
    }

    private void start(Duration idle, Duration header) throws Exception {
        var config = ServerConfig.builder(root).h2Enabled(true).h2Port(0).h2cPort(0).idleTimeout(idle)
                .headerTimeout(header).writeTimeout(Duration.ofSeconds(30)).build();
        this.server = new Http2Server(config, new StaticFileHandler(root), TLS, Http2Server.Limits.DEFAULT).start();
    }

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    /** Reads frames until EOF; returns the GOAWAY seen, or null. */
    private static RawH2.Frame drain(RawH2 h2) throws IOException {
        RawH2.Frame goAway = null;
        for (RawH2.Frame f; (f = h2.read()) != null;) {
            if (f.type() == RawH2.GOAWAY) {
                goAway = f;
            }
        }
        return goAway;
    }

    @Test
    void pingsDoNotKeepAnIdleConnectionOpen() throws Exception {
        start(Duration.ofMillis(400), Duration.ofSeconds(10));
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            long t0 = System.nanoTime();
            boolean closed = false;
            try {
                for (int i = 0; i < 60 && !closed; i++) { // 3 s of pings every 50 ms
                    h2.frame(RawH2.PING, 0, 0, new byte[8]);
                    Thread.sleep(50);
                    closed = this.server.connections() == 0;
                }
            } catch (IOException e) {
                closed = true;
            }
            assertTrue(closed || this.server.connections() == 0, "idle h2 connection closed despite PINGs");
            assertTrue(System.nanoTime() - t0 < 3_000_000_000L);
        }
    }

    @Test
    void anUnfinishedHeaderBlockDoesNotHoldTheConnection() throws Exception {
        start(Duration.ofMillis(400), Duration.ofSeconds(10));
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.frame(RawH2.HEADERS, 0, 1, RawH2.block("GET", "/hello.txt")); // no END_HEADERS, ever
            long t0 = System.nanoTime();
            var goAway = drain(h2);
            assertNotNull(goAway, "GOAWAY before close");
            assertTrue(System.nanoTime() - t0 < 5_000_000_000L);
        }
    }

    @Test
    void anOpenStreamKeepsTheConnectionAlive() throws Exception {
        start(Duration.ofMillis(300), Duration.ofSeconds(10));
        try (var h2 = new RawH2(this.server.h2cPort(), 0)) { // window 0: the response stays in flight
            h2.get(1, "/big.bin");
            for (int i = 0; i < 12; i++) { // the stream trickles: one byte of window every 100 ms
                Thread.sleep(100);
                h2.windowUpdate(1, 1);
                h2.windowUpdate(0, 1);
            }
            assertEquals(1, this.server.connections(), "busy connection not reaped as idle");
        }
    }

    @Test
    void aRequestThatNeverEndsIsResetOnceAnswered() throws Exception {
        start(Duration.ofSeconds(30), Duration.ofSeconds(10));
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1, RawH2.block("GET", "/hello.txt")); // no END_STREAM
            boolean ended = false;
            RawH2.Frame rst = null;
            for (RawH2.Frame f; (f = h2.read()) != null && rst == null;) {
                if (f.stream() == 1 && f.type() == RawH2.DATA && (f.flags() & RawH2.END_STREAM) != 0) {
                    ended = true;
                } else if (f.stream() == 1 && f.type() == RawH2.RST_STREAM) {
                    rst = f;
                }
            }
            assertTrue(ended, "full response first");
            assertNotNull(rst, "then RST_STREAM");
            assertEquals(0, rst.errorCode(), "NO_ERROR");
        }
    }

    private Socket h1() throws IOException {
        var s = new Socket(InetAddress.getLoopbackAddress(), this.server.h2cPort());
        s.setSoTimeout(10_000);
        return s;
    }

    @Test
    void slowHeadersOnTheHttp1FallbackAreCut() throws Exception {
        start(Duration.ofSeconds(30), Duration.ofMillis(400));
        try (var s = h1()) {
            var out = s.getOutputStream();
            out.write("GET /hello.txt HTTP/1.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
            long t0 = System.nanoTime();
            boolean closed = false;
            try {
                for (int i = 0; i < 60; i++) { // a header byte every 50 ms, never finishing
                    out.write('X');
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                closed = true;
            }
            if (!closed) {
                assertEquals(-1, s.getInputStream().read());
            }
            assertTrue(System.nanoTime() - t0 < 4_000_000_000L, "cut near the header timeout");
        }
    }

    @Test
    void theHeaderTimeoutDoesNotCutAnIdleKeepAliveConnection() throws Exception {
        start(Duration.ofSeconds(30), Duration.ofMillis(300));
        try (var s = h1()) {
            var req = "GET /hello.txt HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            s.getOutputStream().write(req);
            assertTrue(readResponse(s.getInputStream()).startsWith("HTTP/1.1 200"));
            Thread.sleep(800); // idle between requests longer than the header timeout
            s.getOutputStream().write(req);
            assertTrue(readResponse(s.getInputStream()).startsWith("HTTP/1.1 200"));
        }
    }

    @Test
    void anUpgradedConnectionIsNotCutByTheHeaderTimeout() throws Exception {
        start(Duration.ofSeconds(30), Duration.ofMillis(300));
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            Thread.sleep(800);
            h2.get(1, "/hello.txt");
            boolean data = false;
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.type() == RawH2.DATA && f.stream() == 1) {
                    data = true;
                    break;
                }
            }
            assertTrue(data, "h2 response after the header timeout elapsed");
        }
    }

    @Test
    void aPingFloodThatNeverReadsIsCut() throws Exception {
        start(Duration.ofSeconds(30), Duration.ofSeconds(10));
        try (var h2 = new RawH2(this.server.h2cPort(), 65535, 4096)) {
            boolean cut = false;
            try {
                var batch = new byte[17 * 1000]; // 1000 PING frames
                for (int i = 0; i < 1000; i++) {
                    batch[i * 17 + 2] = 8;
                    batch[i * 17 + 3] = RawH2.PING;
                }
                var out = h2.socket.getOutputStream();
                for (int i = 0; i < 2000; i++) { // 2M PINGs (34 MB), never reading the ACKs
                    out.write(batch);
                }
            } catch (IOException e) {
                cut = true;
            }
            if (!cut) {
                drain(h2);
                cut = true;
            }
            assertTrue(cut);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (this.server.connections() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(0, this.server.connections(), "flooding connection closed");
        }
    }

    private static String readResponse(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            if (sb.toString().endsWith("\r\n\r\n")) {
                break;
            }
        }
        var head = sb.toString();
        var m = java.util.regex.Pattern.compile("(?i)content-length: (\\d+)").matcher(head);
        if (m.find()) {
            in.readNBytes(Integer.parseInt(m.group(1)));
        }
        return head;
    }
}
