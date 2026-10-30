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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.HttpTestSupport;
import sjws_nettask.ServerConfig;
import sjws_nettask.ServerConfig.AccessLogFormat;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.gateway.GatewayFilters;

/**
 * Access-log credential query masking and log-file mode. The end-to-end cases use only public
 * server APIs so they fail at runtime on master (secret still appears; new files are not 0640).
 */
class AccessTargetMaskTest {

    private static final String SECRET = "S2-DUMMY-KEY-VALUE";

    @TempDir
    Path dir;

    @Test
    void maskReplacesConfiguredValuesAndLeavesOthers() {
        var names = new String[] {"api_key", "token"};
        assertEquals("/x?api_key=***&z=1", AccessTargetMask.mask("/x?api_key=" + SECRET + "&z=1", names));
        assertEquals("/x?z=1&api_key=***", AccessTargetMask.mask("/x?z=1&api_key=" + SECRET, names));
        assertEquals("/x?api_key=***#frag", AccessTargetMask.mask("/x?api_key=" + SECRET + "#frag", names));
        assertEquals("/x?api_key=***&api_key=***",
                AccessTargetMask.mask("/x?api_key=one&api_key=two", names));
        assertEquals("/x?api_key=***&token=***",
                AccessTargetMask.mask("/x?api_key=a&token=b", names));
        // URL-encoded value is masked as a whole; name match stays literal like ApiKeyFilter.
        assertEquals("/x?api_key=***", AccessTargetMask.mask("/x?api_key=hello%20world", names));
        assertEquals("/x?api%5Fkey=" + SECRET, AccessTargetMask.mask("/x?api%5Fkey=" + SECRET, names));
        assertEquals("/x?API_KEY=" + SECRET, AccessTargetMask.mask("/x?API_KEY=" + SECRET, names));
        assertEquals("/x?api_key=***", AccessTargetMask.mask("/x?api_key=", names));
        assertEquals("/no-query", AccessTargetMask.mask("/no-query", names));
        // Fragment ends the query; a later &-pair is not scanned (URI rules).
        assertEquals("/x?api_key=***#frag&api_key=" + SECRET,
                AccessTargetMask.mask("/x?api_key=one#frag&api_key=" + SECRET, names));
        assertEquals("/x?z=1&api_key=***&z=2", AccessTargetMask.mask("/x?z=1&api_key=one&z=2", names));
        assertSame("/x?only", AccessTargetMask.mask("/x?only", names)); // no '=' value
    }

    @Test
    void maskIsNoOpWhenUnconfigured() {
        var target = "/x?api_key=" + SECRET + "&z=1";
        assertSame(target, AccessTargetMask.mask(target, null));
        assertSame(target, AccessTargetMask.mask(target, AccessTargetMask.NONE));
        assertSame(target, AccessTargetMask.mask(target, new String[0]));
        assertSame(null, AccessTargetMask.mask(null, new String[] {"api_key"}));
        assertSame(target, AccessTargetMask.mask(target, new String[] {"other"}));
    }

