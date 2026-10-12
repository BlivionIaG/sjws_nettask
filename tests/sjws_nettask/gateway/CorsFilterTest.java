package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Body;
import sjws_nettask.core.Request;

class CorsFilterTest {

    @Test
    void preflightAndActual() {
        var filter = CorsFilter.create(GatewayTestSupport.spec("cors", Map.of(
                "origins", "https://app.example, https://other.example",
                "methods", "get, post",
                "headers", "X-Trace",
                "expose", "x-out",
                "credentials", "true",
                "max-age", "60")));
        var calls = new AtomicInteger();
        var preflight = GatewayTestSupport.request("OPTIONS", "/api", Map.of(
                "origin", List.of("https://app.example"),
                "access-control-request-method", List.of("POST"),
                "access-control-request-headers", List.of("x-trace, X-Trace")));
        var allowed = filter.filter(preflight, request -> {
            calls.incrementAndGet();
            return GatewayTestSupport.ok(request);
        });
        assertEquals(0, calls.get());
        assertEquals(204, allowed.status());
        assertEquals(0, allowed.body().length());
        assertEquals(Body.Empty.INSTANCE, allowed.body());
        assertEquals("https://app.example", allowed.headers().get("access-control-allow-origin"));
        assertEquals("GET, POST", allowed.headers().get("access-control-allow-methods"));
        assertEquals("x-trace", allowed.headers().get("access-control-allow-headers"));
        assertEquals("true", allowed.headers().get("access-control-allow-credentials"));
        assertEquals("60", allowed.headers().get("access-control-max-age"));
        assertEquals("Origin", allowed.headers().get("vary"));

        var denied = filter.filter(GatewayTestSupport.request("OPTIONS", "/", Map.of(
                "origin", List.of("https://evil.example"),
                "access-control-request-method", List.of("GET"))), GatewayTestSupport::ok);
        assertEquals(403, denied.status());

        var badHeader = filter.filter(GatewayTestSupport.request("OPTIONS", "/", Map.of(
                "origin", List.of("https://app.example"),
                "access-control-request-method", List.of("PUT"),
                "access-control-request-headers", List.of("x-other"))), GatewayTestSupport::ok);
        assertEquals(403, badHeader.status());

        var actual = filter.filter(GatewayTestSupport.request("GET", "/", Map.of(
                "origin", List.of("https://other.example"))), request -> {
                    var response = GatewayTestSupport.ok(request);
                    response.headers().set("vary", "Accept");
                    return response;
                });
        assertEquals(200, actual.status());
        assertEquals("https://other.example", actual.headers().get("access-control-allow-origin"));
        assertEquals("x-out", actual.headers().get("access-control-expose-headers"));
        assertEquals("Accept, Origin", actual.headers().get("vary"));
        var already = filter.filter(GatewayTestSupport.request("GET", "/", Map.of(
                "origin", List.of("https://app.example"))), request -> {
                    var response = GatewayTestSupport.ok(request);
                    response.headers().set("vary", "Origin");
                    return response;
                });
        assertEquals("Origin", already.headers().get("vary"));

        var noOrigin = filter.filter(new Request("GET", "/"), GatewayTestSupport::ok);
        assertNull(noOrigin.headers().get("access-control-allow-origin"));
        var options = filter.filter(new Request("OPTIONS", "/"), GatewayTestSupport::ok);
        assertEquals(200, options.status());
    }

    @Test
    void starAndConfigErrors() {
        var filter = CorsFilter.create(GatewayTestSupport.spec("cors", Map.of("origins", "*")));
        var response = filter.filter(GatewayTestSupport.request("GET", "/", Map.of(
                "origin", List.of("https://anywhere.example"))), GatewayTestSupport::ok);
        assertEquals("*", response.headers().get("access-control-allow-origin"));
        assertNull(response.headers().get("vary"));
        var preflight = filter.filter(GatewayTestSupport.request("OPTIONS", "/", Map.of(
                "origin", List.of("https://anywhere.example"),
                "access-control-request-method", List.of("GET"))), GatewayTestSupport::ok);
        assertEquals(204, preflight.status());
        assertNull(preflight.headers().get("access-control-allow-headers"));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors",
                        Map.of("origins", "*", "credentials", "true"))));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors", Map.of("origins", "https://a.example *"))));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors", Map.of("origins", "a, *"))));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors",
                        Map.of("origins", "https://a.example", "credentials", "maybe"))));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors",
                        Map.of("origins", "https://a.example", "max-age", "-1"))));
        assertThrows(IllegalArgumentException.class,
                () -> CorsFilter.create(GatewayTestSupport.spec("cors",
                        Map.of("origins", "https://a.example", "headers", "a,,b"))));
        assertEquals(false, CorsFilter.create(GatewayTestSupport.spec("cors",
                Map.of("origins", "https://a.example", "credentials", "false"))) == null);
    }
}
