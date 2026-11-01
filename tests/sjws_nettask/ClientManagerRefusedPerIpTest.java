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
 * ({@code ClientManager.java:313}) and publishes {@code sjws_connections_refused_per_ip_total}.
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
                    assertEquals(-1, second.getInputStream().read(),
                            "refused accept must close without an HTTP response");
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
