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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.Body;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.tls.TlsMaterial;

/**
 * Architecture N1 (#296): a paused unread body must not HOL sibling streams at the default 64 KiB
 * connection window. With the connection-window boost, a second stream can finish while the first
 * holds unread DATA.
 */
class ConnWindowHolTest {

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
    void siblingStreamProgressesWhileOneBodyIsPausedUnread() throws Exception {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var buffered = new AtomicLong();
        var files = new StaticFileHandler(this.root);
        var chain = RequestFilters.create()
                .add((request, next) -> {
                    if ("/hold".equals(request.target())) {
                        var body = (Http2RequestBody) request.body();
                        holding.countDown();
                        try {
                            // Wait long enough for the peer to fill windows while we do not read.
                            for (int i = 0; i < 40; i++) {
                                Thread.sleep(25);
                                buffered.accumulateAndGet(body.buffered(), Math::max);
                                if (release.getCount() == 0) {
                                    break;
                                }
                            }
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        // Drain so the connection can finish cleanly.
                        try {
                            var buf = new byte[8192];
                            while (request.body().read(buf, 0, buf.length) >= 0) {
                                // discard
                            }
                        } catch (java.io.IOException e) {
                            return StaticFileHandler.error(400, false);
                        }
                        return new Response(200, ResponseHeaders.of(), new Body.Bytes(ByteBuffer.wrap(new byte[0])));
                    }
                    if ("/ping".equals(request.target())) {
                        var h = ResponseHeaders.of();
                        h.set("x-pong", "1");
                        return new Response(200, h, new Body.Bytes(ByteBuffer.wrap("pong".getBytes(StandardCharsets.UTF_8))));
                    }
                    return next.proceed(request);
                })
                .build(files);
        var config = ServerConfig.builder(this.root).h2Enabled(true).h2Port(0).h2cPort(0)
                .writeTimeout(Duration.ofSeconds(30)).build();
        this.server = new Http2Server(config, files, TLS, Http2Server.Limits.DEFAULT).start();
        this.server.armHandler(chain);

        // > one default connection window so an unread hold can exhaust 64 KiB without the boost.
        byte[] big = new byte[96 * 1024];
        try (var h2 = new RawH2(this.server.h2cPort(), 65535)) {
            // Drain preface SETTINGS (+ connection WINDOW_UPDATE boost) so our send window grows.
            long boostDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean sawBoost = false;
            while (System.nanoTime() < boostDeadline && !sawBoost) {
                var f = h2.read();
                if (f == null) {
                    break;
                }
                if (f.type() == RawH2.WINDOW_UPDATE && f.stream() == 0) {
                    sawBoost = true;
                }
            }
            assertTrue(sawBoost, "server should WINDOW_UPDATE stream 0 after SETTINGS");

            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 1,
                    RawH2.block("POST", "/hold", "content-length", Integer.toString(big.length)));
            // HTTP/2 default max frame size is 16 KiB — chunk the body.
            int off = 0;
            while (off < big.length) {
                int n = Math.min(16 * 1024, big.length - off);
                byte[] chunk = java.util.Arrays.copyOfRange(big, off, off + n);
                off += n;
                int flags = off == big.length ? RawH2.END_STREAM : 0;
                h2.frame(RawH2.DATA, flags, 1, chunk);
            }

            assertTrue(holding.await(5, TimeUnit.SECONDS), "hold handler entered");
            Thread.sleep(300); // peer fills / pauses at HIGH

            long t0 = System.nanoTime();
            h2.frame(RawH2.HEADERS, RawH2.END_HEADERS, 3,
                    RawH2.block("POST", "/ping", "content-length", "4"));
            h2.frame(RawH2.DATA, RawH2.END_STREAM, 3, "ping".getBytes(StandardCharsets.UTF_8));

            String status = null;
            String pong = null;
            var decoder = new io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder();
            h2.socket.setSoTimeout(3000);
            for (RawH2.Frame f; (f = h2.read()) != null;) {
                if (f.stream() == 3 && f.type() == RawH2.HEADERS) {
                    var headers = decoder.decodeHeaders(3, io.netty.buffer.Unpooled.wrappedBuffer(f.payload()));
                    status = headers.status().toString();
                    if (headers.contains("x-pong")) {
                        pong = headers.get("x-pong").toString();
                    }
                }
                if (f.stream() == 3 && (f.flags() & RawH2.END_STREAM) != 0) {
                    break;
                }
                if (System.nanoTime() - t0 > TimeUnit.SECONDS.toNanos(3)) {
                    break;
                }
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            release.countDown();
            assertEquals("200", status, "sibling /ping must complete while /hold is unread (HOL at 64 KiB would stall)");
            assertEquals("1", pong);
            assertTrue(ms < 2500, "sibling should finish promptly, took " + ms + " ms");
        }
    }
}
