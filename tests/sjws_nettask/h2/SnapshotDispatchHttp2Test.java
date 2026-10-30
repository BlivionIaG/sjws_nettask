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
 * #301: stream accepted with {@code attachBodies=false} must not dispatch on a later generation that
 * needs bodies after a reload. {@link Http2StreamHandler} reads {@code generation()} once before
 * {@code toRequest} and passes that instance into {@code start}; {@link Http2StreamHandler.Task}
 * pins the same Generation. {@link sjws_nettask.SjwsServer} pins the snapshot on arm/publish (no
 * re-reading lambda).
 *
 * <p>HTTP/1.1 attaches the body and calls {@code apply} on the same thread with no async gap — not
 * applicable. HTTP/3 still resolves {@code armed}/{@code handler} at {@code Task.run} and does not
 * refresh {@code captureFields} on reload; noted for a follow-up if routing+bodies flip together.
 */
class SnapshotDispatchHttp2Test {

    private static final TlsMaterial TLS = TlsMaterial.selfSigned("localhost");

    @TempDir
    Path root;

    private Http2Server server;

    @AfterEach
    void stop() {
        Http2StreamHandler.beforeDispatch = null;
        Http2StreamHandler.afterHeadersCapture = null;
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
    }

    @Test
    void dispatchStaysOnTheGenerationCapturedWithBodiesOff() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        var files = new StaticFileHandler(this.root);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gen2Called = new AtomicBoolean();
        var seenLength = new AtomicReference<Long>();

        Handler gen1 = request -> {
            seenLength.set(request.body().length());
            var h = ResponseHeaders.of();
            h.set("x-gen", "1");
            return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("g1".getBytes(StandardCharsets.UTF_8))));
        };
        Handler gen2 = request -> {
            gen2Called.set(true);
            seenLength.set(request.body().length());
            var h = ResponseHeaders.of();
            h.set("x-gen", "2");
            return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("g2".getBytes(StandardCharsets.UTF_8))));
        };

        this.server.armHandler(gen1, false);
        assertFalse(this.server.attachBodies());

        Http2StreamHandler.beforeDispatch = () -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        byte[] payload = "abcd".getBytes(StandardCharsets.UTF_8);
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1,
                    RawH2.block("POST", "/upload", "content-length", Integer.toString(payload.length)));
            h2.frame(RawH2.DATA, RawH2.END_STREAM, 1, payload);

            assertTrue(entered.await(5, TimeUnit.SECONDS), "Task must reach the dispatch gate");

            // Reload publishes a body-needing generation while this Task is still gated.
            this.server.armHandler(gen2, true);
            assertTrue(this.server.attachBodies());
            release.countDown();

            var fields = readHeaders(h2, 1);
            assertEquals("1", fields.get("x-gen"),
                    "dispatch must use the generation captured with bodies off");
            assertFalse(gen2Called.get(), "generation 2 would observe RequestBody.EMPTY");
            assertEquals(0L, seenLength.get());
        }
    }

    /**
     * Reload between headers capture and {@code start}: before the single-generation fix,
     * {@code toRequest} used {@code attachBodies()} while {@code start} re-read {@code generation()},
     * so a mid-gap arm mixed captureFields from gen A with body/handler from gen B.
     */
    @Test
    void captureAndStartUseTheSameGenerationAcrossAReloadGap() throws Exception {
        Files.writeString(this.root.resolve("a.txt"), "a");
        var files = new StaticFileHandler(this.root);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gen2Called = new AtomicBoolean();
        var seenFieldsHasContentLength = new AtomicReference<Boolean>();
        var seenBodyLength = new AtomicReference<Long>();

        Handler gen1 = request -> {
            seenFieldsHasContentLength.set(request.headers().fields().containsKey("content-length"));
            seenBodyLength.set(request.body().length());
            var h = ResponseHeaders.of();
            h.set("x-gen", "1");
            return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("g1".getBytes(StandardCharsets.UTF_8))));
        };
        Handler gen2 = request -> {
            gen2Called.set(true);
            seenFieldsHasContentLength.set(request.headers().fields().containsKey("content-length"));
            seenBodyLength.set(request.body().length());
            var h = ResponseHeaders.of();
            h.set("x-gen", "2");
            return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("g2".getBytes(StandardCharsets.UTF_8))));
        };

        this.server.armHandler(gen1, false);
        assertFalse(this.server.attachBodies());

        Http2StreamHandler.afterHeadersCapture = () -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        byte[] payload = "abcd".getBytes(StandardCharsets.UTF_8);
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            // Send HEADERS only first so the capture/start gap can reload before DATA arrives.
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1,
                    RawH2.block("POST", "/upload", "content-length", Integer.toString(payload.length)));

            assertTrue(entered.await(5, TimeUnit.SECONDS), "must reach afterHeadersCapture");

            // Reload publishes bodies-on while start has not yet consumed the captured generation.
            this.server.armHandler(gen2, true);
            assertTrue(this.server.attachBodies());
            release.countDown();

            h2.frame(RawH2.DATA, RawH2.END_STREAM, 1, payload);

            var fields = readHeaders(h2, 1);
            assertEquals("1", fields.get("x-gen"),
                    "start must keep the generation captured with toRequest (not a second volatile read)");
            assertFalse(gen2Called.get(), "gen2 would mean start re-read generation() after the reload");
            // gen1 attachBodies=false: no field map, no body — consistent pair.
            assertEquals(Boolean.FALSE, seenFieldsHasContentLength.get());
            assertEquals(0L, seenBodyLength.get());
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
}
