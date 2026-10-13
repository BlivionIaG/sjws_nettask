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
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;
import sjws_nettask.controlplane.Rbac;
import sjws_nettask.tls.TlsMaterial;

/**
 * Loopback admin listener. Outside tests it starts only with mTLS: a server certificate, its key,
 * and a trust bundle limited to the admin CA. Client authentication is required. Identity is the
 * verified client certificate, mapped by {@link Rbac}. {@code Authorization} is ignored. A
 * connection without a certificate is unauthorized. Certificates, key, CA bundle and an optional
 * revocation list reload without a restart ({@link AdminTls}). Plain HTTP is {@link #startPlainForTests}.
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final CpAdmin admin;
    private final AdminTls tls;
    private volatile Thread watcher;

    private AdminServer(HttpServer server, CpAdmin admin, AdminTls tls) {
        this.server = server;
        this.admin = admin;
        this.tls = tls;
    }

    /**
     * mTLS admin listener. Refuses to bind when the certificate, the key, or the admin-CA trust
     * bundle is missing or empty. Trust managers are built only from {@code trustBundle}. No
     * revocation list and no file watch; {@link #reloadTls()} still works.
     */
    public static AdminServer start(CpStore store, long nowMillis, long ttlMillis, Path certPem, Path keyPem,
            String keyPassword, Path trustBundle) throws IOException {
        return start(store, nowMillis, ttlMillis, certPem, keyPem, keyPassword, trustBundle, null, 0);
    }

    /**
     * mTLS admin listener with optional revocation and rotation. See {@link AdminTls} for the
     * revocation file format. Revoked client certificates are refused at the handshake and each
     * refusal is written to the CP audit log.
     *
     * @param revocations CRL or denylist file, or null for none
     * @param watchMillis when positive, check the files this often and reload when they change;
     *        0 disables the watch ({@link #reloadTls()} remains available)
     */
    public static AdminServer start(CpStore store, long nowMillis, long ttlMillis, Path certPem, Path keyPem,
            String keyPassword, Path trustBundle, Path revocations, long watchMillis) throws IOException {
        var tls = AdminTls.open(certPem, keyPem, keyPassword, trustBundle, revocations, store::recordSecurityEvent);
        var https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls.context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setNeedClientAuth(true);
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                ssl.setProtocols(new String[] {"TLSv1.3"});
                params.setSSLParameters(ssl);
            }
        });
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis), tls);
        https.createContext("/", bound::handle);
        https.start();
        if (watchMillis > 0) {
            bound.watcher = Thread.ofVirtual().name("admin-tls-watch").start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(watchMillis);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tls.reloadIfChanged();
                }
            });
        }
        return bound;
    }

    /**
     * Re-reads the certificate, key, CA bundle and revocation list (for an operator trigger such
     * as SIGHUP). New handshakes use the new material; on failure the old material stays.
     *
     * @return null on success, otherwise the reason
     */
    public String reloadTls() {
        return tls == null ? "plain listener has no TLS material" : tls.reload();
    }

    /**
     * mTLS admin listener on {@code address} with Security's listener TLS: {@link CpServerTls#context()} and
     * {@link CpServerTls#parameters()} on every connection. Reload goes through {@link CpServerTls#reloadTls()}.
     */
    public static AdminServer startMtls(CpStore store, InetSocketAddress address, long nowMillis, long ttlMillis,
            CpServerTls serverTls) throws IOException {
        java.util.Objects.requireNonNull(serverTls, "serverTls");
        var https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls.context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(serverTls.parameters());
            }
        });
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis), null);
        https.createContext("/", bound::handle);
        https.start();
        return bound;
    }

    /** Plain HTTP, for tests that exercise routes without a certificate. Production code uses {@link #start}. */
    public static AdminServer startPlainForTests(CpStore store, long nowMillis, long ttlMillis) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bound = new AdminServer(http, new CpAdmin(store, nowMillis, ttlMillis), null);
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
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
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
        var w = watcher;
        if (w != null) {
            w.interrupt();
        }
        server.stop(0);
    }
}
