package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** HTTP/1.1 connection edge cases end to end: early EOF, bodies to discard, failure logging. */
@org.junit.jupiter.api.extension.ExtendWith(sjws_nettask.core.buf.NoBufferLeaks.class)
class Http1EdgeTest {

    @TempDir
    Path root;

    SjwsServer server;

    @BeforeEach
    void start() throws IOException {
        Files.writeString(root.resolve("hello.txt"), "hello");
        server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), (c, h) -> null);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    Socket connect() throws IOException {
        var s = new Socket(InetAddress.getLoopbackAddress(), server.tcpPort());
        s.setSoTimeout(10_000);
        return s;
    }

    static String readAll(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void headCutShortByEofClosesSilently() throws IOException {
        try (var s = connect()) {
            s.getOutputStream().write("GET /hello.txt HTTP/1.1\r\nHost: h".getBytes(StandardCharsets.ISO_8859_1));
            s.shutdownOutput();
            assertEquals("", readAll(s.getInputStream()), "nothing to answer: the client gave up");
        }
    }

    @Test
    void headEndingInABareCrGets400() throws IOException {
        try (var s = connect()) {
            s.getOutputStream().write("GET /hello.txt HTTP/1.1\r".getBytes(StandardCharsets.ISO_8859_1));
            s.shutdownOutput();
            assertTrue(readAll(s.getInputStream()).startsWith("HTTP/1.1 400 Bad Request\r\n"));
        }
    }

    @Test
    void blankLinesThenEofCloseWithoutAResponse() throws IOException {
        try (var s = connect()) {
            s.getOutputStream().write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.shutdownOutput();
            assertEquals("", readAll(s.getInputStream()));
        }
    }

    @Test
    void bodyTooLargeToDiscardIsAnsweredThenTheConnectionCloses() throws IOException {
        try (var s = connect()) {
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\nContent-Length: "
                    + (CONSTANTS.MAX_DRAINED_BODY + 1) + "\r\n\r\n");
            var r = HttpTestSupport.readResponse(s.getInputStream());
            assertEquals(200, r.statusCode());
            assertEquals("close", r.headers().get("connection"));
            assertEquals(-1, s.getInputStream().read(), "closed instead of reading the body");
        }
    }

    @Test
    void smallBodyIsDiscardedAndTheConnectionKeptAlive() throws IOException {
        try (var s = connect()) {
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\nxxxxx"
                    + "GET /hello.txt HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
            assertEquals("hello", HttpTestSupport.readResponse(s.getInputStream()).bodyText());
            assertEquals("hello", HttpTestSupport.readResponse(s.getInputStream()).bodyText());
        }
    }

    @Test
    void eofInsideABodyToDiscardClosesWithoutAResponse() throws IOException {
        try (var s = connect()) {
            HttpTestSupport.write(s.getOutputStream(), "GET /hello.txt HTTP/1.1\r\nHost: h\r\nContent-Length: 1000\r\n\r\nxx");
            s.shutdownOutput();
            assertEquals("", readAll(s.getInputStream()), "no 408: the head deadline did not fire");
        }
    }

    @Test
    void anUnexpectedFailureIsLoggedWithTheClientAddress() throws Throwable {
        var big = root.resolve("big.bin");
        try (var f = java.nio.channels.FileChannel.open(big, java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            f.write(java.nio.ByteBuffer.wrap(new byte[1]), 64L * 1024 * 1024 - 1); // sparse 64 MiB, sendfile path
        }
        var err = SjwsServerTest.stderrOf(() -> {
            try (var s = connect()) {
                HttpTestSupport.write(s.getOutputStream(), "GET /big.bin HTTP/1.1\r\nHost: h\r\n\r\n");
                var in = s.getInputStream();
                in.readNBytes(1024); // the transfer has started ...
                try (var f = java.nio.channels.FileChannel.open(big, java.nio.file.StandardOpenOption.WRITE)) {
                    f.truncate(1); // ... and the file shrinks under it
                }
                in.readAllBytes(); // the server closes once it notices
                long end = System.nanoTime() + 10_000_000_000L;
                while (server.clientManager().activeConnections() > 0 && System.nanoTime() < end) {
                    Thread.onSpinWait();
                }
            }
        });
        int local = server.tcpPort();
        assertTrue(err.matches("(?s).*Connection /127\\.0\\.0\\.1:\\d+ failed: java\\.io\\.IOException: file shrank during transfer.*"),
                "address, not \"(closed)\", for server port " + local + ": " + err);
    }
}
