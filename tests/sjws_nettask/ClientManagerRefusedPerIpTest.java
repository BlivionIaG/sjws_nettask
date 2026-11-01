package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A2 (inceptor 2026-10-30 / Architecture 2026-10-30): hitting {@code max_connections_per_ip}
 * increments {@link ClientManager#refusedPerIp()} on the refuse branch
 * ({@code ClientManager.java} refuseWith503) and publishes {@code sjws_connections_refused_per_ip_total}.
 * #327/#448: cleartext h1 refuse sends the canned 503 (Retry-After: 1, Connection: close,
 * Content-Length: 0) then EOF — not a silent close.
 */
@org.junit.jupiter.api.extension.ExtendWith(sjws_nettask.core.buf.NoBufferLeaks.class)
class ClientManagerRefusedPerIpTest {

    @TempDir
    Path root;

    @Test
    void refusedPerIpIncrementsWhenCapHitAndAppearsOnMetrics() throws Exception {
        Files.writeString(root.resolve("hi.txt"), "hi");
        var config = ServerConfig.builder(root)
                .port(0)
                .maxConnections(32)
                .maxConnectionsPerIp(1)
                .idleTimeout(Duration.ofSeconds(30))
                .build();
        try (var server = SjwsServer.start(config)) {
            int port = server.tcpPort();
            assertTrue(port > 0);
            try (var held = new Socket()) {
                held.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                held.setTcpNoDelay(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (server.clientManager().activeConnections() < 1 && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, server.clientManager().activeConnections());
                long before = server.clientManager().refusedPerIp();
                try (var second = new Socket()) {
                    second.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                    second.setSoTimeout(1500);
                    // No client request: same as HardeningIntegrationTest.connectionsPerAddressAreCapped
                    // (avoids racing refuseWith503's one-shot drain + close).
                    HttpTestSupport.Response r = HttpTestSupport.readResponse(second.getInputStream());
                    assertEquals(503, r.statusCode(), r.rawHead());
                    assertEquals("1", r.headers().get("retry-after"), r.rawHead());
                    assertEquals("close", r.headers().get("connection"), r.rawHead());
                    assertEquals("0", r.headers().get("content-length"), r.rawHead());
                    assertEquals(-1, second.getInputStream().read(), "EOF after the canned 503");
                }
                long after = before;
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (System.nanoTime() < deadline) {
                    after = server.clientManager().refusedPerIp();
                    if (after > before) {
                        break;
                    }
                    Thread.sleep(10);
                }
                assertTrue(after > before, "refusedPerIp must increment; before=" + before + " after=" + after);
                var text = server.armMetrics().prometheus();
                assertTrue(text.contains("# HELP sjws_connections_refused_per_ip_total"), text);
                assertTrue(text.contains("# TYPE sjws_connections_refused_per_ip_total counter"), text);
                assertTrue(text.contains("sjws_connections_refused_per_ip_total " + after), text);
            }
        }
    }
}
