package sjws_nettask.HTTP;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests of the strict request-head parser. */
class Http1ParserTest {

    private static Http1Parser.Head parse(String head) throws Exception {
        return Http1Parser.read(new ByteArrayInputStream(head.getBytes(StandardCharsets.ISO_8859_1)), 8192, 100);
    }

    private static int status(String head) {
        var e = assertThrows(ParseError.class, () -> parse(head));
        return e.status();
    }

    @Test
    void parsesSimpleGet() throws Exception {
        var r = parse("GET /a.txt?x=1 HTTP/1.1\r\nHost: h\r\nAccept: */*\r\n\r\n");
        assertEquals("GET", r.method());
        assertEquals("/a.txt?x=1", r.target());
        assertEquals("HTTP/1.1", r.version());
        assertEquals(0, r.contentLength());
        assertTrue(r.keepAlive());
        assertEquals("h", r.header("HOST"));
    }

    @Test
    void bareLfLineEndingsAreTolerated() throws Exception {
        assertEquals("/", parse("GET / HTTP/1.1\nHost: h\n\n").target());
    }

    @Test
    void cleanEofBeforeRequestReturnsNull() throws Exception {
        assertNull(parse(""));
    }

    @Test
    void eofInsideHeadIsEof() {
        assertThrows(EOFException.class, () -> parse("GET / HTTP/1.1\r\nHost: h\r\n"));
    }

    @Test
    void fewLeadingEmptyLinesAreIgnored() throws Exception {
        assertEquals("/", parse("\r\n\r\nGET / HTTP/1.1\r\nHost: h\r\n\r\n").target());
    }

    @Test
    void manyLeadingEmptyLinesAre400() {
        assertEquals(400, status("\r\n".repeat(Http1Parser.MAX_LEADING_EMPTY_LINES + 1) + "GET / HTTP/1.1\r\nHost: h\r\n\r\n"));
    }

