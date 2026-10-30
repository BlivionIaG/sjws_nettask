/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package sjws_nettask.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/**
 * Runtime regression gate for access-log credential query masking and new-file mode.
 * Uses only public / master-existing APIs so it compiles on master and fails there at
 * runtime (secret still logged; file mode not 0640).
 */
class AccessLogCredentialQueryTest {

    private static final String SECRET = "S2-RUNTIME-GATE-SECRET";

    @TempDir
    Path dir;

    @Test
    void accessLogMasksConfiguredQueryParamAndNewFileIs0640() throws Exception {
        Files.writeString(dir.resolve("index.html"), "ok");
        Path log = dir.resolve("access.log");

        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(log.toString()).build())) {
            var control = server.chainControl(GatewayFilters.install(RequestFilters.create()), Set.of());
            control.apply(FilterDocument.of(1, List.of(
                    FilterDocument.FilterEntry.of("api-key", 0,
                            Map.of("keys", SECRET, "query", "api_key"), FilterScope.all()))));

            HttpTestSupport.rawRequest(server.tcpPort(),
                    "GET /index.html?api_key=" + SECRET + "&keep=1 HTTP/1.1\r\nHost: h\r\nx-api-key: " + SECRET
                            + "\r\n\r\n",
                    4000);

            String text = awaitLogContaining(log, "index.html");
            assertFalse(text.contains(SECRET), "access log must not contain the query secret: " + text.trim());
            assertTrue(text.contains("api_key=***"), "access log must mask the configured query param: " + text.trim());
            assertTrue(text.contains("keep=1"), text.trim());
        }

        assertTrue(Files.isRegularFile(log));
        var mode = Files.getPosixFilePermissions(log);
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ), mode, "new access-log file mode: " + mode);
    }

    private static String awaitLogContaining(Path log, String needle) throws InterruptedException, IOException {
        String text = "";
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(log)) {
                text = Files.readString(log);
                if (text.contains(needle)) {
                    return text;
                }
            }
            Thread.yield();
        }
        return text;
    }
}
