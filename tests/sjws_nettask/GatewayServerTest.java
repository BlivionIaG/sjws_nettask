package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/** Gateway factories compile through a document and run on HTTP/1.1. */
class GatewayServerTest {

    @TempDir
    Path root;

    SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    void documentAppliesTheGatewayChain() throws Exception {
        Files.writeString(root.resolve("index.html"), "hello");
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build());
        var registry = GatewayFilters.install(RequestFilters.create());
        var control = this.server.chainControl(registry, Set.of("edge"));
        assertNull(this.server.filterChain());

        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("request-size", 0, Map.of("max-target", "32"), FilterScope.all()),
                FilterDocument.FilterEntry.of("cors", 5, Map.of("origins", "https://app.example", "methods", "GET,POST"),
                        FilterScope.all()),
                FilterDocument.FilterEntry.of("api-key", 10, Map.of("keys", "secret"), FilterScope.all()),
                FilterDocument.FilterEntry.of("header-rewrite", 20, Map.of("response-set", "x-edge: yes"),
                        FilterScope.labels("edge")))));

        var port = this.server.tcpPort();
        var blocked = HttpTestSupport.get(port, "/index.html", 4000);
        assertEquals(401, blocked.statusCode());

        var ok = HttpTestSupport.rawRequest(port, """
                GET /index.html HTTP/1.1\r
                Host: localhost\r
                x-api-key: secret\r
                Origin: https://app.example\r
                \r
                """, 4000);
        assertEquals(200, ok.statusCode());
        assertEquals("hello", ok.bodyText());
        assertEquals("yes", ok.headers().get("x-edge"));
        assertEquals("https://app.example", ok.headers().get("access-control-allow-origin"));

        var preflight = HttpTestSupport.rawRequest(port, """
                OPTIONS /index.html HTTP/1.1\r
                Host: localhost\r
                Origin: https://app.example\r
                Access-Control-Request-Method: POST\r
                \r
                """, 4000);
        assertEquals(204, preflight.statusCode());
        assertEquals("https://app.example", preflight.headers().get("access-control-allow-origin"));

        var huge = "GET /" + "a".repeat(40) + " HTTP/1.1\r\nHost: localhost\r\nx-api-key: secret\r\n\r\n";
        assertEquals(414, HttpTestSupport.rawRequest(port, huge, 4000).statusCode());

        assertThrows(IllegalArgumentException.class, () -> control.apply(FilterDocument.of(2, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", ""), FilterScope.labels("internal"))))));
        assertEquals(1, control.version());
        assertEquals(401, HttpTestSupport.get(port, "/index.html", 4000).statusCode());
        assertTrue(control.isLive());
    }
}
