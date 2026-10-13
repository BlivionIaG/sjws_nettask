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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.security.cert.X509Certificate;
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
 * and a revoked node is refused. Without {@link CpTls} the listener is loopback-only plain HTTP
 * (snapshots are signed, so integrity does not depend on the transport). With it, the channel is
 * mutual TLS 1.3: the client certificate must name the node id in {@code X-Sjws-Node-Id} and is
 * the only credential (issuing the node certificate is the enrollment; the join token is unused). One virtual thread per
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
    private final AdminTls tls;
    private volatile Thread watcher;

    private WatchServer(HttpServer server, ExecutorService executor, CpStore store, LongSupplier nowMillis,
            AdminTls tls) {
        this.server = server;
        this.executor = executor;
        this.store = store;
        this.nowMillis = nowMillis;
        this.tls = tls;
    }

    /** Binds {@code 127.0.0.1:port} (0 for ephemeral), plain HTTP. */
    public static WatchServer start(CpStore store, int port, LongSupplier nowMillis) throws IOException {
        return start(store, new InetSocketAddress("127.0.0.1", port), nowMillis, null);
    }

    /**
     * Binds {@code address}. With {@code cpTls} null the listener is plain HTTP and must be on a
     * loopback address. With {@code cpTls} set it is TLS 1.3 with a required client certificate,
     * revocation-checked and reloadable ({@link #reloadTls()}, or the file watch in {@code cpTls}).
     */
    public static WatchServer start(CpStore store, InetSocketAddress address, LongSupplier nowMillis, CpTls cpTls)
            throws IOException {
        if (cpTls == null && !address.getAddress().isLoopbackAddress()) {
            throw new IOException("plain watch listener must be loopback; configure cpTls to bind " + address);
        }
        var tls = cpTls == null ? null : cpTls.server(store::recordSecurityEvent);
        var http = openServerSocket(address, tls);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(executor);
        var watch = new WatchServer(http, executor, store, nowMillis, tls);
        http.createContext("/cp/v1/watch", watch::handle);
        http.start();
        if (tls != null && cpTls.watchMillis() > 0) {
            long every = cpTls.watchMillis();
            watch.watcher = Thread.ofVirtual().name("cp-tls-watch").start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(every);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tls.reloadIfChanged();
                }
            });
        }
        return watch;
    }

    /**
     * Binds {@code address} with Security's listener TLS ({@link CpTls#serverContext}): the stable
     * {@link CpServerTls#context()} and its {@link CpServerTls#parameters()} on every accepted connection.
     * Reload is {@link CpServerTls#reloadTls()} (the CONTROL host's SIGHUP hook); this server only reads it.
     */
    public static WatchServer startMtls(CpStore store, InetSocketAddress address, LongSupplier nowMillis,
            CpServerTls serverTls) throws IOException {
        java.util.Objects.requireNonNull(serverTls, "serverTls");
        var https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls.context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(serverTls.parameters());
            }
        });
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        https.setExecutor(executor);
        var watch = new WatchServer(https, executor, store, nowMillis, null);
        https.createContext("/cp/v1/watch", watch::handle);
        https.start();
        return watch;
    }

    /**
     * The one place the listening socket is created: plain {@code HttpServer} when {@code tls} is
     * null, otherwise an {@code HttpsServer} requiring TLS 1.3 client authentication.
     */
    static HttpServer openServerSocket(InetSocketAddress address, AdminTls tls) throws IOException {
        if (tls == null) {
            return HttpServer.create(address, 0);
        }
        var https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls.context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(CpTls.parameters(getSSLContext(), true));
            }
        });
        return https;
    }

    /** Re-reads the cpTls files; null on success, otherwise why the old material was kept. */
    public String reloadTls() {
        return this.tls == null ? "cpTls is off" : this.tls.reload();
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
            String denied = authenticate(exchange, node, exchange.getRequestHeaders().getFirst(TOKEN_ID),
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
                if (this.store.isRevoked(node)) {
                    send(exchange, 403, "{\"error\":\"revoked\"}");
                    return;
                }
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

    private String authenticate(HttpExchange exchange, String node, String tokenId, String secret) {
        if (this.tls != null) {
            var peer = peer(exchange);
            if (node == null || peer == null || !CpTls.carries(peer, node)) {
                this.store.recordSecurityEvent(peer == null ? "unknown" : peer.getSubjectX500Principal().getName(),
                        "cp-tls.identity", String.valueOf(node), "denied: certificate does not name this node");
                return "identity";
            }
            // The verified, revocation-checked certificate is the only credential under mTLS;
            // token headers are ignored and authenticateWatch is not consulted.
            return this.store.isRevoked(node) ? "revoked" : null;
        }
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
            var result = this.store.authenticateWatch(node, tokenId, raw, this.nowMillis.getAsLong());
            if (!result.accepted()) {
                return result.error();
            }
        } finally {
            java.util.Arrays.fill(raw, (byte) 0);
        }
        return this.store.isRevoked(node) ? "revoked" : null;
    }

    private static X509Certificate peer(HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange https) || https.getSSLSession() == null) {
            return null;
        }
        try {
            var chain = https.getSSLSession().getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate x ? x : null;
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            return null;
        }
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
        var w = this.watcher;
        if (w != null) {
            w.interrupt();
        }
        this.server.stop(0);
        this.executor.shutdownNow();
    }
}
