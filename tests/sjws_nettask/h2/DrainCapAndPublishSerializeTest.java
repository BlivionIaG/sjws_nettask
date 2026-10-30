/*
 * Copyright (C) 2026 BlivionIaG <BlivionIaG at chenco.tk>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.ChainControl;
import sjws_nettask.core.FilterChain;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.Handler;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.gateway.GatewayFilters;

class DrainCapAndPublishSerializeTest {

    @TempDir
    Path dir;

    private SjwsServer server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
    }

    private SjwsServer startStatic() throws Exception {
        Files.writeString(this.dir.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws.yaml");
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(this.dir.toAbsolutePath()));
        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        return SjwsServer.start(loaded);
    }

    @Test
    void drainingGenerationCapForceClosesOldest() throws Exception {
        this.server = startStatic();
        var forced = new AtomicInteger();
        List<Http2Server.Generation> gens = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            var g = new Http2Server.Generation(r -> null, false);
            assertTrue(g.tryEnter());
            g.whenQuiesced(forced::incrementAndGet);
            this.server.trackDrainingGeneration(g);
            gens.add(g);
        }
        assertTrue(this.server.drainingGenerationCount() <= 4);
        assertTrue(forced.get() >= 2, "oldest beyond cap must be force-quiesced, got " + forced.get());
        for (var g : gens) {
            g.leave();
        }
    }

    @Test
    void compileAroundReusesFilterInstances() throws Exception {
        var terminal = new StaticFileHandler(this.dir);
        var stable = new RequestFilter() {
            @Override
            public sjws_nettask.core.Response filter(sjws_nettask.core.Request request, Chain next) {
                return next.proceed(request);
            }
        };
        var registry = RequestFilters.create().add(0, stable);
        var control = ChainControl.open(registry, Set.of(), terminal, null, null);
        // empty doc + outer via pinOuter path: apply empty then compileAround
        control.apply(FilterDocument.empty(1));
        // retained may be null for empty doc — force a non-empty compile via outer
        control.pinOuter(stable);
        Handler a = control.compileAround(r -> null);
        Handler b = control.compileAround(r -> null);
        assertTrue(a instanceof FilterChain);
        assertTrue(b instanceof FilterChain);
        assertSame(firstFilter((FilterChain) a), firstFilter((FilterChain) b));
    }

    private static RequestFilter firstFilter(FilterChain chain) throws Exception {
        var head = FilterChain.class.getDeclaredField("head");
        head.setAccessible(true);
        Object node = head.get(chain);
        var filter = node.getClass().getDeclaredField("filter");
        filter.setAccessible(true);
        return (RequestFilter) filter.get(node);
    }

    @Test
    void concurrentApplyCannotLoseToReloadArm() throws Exception {
        this.server = startStatic();
        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        var control = this.server.chainControl(filters, Set.of());
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "v1", "header", "x-api-key"),
                        FilterScope.all()))));

        var barrier = new CyclicBarrier(2);
        var errors = new AtomicInteger();
        var applyDone = new CountDownLatch(1);
        var reloadDone = new CountDownLatch(1);
        var yaml = this.dir.resolve("sjws.yaml");

        Thread applier = new Thread(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                control.apply(FilterDocument.of(2, List.of(
                        FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "v2", "header", "x-api-key"),
                                FilterScope.all()))));
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                applyDone.countDown();
            }
        }, "apply");
        Thread reloader = new Thread(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                var www2 = Files.createDirectories(this.dir.resolve("www2"));
                Files.writeString(www2.resolve("hello.txt"), "hello2");
                Files.writeString(yaml, """
                        server:
                          port: 0
                          root: "%s"
                        """.formatted(www2.toAbsolutePath()));
                this.server.reload();
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                reloadDone.countDown();
            }
        }, "reload");
        applier.start();
        reloader.start();
        assertTrue(applyDone.await(15, TimeUnit.SECONDS));
        assertTrue(reloadDone.await(15, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(2, control.version(), "apply v2 must remain published");
    }
}
