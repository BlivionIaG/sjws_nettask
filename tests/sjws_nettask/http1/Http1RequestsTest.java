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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

class Http1RequestsTest {

    @Test
    void mapsMethodTargetAndNegotiationHeaders() {
        var headers = Map.of(
                "if-none-match", List.of("\"x\"", "\"y\""), "if-modified-since", List.of("Mon, 12 Oct 2026 00:00:00 GMT"),
                "if-range", List.of("\"x\""), "range", List.of("bytes=0-1"), "accept-encoding", List.of("gzip"),
                "host", List.of("example"), "user-agent", List.of("ignored"));
        var head = new Http1Parser.Head("HEAD", "/a%20b?q=1", "HTTP/1.1", headers, 0, true, false, false);
        var request = Http1Requests.toCore(head);
        assertEquals(new Request("HEAD", "/a%20b?q=1", RequestHeaders.of(headers)), request);
        assertSame(headers, request.headers().fields());
        assertEquals(List.of("ignored"), request.headers().values("user-agent"));
        assertNull(request.peer());
        assertFalse(request.secure());
        var peer = new InetSocketAddress("127.0.0.1", 9);
        assertSame(peer, Http1Requests.toCore(head, peer).peer());
    }

    @Test
    void retainedRequestKeepsTheHeaderMapAfterParserReset() throws Exception {
        var parser = new Http1Parser.HeadParser(8192, 100);
        var first = "GET /a HTTP/1.1\r\nHost: example\r\nX-Trace: one\r\n\r\n"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(first.length, parser.feed(first, 0, first.length));
        var request = Http1Requests.toCore(parser.head(), new InetSocketAddress("127.0.0.1", 9));
        parser.reset();
        assertEquals(List.of("one"), request.headers().values("x-trace"));
        assertEquals("example", request.headers().host());
        assertThrows(UnsupportedOperationException.class, () -> request.headers().fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.headers().values("x-trace").add("z"));
        var second = "GET /b HTTP/1.1\r\nHost: other\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(second.length, parser.feed(second, 0, second.length));
        assertEquals(List.of("one"), request.headers().values("x-trace"));
        assertEquals(List.of("other"), Http1Requests.toCore(parser.head()).headers().values("host"));
    }

    @Test
    void absentHeadersAreNull() {
        var head = new Http1Parser.Head("GET", "/", "HTTP/1.0", Map.of(), 0, false, false);
        assertEquals(new Request("GET", "/", new RequestHeaders(null, null, null, null, null, null)),
                Http1Requests.toCore(head));
    }
}
