package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import sjws_nettask.core.StaticFileHandler;

/**
 * Clustering off is {@link SjwsServer#start(ServerConfig)} with no {@code HaSettings}.
 * The request path then uses the file handler directly: no heartbeat thread, no lease,
 * and no role or drain supplier.
 */
class SingleNodeHotPathTest {

    @TempDir
    Path root;

    @Test
    void detachedServerServesWithoutHeartbeatLeaseOrRoleChecks() throws Exception {
        Files.writeString(root.resolve("index.html"), "ok");
        try (var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0))) {
            assertNull(server.ha());
            assertSame(server.handler(), server.requestHandler());
            assertTrue(server.requestHandler() instanceof StaticFileHandler);
            assertNull(server.clientManager().acceptPause(), "no role supplier on the accept path");
            assertNull(server.clientManager().drainSignal(), "no drain supplier on keep-alive");
            assertEquals(200, HttpTestSupport.get(server.tcpPort(), "/index.html", 2000).statusCode());
            assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2000).bodyText());
            assertNull(server.clientManager().acceptPause());
            assertNull(server.clientManager().drainSignal());
            for (var thread : Thread.getAllStackTraces().keySet()) {
                var name = thread.getName();
                assertTrue(!name.startsWith("ha-heartbeat") && !name.startsWith("raft-"), name);
            }
        }
    }

    @Test
    void reaperStaysUpIfInterruptedBeforeClose() throws Exception {
        var before = new HashSet<>(Thread.getAllStackTraces().keySet());
        var reaper = new ConnectionReaper(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
        try {
            Thread created = null;
            for (var thread : Thread.getAllStackTraces().keySet()) {
                if ("http1-reaper".equals(thread.getName()) && !before.contains(thread)) {
                    created = thread;
                }
            }
            assertNotNull(created);
            created.interrupt();
            Thread.sleep(40);
            assertTrue(created.isAlive());
        } finally {
            reaper.close();
        }
    }
}
