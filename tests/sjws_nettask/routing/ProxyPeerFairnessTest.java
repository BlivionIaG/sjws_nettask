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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;

/**
 * #78 behavioural regression through the public {@link ProxyHandler#handle(Request)} path.
 *
 * <p>Uses only APIs that exist on master: {@code new ProxyHandler(Settings)} (which takes
 * {@link ByteBudget#shared()}), {@link Request} with a peer address, and {@link Body.Stream}.
 * The child JVM starts with {@code -Dsjws.proxy.maxInflightBytes} so {@code shared()} loads a
 * small global budget. On this branch that also turns on the default per-peer share (one eighth);
 * on master there is no per-peer share.
 *
 * <p>Scenario: peer A holds a large in-flight proxied body. A further request from A must be
 * refused with 503 (per-peer admission), while peer B must still complete. On master the further
 * A request is accepted instead of refused, so this fails at runtime there.
 */
class ProxyPeerFairnessTest {

    /** Small global budget; branch peer share is one eighth of this. */
    static final long GLOBAL = 512 * 1024L;
    static final int SIZE = 1 << 20;
    static final int HOLDERS = 8;

    /**
     * Child entry under {@code -Dsjws.proxy.maxInflightBytes}. Exit 0 on success, 1 on failure.
     */
    public static void main(String[] args) throws Exception {
        var reply = "HTTP/1.1 200 OK\r\nContent-Length: " + SIZE + "\r\n\r\n" + "x".repeat(SIZE);
        var replies = new String[HOLDERS + 4];
        for (int i = 0; i < replies.length; i++) {
            replies[i] = reply;
        }
        try (var upstream = script(replies)) {
            var d = Duration.ofSeconds(10);
            try (var p = new ProxyHandler(new ProxyHandler.Settings(
                    URI.create("http://127.0.0.1:" + upstream.getLocalPort()), d, d, d, 1 << 20, null, List.of(),
                    false, HOLDERS + 4))) {
                var budget = p.budget();
                var peerA = addr(10, 0, 0, 1);
                var peerB = addr(10, 0, 0, 2);
                var held = new ArrayList<Body.Stream.Source>();
                int opened = 0;
                for (int i = 0; i < HOLDERS; i++) {
                    Response resp = p.handle(request("GET", "/a" + i, peerA));
                    if (!(resp.body() instanceof Body.Stream stream)) {
                        System.out.println("peer A non-stream status=" + resp.status() + " at i=" + i
                                + " in_flight=" + budget.inFlight());
                        break;
                    }
                    var src = stream.source();
                    if (src.next() == null) {
                        System.err.println("peer A empty body at i=" + i);
                        System.exit(1);
                        return;
                    }
                    held.add(src);
                    opened++;
                    for (int w = 0; w < 80 && budget.inFlight() < 32 * 1024L; w++) {
                        Thread.sleep(10);
                    }
                    // Probe: a further A request. On the branch this becomes 503 once the peer
                    // share is full; on master it stays 200.
                    Response probe = p.handle(request("GET", "/probe" + i, peerA));
                    if (probe.status() == 503) {
                        System.out.println("peer A probe 503 after opened=" + opened
                                + " in_flight=" + budget.inFlight());
                        break;
                    }
                    // Master (or share not yet full): hold this probe as another slow stream if it
                    // is a body, so we keep filling.
                    if (probe.body() instanceof Body.Stream more) {
                        var ms = more.source();
                        if (ms.next() != null) {
                            held.add(ms);
                            opened++;
                        }
                    }
                }
                System.out.println("held_in_flight=" + budget.inFlight() + " limit=" + budget.limit()
                        + " opened=" + opened);

                if (opened == 0 || budget.inFlight() <= 0) {
                    System.err.println("peer A held nothing");
                    System.exit(1);
                    return;
                }

                // The behavioural gate that fails on master: further request from the holding peer
                // must be refused with 503 (per-peer admission).
                Response again = p.handle(request("GET", "/again", peerA));
                System.out.println("peer A again status=" + again.status() + " in_flight=" + budget.inFlight());
                if (again.status() != 503) {
                    System.err.println("FAIL expected 503 for further request from holding peer, got "
                            + again.status());
                    for (var src : held) {
                        try {
                            src.close();
                        } catch (Exception ignored) {
                            // best-effort
                        }
                    }
                    System.exit(1);
                    return;
                }

                // Second peer must still be accepted and complete.
                var got = new AtomicLong();
                var err = new AtomicLong();
                var reader = Thread.ofVirtual().start(() -> {
                    try {
                        Response resp = p.handle(request("GET", "/b", peerB));
                        if (resp.status() != 200 || !(resp.body() instanceof Body.Stream stream)) {
                            err.set(resp.status() == 0 ? 1 : resp.status());
                            return;
                        }
                        try (var src = stream.source()) {
                            for (var buf = src.next(); buf != null; buf = src.next()) {
                                got.addAndGet(buf.remaining());
                            }
                        }
                    } catch (Exception e) {
                        err.set(2);
                        e.printStackTrace(System.err);
                    }
                });
                reader.join(Duration.ofSeconds(5));
                if (reader.isAlive()) {
                    reader.interrupt();
                    System.err.println("FAIL peer B stalled got=" + got.get());
                    System.exit(1);
                    return;
                }
                for (var src : held) {
                    try {
                        src.close();
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }
                if (err.get() != 0 || got.get() != SIZE) {
                    System.err.println("FAIL peer B got=" + got.get() + " err=" + err.get());
                    System.exit(1);
                    return;
                }
                System.out.println("PASS peer A further=503; peer B got=" + got.get());
                System.exit(0);
            }
        }
    }

    @Test
    @Timeout(60)
    void holdingPeerIsRefusedWhileSecondPeerStillCompletes() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(java,
                "-Dsjws.proxy.maxInflightBytes=" + GLOBAL,
                "-cp", System.getProperty("java.class.path"),
                ProxyPeerFairnessTest.class.getName());
        pb.redirectErrorStream(true);
        var p = pb.start();
        var out = new StringBuilder();
        try (var in = p.getInputStream()) {
            out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(p.waitFor(45, TimeUnit.SECONDS), "child timed out; output:\n" + out);
        assertEquals(0, p.exitValue(), "child must pass on this branch (fail on master); output:\n" + out);
        assertTrue(out.toString().contains("PASS"), out.toString());
    }

    private static Request request(String method, String target, InetSocketAddress peer) {
        return new Request(method, target, RequestHeaders.of(Map.of()), peer, false);
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
