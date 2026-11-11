package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/** RFC 7617 basic-auth on the real HTTP/1.1 accept loop. */
class BasicAuthHttp1Test {

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
    void challengesThenAcceptsAValidUserOverHttp11() throws Exception {
        Files.writeString(root.resolve("hello.txt"), "hello");
        var filters = GatewayFilters.install(RequestFilters.create())
                .add(MiddlewareSpec.of("basic-auth", 0, null, Map.of("users", "alice:secret", "realm", "files")));
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), filters);
        int port = this.server.tcpPort();

        var miss = HttpTestSupport.get(port, "/hello.txt", 4000);
        assertEquals(401, miss.statusCode());
        assertEquals("Basic realm=\"files\", charset=\"UTF-8\"", miss.headers().get("www-authenticate"));
        assertTrue(miss.bodyText().contains("401 Unauthorized"), miss.bodyText());

        var token = "Basic " + Base64.getEncoder()
                .encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        var ok = HttpTestSupport.rawRequest(port,
                "GET /hello.txt HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + token + "\r\nConnection: close\r\n\r\n",
                4000);
        assertEquals(200, ok.statusCode());
        assertEquals("hello", ok.bodyText());
    }
}
