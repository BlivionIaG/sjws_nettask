package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.ResponseHeaders;

class HeaderRewriteFilterTest {

    @Test
    void rewritesRequestAndResponse() {
        var filter = HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite", Map.of(
                "request-remove", "x-drop",
                "request-set", "x-from: edge\nHost: app.example",
                "response-remove", "x-old",
                "response-set", "x-out: 1\nx-out: 2")));
        var seen = new AtomicReference<Request>();
        RequestBody body = new RequestBody() {
            @Override
            public long length() {
                return 4;
            }

            @Override
            public int read(byte[] into, int off, int len) {
                return -1;
            }
        };
        var original = new Request("GET", "/a", RequestHeaders.of(Map.of(
                "x-drop", List.of("gone"),
                "x-keep", List.of("yes"))), new InetSocketAddress("127.0.0.1", 9), false, "HTTP/1.1", body);
        var response = filter.filter(original, request -> {
            seen.set(request);
            var out = GatewayTestSupport.ok(request);
            out.headers().set("x-old", "bye");
            out.headers().set("vary", "Accept");
            return out;
        });
        assertEquals(List.of("yes"), seen.get().headers().values("x-keep"));
        assertEquals(List.of(), seen.get().headers().values("x-drop"));
        assertEquals(List.of("edge"), seen.get().headers().values("x-from"));
        assertEquals("app.example", seen.get().headers().host());
        assertEquals(original.peer(), seen.get().peer());
        assertSame(body, seen.get().body());
        assertEquals(List.of("gone"), original.headers().values("x-drop"));
        assertNull(response.headers().get("x-old"));
        assertEquals("2", response.headers().get("x-out"));
    }

    @Test
    void responseOnlyDoesNotCopyTheRequest() {
        var filter = HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite",
                Map.of("response-set", "x-out: 1", "response-remove", "x-content-type-options")));
        var original = new Request("GET", "/");
        var seen = new AtomicReference<Request>();
        var response = filter.filter(original, request -> {
            seen.set(request);
            var out = GatewayTestSupport.ok(request);
            out.headers().set("x-content-type-options", "nosniff");
            return out;
        });
        assertSame(original, seen.get());
        assertEquals("1", response.headers().get("x-out"));
        assertNull(response.headers().get("x-content-type-options"));
    }

    @Test
    void rejectsBadRules() {
        assertThrows(IllegalArgumentException.class,
                () -> HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite",
                        Map.of("request-set", "bad name: v"))));
        assertThrows(IllegalArgumentException.class,
                () -> HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite",
                        Map.of("response-set", "x-a: bad\rvalue"))));
        assertThrows(IllegalArgumentException.class,
                () -> HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite",
                        Map.of("response-set", "content-length: 1"))));
        assertThrows(IllegalArgumentException.class,
                () -> HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite",
                        Map.of("response-remove", "server"))));
        assertThrows(IllegalArgumentException.class,
                () -> HeaderRewriteFilter.create(GatewayTestSupport.spec("header-rewrite",
                        Map.of("request-set", "novalue"))));
        var headers = ResponseHeaders.of();
        headers.set("x-a", "ok");
        assertEquals("ok", headers.get("x-a"));
    }
}
