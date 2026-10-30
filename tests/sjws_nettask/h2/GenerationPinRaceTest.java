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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * B1: {@code generation()} then {@code enter()} must not pin a generation that {@code whenQuiesced}
 * already closed. {@link Http2Server.Generation#tryEnter} + retire bit make pin atomic with drain.
 */
class GenerationPinRaceTest {

    private static final TlsMaterial TLS = TlsMaterial.selfSigned("localhost");

    @TempDir
    Path root;

    private Http2Server server;

    @AfterEach
    void stop() {
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
    }

    @Test
    void tryEnterFailsAfterRetireAndWhenQuiescedWaitsForPin() {
        var g = new Http2Server.Generation(r -> null, false);
        assertTrue(g.tryEnter());
        var closed = new AtomicInteger();
        g.whenQuiesced(closed::incrementAndGet);
        assertEquals(0, closed.get());
        assertTrue(g.retired());
        assertFalse(g.tryEnter());
        g.leave();
        assertEquals(1, closed.get());
        assertFalse(g.tryEnter());
    }

    @Test
    void reloadBetweenGenerationReadAndTryEnterDoesNotDispatchOnClosedGeneration() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        var files = new StaticFileHandler(this.root);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();

        var gen1Called = new AtomicBoolean();
        var gen2Called = new AtomicBoolean();
        var closedGen1 = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        Handler gen1 = request -> {
            gen1Called.set(true);
            var h = ResponseHeaders.of();
            h.set("x-gen", "1");
            return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("g1".getBytes(StandardCharsets.UTF_8))));
        };
        Handler gen2 = request -> {
            gen2Called.set(true);
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var h = ResponseHeaders.of();
            h.set("x-gen", "2");
            return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("g2".getBytes(StandardCharsets.UTF_8))));
        };

        this.server.armHandler(gen1, false);

        AtomicReference<Http2Server.Generation> raced = new AtomicReference<>();
        var once = new AtomicBoolean();
        this.server.instrumentForTest(new DispatchHooks() {
            @Override
            public void afterGenerationRead() {
                if (!once.compareAndSet(false, true)) {
                    return;
                }
                var previous = GenerationPinRaceTest.this.server.generation();
                raced.set(previous);
                GenerationPinRaceTest.this.server.armHandler(gen2, false);
                previous.whenQuiesced(() -> closedGen1.set(true));
            }
        });

        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.get(1, "/a.txt");
            assertTrue(entered.await(5, TimeUnit.SECONDS), "must dispatch on a live generation");
            // Gen1 must not run after being closed; tryEnter should have failed and retried gen2.
            assertFalse(gen1Called.get(), "must not dispatch on a generation closed by whenQuiesced (B1)");
            assertTrue(gen2Called.get());
            assertTrue(closedGen1.get() || raced.get().retired(), "previous generation retired");
            release.countDown();
            var fields = readHeaders(h2, 1);
            assertEquals("2", fields.get("x-gen"));
        }
    }

    private static java.util.Map<String, String> readHeaders(RawH2 h2, int stream) throws Exception {
        var fields = new java.util.LinkedHashMap<String, String>();
        var decoder = new DefaultHttp2HeadersDecoder();
        for (RawH2.Frame f; (f = h2.read()) != null;) {
            if (f.stream() != stream) {
                continue;
            }
            if (f.type() == RawH2.HEADERS) {
                var headers = decoder.decodeHeaders(stream, Unpooled.wrappedBuffer(f.payload()));
                headers.forEach(e -> fields.put(e.getKey().toString(), e.getValue().toString()));
            }
            if ((f.flags() & RawH2.END_STREAM) != 0) {
                break;
            }
        }
        return fields;
    }

    /**
     * Codex P2 4421811440: RETIRED must be set before onQuiesce is visible. With the test hook
     * parked after install, leave() drains pins; tryEnter in the callback must still fail.
     * Fail-before at 7a02a6d (install-then-RETIRED): callback successfully pinned (see
     * /workspace/bench-301-final/fail-before-gen-retire.txt).
     */
    @Test
    void quiesceCallbackCannotPinBeforeRetirement() throws Exception {
        var g = new Http2Server.Generation(r -> null, false);
        assertTrue(g.tryEnter());
        var enteredHook = new CountDownLatch(1);
        var releaseHook = new CountDownLatch(1);
        var pinnedInCallback = new AtomicBoolean(false);
        var prev = Http2Server.Generation.afterQuiesceInstall;
        Http2Server.Generation.afterQuiesceInstall = () -> {
            enteredHook.countDown();
            try {
                assertTrue(releaseHook.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        try {
            var done = new CountDownLatch(1);
            Thread retirer = new Thread(() -> {
                try {
                    g.whenQuiesced(() -> {
                        if (g.tryEnter()) {
                            pinnedInCallback.set(true);
                            g.leave();
                        }
                    });
                } finally {
                    done.countDown();
                }
            }, "retire");
            retirer.start();
            assertTrue(enteredHook.await(10, TimeUnit.SECONDS), "hook after quiesce install");
            // Drain the pin while parked after listener install. Fixed: RETIRED already set.
            g.leave();
            releaseHook.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            retirer.join(2000);
        } finally {
            Http2Server.Generation.afterQuiesceInstall = prev;
        }
        assertFalse(pinnedInCallback.get(),
                "quiesce callback must not pin: RETIRED precedes listener exposure");
        assertTrue(g.retired());
        assertFalse(g.tryEnter());
    }



    /** leave() with pins==0 before RETIRED must not fire (Codex P2 4421811440). */
    @Test
    void leaveBeforeRetireDoesNotFireQuiesce() {
        var g = new Http2Server.Generation(r -> null, false);
        var fired = new AtomicInteger();
        // Install listener without retiring via force path — use whenQuiesced after leave.
        assertTrue(g.tryEnter());
        g.leave(); // pins -> 0, not retired → must not fire (no listener yet anyway)
        assertEquals(0, fired.get());
        g.whenQuiesced(fired::incrementAndGet); // idle + retired → fires once
        assertEquals(1, fired.get());
        g.whenQuiesced(fired::incrementAndGet); // already idle; chains and fires again via getAndSet
        // second whenQuiesced: RETIRED already set, pins 0, installs new chain, fires
        assertEquals(2, fired.get());
    }

}
