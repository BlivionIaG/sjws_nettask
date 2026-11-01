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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import sjws_nettask.controlplane.Rbac;

/**
 * Loopback admin listener. Outside tests it starts only with mTLS: a server certificate, its key,
 * and a trust bundle limited to the admin CA. Client authentication is required. Identity is the
 * verified client certificate, mapped by {@link Rbac}. {@code Authorization} is ignored. A
 * connection without a certificate is unauthorized. Certificates, key, CA bundle and an optional
 * revocation list reload without a restart ({@link AdminTls}). After a successful reload, live
 * admin connections whose client certificate no longer passes policy are dropped (same idea as
 * {@link CpPeerSockets#dropPeersFailingPolicy}). Plain HTTP is {@link #startPlainForTests}.
 */
public final class AdminServer implements AutoCloseable {

    private final HttpServer server;
    private final CpAdmin admin;
    private final AdminTls tls;
    private final CpServerTls serverTls;
    /**
     * Live HTTPS sessions: peer chain plus a best-effort abort handle for the underlying
     * connection (so a reload can close keep-alive sockets, not only invalidate the session).
     */
    private final ConcurrentHashMap<SSLSession, Live> live = new ConcurrentHashMap<>();
    private final AtomicLong policyDropped = new AtomicLong();
    private volatile Thread watcher;

    private record Live(X509Certificate[] chain, AutoCloseable abort) {
    }

