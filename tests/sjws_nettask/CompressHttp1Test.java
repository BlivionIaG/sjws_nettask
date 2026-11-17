package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/** compress on the real HTTP/1.1 accept loop. */
class CompressHttp1Test {

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
    void gzipsWhenTheClientOffersItAndLeavesIdentityAlone() throws Exception {
        var plain = "hello gzip over http/1.1\n".repeat(40);
        Files.writeString(root.resolve("note.txt"), plain);
        var filters = GatewayFilters.install(RequestFilters.create())
                .add(MiddlewareSpec.of("compress", 0, null, Map.of()));
        this.server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), filters);

        var gzipped = HttpTestSupport.rawRequest(this.server.tcpPort(),
                "GET /note.txt HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: gzip\r\n\r\n", 4000);
        assertEquals(200, gzipped.statusCode());
        assertEquals("gzip", gzipped.headers().get("content-encoding"));
        assertTrue(gzipped.headers().getOrDefault("vary", "").toLowerCase().contains("accept-encoding"));
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped.body()))) {
            assertEquals(plain, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        var identity = HttpTestSupport.rawRequest(this.server.tcpPort(),
                "GET /note.txt HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: identity\r\n\r\n", 4000);
        assertEquals(200, identity.statusCode());
        assertEquals(null, identity.headers().get("content-encoding"));
        assertEquals(plain, identity.bodyText());
    }
}
