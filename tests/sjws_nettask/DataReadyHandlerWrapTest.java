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
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.ha.HaSettings;

@Timeout(20)
class DataReadyHandlerWrapTest {

    @TempDir
    Path dir;

    @Test
    void withDataReadinessKeepsHaStandaloneAndExistingWrappers() throws Exception {
        var html = Files.createDirectory(dir.resolve("wrap-html"));
        Files.writeString(html.resolve("index.html"), "ok");
        var dataYaml = dir.resolve("wrap-data.yaml");
        Files.writeString(dataYaml, "schema: 1\nnode:\n  mode: data\n  state_dir: "
                + dir.resolve("wrap-state").toAbsolutePath() + "\nserver:\n  port: 0\n  root: "
                + html.toAbsolutePath() + "\n");
        var dataLoaded = ConfigLoader.load(new String[] {"--config=" + dataYaml}, Map.of());
        var ha = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofMillis(200)).build();
        var filters = RequestFilters.create().factory("mark", spec -> (request, next) -> next.proceed(request));
        try (var server = SjwsServer.start(dataLoaded, ha)) {
            server.chainControl(filters, Set.of()).apply(FilterDocument.empty(1));
            assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
        }
        var standYaml = dir.resolve("wrap-stand.yaml");
        Files.writeString(standYaml, "schema: 1\nnode:\n  mode: standalone\nserver:\n  port: 0\n  root: "
                + html.toAbsolutePath() + "\n");
        var standLoaded = ConfigLoader.load(new String[] {"--config=" + standYaml}, Map.of());
        try (var server = SjwsServer.start(standLoaded, filters)) {
            server.chainControl(filters, Set.of()).apply(FilterDocument.empty(1));
            assertEquals("ok", HttpTestSupport.get(server.tcpPort(), "/index.html", 2_000).bodyText());
            Method wrap = SjwsServer.class.getDeclaredMethod("withDataReadiness", Handler.class);
            wrap.setAccessible(true);
            assertNull(wrap.invoke(server, new Object[] {null}));
            var already = new DataReadyHandler(() -> request -> new sjws_nettask.core.Response(200, sjws_nettask.core.ResponseHeaders.of(), sjws_nettask.core.Body.Bytes.of(new byte[0]), false), () -> true);
            assertSame(already, wrap.invoke(server, already));
        }
        try (var server = SjwsServer.start(dataLoaded, null, false)) {
            Method wrap = SjwsServer.class.getDeclaredMethod("withDataReadiness", Handler.class);
            wrap.setAccessible(true);
            var already = new DataReadyHandler(() -> request -> new sjws_nettask.core.Response(200, sjws_nettask.core.ResponseHeaders.of(), sjws_nettask.core.Body.Bytes.of(new byte[0]), false), () -> true);
            assertSame(already, wrap.invoke(server, already));
        }
    }
}
