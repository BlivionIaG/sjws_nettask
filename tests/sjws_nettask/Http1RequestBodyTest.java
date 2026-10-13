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
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/** {@link Http1Connection}'s streamed request body and {@link Body.Stream} framing. */
class Http1RequestBodyTest {

    private static String exchange(Handler handler, String raw, boolean halfClose) throws Exception {
        var reaper = new ConnectionReaper(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                var client = SocketChannel.open(server.getLocalAddress())) {
            var accepted = server.accept();
            var thread = Thread.ofVirtual().start(new Http1Connection(accepted, handler, ResponseHeaders::of, reaper));
            client.write(ByteBuffer.wrap(raw.getBytes(StandardCharsets.ISO_8859_1)));
            if (halfClose) {
                client.shutdownOutput();
            }
            var out = new ByteArrayOutputStream();
            var buf = ByteBuffer.allocate(4096);
            while (client.read(buf) >= 0) {
                out.write(buf.array(), 0, buf.position());
                buf.clear();
            }
            thread.join(5000);
            return out.toString(StandardCharsets.ISO_8859_1);
        } finally {
            reaper.close();
        }
    }

    private static Handler echo() {
        return request -> {
            var body = request.body();
            var all = new ByteArrayOutputStream();
            var b = new byte[3];
            try {
                assertEquals(0, body.read(b, 0, 0));
                int n;
                while ((n = body.read(b, 0, b.length)) >= 0) {
                    all.write(b, 0, n);
                }
                assertEquals(-1, body.read(b, 0, 1));
            } catch (IOException e) {
                return new Response(502, ResponseHeaders.of(), Body.Bytes.of(e.getMessage().getBytes()));
            }
            return new Response(200, ResponseHeaders.of(), Body.Bytes.of(("got:" + all).getBytes()));
        };
    }

    @Test
    void bodyIsStreamedWithContinueAndTheConnectionStaysUsable() throws Exception {
        var text = exchange(echo(), "POST /a HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 7\r\n\r\nabcdefg"
                + "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\nConnection: close\r\n\r\nxy", false);
        assertTrue(text.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 "), text);
        assertTrue(text.contains("got:abcdefg"), text);
        assertTrue(text.endsWith("got:xy"), text);
    }

    @Test
    void truncatedBodyFailsTheReadAndCloses() throws Exception {
        var text = exchange(echo(), "POST /a HTTP/1.1\r\nHost: h\r\nContent-Length: 50\r\n\r\nabc", true);
        assertTrue(text.startsWith("HTTP/1.1 502 "), text);
        assertTrue(text.contains("after 3 of 50 body bytes"), text);
    }

    @Test
    void anUnreadBodyClosesTheConnection() throws Exception {
        var seen = new AtomicReference<Long>();
        Handler ignoring = request -> {
            seen.set(request.body().length());
            return new Response(200, ResponseHeaders.of(), Body.Bytes.of("ok".getBytes()));
        };
        var text = exchange(ignoring, "PUT /a HTTP/1.1\r\nHost: h\r\nContent-Length: 4\r\n\r\nabcd", false);
        assertEquals(4L, seen.get());
        assertTrue(text.contains("Connection: close"), text);
    }

    @Test
    void unknownLengthStreamToHttp10IsCloseDelimited() throws Exception {
        Handler stream = request -> new Response(200, ResponseHeaders.of(),
                new Body.Stream(-1, new sjws_nettask.core.TestSource(false, "ab".getBytes(), "cd".getBytes())));
        var text = exchange(stream, "GET / HTTP/1.0\r\nHost: h\r\nConnection: keep-alive\r\n\r\n", false);
        assertFalse(text.contains("Transfer-Encoding"), text);
        assertTrue(text.endsWith("\r\n\r\nabcd"), text);
        var chunked = exchange(stream, "GET / HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n", false);
        assertTrue(chunked.endsWith("\r\n\r\nabcd"), chunked);
        var known = exchange(request -> new Response(200, ResponseHeaders.of(),
                new Body.Stream(2, new sjws_nettask.core.TestSource(false, "ab".getBytes()))),
                "GET / HTTP/1.0\r\nHost: h\r\n\r\n", false);
        assertTrue(known.endsWith("Content-Length: 2\r\n\r\nab") || known.endsWith("\r\n\r\nab"), known);
    }
}
