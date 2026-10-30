package sjws_nettask.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import sjws_nettask.core.Request;

class ApiKeyFilterTest {

    @TempDir
    Path dir;

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
        // '#' only truncates the matched value; scanning continues after '#…&'.
        assertEquals("SECRET", ApiKeyFilter.query("/x?other=1#frag&k=SECRET", "k"));
        assertEquals("SECRET", ApiKeyFilter.query("/x?keep=1#x&k=SECRET&y=2", "k"));
        assertNull(ApiKeyFilter.query("/x?api%5Fkey=SECRET", "api_key"));
        assertNull(ApiKeyFilter.query("/x?API_KEY=SECRET", "api_key"));
        assertEquals("", ApiKeyFilter.query("/x?k=&k=SECRET", "k"));
    }

    @Test
    void keysFileResolvesAndRejectsBothForms() throws Exception {
        var file = dir.resolve("keys");
        Files.writeString(file, "alpha, beta");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        var filter = ApiKeyFilter.create(GatewayTestSupport.spec("api-key",
                Map.of("keys-file", file.toAbsolutePath().toString())));
        assertEquals(200, GatewayTestSupport.call(filter, GatewayTestSupport.request("GET", "/",
                Map.of("x-api-key", List.of("beta")))).status());
        assertThrows(IllegalArgumentException.class, () -> ApiKeyFilter.create(GatewayTestSupport.spec("api-key",
                Map.of("keys", "a", "keys-file", file.toAbsolutePath().toString()))));
    }
}
