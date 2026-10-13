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
 * connection without a certificate is unauthorized. Plain HTTP is {@link #startPlainForTests}.
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final CpAdmin admin;

    private AdminServer(HttpServer server, CpAdmin admin) {
        this.server = server;
        this.admin = admin;
    }

    /**
     * mTLS admin listener. Refuses to bind when the certificate, the key, or the admin-CA trust
     * bundle is missing or empty. Trust managers are built only from {@code trustBundle}.
     */
    public static AdminServer start(CpStore store, long nowMillis, long ttlMillis, Path certPem, Path keyPem,
            String keyPassword, Path trustBundle) throws IOException {
        if (certPem == null || keyPem == null || trustBundle == null) {
            throw new IOException("admin listener requires a server certificate, a key, and an admin CA trust bundle");
        }
        var material = TlsMaterial.fromPem(certPem, keyPem, keyPassword);
        var trusted = trustCertificates(trustBundle);
        if (trusted.length == 0) {
            throw new IOException("admin CA trust bundle " + trustBundle + " contains no certificates");
        }
        SSLContext tls;
        try {
            var storeKeys = KeyStore.getInstance("PKCS12");
            storeKeys.load(null, null);
            for (int i = 0; i < trusted.length; i++) {
                storeKeys.setCertificateEntry("ca" + i, trusted[i]);
            }
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(storeKeys);
            tls = SSLContext.getInstance("TLSv1.3");
            tls.init(material.keyManagerFactory().getKeyManagers(), tmf.getTrustManagers(), null);
        } catch (GeneralSecurityException e) {
            throw new IOException("admin mTLS material rejected: " + e.getMessage(), e);
        }
        var https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override
            public void configure(HttpsParameters params) {
                params.setNeedClientAuth(true);
                var ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(true);
                ssl.setProtocols(new String[] {"TLSv1.3"});
                params.setSSLParameters(ssl);
            }
        });
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis));
        https.createContext("/", bound::handle);
        https.start();
        return bound;
    }

    /** Plain HTTP, for tests that exercise routes without a certificate. Production code uses {@link #start}. */
    public static AdminServer startPlainForTests(CpStore store, long nowMillis, long ttlMillis) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bound = new AdminServer(http, new CpAdmin(store, nowMillis, ttlMillis));
        http.createContext("/", bound::handle);
        http.start();
        return bound;
    }

    private static X509Certificate[] trustCertificates(Path trustBundle) throws IOException {
        if (!Files.isRegularFile(trustBundle)) {
            throw new IOException("admin CA trust bundle is missing: " + trustBundle);
        }
        try (var in = Files.newInputStream(trustBundle)) {
            var parsed = CertificateFactory.getInstance("X.509").generateCertificates(in);
            var certs = new ArrayList<X509Certificate>();
            for (var cert : parsed) {
                if (cert instanceof X509Certificate x509) {
                    certs.add(x509);
                }
            }
            return certs.toArray(X509Certificate[]::new);
        } catch (CertificateException e) {
            throw new IOException("admin CA trust bundle rejected: " + e.getMessage(), e);
        }
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
        server.stop(0);
    }
}
