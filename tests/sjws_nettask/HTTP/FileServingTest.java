package sjws_nettask.HTTP;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.HttpTestSupport.Response;

/**
 * Exercises HTML.send / Page.send / Page.sendFolder directly over a loopback
 * socket pair, against a temporary document root.
 */
class FileServingTest {

    @TempDir
    Path tmp;
    Path root;
    HTML html;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("hello.txt"), "hello world");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub").resolve("a.css"), "body{}");
        // A file OUTSIDE the document root, which must never be served.
        Files.writeString(tmp.resolve("secret.txt"), "TOP-SECRET");
        html = new HTML(root.toString());
    }

    /** Runs html.send(serverSide, path), closes the server side, returns what the client got. */
    private Response serve(String path) throws IOException {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             var serverSide = listener.accept()) {
            client.setSoTimeout(3000);
            var sender = new Thread(() -> {
                try {
                    html.send(serverSide, path);
                } catch (IOException e) {
                    // surfaced through the response assertions
                } finally {
                    try {
                        serverSide.close();
                    } catch (IOException ignored) {
                    }
                }
            });
            sender.start();
            var response = HttpTestSupport.readResponse(client.getInputStream());
            try {
                sender.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return response;
        }
    }

    @Test
    void servesFileWithCorrectBodyLengthAndType() throws IOException {
        Response r = serve("/hello.txt");
        assertNotNull(r.statusLine(), "nothing was sent");
        assertEquals(200, r.statusCode());
        assertEquals("hello world", r.bodyText());
        assertEquals("11", r.headers().get("content-length"));
        assertEquals("text/plain", r.headers().get("content-type"));
    }

    @Test
    void servesFileInSubdirectory() throws IOException {
        Response r = serve("/sub/a.css");
        assertEquals(200, r.statusCode());
        assertEquals("body{}", r.bodyText());
        assertEquals("text/css", r.headers().get("content-type"));
    }

    @Test
    void rootServesIndexHtml() throws IOException {
        Response r = serve("/");
        assertEquals(200, r.statusCode());
        assertEquals("<h1>home</h1>", r.bodyText());
    }

    @Test
    void largeBinaryFileIsServedByteForByte() throws IOException {
        // Larger than HTML_BUFFER_SIZE (64 KiB) so the read loop runs several times.
        byte[] data = new byte[1_000_003];
        new Random(42).nextBytes(data);
        Files.write(root.resolve("blob.zip"), data);
        Response r = serve("/blob.zip");
        assertEquals(200, r.statusCode());
        assertEquals(String.valueOf(data.length), r.headers().get("content-length"));
        assertArrayEquals(data, r.body());
    }

    @Test
    void directoryListingLinksToChildren() throws IOException {
        Response r = serve("/sub");
        assertEquals(200, r.statusCode());
        assertTrue(r.bodyText().contains("a.css"), r.bodyText());
    }

    @Test
    void BUG_directoryListingContentLengthMatchesBody() throws IOException {
        // sendFolder appends an extra "\r\n\r\n" after a head that already ends in one,
        // and uses String.length() (chars) instead of the UTF-8 byte count.
        Files.writeString(root.resolve("sub").resolve("café-é.txt"), "x");
        Response r = serve("/sub");
        // After the declared Content-Length bytes the body must end with </html>.
        assertTrue(r.bodyText().endsWith("</html>"),
                "body framed by Content-Length is cut/shifted: ...'"
                + r.bodyText().substring(Math.max(0, r.bodyText().length() - 30)) + "'");
    }

    @Test
    void BUG_directoryListingEscapesFileNames() throws IOException {
        Files.writeString(root.resolve("sub").resolve("<script>x.txt"), "x");
        Response r = serve("/sub");
        assertFalse(r.bodyText().contains("<script>"), "file name injected unescaped into HTML");
    }

    @Test
    void BUG_subdirectoryWithIndexServesIndex() throws IOException {
        Files.writeString(root.resolve("sub").resolve("index.html"), "<p>sub index</p>");
        Response r = serve("/sub/");
        assertEquals("<p>sub index</p>", r.bodyText(), "index.html is only honoured for \"/\"");
    }

    @Test
    void BUG_missingFileReturns404() throws IOException {
        Response r = serve("/does-not-exist.html");
        assertNotNull(r.statusLine(), "server sent NOTHING for a missing file (empty if-branch in HTML.send)");
        assertEquals(404, r.statusCode());
    }

    @Test
    void BUG_pathTraversalIsRejected() throws IOException {
        Response r = serve("/../secret.txt");
        assertFalse(r.bodyText().contains("TOP-SECRET"), "file outside the document root was served");
        assertNotNull(r.statusLine(), "expected a 403/404/400 response, got nothing");
        assertTrue(r.statusCode() == 400 || r.statusCode() == 403 || r.statusCode() == 404, r.statusLine());
    }

    @Test
    void BUG_queryStringIsIgnoredForFileLookup() throws IOException {
        Response r = serve("/hello.txt?v=2");
        assertNotNull(r.statusLine(), "no response: query string is treated as part of the filename");
        assertEquals("hello world", r.bodyText());
    }

    @Test
    void BUG_percentEncodedPathIsDecoded() throws IOException {
        Files.writeString(root.resolve("my file.txt"), "spaced");
        Response r = serve("/my%20file.txt");
        assertNotNull(r.statusLine(), "no response: %20 is not decoded");
        assertEquals("spaced", r.bodyText());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "/%2e%2e/secret.txt", "/..%2fsecret.txt", "/sub/../../secret.txt", "/%2E%2E%2Fsecret.txt"})
    void encodedPathTraversalIsRejected(String path) throws IOException {
        Response r = serve(path);
        assertNotNull(r.statusLine(), "expected an error response");
        assertFalse(r.bodyText().contains("TOP-SECRET"));
        assertEquals(404, r.statusCode());
    }

    @Test
    void dotDotInsideRootIsAllowed() throws IOException {
        Response r = serve("/sub/../hello.txt");
        assertEquals(200, r.statusCode());
        assertEquals("hello world", r.bodyText());
    }

    @Test
    void malformedPercentEncodingIs400() throws IOException {
        assertEquals(400, serve("/%zz").statusCode());
    }

    @Test
    void lastModifiedIsTheFileMtime() throws IOException {
        var mtime = java.time.Instant.parse("2001-02-03T04:05:06Z");
        Files.setLastModifiedTime(root.resolve("hello.txt"), java.nio.file.attribute.FileTime.from(mtime));
        Response r = serve("/hello.txt");
        assertEquals("Sat, 03 Feb 2001 04:05:06 GMT", r.headers().get("last-modified"));
    }

    @Test
    void htmlGetsUtf8Charset() throws IOException {
        assertEquals("text/html; charset=utf-8", serve("/index.html").headers().get("content-type"));
    }
}