    // --- request smuggling vectors: all 400 ---------------------------------

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 30\r\n\r\n",   // conflicting duplicates
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5, 30\r\n\r\n",                      // conflicting list
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length : 5\r\n\r\n",                         // space before colon
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length\t: 5\r\n\r\n",                        // tab before colon
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: +5\r\n\r\n",                         // sign
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: -1\r\n\r\n",                         // negative
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 0x10\r\n\r\n",                       // hex
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 1 0\r\n\r\n",                        // inner space
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 9999999999999999999\r\n\r\n",        // overflow
        "POST / HTTP/1.1\r\nHost: h\r\nContent-Length: \r\n\r\n",                           // empty
        "GET / HTTP/1.1\r\nHost: h\r\nX-A: a\rContent-Length: 5\r\n\r\n",                   // bare CR in header
        "GET / HTTP/1.1\r\nHost: h\r\nX-A: a\r\n Content-Length: 5\r\n\r\n",                // obs-fold (SP)
        "GET / HTTP/1.1\r\nHost: h\r\nX-A: a\r\n\tContent-Length: 5\r\n\r\n",               // obs-fold (HTAB)
        "GET / HTTP/1.1\r\nHost: h\r\n Host: evil\r\n\r\n",                                 // fold right after Host
        "GET / HTTP/1.1\r\nHost: h\r\n: empty-name\r\n\r\n",                                // empty name
        "GET / HTTP/1.1\r\nHost: h\r\nBad[Name]: x\r\n\r\n",                                // non-token name
        "GET / HTTP/1.1\r\nHost: h\r\nNoColon\r\n\r\n",                                     // no colon
        "GET / HTTP/1.1\r\nHost: h\r\nX-A: a\u0000b\r\n\r\n",                               // NUL in value
        "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",                                     // duplicate Host
        "GET /\u0001 HTTP/1.1\r\nHost: h\r\n\r\n",                                          // CTL in target
        "GET  / HTTP/1.1\r\nHost: h\r\n\r\n",                                               // double space
        "GET / HTTP/1.1 \r\nHost: h\r\n\r\n",                                               // trailing space
        "GET /\rHTTP/1.1\r\nHost: h\r\n\r\n"                                                // bare CR in request line
    })
    void smugglingVectorsAre400(String head) {
        assertEquals(400, status(head));
    }

    @Test
    void identicalDuplicateContentLengthIsAccepted() throws Exception {
        assertEquals(5, parse("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\n").contentLength());
        assertEquals(5, parse("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 5, 5\r\n\r\n").contentLength());
    }

    @Test
    void leadingZerosAreDecimal() throws Exception {
        // RFC 9110 allows leading zeros (1*DIGIT); they must not be read as octal or rejected inconsistently.
        assertEquals(10, parse("POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 010\r\n\r\n").contentLength());
    }

    @Test
    void transferEncodingIs501() {
        assertEquals(501, status("POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n"));
        assertEquals(501, status("POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n"));
    }

    @ParameterizedTest
    @CsvSource({
        "'GET / HTTP/2.0', 505",
        "'GET / HTTP/0.9', 505",
        "'GET / HTTP/1.2', 505",
        "'GET / HTTPS/1.1', 400",
        "'GET / HTTP/1', 400",
        "'GET /', 400",
        "'GET', 400",
        "'BREW / HTTP/1.1', 501",
        "'G@T / HTTP/1.1', 400",
        "'GET * HTTP/1.1', 400",
        "'GET example.com:80 HTTP/1.1', 400",
        "'GET http:///x HTTP/1.1', 400"
    })
    void requestLineErrors(String line, int expected) {
        assertEquals(expected, status(line + "\r\nHost: h\r\n\r\n"));
    }

    @Test
    void missingHostOnHttp11Is400ButFineOnHttp10() throws Exception {
        assertEquals(400, status("GET / HTTP/1.1\r\n\r\n"));
        assertFalse(parse("GET / HTTP/1.0\r\n\r\n").keepAlive());
    }

    @ParameterizedTest
    @CsvSource({
        "'http://example.com/hello.txt', '/hello.txt'",
        "'HTTP://Example.com:8080/a/b?q=1', '/a/b?q=1'",
        "'https://example.com', '/'",
        "'http://example.com?x=1', '/?x=1'"
    })
    void absoluteFormBecomesOriginForm(String target, String expected) throws Exception {
        assertEquals(expected, parse("GET " + target + " HTTP/1.1\r\nHost: h\r\n\r\n").target());
    }

    @Test
    void requestLineTooLongIs414HeaderLineTooLongIs431() {
        assertEquals(414, status("GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertEquals(431, status("GET / HTTP/1.1\r\nHost: h\r\nX-Big: " + "a".repeat(9000) + "\r\n\r\n"));
    }

    @Test
    void tooManyHeadersIs431() {
        var sb = new StringBuilder("GET / HTTP/1.1\r\nHost: h\r\n");
        for (int i = 0; i < 100; i++) {
            sb.append("X-").append(i).append(": v\r\n");
        }
        assertEquals(431, status(sb.append("\r\n").toString()));
    }

    @ParameterizedTest
    @CsvSource({
        "'HTTP/1.1', '', true",
        "'HTTP/1.1', 'Connection: close', false",
        "'HTTP/1.1', 'Connection: Upgrade, Close', false",
        "'HTTP/1.1', 'Connection: notclose', true",
        "'HTTP/1.1', 'Connection: keep-alive', true",
        "'HTTP/1.0', '', false",
        "'HTTP/1.0', 'Connection: Keep-Alive', true",
        "'HTTP/1.0', 'Connection: keep-alive-ish', false",
        "'HTTP/1.0', 'Connection: foo, keep-alive', true"
    })
    void connectionHeaderIsTokenized(String version, String header, boolean keepAlive) throws Exception {
        var head = "GET / " + version + "\r\nHost: h\r\n" + (header.isEmpty() ? "" : header + "\r\n") + "\r\n";
        assertEquals(keepAlive, parse(head).keepAlive());
    }

    @Test
    void expectHeader() throws Exception {
        assertTrue(parse("GET / HTTP/1.1\r\nHost: h\r\nExpect: 100-Continue\r\nContent-Length: 3\r\n\r\n").expectContinue());
        assertFalse(parse("GET / HTTP/1.0\r\nExpect: 100-continue\r\n\r\n").expectContinue());
        assertEquals(417, status("GET / HTTP/1.1\r\nHost: h\r\nExpect: teapot\r\n\r\n"));
    }
}
