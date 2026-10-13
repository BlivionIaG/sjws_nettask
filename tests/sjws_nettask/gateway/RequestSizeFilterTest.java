package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;

class RequestSizeFilterTest {

    @Test
    void limits() {
        var filter = RequestSizeFilter.create(GatewayTestSupport.spec("request-size",
                Map.of("max-bytes", "4", "max-target", "8", "reject-chunked", "true")));
        var calls = new AtomicInteger();
        RequestFilter.Chain next = request -> {
            calls.incrementAndGet();
            return GatewayTestSupport.ok(request);
        };

        assertEquals(414, filter.filter(new Request("GET", "/too-long"), next).status());
        assertEquals(411, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("transfer-encoding", List.of("gzip, chunked"))), next).status());
        assertEquals(413, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("content-length", List.of("5"))), next).status());
        assertEquals(400, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("content-length", List.of("nope"))), next).status());
        assertEquals(400, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("content-length", List.of("1", "2"))), next).status());
        assertEquals(200, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("content-length", List.of("4"))), next).status());
        assertEquals(200, filter.filter(new Request("GET", "/"), next).status());
        assertEquals(2, calls.get());
    }

    @Test
    void chunkedCodingAndConfig() {
        var filter = RequestSizeFilter.create(GatewayTestSupport.spec("request-size",
                Map.of("max-bytes", "0", "reject-chunked", "false")));
        assertEquals(200, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("transfer-encoding", List.of("chunked ;ext=1"))), GatewayTestSupport::ok).status());
        assertEquals(413, filter.filter(GatewayTestSupport.request("POST", "/",
                Map.of("content-length", List.of("1"))), GatewayTestSupport::ok).status());
        assertThrows(IllegalArgumentException.class,
                () -> RequestSizeFilter.create(GatewayTestSupport.spec("request-size", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> RequestSizeFilter.create(GatewayTestSupport.spec("request-size", Map.of("max-bytes", "-1"))));
        var chunked = RequestSizeFilter.create(GatewayTestSupport.spec("request-size",
                Map.of("max-bytes", "10", "reject-chunked", "true")));
        assertEquals(411, chunked.filter(GatewayTestSupport.request("POST", "/",
                Map.of("transfer-encoding", List.of(" Chunked ;ext=1 "))), GatewayTestSupport::ok).status());
        assertEquals(200, chunked.filter(GatewayTestSupport.request("POST", "/",
                Map.of("transfer-encoding", List.of("gzip"))), GatewayTestSupport::ok).status());
        assertThrows(IllegalArgumentException.class,
                () -> RequestSizeFilter.create(GatewayTestSupport.spec("request-size", Map.of("max-target", "no"))));
        assertThrows(IllegalArgumentException.class,
                () -> RequestSizeFilter.create(GatewayTestSupport.spec("request-size",
                        Map.of("max-target", "999999999999"))));
    }
}
