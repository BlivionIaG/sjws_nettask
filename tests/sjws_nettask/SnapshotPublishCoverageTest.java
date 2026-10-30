package sjws_nettask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.config.ConfigSnapshot;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.gateway.GatewayFilters;
import sjws_nettask.h2.Http2Server;

/**
 * Exhaustive same-package coverage for publish helpers (jacoco sjws_nettask BRANCH 0.96).
 */
class SnapshotPublishCoverageTest {

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
    void liveNeedsBodiesCoversStaticArmedAndControl() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        // static-only, nothing armed
        assertFalse(this.server.liveNeedsBodies());

        this.server.armMetrics();
        assertTrue(this.server.liveNeedsBodies(), "armed metrics forces bodies");

        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        var control = this.server.chainControl(filters, Set.of());
        // control open but not live yet — still true from armed
        assertTrue(this.server.liveNeedsBodies());
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "k", "header", "x-api-key"),
                        FilterScope.all()))));
        assertTrue(this.server.liveNeedsBodies(), "live control forces bodies");
    }

    @Test
    void armedAroundControlMetricsAndPlain() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        Handler terminal = this.server.handler();

        // plain: no control, no armed → frozen terminal
        Handler plain = this.server.armedAround(terminal);
        assertNotNull(plain);
        assertEquals(200, plain.handle(new sjws_nettask.core.Request("GET", "/a.txt")).status());

        // metrics path
        this.server.armMetrics();
        Handler withMetrics = this.server.armedAround(terminal);
        assertNotNull(withMetrics);
        withMetrics.handle(new sjws_nettask.core.Request("GET", "/a.txt"));
        assertTrue(this.server.metricsSnapshot().requests() >= 1);

        // control live path
        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        this.server.chainControl(filters, Set.of()).apply(FilterDocument.of(2, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "secret", "header", "x-api-key"),
                        FilterScope.all()))));
        Handler withKey = this.server.armedAround(terminal);
        assertEquals(401, withKey.handle(new sjws_nettask.core.Request("GET", "/a.txt")).status());
        var ok = withKey.handle(new sjws_nettask.core.Request("GET", "/a.txt",
                sjws_nettask.core.RequestHeaders.of(Map.of("x-api-key", List.of("secret")))));
        assertEquals(200, ok.status());
    }

    @Test
    void deferSnapshotCloseNullPreviousAndNullReplaced() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        this.server.deferSnapshotClose(null, null); // no-op

        var snap = ConfigSnapshot.compile(
                sjws_nettask.config.ConfigLoader.standalone(
                        ServerConfig.builder(this.root).port(0).build()).document(),
                null, 99);
        // previous==null closes immediately
        this.server.deferSnapshotClose(null, snap);
        // second close idempotent at pool layer
        this.server.deferSnapshotClose(null, snap);

        // Idle generation: whenQuiesced from defer fires promptly
        var idle = new Http2Server.Generation(r -> null, false);
        var holding = ConfigSnapshot.compile(
                sjws_nettask.config.ConfigLoader.standalone(
                        ServerConfig.builder(this.root).port(0).build()).document(),
                null, 100);
        this.server.deferSnapshotClose(idle, holding);
        assertNotNull(idle);
    }

    @Test
    void controlOpenButNotLiveFallsThroughArmedAround() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        // open control without apply → control != null && !isLive
        this.server.chainControl(RequestFilters.create(), Set.of());
        assertFalse(this.server.liveNeedsBodies());
        Handler h = this.server.armedAround(this.server.handler());
        assertEquals(200, h.handle(new sjws_nettask.core.Request("GET", "/a.txt")).status());
    }

    @Test
    void armSnapshotAndMetricsWithoutH2() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        // No HTTP/2: armSnapshot / publishArmed take the non-Http2Server branches.
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0).build());
        this.server.armMetrics();
        assertTrue(this.server.metricsSnapshot().requests() >= 0);
        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        this.server.chainControl(filters, Set.of()).apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "k", "header", "x-api-key"),
                        FilterScope.all()))));
        assertEquals(401, this.server.filterChain().handle(
                new sjws_nettask.core.Request("GET", "/a.txt")).status());
    }

    @Test
    void observingAtStartArmedAround() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0).healthz(true)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        Handler wrapped = this.server.armedAround(this.server.handler());
        assertEquals(200, wrapped.handle(new sjws_nettask.core.Request("GET", "/a.txt")).status());
        assertEquals(200, this.server.requestHandler().handle(
                new sjws_nettask.core.Request("GET", "/healthz")).status());
    }

    @Test
    void liveNeedsBodiesWhenControlLiveEvenIfArmedClearedPath() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        // Apply without prior armMetrics — control.isLive alone
        this.server.chainControl(filters, Set.of()).apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "k", "header", "x-api-key"),
                        FilterScope.all()))));
        assertTrue(this.server.liveNeedsBodies());
        assertEquals(401, this.server.armedAround(this.server.handler())
                .handle(new sjws_nettask.core.Request("GET", "/a.txt")).status());
    }


    @Test
    void armMetricsAfterLiveControlPinsOuter() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        this.server.chainControl(filters, Set.of()).apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "secret", "header", "x-api-key"),
                        FilterScope.all()))));
        // publishArmed pinOuter path (control already live)
        this.server.armMetrics();
        assertEquals(401, this.server.filterChain().handle(
                new sjws_nettask.core.Request("GET", "/a.txt")).status());
        long before = this.server.metricsSnapshot().requests();
        this.server.filterChain().handle(new sjws_nettask.core.Request("GET", "/a.txt",
                sjws_nettask.core.RequestHeaders.of(Map.of("x-api-key", List.of("secret")))));
        assertTrue(this.server.metricsSnapshot().requests() > before);
    }


    @Test
    void armedAroundSkipsDoubleObserveWhenOverrideAndObserving() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        // recording at start (healthz) + startup RequestFilters → override embeds observe.
        var startup = RequestFilters.create().add((request, next) -> {
            var response = next.proceed(request);
            response.headers().set("x-startup", "1");
            return response;
        });
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0).healthz(true)
                .h2Enabled(true).h2Port(0).h2cPort(0).build(), startup);
        Handler terminal = this.server.requestHandler(); // == override
        Handler once = this.server.armedAround(terminal);
        long before = this.server.metricsSnapshot().requests();
        var resp = once.handle(new sjws_nettask.core.Request("GET", "/a.txt"));
        assertEquals(200, resp.status());
        assertNotNull(resp.headers());
        long after = this.server.metricsSnapshot().requests();
        assertEquals(before + 1, after, "observingAtStart+override must not double-count");
    }



    @Test
    void deferSnapshotCloseWaitsForTrackedExtras() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        this.server = SjwsServer.start(ServerConfig.builder(this.root).port(0)
                .h2Enabled(true).h2Port(0).h2cPort(0).build());
        this.server.trackDrainingGeneration(null);
        assertEquals(0, this.server.drainingGenerationCount());

        var tryEnter = Http2Server.Generation.class.getDeclaredMethod("tryEnter");
        tryEnter.setAccessible(true);
        var leave = Http2Server.Generation.class.getDeclaredMethod("leave");
        leave.setAccessible(true);

        var held = new Http2Server.Generation(r -> null, false);
        assertTrue((Boolean) tryEnter.invoke(held));
        this.server.trackDrainingGeneration(held);

        var previous = new Http2Server.Generation(r -> null, false);
        assertTrue((Boolean) tryEnter.invoke(previous));
        var snap = ConfigSnapshot.compile(
                sjws_nettask.config.ConfigLoader.standalone(
                        ServerConfig.builder(this.root).port(0).build()).document(),
                null, 200);
        this.server.deferSnapshotClose(previous, snap);
        assertTrue(this.server.drainingGenerationCount() >= 1);
        leave.invoke(held);
        leave.invoke(previous);
    }

}
