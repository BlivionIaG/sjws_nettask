package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/** New handshakes see replaceTls; the next request sees replaceHandler. */
class Http3CertSwapTest {

    @TempDir
    java.nio.file.Path root;

    @Test
    void replaceTlsAndHandlerAffectTheNextConnection() throws Exception {
        Assumptions.assumeTrue(Http3Server.isAvailable(), "QUIC natives are not available");
        Files.writeString(root.resolve("index.html"), "file");
        var first = TlsMaterial.selfSigned("localhost");
        var second = TlsMaterial.selfSigned("localhost");
        assertNotEquals(first.chain()[0], second.chain()[0]);
        var config = ServerConfig.builder(root).port(0).h3Enabled(true).h3Port(0).h3Host("127.0.0.1").build();
        var server = new Http3Server(config, new StaticFileHandler(root), first);
        server.start();
        try (var client = new H3TestClient(first.chain()[0])) {
            var quic = client.connect(server.localAddress());
            var response = H3TestClient.request(quic, "GET", "/", Map.of()).get(15, TimeUnit.SECONDS);
            assertEquals(200, response.status());
        }
        server.replaceTls(second);
        server.replaceHandler(request -> new Response(200, ResponseHeaders.of().add("content-type", "text/plain"),
                Body.Bytes.of("swapped".getBytes())));
        assertEquals(second.chain()[0], server.tls().chain()[0]);
        try (var client = new H3TestClient(second.chain()[0])) {
            var quic = client.connect(server.localAddress());
            var response = H3TestClient.request(quic, "GET", "/", Map.of()).get(15, TimeUnit.SECONDS);
            assertEquals(200, response.status());
            assertEquals("swapped", new String(response.body()));
        }
        try (var pinned = new H3TestClient(first.chain()[0])) {
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> pinned.connect(server.localAddress()));
        } finally {
            server.close();
        }
    }
}
