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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Loopback admin listener. The single-node lab accepts the bootstrap bearer token as
 * {@link Role#ADMIN} until an admin SAN is enrolled. After that the token is rejected.
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final CpAdmin admin;
    private final CpStore store;
    private final byte[] tokenHash;

    private AdminServer(HttpServer server, CpAdmin admin, CpStore store, byte[] tokenHash) {
        this.server = server;
        this.admin = admin;
        this.store = store;
        this.tokenHash = tokenHash;
    }

    public static AdminServer start(CpStore store, long nowMillis, long ttlMillis) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var admin = new CpAdmin(store, nowMillis, ttlMillis);
        var bound = new AdminServer(http, admin, store, sha256(store.bootstrapToken()));
        http.createContext("/", bound::handle);
        http.start();
        return bound;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var role = role(exchange.getRequestHeaders().getFirst("Authorization"));
        var query = exchange.getRequestURI().getRawQuery();
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var outcome = admin.handle(role, exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query, body);
        var bytes = outcome.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(outcome.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private Role role(String authorization) {
        if (store.adminEnrolled() || authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        var presented = sha256(authorization.substring("Bearer ".length()).trim());
        if (!MessageDigest.isEqual(presented, tokenHash)) {
            return null;
        }
        return Role.ADMIN;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
