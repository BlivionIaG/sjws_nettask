package sjws_nettask.http1;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HeaderTest {

    @ParameterizedTest
    @CsvSource({
        "/index.html, text/html",
        "/style.css, text/css",
        "/app.js, application/javascript",
        "/a/b/c.png, image/png",
        "/photo.jpg, image/jpeg",
        "/photo.jpeg, image/jpeg",
        "/doc.pdf, application/pdf",
        "/noext, application/octet-stream",
        "/trailingdot., application/octet-stream",
        "/archive.unknownext, application/octet-stream"
    })
    void mimeByExtension(String path, String expected) {
        assertEquals(expected, Header.getMime(path));
    }

    @Test
    void BUG_mimeLookupIsCaseSensitive() {
        assertEquals("text/html", Header.getMime("/INDEX.HTML"));
    }

    @Test
    void contentLengthAndTypeArePresent() {
        String h = new Header(200, "/index.html", 1234).toString();
        assertTrue(h.contains("Content-Length: 1234"), h);
        assertTrue(h.contains("Content-Type: text/html"), h);
    }

    @Test
    void headEndsWithBlankLine() {
        assertTrue(new Header(200, "/x.txt", 1).toString().endsWith("\r\n\r\n"));
    }

    @Test
    void BUG_statusLineHasProtocolVersion() {
        // httpProtocol is never assigned -> "HTTP/null 200 OK"
        String statusLine = new Header(200, "/index.html", 1).toString().split("\r?\n")[0];
        assertEquals("HTTP/1.1 200 OK", statusLine);
    }

    @Test
    void BUG_reasonPhraseMatchesStatusCode() {
        String statusLine = new Header(404, "/missing", 0).toString().split("\r?\n")[0];
        assertTrue(statusLine.endsWith(" 404 Not Found"), statusLine);
    }

    @Test
    void BUG_headerLinesUseCrlf() {
        // RFC 9112: header fields are terminated by CRLF; Header uses bare "\n".
        String h = new Header(200, "/index.html", 1).toString();
        String withoutCrlf = h.replace("\r\n", "");
        assertFalse(withoutCrlf.contains("\n"), "bare LF found in: " + h.replace("\r", "\\r").replace("\n", "\\n"));
    }

    @Test
    void BUG_noNullHeaderValues() {
        // acceptRanges is never assigned -> "Accept-Ranges: null"
        assertFalse(new Header(200, "/index.html", 1).toString().contains("null"));
    }

    @Test
    void BUG_lastModifiedIsAPlainDate() {
        // lastModified = currentDate which already contains "Date: " -> "Last-Modified: Date: ..."
        String h = new Header(200, "/index.html", 1).toString();
        assertFalse(h.contains("Last-Modified: Date:"), h);
    }
}
