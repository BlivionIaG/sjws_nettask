/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.http1;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.TestSource;

/** {@link Http1Writer} with {@link Body.Stream}: framing by length, chunked, close-delimited, and failures. */
class Http1WriterStreamTest {

    private static String write(Body.Stream body, boolean keepAlive, boolean head) throws IOException {
        var out = new ByteArrayOutputStream();
        Http1Writer.write(out, new Response(200, ResponseHeaders.of().add("x-a", "1"), body, head), keepAlive,
                ResponseHeaders.of().add("x-extra", "e"));
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void knownLength() throws IOException {
        var src = new TestSource(false, b("hel"), b("lo"));
        var text = write(new Body.Stream(5, src), true, false);
        assertTrue(text.contains("Content-Length: 5\r\n"), text);
        assertTrue(text.endsWith("\r\n\r\nhello"), text);
        assertEquals(1, src.closes);
    }

    @Test
    void unknownLengthIsChunkedOnKeepAliveAndDirectBuffersAreCopied() throws IOException {
        var src = new TestSource(true, b("ab"), b("cdefghijklmnopq"));
        var text = write(new Body.Stream(-1, src), true, false);
        assertTrue(text.contains("Transfer-Encoding: chunked\r\n"), text);
        assertTrue(text.contains("X-Extra: e\r\n"), text);
        assertFalse(text.contains("Content-Length"), text);
        assertTrue(text.endsWith("\r\n\r\n2\r\nab\r\nf\r\ncdefghijklmnopq\r\n0\r\n\r\n"), text);
    }

    @Test
    void unknownLengthWithoutKeepAliveIsDelimitedByClose() throws IOException {
        var text = write(new Body.Stream(-1, new TestSource(false, b("xyz"))), false, false);
        assertFalse(text.contains("Transfer-Encoding"), text);
        assertTrue(text.contains("Connection: close\r\n"), text);
        assertTrue(text.endsWith("\r\n\r\nxyz"), text);
    }

    @Test
    void headSendsTheLengthButNoBody() throws IOException {
        var src = new TestSource(false, b("never"));
        var text = write(new Body.Stream(5, src), true, true);
        assertTrue(text.contains("Content-Length: 5\r\n"), text);
        assertTrue(text.endsWith("\r\n\r\n"), text);
        assertEquals(1, src.closes);
    }

    @Test
    void wrongLengthOrFailureAborts() {
        var shortSrc = new TestSource(false, b("ab"));
        assertThrows(IOException.class, () -> write(new Body.Stream(5, shortSrc), true, false));
        assertEquals(1, shortSrc.closes);
        assertThrows(IOException.class, () -> write(new Body.Stream(1, new TestSource(false, b("ab"))), true, false));
        var failing = new TestSource(false, b("ab"));
        failing.failWith = new IOException("upstream reset");
        assertThrows(IOException.class, () -> write(new Body.Stream(-1, failing), true, false));
        assertEquals(1, failing.closes);
        assertThrows(IllegalArgumentException.class, () -> new Body.Stream(-2, failing));
        assertThrows(NullPointerException.class, () -> new Body.Stream(1, null));
    }

    /** A {@link Http1Writer.BufferSink} receives the buffers themselves (no copy). */
    @Test
    void bufferSinkGetsTheBuffer() throws IOException {
        class Sink extends ByteArrayOutputStream implements Http1Writer.BufferSink {
            int buffers;

            @Override
            public void write(ByteBuffer data) {
                this.buffers++;
                var c = new byte[data.remaining()];
                data.get(c);
                write(c, 0, c.length);
            }
        }
        var sink = new Sink();
        Http1Writer.write(sink, new Response(200, ResponseHeaders.of(), new Body.Stream(4, new TestSource(true, b("ab"), b("cd")))),
                true);
        assertEquals(2, sink.buffers);
        assertTrue(sink.toString(StandardCharsets.ISO_8859_1).endsWith("abcd"));
    }
}
