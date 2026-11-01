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
import java.util.Set;
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

    /** Bound retries when a reload bumps {@link #tlsEpoch} between revalidate and put (#321 / #442). */
    static final int REGISTER_LIVE_MAX_ATTEMPTS = 32;
    private static final System.Logger LOG = System.getLogger(AdminServer.class.getName());

    private final HttpServer server;
    private final CpAdmin admin;
    private final AdminTls tls;
    private final CpServerTls serverTls;
    private final Set<String> allowedOrigins;
    /**
     * Live HTTPS sessions: peer chain plus an abort handle for the underlying sun httpserver
     * connection (so a reload can close keep-alive sockets, not only invalidate the session).
     * Requires {@code --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED}.
     */
    private final ConcurrentHashMap<SSLSession, Live> live = new ConcurrentHashMap<>();
    private final AtomicLong policyDropped = new AtomicLong();
    private final AtomicLong abortUnavailable = new AtomicLong();
    /** Bumped at the start of every {@link #dropConnectionsFailingPolicy} so registration cannot escape a concurrent reload. */
    private final AtomicLong tlsEpoch = new AtomicLong();
    private volatile Thread watcher;
    /** Test hook: runs after revalidate and before {@code live.put} inside {@link #registerLive}. */
    volatile Runnable betweenValidateAndRegister = () -> {
    };
    /** Test hook: runs after register and before {@code admin.handle}. */
    volatile Runnable afterRegisterBeforeHandle = () -> {
    };

    /**
     * Mutable so a reload can mark {@link #dropped} and a late {@link #abort} fill still sees the drop.
     */
    static final class Live {
        final X509Certificate[] chain;
        volatile ConnAbort abort;
        volatile boolean dropped;

        Live(X509Certificate[] chain, ConnAbort abort) {
            this.chain = chain;
            this.abort = abort;
        }

        X509Certificate[] chain() {
            return chain;
        }

        ConnAbort abort() {
            return abort;
        }
    }

    private AdminServer(HttpServer server, CpAdmin admin, AdminTls tls, CpServerTls serverTls,
            Set<String> allowedOrigins) {
        this.server = server;
        this.admin = admin;
        this.tls = tls;
        this.serverTls = serverTls;
        this.allowedOrigins = Set.copyOf(Objects.requireNonNullElse(allowedOrigins, Set.of()));
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
        return start(store, nowMillis, ttlMillis, certPem, keyPem, keyPassword, trustBundle, null, 0, Set.of());
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
        return start(store, nowMillis, ttlMillis, certPem, keyPem, keyPassword, trustBundle, revocations, watchMillis,
                Set.of());
    }

    /** File-based mTLS admin listener with an explicit {@code Origin} allowlist for mutating methods. */
    public static AdminServer start(CpStore store, long nowMillis, long ttlMillis, Path certPem, Path keyPem,
            String keyPassword, Path trustBundle, Path revocations, long watchMillis, Set<String> allowedOrigins)
            throws IOException {
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
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis), tls, null, allowedOrigins);
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
        return startMtls(store, address, nowMillis, ttlMillis, serverTls, Set.of());
    }

    /** mTLS admin listener with an explicit {@code Origin} allowlist for mutating methods. */
    public static AdminServer startMtls(CpStore store, InetSocketAddress address, long nowMillis, long ttlMillis,
            CpServerTls serverTls, Set<String> allowedOrigins) throws IOException {
        Objects.requireNonNull(serverTls, "serverTls");
        var https = HttpsServer.create(address, 0);
        https.setHttpsConfigurator(new HttpsConfigurator(serverTls.context()) {
            @Override
            public void configure(HttpsParameters params) {
                params.setSSLParameters(serverTls.parameters());
            }
        });
        var bound = new AdminServer(https, new CpAdmin(store, nowMillis, ttlMillis), null, serverTls, allowedOrigins);
        https.createContext("/", bound::handle);
        https.start();
        return bound;
    }

    /** Plain HTTP, for tests that exercise routes without a certificate. Production code uses {@link #start}. */
    public static AdminServer startPlainForTests(CpStore store, long nowMillis, long ttlMillis) throws IOException {
        return startPlainForTests(store, nowMillis, ttlMillis, Set.of());
    }

    /**
     * Plain HTTP with an explicit {@code Origin} allowlist for mutating methods. Empty means any
     * present {@code Origin} is refused (CLI clients that omit the header are unaffected).
     */
    public static AdminServer startPlainForTests(CpStore store, long nowMillis, long ttlMillis,
            Set<String> allowedOrigins) throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bound = new AdminServer(http, new CpAdmin(store, nowMillis, ttlMillis), null, null, allowedOrigins);
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

    /** How many HTTPS sessions are retained for reload drop; for tests. */
    int liveSize() {
        return live.size();
    }

    /** How many times reflective abort capture failed; for tests / ops. */
    long abortUnavailable() {
        return abortUnavailable.get();
    }

    /** Test/ops: drop closed or invalid sessions without a policy reload. */
    void pruneDeadSessionsForTest() {
        pruneDeadSessions();
    }

    /** Test-only: bump the TLS epoch without a material reload. */
    void bumpTlsEpochForTest() {
        tlsEpoch.incrementAndGet();
    }

    private void handle(HttpExchange exchange) throws IOException {
        var method = exchange.getRequestMethod();
        if (isMutation(method)) {
            var refused = refuseMutation(exchange);
            if (refused != null) {
                exchange.getRequestBody().readAllBytes();
                write(exchange, refused, true);
                return;
            }
        }
        if (!(exchange instanceof HttpsExchange https)) {
            var outcome = admin.handle(credential(exchange), method, exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            write(exchange, outcome, wantsClose(exchange));
            return;
        }
        pruneDeadSessions();
        var session = https.getSSLSession();
        var chain = session == null ? null : peerChain(session);
        // Reflective read is safe mid-request; close() is not. Prefer capturing before dispatch so a
        // concurrent reload can abort an in-flight exchange.
        var abort = connectionAbort(https);
        Live registered = null;
        if (chain != null) {
            try {
                registered = registerLive(session, chain, abort);
            } catch (CertificateException | IOException e) {
                policyDropped.incrementAndGet();
                live.remove(session);
                invalidateQuietly(session);
                abortQuietly(abort);
                exchange.getRequestBody().readAllBytes();
                write(exchange, CpStore.Outcome.error(401, "tls"), true);
                return;
            }
        }
        var mid = afterRegisterBeforeHandle;
        afterRegisterBeforeHandle = () -> {
        };
        mid.run();
        if (registered != null && registered.dropped) {
            exchange.getRequestBody().readAllBytes();
            abortQuietly(registered.abort());
            live.remove(session);
            write(exchange, CpStore.Outcome.error(401, "tls"), true);
            return;
        }
        var outcome = admin.handle(credential(exchange), method, exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
        if (registered != null && registered.dropped) {
            if (registered.abort() == null && abort != null) {
                registered.abort = abort;
            }
            abortQuietly(registered.abort());
            live.remove(session);
            write(exchange, outcome, true);
            return;
        }
        if (registered != null && registered.abort() == null && abort != null) {
            registered.abort = abort;
        }
        if (abort == null && session != null) {
            long n = abortUnavailable.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING,
                    "admin-tls: connection abort unavailable (need --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED); keep-alive drop degraded ({0} so far)",
                    n);
        }
        boolean closeConnection = wantsClose(exchange) || abort == null || (registered != null && registered.dropped);
        write(exchange, outcome, closeConnection);
        if (session != null && closeConnection) {
            live.remove(session);
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

    private static boolean wantsClose(HttpExchange exchange) {
        var value = exchange.getRequestHeaders().getFirst("Connection");
        return value != null && value.equalsIgnoreCase("close");
    }

    /** POST, PUT, PATCH and DELETE. GET and HEAD are unchanged. */
    public static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    /**
     * A present {@code Origin} must be in {@link #allowedOrigins} (empty by default). The header is
     * trimmed; blank-after-trim is treated as present and refused. Mutations must send
     * {@code Content-Type: application/json} (optional parameters allowed). Match is exact on the
     * trimmed value (scheme, host, port, case).
     */
    CpStore.Outcome refuseMutation(HttpExchange exchange) {
        var raw = exchange.getRequestHeaders().getFirst("Origin");
        if (raw != null) {
            var origin = raw.trim();
            if (origin.isEmpty() || !allowedOrigins.contains(origin)) {
                return CpStore.Outcome.error(403, "origin");
            }
        }
        if (!isJsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            return CpStore.Outcome.error(415, "content-type");
        }
        return null;
    }

    /** {@code application/json} with optional parameters such as {@code charset=utf-8}. */
    public static boolean isJsonContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        int semi = contentType.indexOf(';');
        var type = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
        return type.equalsIgnoreCase("application/json");
    }

    /**
     * Revalidate under the current TLS epoch, then put into {@link #live} <em>before</em> the handler runs.
     * If a reload bumps the epoch between the check and the put, retry so the connection cannot escape both
     * the new-policy check and the reload sweep (#321 / #442). Exhausting the budget fails closed.
     */
    private Live registerLive(SSLSession session, X509Certificate[] chain, ConnAbort abort)
            throws CertificateException, IOException {
        long epoch;
        int attempts = 0;
        Live peer;
        do {
            if (++attempts > REGISTER_LIVE_MAX_ATTEMPTS) {
                throw new IOException("admin session registration exceeded TLS epoch retry budget ("
                        + REGISTER_LIVE_MAX_ATTEMPTS + " attempts)");
            }
            epoch = tlsEpoch.get();
            revalidate(chain);
            var mid = betweenValidateAndRegister;
            betweenValidateAndRegister = () -> {
            };
            mid.run();
            peer = new Live(chain, abort);
            live.put(session, peer);
        } while (tlsEpoch.get() != epoch);
        return peer;
    }

    /**
     * After reload: re-check every remembered peer chain against the new material. {@link AdminTls#reload()}
     * may already have invalidated SSL sessions before this runs, so we key off the stored chain, not
     * {@link SSLSession#isValid()}.
     */
    void dropConnectionsFailingPolicy() {
        tlsEpoch.incrementAndGet();
        for (var entry : List.copyOf(live.entrySet())) {
            var session = entry.getKey();
            var peer = entry.getValue();
            if (isDead(session, peer)) {
                live.remove(session);
                continue;
            }
            try {
                revalidate(peer.chain());
            } catch (CertificateException | IOException | RuntimeException failed) {
                // Stale snapshot: registerLive replaced Live under the new epoch — do not abort the newer entry.
                if (live.get(session) != peer) {
                    continue;
                }
                peer.dropped = true;
                policyDropped.incrementAndGet();
                abortQuietly(peer.abort());
                invalidateQuietly(session);
                // By key after abort: remove-by-value can miss a concurrent replace and leave a closed connection.
                live.remove(session);
            }
        }
        pruneDeadSessions();
    }

    /** Drop entries whose TLS session or underlying connection is already gone so the map cannot grow without bound. */
    private void pruneDeadSessions() {
        for (var entry : List.copyOf(live.entrySet())) {
            if (isDead(entry.getKey(), entry.getValue())) {
                live.remove(entry.getKey());
            }
        }
    }

    private static boolean isDead(SSLSession session, Live peer) {
        if (peer.dropped) {
            return true;
        }
        if (session == null || !session.isValid()) {
            return true;
        }
        return peer.abort() != null && peer.abort().isClosed();
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

    private static void abortQuietly(ConnAbort abort) {
        if (abort == null) {
            return;
        }
        try {
            abort.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void invalidateQuietly(SSLSession session) {
        if (session == null) {
            return;
        }
        try {
            session.invalidate();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /**
     * Handle that closes the sun.net.httpserver connection behind an exchange and reports whether it
     * is already closed. Public HTTPS APIs do not expose the socket; without this, invalidate alone
     * leaves keep-alive TCP up until the next request. Needs
     * {@code --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED}.
     */
    static ConnAbort connectionAbort(HttpExchange exchange) {
        try {
            var implField = exchange.getClass().getDeclaredField("impl");
            implField.setAccessible(true);
            var exchangeImpl = implField.get(exchange);
            var getConnection = exchangeImpl.getClass().getDeclaredMethod("getConnection");
            getConnection.setAccessible(true);
            var connection = getConnection.invoke(exchangeImpl);
            var close = connection.getClass().getDeclaredMethod("close");
            close.setAccessible(true);
            var closedField = connection.getClass().getDeclaredField("closed");
            closedField.setAccessible(true);
            var getChannel = connection.getClass().getDeclaredMethod("getChannel");
            getChannel.setAccessible(true);
            return new ConnAbort(connection, close, closedField, getChannel);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Reflective abort handle for one sun.net.httpserver.HttpConnection. */
    static final class ConnAbort implements AutoCloseable {
        private final Object connection;
        private final java.lang.reflect.Method close;
        private final java.lang.reflect.Field closedField;
        private final java.lang.reflect.Method getChannel;

        ConnAbort(Object connection, java.lang.reflect.Method close, java.lang.reflect.Field closedField,
                java.lang.reflect.Method getChannel) {
            this.connection = connection;
            this.close = close;
            this.closedField = closedField;
            this.getChannel = getChannel;
        }

        boolean isClosed() {
            try {
                if (closedField.getBoolean(connection)) {
                    return true;
                }
                var channel = getChannel.invoke(connection);
                return channel instanceof java.nio.channels.Channel ch && !ch.isOpen();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                close.invoke(connection);
            } catch (ReflectiveOperationException e) {
                throw new IOException(e);
            }
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
        for (var entry : List.copyOf(live.entrySet())) {
            if (live.remove(entry.getKey(), entry.getValue())) {
                abortQuietly(entry.getValue().abort());
            }
        }
        server.stop(0);
    }
}
