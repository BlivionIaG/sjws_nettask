package sjws_nettask.http1;

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

    /**
     * Parses with the stream API and checks that the byte-level {@link Http1Parser.HeadParser}
     * (used by the channel transport) agrees exactly: same head or same error status, same bytes
     * consumed, whatever the fragmentation (whole, every 2-way split, fixed chunks, reused parser).
     * Every case of this class therefore runs against both APIs.
     */
    private static Http1Parser.Head parse(String head) throws Exception {
        var bytes = head.getBytes(StandardCharsets.ISO_8859_1);
        var in = new ByteArrayInputStream(bytes);
        var stream = outcome(() -> Http1Parser.read(in, 8192, 100), () -> bytes.length - in.available());
        var reused = new Http1Parser.HeadParser(8192, 100);
        reused.feed(PRIMER, 0, PRIMER.length);
        assertTrue(reused.isComplete());
        for (int chunk : new int[] {1, 3, 7, 64, 4096, Integer.MAX_VALUE}) {
            assertEquals(stream, fed(bytes, new int[] {chunk}, null), "chunk " + chunk);
        }
        reused.reset();
        assertEquals(stream, fed(bytes, new int[] {5}, reused), "reused parser");
        if (bytes.length <= 512) {
            for (int split = 1; split < bytes.length; split++) {
                assertEquals(stream, fed(bytes, new int[] {split, Integer.MAX_VALUE}, null), "split at " + split);
            }
        }
        if (stream.error() != null) {
            throw stream.error();
        }
        return stream.head();
    }

    private static final byte[] PRIMER = "GET /prime HTTP/1.1\r\nHost: p\r\nX-Long: ".concat("y".repeat(3000))
            .concat("\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

    /** Head or failure (error class + status), plus the bytes consumed. */
    private record Outcome(Http1Parser.Head head, Exception error, int consumed) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Outcome other && java.util.Objects.equals(head, other.head)
                    && consumed == other.consumed && describe(error).equals(describe(other.error));
        }

        @Override
        public int hashCode() {
            return 0;
        }

        private static String describe(Exception e) {
            return e == null ? "-" : e instanceof ParseError p ? "ParseError " + p.status() : e.getClass().getName();
        }
    }

    private interface Call {
        Http1Parser.Head run() throws Exception;
    }

    private static Outcome outcome(Call call, java.util.function.IntSupplier consumed) {
        try {
            var h = call.run();
            return new Outcome(h, null, h == null ? -1 : consumed.getAsInt());
        } catch (ParseError | EOFException e) {
            return new Outcome(null, e, -1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Feeds {@code bytes} in chunks of the given sizes (the last size repeats), then end of input. */
    private static Outcome fed(byte[] bytes, int[] chunks, Http1Parser.HeadParser parser) {
        var p = parser != null ? parser : new Http1Parser.HeadParser(8192, 100);
        int[] pos = {0};
        return outcome(() -> {
            int k = 0;
            while (pos[0] < bytes.length) {
                int n = (int) Math.min((long) chunks[Math.min(k++, chunks.length - 1)], bytes.length - pos[0]);
                int used = p.feed(bytes, pos[0], n);
                assertTrue(used >= 0 && used <= n);
                pos[0] += used;
                if (p.isComplete()) {
                    return p.head();
                }
                assertEquals(n, used, "an incomplete head consumes everything it is fed");
            }
            return p.endOfInput();
        }, () -> pos[0]);
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

    @ParameterizedTest
    @ValueSource(strings = {"chunked", "Chunked", "CHUNKED", " chunked "})
    void chunkedTransferEncodingIsAccepted(String value) throws Exception {
        var r = parse("POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: " + value + "\r\n\r\n");
        assertTrue(r.chunked());
        assertTrue(r.hasBody());
        assertEquals(0, r.contentLength());
    }

    @Test
    void noBodyWithoutLengthOrChunked() throws Exception {
        var r = parse("GET / HTTP/1.1\r\nHost: h\r\n\r\n");
        assertFalse(r.chunked());
        assertFalse(r.hasBody());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // RFC 9112 §6.1/§6.3: TE + CL is a smuggling vector; TE in 1.0 is faulty framing
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n'|400",
        "'POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n'|400",
        // final coding must be a single chunked
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: gzip\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked, gzip\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked, chunked\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked,\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: \r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: xchunked\r\n\r\n'|400",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked;q=1\r\n\r\n'|400",
        // another coding before chunked: understood framing, unimplemented coding
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: gzip, chunked\r\n\r\n'|501",
        "'POST / HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n'|501",
    })
    void transferEncodingRules(String raw, int expected) {
        assertEquals(expected, status(raw));
    }

    @Test
    void asteriskFormOnlyForOptions() throws Exception {
        assertEquals("*", parse("OPTIONS * HTTP/1.1\r\nHost: h\r\n\r\n").target());
        assertEquals(400, status("HEAD * HTTP/1.1\r\nHost: h\r\n\r\n"));
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

    @Test
    void byteParserStopsAtTheEndOfTheHeadAndLeavesPipelinedBytes() throws Exception {
        var first = "GET /a HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\n";
        var wire = (first + "abcGET /b HTTP/1.1\r\nHost: h\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var p = new Http1Parser.HeadParser(8192, 100);
        int used = p.feed(wire, 0, wire.length);
        assertEquals(first.length(), used);
        assertEquals("/a", p.head().target());
        assertEquals(3, p.head().contentLength());
        assertThrows(IllegalStateException.class, () -> p.feed(wire, used, 1));
        p.reset();
        assertThrows(IllegalStateException.class, p::head);
        int second = used + 3;
        assertEquals(wire.length - second, p.feed(wire, second, wire.length - second));
        assertEquals("/b", p.head().target());
        assertSame(p.head(), p.endOfInput());
    }

    @Test
    void byteParserEndOfInput() throws Exception {
        var p = new Http1Parser.HeadParser(8192, 100);
        assertNull(p.endOfInput(), "nothing received");
        p.feed("\r\n\n".getBytes(StandardCharsets.ISO_8859_1), 0, 3);
        assertNull(p.endOfInput(), "only empty lines");
        p.feed("G".getBytes(StandardCharsets.ISO_8859_1), 0, 1);
        assertThrows(EOFException.class, p::endOfInput);
        p.reset();
        p.feed("GET / HTTP/1.1\r".getBytes(StandardCharsets.ISO_8859_1), 0, 15);
        assertEquals(400, assertThrows(ParseError.class, p::endOfInput).status());
    }

    @Test
    void byteParserRejectsAnOverlongLineBeforeItEnds() {
        var p = new Http1Parser.HeadParser(16, 100);
        var line = "GET /0123456789ab".getBytes(StandardCharsets.ISO_8859_1); // 17 bytes, no line end yet
        assertEquals(414, assertThrows(ParseError.class, () -> p.feed(line, 0, line.length)).status());
    }

    @Test
    void emptyTargetAndControlCharactersAreRejected() {
        assertEquals(400, status("GET  HTTP/1.1\r\nHost: h\r\n\r\n"));
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: h\r\nX: a\u007fb\r\n\r\n"));
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: h\r\nX: a\u0001b\r\n\r\n"));
    }

    @Test
    void valuesAreTrimmedOfSpacesAndTabsButKeepInnerTabs() throws Exception {
        var r = parse("HEAD / HTTP/1.1\r\nHost: h\r\nX-A: \t a\tb \t\r\nX-B:\r\nx-a: second\r\n\r\n");
        assertTrue(r.headOnly());
        assertEquals("a\tb", r.header("X-A"));
        assertEquals(java.util.List.of("a\tb", "second"), r.headers("x-A"));
        assertEquals("", r.header("x-b"));
        assertNull(r.header("x-missing"));
        assertEquals(java.util.List.of(), r.headers("x-missing"));
        assertFalse(parse("GET / HTTP/1.1\r\nHost: h\r\n\r\n").headOnly());
    }
}
