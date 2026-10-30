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

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.SjwsServer;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.core.FilterDocument;
import sjws_nettask.core.FilterScope;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/**
 * Codex P1 on #302: publish must keep chainControl filters and armMetrics on the h2 generation.
 * Codex P2b: a request pinned to the old generation across reload still completes; old pools close
 * after it releases.
 */
class ArmedFiltersSurviveReloadTest {

    @TempDir
    Path dir;

    private SjwsServer server;
    private HttpServer upstream;

    @AfterEach
    void stop() {
        Http2StreamHandler.beforeDispatch = null;
        if (this.server != null) {
            this.server.close();
            this.server = null;
        }
        if (this.upstream != null) {
            this.upstream.stop(0);
            this.upstream = null;
        }
    }

    @Test
    void apiKeyAndMetricsSurviveReloadOverH2() throws Exception {
        var www = Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(www.resolve("hello.txt"), "hello");
        var yaml = this.dir.resolve("sjws.yaml");
        writeStatic(yaml, www);

        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded);

        var filters = RequestFilters.create();
        GatewayFilters.install(filters);
        var control = this.server.chainControl(filters, Set.of());
        control.apply(FilterDocument.of(1, List.of(
                FilterDocument.FilterEntry.of("api-key", 0, Map.of("keys", "secret", "header", "x-api-key"),
                        FilterScope.all()))));
        this.server.armMetrics();

        int h2c = ((Http2Server) this.server.h2()).h2cPort();
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5)).build();

        assertEquals(401, get(client, h2c, "/hello.txt", null).statusCode(), "api-key before reload");
        var ok = get(client, h2c, "/hello.txt", "secret");
        assertEquals(200, ok.statusCode());
        assertEquals("hello", ok.body());
        assertEquals(HttpClient.Version.HTTP_2, ok.version());
        long before = this.server.metricsSnapshot().requests();
        assertTrue(before >= 2, "metrics counted pre-reload requests, got " + before);

        var www2 = Files.createDirectories(this.dir.resolve("www2"));
        Files.writeString(www2.resolve("hello.txt"), "hello2");
        // New root forces a new ConfigSnapshot so publish() runs (equal-hash noop would skip it).
        writeStatic(yaml, www2);
        assertTrue(this.server.reload().applied());

        assertEquals(401, get(client, h2c, "/hello.txt", null).statusCode(),
                "api-key must still be enforced on h2 after reload (P1)");
        var after = get(client, h2c, "/hello.txt", "secret");
        assertEquals(200, after.statusCode());
        assertEquals("hello2", after.body());
        long requests = this.server.metricsSnapshot().requests();
        assertTrue(requests > before, "metrics must keep counting after reload, before=" + before + " after=" + requests);
    }

    @Test
    void pinnedProxyRequestSurvivesReloadUntilItFinishes() throws Exception {
        // Gate BEFORE the pinned handler runs so UpstreamPool.acquire happens after reload.
        // Without P2b, publish closes the old pools and acquire fails with 502.
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var upstreamEntered = new AtomicInteger();
        this.upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.upstream.createContext("/hold", exchange -> {
            upstreamEntered.incrementAndGet();
            byte[] body = "held-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        this.upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.upstream.start();
        String base = "http://127.0.0.1:" + this.upstream.getAddress().getPort();

        var www = Files.createDirectories(this.dir.resolve("www"));
        Files.writeString(www.resolve("index.html"), "static");
        var yaml = this.dir.resolve("sjws.yaml");
        writeProxy(yaml, www, base);

        var loaded = ConfigLoader.load(new String[] {
                "--config=" + yaml, "--h2", "--h2-port=0", "--h2c-port=0", "--no-h3"}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        this.server = SjwsServer.start(loaded);
        int h2c = ((Http2Server) this.server.h2()).h2cPort();
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5)).build();

        Http2StreamHandler.beforeDispatch = () -> {
            entered.countDown();
            try {
                assertTrue(release.await(15, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            var future = client.sendAsync(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h2c + "/api/hold"))
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Task must reach the dispatch gate");

            var www2 = Files.createDirectories(this.dir.resolve("www2"));
            Files.writeString(www2.resolve("index.html"), "static2");
            writeProxy(yaml, www2, base);
            assertTrue(this.server.reload().applied());

            release.countDown();
            var response = future.get(15, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(),
                    "pinned generation must still acquire on its upstream pools after reload");
            assertEquals("held-ok", response.body());
            assertEquals(1, upstreamEntered.get());
        } finally {
            Http2StreamHandler.beforeDispatch = null;
        }
    }

    private static void writeStatic(Path yaml, Path www) throws Exception {
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                """.formatted(www.toAbsolutePath()));
    }

    private static void writeProxy(Path yaml, Path www, String upstream) throws Exception {
        Files.writeString(yaml, """
                server:
                  port: 0
                  root: "%s"
                routing:
                  enabled: true
                  upstreams:
                    api: { url: "%s", connect_timeout: 5s, response_timeout: 30s, max_body: 16m }
                  routes:
                    - { prefix: /api, rewrite: /, proxy: api }
                """.formatted(www.toAbsolutePath(), upstream));
    }

    private static HttpResponse<String> get(HttpClient client, int h2c, String path, String apiKey)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + h2c + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (apiKey != null) {
            b.header("x-api-key", apiKey);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
