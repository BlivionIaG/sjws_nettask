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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertEquals("text/plain; charset=utf-8", r.headers().get("content-type"));
    }

    @Test
    void servesFileInSubdirectory() throws IOException {
        Response r = serve("/sub/a.css");
        assertEquals(200, r.statusCode());
        assertEquals("body{}", r.bodyText());
        assertEquals("text/css; charset=utf-8", r.headers().get("content-type"));
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
        Response r = serve("/sub/");
        assertEquals(200, r.statusCode());
        assertTrue(r.bodyText().contains("a.css"), r.bodyText());
    }

    @Test
    void BUG_directoryListingContentLengthMatchesBody() throws IOException {
        // sendFolder appends an extra "\r\n\r\n" after a head that already ends in one,
        // and uses String.length() (chars) instead of the UTF-8 byte count.
        Files.writeString(root.resolve("sub").resolve("café-é.txt"), "x");
        Response r = serve("/sub/");
        // After the declared Content-Length bytes the body must end with </html>.
        assertTrue(r.bodyText().endsWith("</html>"),
                "body framed by Content-Length is cut/shifted: ...'"
                + r.bodyText().substring(Math.max(0, r.bodyText().length() - 30)) + "'");
    }

    @Test
    void BUG_directoryListingEscapesFileNames() throws IOException {
        Files.writeString(root.resolve("sub").resolve("<script>x.txt"), "x");
        Response r = serve("/sub/");
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

    @ParameterizedTest
    @ValueSource(strings = {
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

    // --- review-fixes ---------------------------------------------------------

    /** Calls HTML.send into memory; HEAD and 304 responses are parsed without a body. */
    private Response call(String target, boolean head, String ifModifiedSince) throws IOException {
        var bos = new java.io.ByteArrayOutputStream();
        html.send(bos, target, head, true, ifModifiedSince);
        return HttpTestSupport.readResponse(new java.io.ByteArrayInputStream(bos.toByteArray()), head);
    }

    @Test
    void directoryWithoutSlashRedirects() throws IOException {
        Response r = call("/sub", false, null);
        assertEquals(301, r.statusCode());
        assertEquals("/sub/", r.headers().get("location"));
        assertEquals("/sub/?x=1&y=2", call("/sub?x=1&y=2", false, null).headers().get("location"));
    }

    @Test
    void encodedDirectoryRedirectKeepsEncoding() throws IOException {
        Files.createDirectory(root.resolve("my dir"));
        assertEquals("/my%20dir/", call("/my%20dir", false, null).headers().get("location"));
    }

    @Test
    void symlinkOutsideRootIs404AndHiddenFromListing() throws IOException {
        Files.createSymbolicLink(root.resolve("out.txt"), tmp.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("outdir"), tmp);
        Response r = call("/out.txt", false, null);
        assertEquals(404, r.statusCode());
        assertFalse(r.bodyText().contains("TOP-SECRET"));
        assertEquals(404, call("/outdir/secret.txt", false, null).statusCode());
        assertEquals(404, call("/outdir/", false, null).statusCode());
        Files.createDirectory(root.resolve("list"));
        Files.createSymbolicLink(root.resolve("list").resolve("escape"), tmp.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("list").resolve("dangling"), tmp.resolve("nope"));
        Files.writeString(root.resolve("list").resolve("shown.txt"), "x");
        String listing = call("/list/", false, null).bodyText();
        assertTrue(listing.contains("shown.txt"), listing);
        assertFalse(listing.contains("escape"), listing);
        assertFalse(listing.contains("dangling"), listing);
    }

    @Test
    void symlinkInsideRootIsServed() throws IOException {
        Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("hello.txt"));
        Files.createSymbolicLink(root.resolve("subalias"), root.resolve("sub"));
        assertEquals("hello world", call("/alias.txt", false, null).bodyText());
        assertEquals("body{}", call("/subalias/a.css", false, null).bodyText());
        assertTrue(call("/", false, null).statusCode() == 200);
    }

    @Test
    void dotfilesAreNotServedNorListed() throws IOException {
        Files.writeString(root.resolve("sub").resolve(".env"), "PASSWORD=x");
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git").resolve("config"), "[core]");
        assertEquals(404, call("/sub/.env", false, null).statusCode());
        assertEquals(404, call("/.git/config", false, null).statusCode());
        assertEquals(404, call("/%2egit/config", false, null).statusCode());
        assertEquals(404, call("/.git/", false, null).statusCode());
        assertFalse(call("/sub/", false, null).bodyText().contains(".env"));
    }

    @Test
    void unreadableDirectoryIs403() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "permissions are not enforced for root");
        var locked = Files.createDirectory(root.resolve("locked"));
        Files.setPosixFilePermissions(locked, java.util.Set.of());
        try {
            Response r = call("/locked/", false, null);
            assertEquals(403, r.statusCode());
            assertEquals(String.valueOf(r.body().length), r.headers().get("content-length"));
        } finally {
            Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void unreadableFileIs403() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        var f = root.resolve("private.txt");
        Files.writeString(f, "nope");
        Files.setPosixFilePermissions(f, java.util.Set.of());
        assertEquals(403, call("/private.txt", false, null).statusCode());
    }

    @Test
    void headOn404HasNoBody() throws IOException {
        var bos = new java.io.ByteArrayOutputStream();
        html.send(bos, "/missing.txt", true, true, null);
        String raw = bos.toString(StandardCharsets.ISO_8859_1);
        assertTrue(raw.startsWith("HTTP/1.1 404 Not Found\r\n"), raw);
        assertTrue(raw.endsWith("\r\n\r\n"), "HEAD response must end after the head: " + raw);
        assertTrue(raw.contains("Content-Length: "), raw);
    }

    @Test
    void ifModifiedSince() throws IOException {
        var mtime = java.time.Instant.parse("2001-02-03T04:05:06Z");
        Files.setLastModifiedTime(root.resolve("hello.txt"), java.nio.file.attribute.FileTime.from(mtime));
        Response same = call("/hello.txt", false, "Sat, 03 Feb 2001 04:05:06 GMT");
        assertEquals(304, same.statusCode());
        assertEquals(0, same.body().length);
        assertEquals("Sat, 03 Feb 2001 04:05:06 GMT", same.headers().get("last-modified"));
        assertNull(same.headers().get("content-length"));
        assertEquals(304, call("/hello.txt", false, "Sun, 01 Jan 2023 00:00:00 GMT").statusCode());
        assertEquals(200, call("/hello.txt", false, "Sat, 03 Feb 2001 04:05:05 GMT").statusCode());
        assertEquals(200, call("/hello.txt", false, "not a date").statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b.txt", "100%.txt", "plus+sign.txt", "café.txt", "q?x.txt", "hash#.txt",
        "amp&lt;.txt", "quote\"s.txt", "semi;colon.txt", "日本.txt"})
    void listingLinksRoundTrip(String name) throws IOException {
        var dir = Files.createDirectory(root.resolve("odd"));
        Files.writeString(dir.resolve(name), "content of " + name);
        String listing = call("/odd/", false, null).bodyText();
        var m = java.util.regex.Pattern.compile("href=\"([^\"]+)\"").matcher(listing);
        assertTrue(m.find(), listing);
        String href = m.group(1).replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        Response r = call(href, false, null);
        assertEquals(200, r.statusCode(), "href " + href);
        assertEquals("content of " + name, r.bodyText());
    }

    /** OutputStream that runs an action on every write after the first (the head). */
    private static final class Hooked extends java.io.OutputStream {

        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        final IoAction onBodyWrite;
        int writes;

        interface IoAction {
            void run() throws IOException;
        }

        Hooked(IoAction onBodyWrite) {
            this.onBodyWrite = onBodyWrite;
        }

        @Override
        public void write(int b) {
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (writes++ > 0) {
                onBodyWrite.run();
            }
            bytes.write(b, off, len);
        }
    }

    @Test
    void growingFileSendsExactlyContentLength() throws IOException {
        byte[] data = new byte[300_000];
        new Random(7).nextBytes(data);
        var f = root.resolve("grow.bin");
        Files.write(f, data);
        byte[] more = new byte[100_000];
        var out = new Hooked(() -> Files.write(f, more, java.nio.file.StandardOpenOption.APPEND));
        Page.sendFile(out, f, false, true);
        Response r = HttpTestSupport.readResponse(new java.io.ByteArrayInputStream(out.bytes.toByteArray()));
        assertEquals(String.valueOf(data.length), r.headers().get("content-length"));
        assertArrayEquals(data, r.body());
        assertTrue(Files.size(f) > data.length, "file really grew");
        int headLen = r.rawHead().length();
        assertEquals(headLen + data.length, out.bytes.size(), "no bytes beyond Content-Length");
    }

    @Test
    void shrinkingFileFailsInsteadOfHanging() throws IOException {
        byte[] data = new byte[300_000];
        var f = root.resolve("shrink.bin");
        Files.write(f, data);
        var out = new Hooked(() -> {
            try (var ch = java.nio.channels.FileChannel.open(f, java.nio.file.StandardOpenOption.WRITE)) {
                ch.truncate(1000);
            }
        });
        var e = assertThrows(IOException.class, () -> Page.sendFile(out, f, false, true));
        assertTrue(e.getMessage().contains("shrank"), e.getMessage());
    }

    @Test
    void rootMustExist() {
        assertThrows(java.io.UncheckedIOException.class, () -> new HTML(tmp.resolve("missing").toString()));
        assertThrows(java.io.UncheckedIOException.class, () -> new HTML(tmp.resolve("secret.txt").toString()));
    }
}
