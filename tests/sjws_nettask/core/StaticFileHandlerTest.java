package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The handler on its own: no sockets, no HTTP/1 serialization. Asserts content, not Body subtypes. */
class StaticFileHandlerTest {

    static final Instant MTIME = Instant.parse("2001-02-03T04:05:06Z");

    @TempDir
    Path tmp;
    Path root;
    StaticFileHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(root.resolve("hello.txt"), "hello world");
        Files.setLastModifiedTime(root.resolve("hello.txt"), FileTime.from(MTIME));
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        var sub = Files.createDirectory(root.resolve("sub"));
        Files.writeString(sub.resolve("a.css"), "body{}");
        Files.writeString(tmp.resolve("secret.txt"), "TOP-SECRET");
        handler = new StaticFileHandler(root);
    }

    private Response get(String target) {
        return handler.handle(new Request("GET", target));
    }

    /** Reads whatever the body is: Bytes, a FileBody region, or nothing. */
    static byte[] content(Body body) throws IOException {
        return switch (body) {
            case Body.Empty e -> new byte[0];
            case Body.Bytes b -> {
                var d = b.data().duplicate();
                var out = new byte[d.remaining()];
                d.get(out);
                yield out;
            }
            case Body.FileBody f -> {
                try (var ch = FileChannel.open(f.realPath())) {
                    var buf = java.nio.ByteBuffer.allocate((int) f.length());
                    ch.read(buf, f.offset());
                    yield buf.array();
                }
            }
        };
    }

    static String text(Response r) throws IOException {
        return new String(content(r.body()), StandardCharsets.UTF_8);
    }

    @Test
    void ok200File() throws IOException {
        var r = get("/hello.txt");
        assertEquals(200, r.status());
        assertEquals("hello world", text(r));
        assertEquals(11, r.contentLength());
        assertEquals("text/plain; charset=utf-8", r.headers().get("Content-Type"));
        assertEquals("Sat, 03 Feb 2001 04:05:06 GMT", r.headers().get("last-modified"));
        assertEquals("bytes", r.headers().get("accept-ranges"));
        assertFalse(r.headOnly());
        if (r.body() instanceof Body.FileBody f) {
            assertEquals(root.resolve("hello.txt").toRealPath(), f.realPath());
            assertEquals(0, f.offset());
            assertEquals(11, f.expected().size());
        }
    }

    @Test
    void headKeepsLengthAndFlagsHeadOnly() throws IOException {
        var r = handler.handle(new Request("HEAD", "/hello.txt"));
        assertEquals(200, r.status());
        assertTrue(r.headOnly());
        assertEquals(11, r.contentLength());
        var miss = handler.handle(new Request("HEAD", "/nope"));
        assertEquals(404, miss.status());
        assertTrue(miss.headOnly());
        assertTrue(miss.contentLength() > 0);
    }

    @Test
    void index() throws IOException {
        assertEquals("<h1>home</h1>", text(get("/")));
        assertEquals("text/html; charset=utf-8", get("/").headers().get("content-type"));
    }

    @Test
    void listing() throws IOException {
        Files.writeString(root.resolve("sub").resolve("<b>.txt"), "x");
        Files.writeString(root.resolve("sub").resolve(".env"), "x");
        var r = get("/sub/");
        assertEquals(200, r.status());
        var html = text(r);
        assertTrue(html.contains("href=\"/sub/a.css\""), html);
        assertTrue(html.contains("&lt;b&gt;.txt"), html);
        assertFalse(html.contains("<b>"), html);
        assertFalse(html.contains(".env"), html);
        assertEquals(content(r.body()).length, r.contentLength());
    }

    @Test
    void redirect301KeepsQuery() throws IOException {
        var r = get("/sub?a=1");
        assertEquals(301, r.status());
        assertEquals("/sub/?a=1", r.headers().get("location"));
        assertTrue(text(r).contains("/sub/?a=1"));
    }

    @Test
    void notModified304() throws IOException {
        var r = handler.handle(new Request("GET", "/hello.txt",
                RequestHeaders.ifModifiedSince("Sat, 03 Feb 2001 04:05:06 GMT")));
        assertEquals(304, r.status());
        assertFalse(r.mayHaveBody());
        assertEquals(0, r.contentLength());
        assertEquals("Sat, 03 Feb 2001 04:05:06 GMT", r.headers().get("last-modified"));
        assertEquals(200, handler.handle(new Request("GET", "/hello.txt",
                RequestHeaders.ifModifiedSince("Sat, 03 Feb 2001 04:05:05 GMT"))).status());
        assertEquals(200, handler.handle(new Request("GET", "/hello.txt",
                RequestHeaders.ifModifiedSince("garbage"))).status());
    }

    @Test
    void forbidden403() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")));
        var locked = Files.createDirectory(root.resolve("locked"));
        Files.writeString(root.resolve("private.txt"), "p");
        Files.setPosixFilePermissions(locked, Set.of());
        Files.setPosixFilePermissions(root.resolve("private.txt"), Set.of());
        try {
            assertEquals(403, get("/locked/").status());
            assertEquals(403, get("/private.txt").status());
            assertEquals(403, handler.handle(new Request("HEAD", "/private.txt")).status());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/nope.txt", "/../secret.txt", "/sub/../../secret.txt", "/%2e%2e/secret.txt",
        "/.env", "/sub/.hidden/x", "/out.txt", "/dangling"})
    void notFound404(String target) throws IOException {
        Files.createSymbolicLink(root.resolve("out.txt"), tmp.resolve("secret.txt"));
        Files.createSymbolicLink(root.resolve("dangling"), tmp.resolve("missing"));
        var r = get(target);
        assertEquals(404, r.status());
        assertFalse(text(r).contains("TOP-SECRET"));
    }

    @Test
    void indexSymlinkOutsideRootIsNotServed() throws IOException {
        var d = Files.createDirectory(root.resolve("d"));
        Files.createSymbolicLink(d.resolve("index.html"), tmp.resolve("secret.txt"));
        var r = get("/d/");
        assertEquals(200, r.status()); // falls back to the (empty) listing
        assertFalse(text(r).contains("TOP-SECRET"));
        assertFalse(text(r).contains("index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "TRACE", "CONNECT"})
    void methodNotAllowed405(String method) throws IOException {
        var r = handler.handle(new Request(method, "/hello.txt"));
        assertEquals(405, r.status());
        assertEquals("GET, HEAD, OPTIONS", r.headers().get("allow"));
        assertTrue(text(r).contains("405 Method Not Allowed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "/", "/hello.txt", "/missing.txt"})
    void optionsAnswers200WithAllowAndNoContent(String target) {
        var r = handler.handle(new Request("OPTIONS", target));
        assertEquals(200, r.status());
        assertEquals("GET, HEAD, OPTIONS", r.headers().get("allow"));
        assertEquals(0, r.contentLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    void asteriskTargetIs400ForOtherMethods(String method) {
        assertEquals(400, handler.handle(new Request(method, "*")).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%zz", "/a%00b", "nopath"})
    void badRequest400(String target) {
        assertEquals(400, get(target).status());
    }

    @Test
    void errorPagesAreHtml() throws IOException {
        var r = StaticFileHandler.error(404, false);
        assertEquals("text/html; charset=utf-8", r.headers().get("content-type"));
        assertTrue(text(r).contains("<title>404 Not Found</title>"));
    }

    @Test
    void rootMustBeADirectory() {
        assertThrows(java.io.UncheckedIOException.class, () -> new StaticFileHandler(tmp.resolve("missing")));
        assertThrows(java.io.UncheckedIOException.class, () -> new StaticFileHandler(tmp.resolve("secret.txt")));
    }
}
