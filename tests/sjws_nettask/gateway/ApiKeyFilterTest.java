package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;

class ApiKeyFilterTest {

    @Test
    void headerAndQuery() {
        var filter = ApiKeyFilter.create(GatewayTestSupport.spec("api-key",
                Map.of("keys", "alpha, beta", "query", "k")));
        var missing = GatewayTestSupport.call(filter, new Request("GET", "/"));
        assertEquals(401, missing.status());
        assertEquals("ApiKey", missing.headers().get("www-authenticate"));

        var wrong = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("x-api-key", List.of("nope"))));
        assertEquals(401, wrong.status());

        var second = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("x-api-key", List.of("beta"))));
        assertEquals(200, second.status());
        assertEquals("ok", GatewayTestSupport.text(second));

        var fromQuery = GatewayTestSupport.call(filter, new Request("GET", "/x?k=alpha"));
        assertEquals(200, fromQuery.status());

        var headerWins = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/x?k=alpha",
                Map.of("x-api-key", List.of("nope"))));
        assertEquals(401, headerWins.status());
    }

    @Test
    void customHeaderAndHead() {
        var filter = ApiKeyFilter.create(GatewayTestSupport.spec("api-key",
                Map.of("keys", "secret", "header", "X-Token")));
        var head = GatewayTestSupport.call(filter, new Request("HEAD", "/"));
        assertEquals(401, head.status());
        assertTrue(head.headOnly());
        var ok = GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("x-token", List.of("secret"))));
        assertEquals(200, ok.status());
    }

    @Test
    void rejectsBadConfigAndDoesNotKeepTheRawKey() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyFilter.create(GatewayTestSupport.spec("api-key", Map.of())));
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyFilter.create(GatewayTestSupport.spec("api-key", Map.of("keys", "a,,b"))));
        assertThrows(IllegalArgumentException.class,
                () -> ApiKeyFilter.create(GatewayTestSupport.spec("api-key", Map.of("keys", " "))));
        var filter = (ApiKeyFilter) ApiKeyFilter.create(GatewayTestSupport.spec("api-key",
                Map.of("keys", "secret", "query", " ")));
        var hash = filter.hashes()[0];
        assertNotEquals("secret", new String(hash, StandardCharsets.UTF_8));
        assertEquals(32, hash.length);
    }

    @Test
    void queryParser() {
        assertNull(ApiKeyFilter.query("/no", "k"));
        assertNull(ApiKeyFilter.query("/x?other=1", "k"));
        assertEquals("a", ApiKeyFilter.query("/x?k=a&z=1", "k"));
        assertEquals("a", ApiKeyFilter.query("/x?k=a#frag", "k"));
        assertNull(ApiKeyFilter.query("/x?kk=a", "k"));
        assertEquals("", ApiKeyFilter.query("/x?k=", "k"));
        assertFalse(ApiKeyFilter.query("/x?k=a", "k").isBlank());
    }
}