    @Test
    void namesCollectedFromMiddlewareAndDocument() {
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromMiddleware(null));
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromMiddleware(List.of()));
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromMiddleware(List.of(
                MiddlewareSpec.of("api-key", 0, null, Map.of("keys", "a")))));
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromMiddleware(List.of(
                MiddlewareSpec.of("api-key", 0, null, Map.of("keys", "a", "query", "  ")))));
        assertArrayEquals(new String[] {"k", "token"}, AccessTargetMask.namesFromMiddleware(List.of(
                MiddlewareSpec.of("jwt", 0, null, Map.of("hmac-keys", "x")),
                MiddlewareSpec.of("api-key", 0, null, Map.of("keys", "a", "query", "k")),
                MiddlewareSpec.of("api-key", 1, "/r", Map.of("keys", "b", "query", " k ")),
                MiddlewareSpec.of("api-key", 2, null, Map.of("keys", "c", "query", "token")))));
        var doc = FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "a", "query", "q"), FilterScope.all()),
                FilterDocument.FilterEntry.of("cors", 1, Map.of("origins", "*"), FilterScope.all())));
        assertArrayEquals(new String[] {"q"}, AccessTargetMask.namesFromDocument(doc));
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromDocument(FilterDocument.empty(0)));
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromDocument(null));
        assertSame(AccessTargetMask.NONE, AccessTargetMask.namesFromDocument(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "a", "query", " "), FilterScope.all())))));
    }

    @Test
    void configReloadPicksUpMiddlewareQueryNames() throws Exception {
        Files.writeString(dir.resolve("index.html"), "ok");
        var log = dir.resolve("reload.log");
        var yaml = dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: %s
                  access_log: %s
                middleware:
                  - name: api-key
                    options:
                      keys: %s
                      query: api_key
                """.formatted(dir.toAbsolutePath(), log.toAbsolutePath(), SECRET));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            // middleware is stored on the document; attach uses that list at construction.
            get(server, "/index.html?api_key=" + SECRET + "&r=1");
            var text = awaitLog(log, "api_key=***");
            assertFalse(text.contains(SECRET), text);

            Files.writeString(yaml, """
                    server:
                      port: 0
                      root: %s
                      access_log: %s
                    middleware: []
                    """.formatted(dir.toAbsolutePath(), log.toAbsolutePath()));
            var reloaded = server.reload();
            assertTrue(reloaded.applied(), reloaded.errors().toString());
            get(server, "/index.html?api_key=" + SECRET + "&after=1");
            var after = awaitLog(log, "after=1");
            assertTrue(after.contains("api_key=" + SECRET), after);
        }
    }

    @Test
    void configReloadLeavesLiveFilterDocumentNames() throws Exception {
        Files.writeString(dir.resolve("index.html"), "ok");
        var log = dir.resolve("live-doc.log");
        var yaml = dir.resolve("sjws-live.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: %s
                  access_log: %s
                middleware:
                  - name: api-key
                    options:
                      keys: %s
                      query: from_mw
                """.formatted(dir.toAbsolutePath(), log.toAbsolutePath(), SECRET));
        var loaded = ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var control = server.chainControl(GatewayFilters.install(RequestFilters.create()), Set.of());
            control.apply(FilterDocument.of(1, List.of(
                    FilterDocument.FilterEntry.of("api-key", 0,
                            Map.of("keys", SECRET, "query", "from_doc"), FilterScope.all()))));
            get(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET);
            var text = awaitLog(log, "from_doc=***");
            assertTrue(text.contains("from_doc=***"), text);
            assertTrue(text.contains("from_mw=" + SECRET), text);

            // Config still advertises from_mw; the live filter document must keep owning the names.
            Files.writeString(yaml, """
                    server:
                      port: 0
                      root: %s
                      access_log: %s
                    middleware:
                      - name: api-key
                        options:
                          keys: %s
                          query: from_mw
                    """.formatted(dir.toAbsolutePath(), log.toAbsolutePath(), SECRET));
            var reloaded = server.reload();
            assertTrue(reloaded.applied(), reloaded.errors().toString());
            get(server, "/index.html?from_doc=" + SECRET + "&after=1");
            var after = awaitLog(log, "after=1");
            assertTrue(after.contains("from_doc=***"), after);
            assertFalse(after.contains("from_doc=" + SECRET), after);
        }
    }

    @Test
    void accessLogMasksQueryAcrossFormatsAndReloadClears() throws Exception {
        Files.writeString(dir.resolve("index.html"), "ok");
        var log = dir.resolve("access.log");
        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(log.toString())
                .accessLogFormat(AccessLogFormat.COMBINED).build())) {
            var control = server.chainControl(GatewayFilters.install(RequestFilters.create()), Set.of());
            control.apply(FilterDocument.of(1, List.of(
                    FilterDocument.FilterEntry.of("api-key", 0,
                            Map.of("keys", SECRET, "query", "api_key"), FilterScope.all()))));

            get(server, "/index.html?api_key=" + SECRET + "&keep=1");
            var combined = awaitLog(log, "api_key=***");
            assertTrue(combined.contains("api_key=***"), combined);
            assertTrue(combined.contains("keep=1"), combined);
            assertFalse(combined.contains(SECRET), combined);

            // Empty filter document clears the configured names; the raw value is logged again.
            control.apply(FilterDocument.empty(2));
            get(server, "/index.html?api_key=" + SECRET + "&cleared=1");
            var cleared = awaitLog(log, "cleared=1");
            assertTrue(cleared.contains("api_key=" + SECRET), cleared);
        }

        var jsonLog = dir.resolve("access.json");
        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(jsonLog.toString())
                .accessLogFormat(AccessLogFormat.JSON).build())) {
            server.attachCredentialQueryParams(new String[] {"api_key"});
            get(server, "/index.html?api_key=" + SECRET);
            var json = awaitLog(jsonLog, "api_key=***");
            assertTrue(json.contains("\"path\":\"/index.html?api_key=***\""), json);
            assertFalse(json.contains(SECRET), json);
        }

        var commonLog = dir.resolve("access.common");
        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(commonLog.toString())
                .accessLogFormat(AccessLogFormat.COMMON).build())) {
            server.attachCredentialQueryParams(AccessTargetMask.namesFromMiddleware(List.of(
                    MiddlewareSpec.of("api-key", 0, null, Map.of("keys", "x", "query", "api_key")))));
            get(server, "/index.html?api_key=" + SECRET);
            var common = awaitLog(commonLog, "api_key=***");
            assertFalse(common.contains(SECRET), common);
        }
    }

    @Test
    void newAccessLogFileIsGroupReadableOnly() throws Exception {
        var nested = dir.resolve("logs").resolve("access.log");
        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(nested.toString()).build())) {
            assertNotNull(server);
            assertTrue(Files.isRegularFile(nested));
            var fileMode = Files.getPosixFilePermissions(nested);
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ), fileMode, fileMode.toString());
            var dirMode = Files.getPosixFilePermissions(nested.getParent());
            assertTrue(dirMode.contains(PosixFilePermission.OWNER_READ));
            assertTrue(dirMode.contains(PosixFilePermission.OWNER_EXECUTE));
            assertFalse(dirMode.contains(PosixFilePermission.OTHERS_READ), dirMode.toString());
            assertFalse(dirMode.contains(PosixFilePermission.OTHERS_EXECUTE), dirMode.toString());
        }
    }

    @Test
    void existingWorldReadableFileIsLeftAlone() throws Exception {
        var log = dir.resolve("preexisting.log");
        Files.writeString(log, "");
        Files.setPosixFilePermissions(log, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OTHERS_READ));
        try (var started = AccessLog.start(log.toString(), AccessLogFormat.COMMON)) {
            assertNotNull(started);
        }
        var perms = Files.getPosixFilePermissions(log);
        assertTrue(perms.contains(PosixFilePermission.OTHERS_READ), perms.toString());
    }

    private static void get(SjwsServer server, String target) throws IOException {
        var response = HttpTestSupport.rawRequest(server.tcpPort(),
                "GET " + target + " HTTP/1.1\r\nHost: h\r\nx-api-key: " + SECRET + "\r\n\r\n", 4000);
        // With api-key filter the header also works; without filter, 200 from static file.
        assertTrue(response.statusCode() == 200 || response.statusCode() == 401,
                "status=" + response.statusCode());
    }

    private static String awaitLog(Path log, String needle) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String text = Files.exists(log) ? Files.readString(log) : "";
        while (!text.contains(needle)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("access log did not contain " + needle + " within 2s: " + text);
            }
            Thread.yield();
            text = Files.exists(log) ? Files.readString(log) : "";
        }
        return text;
    }
}
