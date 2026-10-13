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
package sjws_nettask.cp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Data-node watch listener (design §8), first increment: long-poll {@code GET /cp/v1/watch?after=N}
 * answers with the trust bundle and the activated full snapshot ({@link WatchWire}) once its version
 * is above {@code N}, or 204 after {@code waitMs}. {@code POST /cp/v1/watch/ack?version=N} records the
 * node's applied version. Every request presents the node's join token (idempotent within its TTL)
 * and a revoked node is refused. Loopback only: snapshots are signed, so integrity does not depend
 * on the transport, but the mTLS node identity of §12 is still to come. One virtual thread per
 * request, so a long poll does not hold up another node. Not on any content request path.
 */
public final class WatchServer implements AutoCloseable {

    public static final String NODE = "X-Sjws-Node-Id";
    public static final String TOKEN_ID = "X-Sjws-Token-Id";
    public static final String SECRET = "X-Sjws-Join-Secret";
    static final long MAX_WAIT_MS = 30_000L;

    private final HttpServer server;
    private final ExecutorService executor;
    private final CpStore store;
    private final LongSupplier nowMillis;

    private WatchServer(HttpServer server, ExecutorService executor, CpStore store, LongSupplier nowMillis) {
        this.server = server;
        this.executor = executor;
        this.store = store;
        this.nowMillis = nowMillis;
    }

    /** Binds {@code 127.0.0.1:port} (0 for ephemeral). */
    public static WatchServer start(CpStore store, int port, LongSupplier nowMillis) throws IOException {
        var http = openServerSocket(new InetSocketAddress("127.0.0.1", port));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(executor);
        var watch = new WatchServer(http, executor, store, nowMillis);
        http.createContext("/cp/v1/watch", watch::handle);
        http.start();
        return watch;
    }

    /**
     * The one place the listening socket is created. Plain HTTP today; the opt-in mTLS of the
     * node-to-CP channel swaps in an {@code HttpsServer} with its configurator here and nowhere else.
     */
    static HttpServer openServerSocket(InetSocketAddress address) throws IOException {
        return HttpServer.create(address, 0);
    }

    public int port() {
        return this.server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            var node = exchange.getRequestHeaders().getFirst(NODE);
            String denied = authenticate(node, exchange.getRequestHeaders().getFirst(TOKEN_ID),
                    exchange.getRequestHeaders().getFirst(SECRET));
            if (denied != null) {
                send(exchange, denied.equals("revoked") ? 403 : 401, "{\"error\":\"" + denied + "\"}");
                return;
            }
            var query = exchange.getRequestURI().getRawQuery();
            if ("GET".equals(method) && "/cp/v1/watch".equals(path)) {
                long after = param(query, "after", 0L);
                long wait = Math.min(MAX_WAIT_MS, param(query, "waitMs", MAX_WAIT_MS));
                if (after < 0 || wait < 0) {
                    send(exchange, 400, "{\"error\":\"query\"}");
                    return;
                }
                var snapshot = this.store.awaitActivated(after, wait);
                if (snapshot == null) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                send(exchange, 200, WatchWire.encode(this.store.keys(), snapshot));
                return;
            }
            if ("POST".equals(method) && "/cp/v1/watch/ack".equals(path)) {
                long version = param(query, "version", -1L);
                if (version < 0) {
                    send(exchange, 400, "{\"error\":\"version\"}");
                    return;
                }
                this.store.reportNode(node, Map.of(), "READY", version);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            send(exchange, 404, "{\"error\":\"route\"}");
        }
    }

    private String authenticate(String node, String tokenId, String secret) {
        if (node == null || tokenId == null || secret == null) {
            return "token";
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            return "token";
        }
        try {
            var result = this.store.presentJoin(node, tokenId, raw, this.nowMillis.getAsLong());
            if (!result.accepted()) {
                return result.error();
            }
        } finally {
            java.util.Arrays.fill(raw, (byte) 0);
        }
        return this.store.isRevoked(node) ? "revoked" : null;
    }

    static long param(String query, String name, long otherwise) {
        if (query == null) {
            return otherwise;
        }
        for (var part : query.split("&")) {
            if (part.startsWith(name + "=")) {
                try {
                    return Long.parseLong(part.substring(name.length() + 1));
                } catch (NumberFormatException e) {
                    return -1L;
                }
            }
        }
        return otherwise;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        this.server.stop(0);
        this.executor.shutdownNow();
    }
}
