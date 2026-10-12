package sjws_nettask.http1;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pre-core public API (HTML, Page, Header, CONSTANTS) kept as thin wrappers: same bytes as the core path. */
class LegacyApiTest {

    @TempDir
    Path root;

    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    String text() {
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void htmlDefaultsToTheHtmlFolderOfTheProject() {
        var html = new HTML();
        assertEquals(Path.of("HTML").toAbsolutePath().normalize().toString(),
                Path.of(html.getFolderPath()).toAbsolutePath().normalize().toString());
        assertEquals(Path.of(html.getFolderPath()), html.handler().root());
    }

    @Test
    void htmlSendWithoutConditionals() throws IOException {
        Files.writeString(root.resolve("a.txt"), "abc");
        var html = new HTML(root.toString());
        assertEquals(200, html.send(out, "/a.txt", false, false));
        assertTrue(text().startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(text().contains("Connection: close\r\n"));
        assertTrue(text().endsWith("\r\n\r\nabc"));
    }

    @Test
    void pageHelpersWriteCompleteResponses() throws IOException {
        assertEquals(301, Page.sendRedirect(out, "/d/", false, true));
        assertTrue(text().startsWith("HTTP/1.1 301 Moved Permanently\r\n") && text().contains("Location: /d/\r\n"), text());
        out.reset();
        assertEquals(200, Page.sendFolder(out, List.of("a<b>.txt"), "/d/", false, true));
        assertTrue(text().contains("a&lt;b&gt;.txt"), text());
        out.reset();
        assertEquals(404, Page.sendError(out, 404, true, true));
        assertTrue(text().startsWith("HTTP/1.1 404 Not Found\r\n") && text().endsWith("\r\n\r\n"), "HEAD: no body");
        out.reset();
        assertEquals(418, Page.sendBody(out, 418, "text/plain", "teapot", false, false, "X-A", "1", "X-Dangling"));
        assertTrue(text().contains("Content-Type: text/plain; charset=utf-8\r\n"), text());
        assertTrue(text().contains("X-A: 1\r\n") && !text().contains("X-Dangling"), text());
        assertTrue(text().contains("Accept-Ranges: none\r\n") && text().endsWith("teapot"), text());
    }

    @Test
    void headerBuilder() {
        var when = Instant.parse("2001-02-03T04:05:06Z");
        var h = new Header(200, "/x.css", 10).lastModified(when).keepAlive(false).with("X-Extra", "v");
        assertEquals(200, h.getHttpCode());
        var s = h.toString();
        assertTrue(s.contains("Last-Modified: Sat, 03 Feb 2001 04:05:06 GMT\r\n"), s);
        assertTrue(s.contains("Content-Type: text/css; charset=utf-8\r\n"), s);
        assertTrue(s.contains("X-Extra: v\r\n") && s.contains("Connection: close\r\n") && s.contains("Content-Length: 10\r\n"), s);
        assertArrayEquals(s.getBytes(StandardCharsets.ISO_8859_1), h.toBytes());
        assertEquals("Not Modified", Header.reasonPhrase(304));
        for (int code : new int[] {100, 204, 304}) {
            assertFalse(new Header(code, "/x", 5).toString().contains("Content-Length"), "no length for " + code);
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void deprecatedMimeTableStillAnswers() {
        assertEquals("text/html", sjws_nettask.CONSTANTS.MIME_TYPE.get("html"));
        assertSame(sjws_nettask.core.Mime.types(), sjws_nettask.CONSTANTS.MIME_TYPE);
    }

    @Test
    void pageSendFileReturnsTheStatus() throws IOException {
        var f = Files.writeString(root.resolve("f.txt"), "hello");
        assertEquals(200, Page.sendFile(out, f, false, true));
        assertTrue(text().endsWith("hello"));
        out.reset();
        assertEquals(304, Page.sendFile(out, f, false, true, "Sun, 01 Jan 2090 00:00:00 GMT"));
        assertTrue(text().startsWith("HTTP/1.1 304 "), text());
    }

    @Test
    void htmlSendOnASocketFlushesTheResponse() throws Exception {
        Files.writeString(root.resolve("s.txt"), "sock");
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                var client = new java.net.Socket(server.getInetAddress(), server.getLocalPort());
                var accepted = server.accept()) {
            new HTML(root.toString()).send(accepted, "/s.txt");
            accepted.shutdownOutput();
            var got = new String(client.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(got.startsWith("HTTP/1.1 200 OK\r\n") && got.endsWith("sock"), got);
        }
    }
}
