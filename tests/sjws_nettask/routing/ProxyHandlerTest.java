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
package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;

class ProxyHandlerTest {

    private HttpServer upstream;
    private URI base;
    private final AtomicReference<com.sun.net.httpserver.Headers> seen = new AtomicReference<>();
    private final AtomicReference<String> seenUri = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        this.upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.upstream.createContext("/", ex -> {
            this.seen.set(ex.getRequestHeaders());
            this.seenUri.set(ex.getRequestMethod() + " " + ex.getRequestURI());
            var path = ex.getRequestURI().getPath();
            byte[] body = ("hello " + path).getBytes(StandardCharsets.UTF_8);
            if (path.startsWith("/slow")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (path.startsWith("/big")) {
                body = new byte[100];
            }
            ex.getResponseHeaders().add("X-Up", "1");
            ex.getResponseHeaders().add("Connection", "close");
            ex.getResponseHeaders().add("Keep-Alive", "timeout=5");
            int status = path.startsWith("/missing") ? 404 : 200;
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        this.upstream.start();
        this.base = URI.create("http://127.0.0.1:" + this.upstream.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        this.upstream.stop(0);
    }

    private ProxyHandler proxy(URI uri, Duration timeout, int max) {
        return new ProxyHandler(new ProxyHandler.Settings(uri, Duration.ofSeconds(2), timeout, max, "https"));
    }

    @Test
    void forwardsAndRelays() {
        var p = proxy(URI.create(this.base + "/base/"), Duration.ofSeconds(5), 1 << 20);
        var h = new RequestHeaders("\"e\"", "Mon, 12 Oct 2026 00:00:00 GMT", "\"r\"", "bytes=0-1", "gzip", "site.test:8443");
        var r = p.handle(new Request("GET", "/x?y=1", h));
        assertEquals(200, r.status());
        assertEquals("hello /base/x", StandardCharsets.UTF_8.decode(((Body.Bytes) r.body()).data()).toString());
        assertEquals("1", r.headers().get("x-up"));
        assertNull(r.headers().get("connection"));
        assertNull(r.headers().get("keep-alive"));
        assertNull(r.headers().get("content-length"));
        assertNull(r.headers().get("date"));
        assertEquals("GET /base/x?y=1", this.seenUri.get());
        var s = this.seen.get();
        assertEquals("site.test:8443", s.getFirst("X-Forwarded-Host"));
        assertEquals("https", s.getFirst("X-Forwarded-Proto"));
        assertEquals("proto=https;host=\"site.test:8443\"", s.getFirst("Forwarded"));
        assertEquals("\"e\"", s.getFirst("If-None-Match"));
        assertEquals("bytes=0-1", s.getFirst("Range"));
        assertEquals("gzip", s.getFirst("Accept-Encoding"));
        assertEquals("\"r\"", s.getFirst("If-Range"));
        assertNotNull(s.getFirst("If-Modified-Since"));
    }

    @Test
    void relaysStatusAndHead() {
        var p = new ProxyHandler(new ProxyHandler.Settings(this.base));
        assertEquals(404, p.handle(new Request("GET", "/missing")).status());
        var head = p.handle(new Request("HEAD", "/"));
        assertEquals(200, head.status());
        assertTrue(head.headOnly());
        assertEquals("proto=http", this.seen.get().getFirst("Forwarded"));
        assertNull(this.seen.get().getFirst("X-Forwarded-Host"));
        assertEquals(this.base, p.settings().upstream());
    }

    @Test
    void quotesHostInForwarded() {
        var p = proxy(this.base, Duration.ofSeconds(5), 1024);
        p.handle(new Request("GET", "/", new RequestHeaders(null, null, null, null, null, "a\"b\\c")));
        assertEquals("proto=https;host=\"a\\\"b\\\\c\"", this.seen.get().getFirst("Forwarded"));
    }

    @Test
    void timeoutIs504() {
        var r = proxy(this.base, Duration.ofMillis(200), 1024).handle(new Request("GET", "/slow"));
        assertEquals(504, r.status());
    }

    @Test
    void refusedIs502() throws Exception {
        int port;
        try (var s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        var r = proxy(URI.create("http://127.0.0.1:" + port), Duration.ofSeconds(2), 1024).handle(new Request("GET", "/"));
        assertEquals(502, r.status());
    }

    @Test
    void oversizeIs502() {
        assertEquals(502, proxy(this.base, Duration.ofSeconds(5), 10).handle(new Request("GET", "/big")).status());
    }

    @Test
    void badRequestsAre400() {
        var p = proxy(this.base, Duration.ofSeconds(5), 1024);
        assertEquals(400, p.handle(new Request("OPTIONS", "*")).status());
        assertEquals(400, p.handle(new Request("BAD METHOD", "/")).status());
        assertEquals(400, p.handle(new Request("GET", "/a b")).status());
    }

    @Test
    void interruptedIs502() {
        var p = proxy(this.base, Duration.ofSeconds(5), 1024);
        Thread.currentThread().interrupt();
        try {
            assertEquals(502, p.handle(new Request("GET", "/slow")).status());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void settingsValidation() {
        var d = Duration.ofSeconds(1);
        assertThrows(IllegalArgumentException.class, () -> new ProxyHandler.Settings(URI.create("ftp://x")));
        assertThrows(IllegalArgumentException.class, () -> new ProxyHandler.Settings(URI.create("/rel")));
        assertThrows(IllegalArgumentException.class, () -> new ProxyHandler.Settings(this.base, d, d, -1, "http"));
        assertThrows(NullPointerException.class, () -> new ProxyHandler.Settings(null, d, d, 1, "http"));
        assertThrows(NullPointerException.class, () -> new ProxyHandler.Settings(this.base, null, d, 1, "http"));
        assertThrows(NullPointerException.class, () -> new ProxyHandler.Settings(this.base, d, null, 1, "http"));
        assertThrows(NullPointerException.class, () -> new ProxyHandler.Settings(this.base, d, d, 1, null));
        assertNotNull(new ProxyHandler(new ProxyHandler.Settings(URI.create("https://h"))).settings());
    }
}
