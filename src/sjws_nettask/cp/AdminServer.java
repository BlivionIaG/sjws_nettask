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
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;
import sjws_nettask.controlplane.Rbac;

/**
 * Loopback admin listener. Identity is the verified client certificate. {@code Authorization}
 * is ignored. A connection without a certificate is unauthorized.
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final CpAdmin admin;

    private AdminServer(HttpServer server, CpAdmin admin) {
        this.server = server;
        this.admin = admin;
    }

    public static AdminServer start(CpStore store, long nowMillis, long ttlMillis) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bound = new AdminServer(http, new CpAdmin(store, nowMillis, ttlMillis));
        http.createContext("/", bound::handle);
        http.start();
        return bound;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var outcome = admin.handle(credential(exchange), exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        var bytes = outcome.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(outcome.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** The verified peer certificate, or null when the connection did not present one. */
    static Rbac.Credential credential(HttpExchange exchange) {
        if (!(exchange instanceof com.sun.net.httpserver.HttpsExchange https)) {
            return null;
        }
        var session = https.getSSLSession();
        if (session == null) {
            return null;
        }
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate cert)) {
                return null;
            }
            var sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (var san : sans) {
                    if (san.size() >= 2 && Integer.valueOf(6).equals(san.get(0)) && san.get(1) instanceof String uri
                            && uri.startsWith("urn:")) {
                        return new Rbac.ClientCert(uri);
                    }
                }
            }
            return new Rbac.ClientCert(cert.getSubjectX500Principal().getName(X500Principal.CANONICAL));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
