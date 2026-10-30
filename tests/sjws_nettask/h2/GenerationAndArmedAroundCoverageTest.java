/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 */
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.ChainControl;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;

/** Branch coverage for Generation quiesce, compileAround, and metrics-only armedAround. */
class GenerationAndArmedAroundCoverageTest {

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
    void generationWhenQuiescedRunsImmediatelyWhenIdleAndOnceOnLeave() {
        var g = new Http2Server.Generation(r -> null, false);
        var n = new AtomicInteger();
        g.whenQuiesced(n::incrementAndGet);
        assertEquals(1, n.get(), "idle generation fires whenQuiesced immediately");

        g.enter();
        var later = new AtomicInteger();
        g.whenQuiesced(later::incrementAndGet);
        assertEquals(0, later.get());
        g.leave();
        assertEquals(1, later.get());
    }

    @Test
    void compileAroundWithoutDocumentUsesOuterOrTerminal() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        var terminal = new StaticFileHandler(this.root);
        var filters = RequestFilters.create();
        var outer = new AtomicBoolean();
        var control = ChainControl.open(filters, Set.of(), terminal, () -> (request, next) -> {
            outer.set(true);
            return next.proceed(request);
        }, null);
        Handler around = control.compileAround(terminal);
        // empty doc + outer → compiled chain with outer
        var req = new sjws_nettask.core.Request("GET", "/a.txt");
        around.handle(req);
        assertTrue(outer.get());

        // no outer supplier → terminal
        var plain = ChainControl.open(filters, Set.of(), terminal, null, null);
        assertSame(terminal, plain.compileAround(terminal));
    }

    @Test
    void metricsOnlyArmSurvivesReloadOverH2() throws Exception {
        Files.writeString(this.root.resolve("hello.txt"), "hello");
        var yaml = this.root.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(this.root.toAbsolutePath()));
        var loaded = sjws_nettask.config.ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, java.util.Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded);
        this.server.armMetrics();
        long before = this.server.metricsSnapshot().requests();

        var www2 = Files.createDirectories(this.root.resolve("www2"));
        Files.writeString(www2.resolve("hello.txt"), "hello2");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www2.toAbsolutePath()));
        assertTrue(this.server.reload().applied());

        int h2c = ((Http2Server) this.server.h2()).h2cPort();
        var client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_2).build();
        var r = client.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + h2c + "/hello.txt")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("hello2", r.body());
        assertTrue(this.server.metricsSnapshot().requests() > before);
    }

    @Test
    void armHandlerReturnsPreviousGeneration() throws Exception {
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0).build();
        var files = new StaticFileHandler(this.root);
        try (var h2 = new Http2Server(config, files, sjws_nettask.tls.TlsMaterial.selfSigned("localhost"),
                Http2Server.Limits.DEFAULT).start()) {
            assertFalse(h2.attachBodies());
            var prev = h2.armHandler(files, true);
            // first arm: previous may be null
            assertTrue(h2.attachBodies());
            var g2 = h2.armHandler(r -> null, false);
            assertTrue(g2 != null);
            assertTrue(g2.attachBodies());
            assertFalse(h2.attachBodies());
        }
    }
}
