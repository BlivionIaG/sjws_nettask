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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/** The HTTP/1.1 connection depends only on the {@link Handler} seam, not on static files. */
@org.junit.jupiter.api.extension.ExtendWith(sjws_nettask.core.buf.NoBufferLeaks.class)
class Http1ConnectionHandlerSeamTest {

    @Test
    void servesThroughAnyHandler() throws Exception {
        var seen = new AtomicReference<Request>();
        Handler handler = request -> {
            seen.set(request);
            return new Response(200, ResponseHeaders.of(), Body.Bytes.of("hi".getBytes(StandardCharsets.US_ASCII)));
        };
        var reaper = new ConnectionReaper(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var server = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
                var client = SocketChannel.open(server.getLocalAddress())) {
            var accepted = server.accept();
            var thread = Thread.ofVirtual().start(new Http1Connection(accepted, handler, ResponseHeaders::of, reaper));
            client.write(ByteBuffer.wrap("GET /x?y HTTP/1.1\r\nHost: h\r\nRange: bytes=0-0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII)));
            var out = new ByteArrayOutputStream();
            var buf = ByteBuffer.allocate(1024);
            while (client.read(buf) >= 0) {
                out.write(buf.array(), 0, buf.position());
                buf.clear();
            }
            thread.join(5000);
            var text = out.toString(StandardCharsets.US_ASCII);
            assertTrue(text.startsWith("HTTP/1.1 200 "), text);
            assertTrue(text.endsWith("\r\n\r\nhi"), text);
            assertEquals("/x?y", seen.get().target());
            assertEquals("h", seen.get().headers().host());
            assertEquals("bytes=0-0", seen.get().headers().range());
        } finally {
            reaper.close();
        }
    }

    @Test
    void closedAndUnixPeersDoNotResolveAnInetAddress() throws Exception {
        var reaper = new ConnectionReaper(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        Handler handler = request -> new Response(200, ResponseHeaders.of(), Body.Bytes.of("ok".getBytes()));
        try (var closed = SocketChannel.open()) {
            closed.close();
            assertNotNull(new Http1Connection(closed, handler, ResponseHeaders::of, reaper));
        }
        var path = java.nio.file.Files.createTempDirectory("h1-unix").resolve("sock");
        try (var server = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
                var client = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            var address = java.net.UnixDomainSocketAddress.of(path);
            server.bind(address);
            client.connect(address);
            try (var accepted = server.accept()) {
                assertNotNull(new Http1Connection(accepted, handler, ResponseHeaders::of, reaper));
            }
        } finally {
            reaper.close();
        }
    }
}