    private AdminServer(HttpServer server, CpAdmin admin, AdminTls tls, CpServerTls serverTls) {
        this.server = server;
        this.admin = admin;
        this.tls = tls;
        this.serverTls = serverTls;
        if (tls != null) {
            tls.afterReload(this::dropConnectionsFailingPolicy);
        }
        if (serverTls != null) {
            serverTls.afterReload(this::dropConnectionsFailingPolicy);
        }
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
     * refusal is written to the CP audit log. A successful reload also drops established connections
     * whose client certificate fails the new policy.
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
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis), tls, null);
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
     * as SIGHUP). New handshakes use the new material; on failure the old material stays. On
     * success, established admin connections that fail the new policy are dropped.
     *
     * @return null on success, otherwise the reason
     */
    public String reloadTls() {
        if (tls == null) {
            return "plain listener has no TLS material";
        }
        return tls.reload();
    }

    /**
     * mTLS admin listener on {@code address} with Security's listener TLS: {@link CpServerTls#context()} and
     * {@link CpServerTls#parameters()} on every connection. Reload goes through {@link CpServerTls#reloadTls()}.
     */
    public static AdminServer startMtls(CpStore store, InetSocketAddress address, long nowMillis, long ttlMillis,
            CpServerTls serverTls) throws IOException {
        Objects.requireNonNull(serverTls, "serverTls");
        var https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls.context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(serverTls.parameters());
            }
        });
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis), null, serverTls);
        https.createContext("/", bound::handle);
        https.start();
        return bound;
    }

    /** Plain HTTP, for tests that exercise routes without a certificate. Production code uses {@link #start}. */
    public static AdminServer startPlainForTests(CpStore store, long nowMillis, long ttlMillis) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bound = new AdminServer(http, new CpAdmin(store, nowMillis, ttlMillis), null, null);
        http.createContext("/", bound::handle);
        http.start();
        return bound;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** How many live admin sessions were dropped because they failed policy after a reload. */
    long policyDropped() {
        return policyDropped.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        HttpsExchange https = exchange instanceof HttpsExchange h ? h : null;
        X509Certificate[] chain = null;
        SSLSession session = null;
        if (https != null) {
            pruneDeadSessions();
            session = https.getSSLSession();
            chain = session == null ? null : peerChain(session);
            if (chain != null) {
                try {
                    revalidate(chain);
                } catch (CertificateException | IOException e) {
                    policyDropped.incrementAndGet();
                    live.remove(session);
                    try {
                        session.invalidate();
                    } catch (RuntimeException ignored) {
                        // best effort
                    }
                    exchange.getRequestBody().readAllBytes();
                    write(exchange, CpStore.Outcome.error(401, "tls"), true);
                    return;
                }
            }
        }
        var outcome = admin.handle(credential(exchange), exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        if (https != null && session != null && chain != null) {
            live.put(session, new Live(chain, null));
        }
        write(exchange, outcome);
        // Fill the abort handle only after the response is written; probing the connection
        // mid-request via reflection breaks the sun httpserver exchange.
        if (https != null && session != null && chain != null && session.isValid()) {
            live.put(session, new Live(chain, connectionAbort(https)));
        }
    }

    private static void write(HttpExchange exchange, CpStore.Outcome outcome) throws IOException {
        write(exchange, outcome, false);
    }

    private static void write(HttpExchange exchange, CpStore.Outcome outcome, boolean closeConnection)
            throws IOException {
        var bytes = outcome.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (closeConnection) {
            exchange.getResponseHeaders().set("Connection", "close");
        }
        exchange.sendResponseHeaders(outcome.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * After reload: re-check every remembered peer chain against the new material. {@link AdminTls#reload()}
     * may already have invalidated SSL sessions before this runs, so we key off the stored chain, not
     * {@link SSLSession#isValid()}.
     */
    void dropConnectionsFailingPolicy() {
        for (var entry : List.copyOf(live.entrySet())) {
            var session = entry.getKey();
            var peer = entry.getValue();
            try {
                revalidate(peer.chain());
                if (!session.isValid()) {
                    // Cert still OK; drop the stale map entry only (connection may stay for a new handshake).
                    live.remove(session, peer);
                }
            } catch (CertificateException | IOException | RuntimeException failed) {
                policyDropped.incrementAndGet();
                live.remove(session, peer);
                abortQuietly(peer);
                try {
                    if (session.isValid()) {
                        session.invalidate();
                    }
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
        pruneDeadSessions();
    }

    /** Drop entries whose TLS session is already invalid so the map cannot grow without bound. */
    private void pruneDeadSessions() {
        for (var entry : List.copyOf(live.entrySet())) {
            if (!entry.getKey().isValid()) {
                if (live.remove(entry.getKey(), entry.getValue())) {
                    abortQuietly(entry.getValue());
                }
            }
        }
    }

    private void revalidate(X509Certificate[] chain) throws CertificateException, IOException {
        if (tls != null) {
            tls.revalidatePeer(chain, true);
            return;
        }
        if (serverTls != null) {
            serverTls.revalidatePeer(chain, true);
            return;
        }
        // Plain listener: nothing to revalidate.
    }

    private static void abortQuietly(Live live) {
        if (live == null || live.abort() == null) {
            return;
        }
        try {
            live.abort().close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    /**
     * Best-effort handle that closes the sun.net.httpserver connection behind an exchange.
     * Public HTTPS APIs do not expose the socket; without this, invalidate alone leaves keep-alive
     * TCP up until the next request.
     */
    static AutoCloseable connectionAbort(HttpExchange exchange) {
        try {
            var implField = exchange.getClass().getDeclaredField("impl");
            implField.setAccessible(true);
            var exchangeImpl = implField.get(exchange);
            var getConnection = exchangeImpl.getClass().getDeclaredMethod("getConnection");
            getConnection.setAccessible(true);
            var connection = getConnection.invoke(exchangeImpl);
            var close = connection.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            return () -> {
                try {
                    close.invoke(connection);
                } catch (ReflectiveOperationException e) {
                    throw new IOException(e);
                }
            };
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }


    private static X509Certificate[] peerChain(SSLSession session) {
        try {
            Certificate[] presented = session.getPeerCertificates();
            if (presented == null || presented.length == 0) {
                return null;
            }
            var chain = new X509Certificate[presented.length];
            for (int i = 0; i < presented.length; i++) {
                if (!(presented[i] instanceof X509Certificate x509)) {
                    return null;
                }
                chain[i] = x509;
            }
            return chain;
        } catch (Exception e) {
            return null;
        }
    }

    /** The verified peer certificate, or null when the connection did not present one. */
    static Rbac.Credential credential(HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange https)) {
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
