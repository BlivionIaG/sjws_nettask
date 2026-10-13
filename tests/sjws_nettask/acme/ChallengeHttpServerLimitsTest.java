package sjws_nettask.acme;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The HTTP-01 listener faces the internet on its own port: idle or trickling clients must not pin
 * an unbounded number of sockets (file descriptors shared with the main server) or keep one open
 * forever.
 */
class ChallengeHttpServerLimitsTest {

    private static Socket connect(int port) throws IOException {
        var s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setSoTimeout(5_000);
        return s;
    }

    /** @return true if the server closed the socket (EOF or reset) within the socket's timeout */
    private static boolean closedByServer(Socket s) {
        try {
            InputStream in = s.getInputStream();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    return true;
                }
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true; // reset
        }
    }

    @Test
    void connectionsBeyondTheCapAreClosedAtOnce() throws Exception {
        int cap = 8;
        try (var server = ChallengeHttpServer.open(0, new ChallengeBoard(), cap, Duration.ofSeconds(30))) {
            List<Socket> idle = new ArrayList<>();
            try {
                for (int i = 0; i < cap; i++) {
                    idle.add(connect(server.port()));
                }
                // let the accept loop take all of them
                long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (server.activeConnections() < cap && System.nanoTime() < until) {
                    Thread.sleep(10);
                }
                assertEquals(cap, server.activeConnections());
                try (var extra = connect(server.port())) {
                    extra.setSoTimeout(2_000);
                    assertTrue(closedByServer(extra), "connection over the cap must be closed");
                }
                assertEquals(cap, server.activeConnections());
            } finally {
                for (var s : idle) {
                    s.close();
                }
            }
            // slots are released once clients go away
            long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (server.activeConnections() > 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(0, server.activeConnections());
        }
    }

    @Test
    void tricklingClientIsCutAtTheHeadDeadline() throws Exception {
        try (var server = ChallengeHttpServer.open(0, new ChallengeBoard(), 8, Duration.ofMillis(800));
                var s = connect(server.port())) {
            s.setSoTimeout(6_000);
            long start = System.nanoTime();
            var closed = new java.util.concurrent.CompletableFuture<Long>();
            Thread.ofVirtual().start(() -> {
                boolean c = closedByServer(s);
                closed.complete(c ? System.nanoTime() : -1L);
            });
            var out = s.getOutputStream();
            // one byte every 100 ms: every read succeeds well inside any per-read timeout
            while (!closed.isDone() && System.nanoTime() - start < Duration.ofSeconds(5).toNanos()) {
                try {
                    out.write('G');
                    out.flush();
                } catch (IOException e) {
                    break;
                }
                Thread.sleep(100);
            }
            long at = closed.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(at > 0, "trickling client never cut");
            long ms = Duration.ofNanos(at - start).toMillis();
            assertTrue(ms >= 700 && ms < 3_000, "cut after " + ms + " ms");
        }
    }

    @Test
    void wellFormedRequestStillAnswered() throws Exception {
        var board = new ChallengeBoard();
        board.put("tok_en-123", "tok_en-123.key");
        try (var server = ChallengeHttpServer.open(0, board, 8, Duration.ofSeconds(10));
                var s = connect(server.port())) {
            s.getOutputStream().write(("GET /.well-known/acme-challenge/tok_en-123 HTTP/1.1\r\nHost: x\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            var reply = new String(s.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(reply.startsWith("HTTP/1.1 200"), reply);
            assertTrue(reply.endsWith("tok_en-123.key"), reply);
        }
    }
}
