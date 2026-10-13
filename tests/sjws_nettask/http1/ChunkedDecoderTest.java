package sjws_nettask.http1;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RFC 9112 §7.1 chunked request bodies: framing, limits, and every split of the input. */
class ChunkedDecoderTest {

    private static final String NEXT = "GET / HTTP/1.1\r\n";

    /**
     * @return bytes consumed when fed in pieces of {@code step} (0 = whole). Odd steps feed a direct
     *         {@link java.nio.ByteBuffer} (the pooled HTTP/1 input path), a negative step the whole input from one
     */
    private static int consume(ChunkedDecoder d, String input, int step) throws ParseError {
        var bytes = input.getBytes(StandardCharsets.ISO_8859_1);
        boolean direct = step < 0 || (step & 1) == 1;
        var buffer = java.nio.ByteBuffer.allocateDirect(bytes.length + 3).position(3).put(bytes);
        step = Math.max(0, step);
        int pos = 0;
        while (pos < bytes.length && !d.isComplete() && !d.overLimit()) {
            int n = step == 0 ? bytes.length - pos : Math.min(step, bytes.length - pos);
            int used = direct ? d.feed(buffer, 3 + pos, n) : d.feed(bytes, pos, n);
            pos += used;
            if (used < n) {
                break;
            }
        }
        return pos;
    }

    private static ChunkedDecoder decoder() {
        return new ChunkedDecoder(64, 4, 1024);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "5\r\nhello\r\n0\r\n\r\n",
        "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
        "5;ext=1;q=\"x\"\r\nhello\r\n0\r\n\r\n",
        "5 ;ext\r\nhello\r\n0\r\n\r\n",
        "A\r\n0123456789\r\n0\r\n\r\n",
        "a\r\n0123456789\r\n00\r\n\r\n",
        "000005\r\nhello\r\n0\r\n\r\n",
        "5\nhello\r\n0\n\n",
        "5\r\nhello\r\n0\r\nX-Trailer: 1\r\nY: 2\r\n\r\n",
        "0\r\n\r\n",
        "5\t;e\r\nhello\r\n0\r\n\r\n",
        "5;e=\"a\tb\"\nhello\r\n0\r\n\r\n",
        "0\r\nX:\ta\nY: b\r\n\n",
    })
    void validBodiesStopExactlyAtTheEnd(String body) throws ParseError {
        for (int step : new int[] {0, 1, 2, 3, 7}) {
            var d = decoder();
            int used = consume(d, body + NEXT, step);
            assertTrue(d.isComplete(), body + " step " + step);
            assertEquals(body.length(), used, "the next request is left untouched");
        }
    }

    @Test
    void countsContent() throws ParseError {
        var d = decoder();
        consume(d, "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n", 0);
        assertEquals(11, d.contentLength());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\r\nhello\r\n0\r\n\r\n",         // missing size
        "x\r\n",                          // not hex
        "-1\r\n",                         // sign
        "0x5\r\nhello\r\n0\r\n\r\n",       // prefix
        "5\r\nhelloXX0\r\n\r\n",           // no CRLF after data
        "5\r\nhello\n0\r\n\r\n",           // bare LF after data
        "5\rhello",                       // bare CR
        "5;a\u0001\r\n",                  // control char in extension
        "10000000000000000\r\n",          // 17 digits
        "FFFFFFFFFFFFFFFF\r\n",           // overflows long
        "0\r\nA: 1\r\nB: 2\r\nC: 3\r\nD: 4\r\nE: 5\r\n\r\n", // too many trailers
        "0\r\nA:\u0000\r\n\r\n",          // control char in trailer
        "0\r\nA:\u007f\r\n\r\n",          // DEL in trailer
        "5;a\u007f\r\n",                  // DEL in extension
        "0\r\nA: 1\rX",                   // bare CR in trailer
    })
    void malformedIs400(String body) {
        for (int step : new int[] {0, 1}) {
            var e = assertThrows(ParseError.class, () -> consume(decoder(), body, step), body);
            assertEquals(400, e.status());
        }
    }

    @Test
    void overlongLineIs400() {
        var e = assertThrows(ParseError.class, () -> consume(decoder(), "5;" + "x".repeat(100) + "\r\n", 0));
        assertEquals(400, e.status());
    }

    @Test
    void declaredSizeOverLimitStopsBeforeTheData() throws ParseError {
        var d = decoder();
        int used = consume(d, "801\r\n" + "x".repeat(100), 0);
        assertTrue(d.overLimit());
        assertFalse(d.isComplete());
        assertEquals(5, used, "nothing past the size line is read");
    }

    @Test
    void directBufferStopsAtTheLimitAndAtTheEnd() throws ParseError {
        var d = decoder();
        assertEquals(5, consume(d, "801\r\n" + "x".repeat(100), -1));
        assertTrue(d.overLimit());
        d.reset();
        assertEquals(5, consume(d, "0\r\n\r\n" + NEXT, -1), "the next request is left untouched");
        assertTrue(d.isComplete());
    }

    @Test
    void manySmallChunksAddUpToTheLimit() throws ParseError {
        var d = decoder();
        var chunk = "100\r\n" + "x".repeat(256) + "\r\n";
        consume(d, chunk.repeat(4) + "0\r\n\r\n", 0); // exactly 1024: allowed
        assertTrue(d.isComplete());
        assertFalse(d.overLimit());
        d.reset();
        consume(d, chunk.repeat(4) + "1\r\nx\r\n0\r\n\r\n", 0);
        assertTrue(d.overLimit());
        assertFalse(d.isComplete());
    }
}
