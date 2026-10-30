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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import sjws_nettask.h2.Http2Server;
import java.time.Duration;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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
        // Shared scanner: pairs continue after #…& (same as ApiKeyFilter.query).
        assertEquals("/x?api_key=***#frag&api_key=***",
                AccessTargetMask.mask("/x?api_key=one#frag&api_key=" + SECRET, names));
        assertEquals("/x?z=1&api_key=***&z=2", AccessTargetMask.mask("/x?z=1&api_key=one&z=2", names));
        assertSame("/x?only", AccessTargetMask.mask("/x?only", names)); // no '=' value
    }

    /**
     * Fail-closed: targets ApiKeyFilter accepts after {@code #…&} must be masked. These fail at
     * bde2110 (mask stopped at {@code #}) and pass with the shared {@code QueryPairs} scanner.
     * Filter acceptance for the same targets is covered in {@code ApiKeyFilterTest#queryParser}.
     */
    @Test
    void maskFollowsFilterPastHashAmpersandPairs() {
        var names = new String[] {"api_key"};
        var a = "/x?other=1#frag&api_key=" + SECRET;
        assertEquals("/x?other=1#frag&api_key=***", AccessTargetMask.mask(a, names));
        assertFalse(AccessTargetMask.mask(a, names).contains(SECRET));

        var b = "/x?keep=1#x&api_key=" + SECRET + "&y=2";
        assertEquals("/x?keep=1#x&api_key=***&y=2", AccessTargetMask.mask(b, names));
        assertFalse(AccessTargetMask.mask(b, names).contains(SECRET));
    }

    @Test
    void maskMatchesFilterOnEncodedNameCaseEmptyAndSeparators() {
        var names = new String[] {"api_key"};
        // Percent-encoded name: filter does not decode; mask must not either.
        var enc = "/x?api%5Fkey=" + SECRET;
        assertSame(enc, AccessTargetMask.mask(enc, names));
        // Case: regionMatches is case-sensitive.
        var upper = "/x?API_KEY=" + SECRET;
        assertSame(upper, AccessTargetMask.mask(upper, names));
        // Empty first value, second holds the secret: mask covers every matching pair.
        assertEquals("/x?api_key=***&api_key=***",
                AccessTargetMask.mask("/x?api_key=&api_key=" + SECRET, names));
        // '+' and '%26' stay inside the value; ';' is not a separator.
        assertEquals("/x?api_key=***", AccessTargetMask.mask("/x?api_key=a+b%26c", names));
        assertEquals("/x?api_key=***", AccessTargetMask.mask("/x?api_key=" + SECRET + ";x=1", names));
        // Absolute-form target: first '?' starts the query.
        assertEquals("http://h/x?api_key=***", AccessTargetMask.mask("http://h/x?api_key=" + SECRET, names));
        // Path containing '?' is still first-'?' query start (same as filter).
        assertEquals("/a?b=c&api_key=***", AccessTargetMask.mask("/a?b=c&api_key=" + SECRET, names));
    }

    @Test
    void maskManyParamsStaysLinear() {
        var names = new String[] {"api_key"};
        var sb = new StringBuilder("/x?");
        for (int i = 0; i < 5000; i++) {
            if (i > 0) {
                sb.append('&');
            }
            sb.append("p").append(i).append('=').append(i);
        }
        sb.append("&api_key=").append(SECRET);
        long t0 = System.nanoTime();
        var out = AccessTargetMask.mask(sb.toString(), names);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(out.endsWith("api_key=***"), out.substring(Math.max(0, out.length() - 40)));
        assertFalse(out.contains(SECRET));
        assertTrue(ms < 500, "mask of 5001 pairs took " + ms + "ms");
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

    /**
     * After a live control document removes or adds {@code api-key}, a following config reload must
     * not resurrect startup/middleware names or drop the document's names. Covers the #302
     * chainLeaf publish path on h1 and h2c (access-log masking is observer-side and shared).
     */
    @Test
    void controlDocRemoveOrAddThenReloadKeepsDocumentOwnedNames() throws Exception {
        Files.writeString(dir.resolve("index.html"), "ok");
        var www2 = Files.createDirectories(dir.resolve("www2"));
        Files.writeString(www2.resolve("index.html"), "ok2");
        var log = dir.resolve("cp-reload.log");
        var yaml = dir.resolve("sjws-cp-reload.yaml");
        writeAccessYaml(yaml, dir, log, SECRET);
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        try (var server = SjwsServer.start(loaded)) {
            var control = server.chainControl(GatewayFilters.install(RequestFilters.create()), Set.of());
            control.apply(FilterDocument.of(1, List.of(
                    FilterDocument.FilterEntry.of("api-key", 0,
                            Map.of("keys", SECRET, "query", "from_doc"), FilterScope.all()))));

            get(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&add=1");
            getH2c(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&add-h2=1");
            var added = lineContaining(awaitLog(log, "add=1"), "add=1");
            assertTrue(added.contains("from_doc=***"), added);
            assertTrue(added.contains("from_mw=" + SECRET), added);
            var addedH2 = lineContaining(awaitLog(log, "add-h2=1"), "add-h2=1");
            assertTrue(addedH2.contains("from_doc=***"), addedH2);

            // Root change forces publish() → chainLeaf terminal under live control.
            writeAccessYaml(yaml, www2, log, SECRET);
            assertTrue(server.reload().applied());
            get(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&after-add=1");
            getH2c(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&after-add-h2=1");
            var afterAdd = lineContaining(awaitLog(log, "after-add=1"), "after-add=1");
            assertTrue(afterAdd.contains("from_doc=***"), afterAdd);
            assertFalse(afterAdd.contains("from_doc=" + SECRET), afterAdd);
            var afterAddH2 = lineContaining(awaitLog(log, "after-add-h2=1"), "after-add-h2=1");
            assertTrue(afterAddH2.contains("from_doc=***"), afterAddH2);

            control.apply(FilterDocument.empty(2));
            get(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&cleared=1");
            getH2c(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&cleared-h2=1");
            var cleared = lineContaining(awaitLog(log, "cleared=1"), "cleared=1");
            assertTrue(cleared.contains("from_doc=" + SECRET), cleared);
            var clearedH2 = lineContaining(awaitLog(log, "cleared-h2=1"), "cleared-h2=1");
            assertTrue(clearedH2.contains("from_doc=" + SECRET), clearedH2);

            writeAccessYaml(yaml, dir, log, SECRET);
            assertTrue(server.reload().applied());
            get(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&after-clear=1");
            getH2c(server, "/index.html?from_doc=" + SECRET + "&from_mw=" + SECRET + "&after-clear-h2=1");
            var afterClear = lineContaining(awaitLog(log, "after-clear=1"), "after-clear=1");
            assertTrue(afterClear.contains("from_doc=" + SECRET), afterClear);
            assertTrue(afterClear.contains("from_mw=" + SECRET), afterClear);
            assertFalse(afterClear.contains("***"), afterClear);
            var afterClearH2 = lineContaining(awaitLog(log, "after-clear-h2=1"), "after-clear-h2=1");
            assertFalse(afterClearH2.contains("***"), afterClearH2);
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


    /**
     * A request that entered under a configured query name must stay masked even when a reload
     * clears that name while the request is still inside the chain. Fails at 3757ae5 (live names
     * at log time); passes when names are pinned at observe-filter entry.
     */
    @Test
    void inFlightRequestKeepsPinnedMaskWhenReloadClearsNames() throws Exception {
        Files.writeString(dir.resolve("index.html"), "ok");
        var log = dir.resolve("inflight.log");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(log.toString()).build())) {
            var filters = GatewayFilters.install(RequestFilters.create()).factory("hold", spec -> (request, next) -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "release");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return next.proceed(request);
            });
            var control = server.chainControl(filters, Set.of());
            control.apply(FilterDocument.of(1, List.of(
                    FilterDocument.FilterEntry.of("api-key", 0,
                            Map.of("keys", SECRET, "query", "api_key"), FilterScope.all()),
                    FilterDocument.FilterEntry.of("hold", 10, Map.of(), FilterScope.all()))));

            var error = new AtomicReference<Throwable>();
            var thread = new Thread(() -> {
                try {
                    get(server, "/index.html?api_key=" + SECRET + "&inflight=1");
                } catch (Throwable t) {
                    error.set(t);
                }
            }, "s2-inflight-mask");
            thread.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "request entered hold");
            control.apply(FilterDocument.empty(2));
            release.countDown();
            thread.join(5_000L);
            assertFalse(thread.isAlive(), "request finished");
            if (error.get() != null) {
                throw new AssertionError(error.get());
            }

            var text = awaitLog(log, "inflight=1");
            assertFalse(text.contains(SECRET), "in-flight request must stay masked after clear: " + text.trim());
            assertTrue(text.contains("api_key=***"), text.trim());
        }
    }

    /**
     * Observability nit: every ancestor this process creates for the access-log path is mode
     * {@code 0750}; a pre-existing parent keeps whatever mode it already had.
     */
    @Test
    void accessLogCreatesEachMissingParentAt0750AndLeavesExistingAlone() throws Exception {
        var preexisting = Files.createDirectories(dir.resolve("logs"));
        var odd = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        Files.setPosixFilePermissions(preexisting, odd);
        var nested = preexisting.resolve("a").resolve("b").resolve("access.log");
        try (var server = SjwsServer.start(ServerConfig.builder(dir).port(0).accessLog(nested.toString()).build())) {
            assertNotNull(server);
            assertTrue(Files.isRegularFile(nested));
            var fileMode = Files.getPosixFilePermissions(nested);
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ), fileMode, fileMode.toString());
            var a = preexisting.resolve("a");
            var b = a.resolve("b");
            var expect750 = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE);
            assertEquals(expect750, Files.getPosixFilePermissions(a), "created parent a");
            assertEquals(expect750, Files.getPosixFilePermissions(b), "created parent b");
            assertEquals(odd, Files.getPosixFilePermissions(preexisting), "pre-existing parent untouched");
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
        var captured = new ByteArrayOutputStream();
        var saved = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (var started = AccessLog.start(log.toString(), AccessLogFormat.COMMON)) {
            assertNotNull(started);
        } finally {
            System.setErr(saved);
        }
        var perms = Files.getPosixFilePermissions(log);
        assertTrue(perms.contains(PosixFilePermission.OTHERS_READ), perms.toString());
        var err = captured.toString(StandardCharsets.UTF_8);
        assertEquals(1, err.split("WARN: access log ", -1).length - 1, err);
        assertTrue(err.contains("world-accessible"), err);
        assertTrue(err.contains(log.toAbsolutePath().toString()) || err.contains(log.toString()), err);
    }

    private static void writeAccessYaml(Path yaml, Path root, Path log, String secret) throws IOException {
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
                """.formatted(root.toAbsolutePath(), log.toAbsolutePath(), secret));
    }




    private static void getH2c(SjwsServer server, String target) throws Exception {
        int h2c = ((Http2Server) server.h2()).h2cPort();
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5)).build();
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h2c + target))
                .timeout(Duration.ofSeconds(5)).header("x-api-key", SECRET).GET().build();
        var resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 401, "h2c status=" + resp.statusCode());
    }

    private static void get(SjwsServer server, String target) throws IOException {
        var response = HttpTestSupport.rawRequest(server.tcpPort(),
                "GET " + target + " HTTP/1.1\r\nHost: h\r\nx-api-key: " + SECRET + "\r\n\r\n", 4000);
        // With api-key filter the header also works; without filter, 200 from static file.
        assertTrue(response.statusCode() == 200 || response.statusCode() == 401,
                "status=" + response.statusCode());
    }

    private static String lineContaining(String text, String needle) {
        for (var line : text.split("\n")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        return text;
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
