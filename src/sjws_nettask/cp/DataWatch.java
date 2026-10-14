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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.LongSupplier;
import sjws_nettask.SjwsServer;
import sjws_nettask.service.ControlPlaneBinding;
import sjws_nettask.service.NodeEnrollment;

/**
 * The data node's watch client (design §8, first increment). One daemon thread long-polls
 * {@link WatchServer}, hands every new envelope to {@link SjwsServer#applyRemote(SignedSnapshot)}
 * (signature, rollback, floor checks under {@code reloadLock}, fail closed) and acks only an
 * applied version. A rejected envelope is skipped, not retried, and the installed snapshot stays.
 * Connection state goes to {@link NodeEnrollment#attach}: {@code pending} until the first answer,
 * {@code connected} while the watch answers, {@code disconnected} (with last-good) or
 * {@code pending} (without) while it does not. Nothing here runs on the request path.
 */
public final class DataWatch implements AutoCloseable {

    /** Long-poll length; the server caps it at {@link WatchServer#MAX_WAIT_MS}. */
    static final long WAIT_MS = 25_000L;
    static final long BACKOFF_MIN_MS = 200L;
    static final long BACKOFF_MAX_MS = 10_000L;

    private final URI base;
    private final String nodeId;
    private final String tokenId;
    private final String secret;
    private final Path pin;
    private final Path stateDir;
    private final SjwsServer server;
    private final NodeEnrollment enrollment;
    private final LongSupplier nowMillis;
    private final CpTls cpTls;
    private SSLContext clientTls;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile HttpURLConnection open;
    private DataApply apply;
    /**
     * Version and hash of the last applied envelope, published as one value so a reader never pairs a
     * new version with the previous hash (#196).
     */
    private volatile Applied applied = Applied.NONE;

    private record Applied(long version, String hash) {
        static final Applied NONE = new Applied(0L, null);
    }
    /** Status when no {@link NodeEnrollment} was given (several watches in one test process). */
    private volatile ControlPlaneBinding own;
    private long skipped;
    private String state = "pending";
    private long reportedVersion;

    private DataWatch(URI base, String nodeId, String tokenId, byte[] secret, Path pin, Path stateDir,
            SjwsServer server, NodeEnrollment enrollment, LongSupplier nowMillis, CpTls cpTls, SSLContext clientTls) {
        this.base = base;
        this.nodeId = nodeId;
        this.tokenId = tokenId;
        this.secret = secret == null ? null : Base64.getEncoder().encodeToString(secret);
        if (secret != null) {
            Arrays.fill(secret, (byte) 0);
        }
        this.cpTls = cpTls;
        this.clientTls = clientTls;
        this.pin = pin;
        this.stateDir = stateDir;
        this.server = server;
        this.enrollment = enrollment;
        this.nowMillis = nowMillis;
        this.thread = Thread.ofPlatform().daemon().name("sjws-cp-watch").unstarted(this::loop);
    }

    /**
     * Starts the watch. {@code controlPlane} is {@code http://host:port} or {@code host:port}; until
     * the mTLS watch of §12 lands only a loopback host is accepted. Takes ownership of {@code secret}.
     */
    public static DataWatch open(String controlPlane, String nodeId, String tokenId, byte[] secret, Path pin,
            Path stateDir, SjwsServer server, NodeEnrollment enrollment, LongSupplier nowMillis) throws IOException {
        return open(controlPlane, nodeId, tokenId, secret, pin, stateDir, server, enrollment, nowMillis, null);
    }

    /**
     * Starts the watch over mutual TLS when {@code cpTls} is set: any host, {@code https}, the CP
     * certificate checked against the pinned CA and {@link CpTls#peerName()}. The join token may be
     * null once this node has joined, because its certificate is then the credential. With
     * {@code cpTls} null this is the plain loopback watch. {@code enrollment} may be null: the
     * status then stays in {@link #binding()} only (several data nodes in one test process).
     */
    public static DataWatch open(String controlPlane, String nodeId, String tokenId, byte[] secret, Path pin,
            Path stateDir, SjwsServer server, NodeEnrollment enrollment, LongSupplier nowMillis, CpTls cpTls)
            throws IOException {
        if (cpTls == null && (tokenId == null || secret == null)) {
            throw new IOException("plain control-plane watch needs the join token");
        }
        var clientTls = cpTls == null ? null : cpTls.client();
        var watch = new DataWatch(endpoint(controlPlane, cpTls != null), nodeId, tokenId, secret, pin, stateDir,
                server, enrollment, nowMillis, cpTls, clientTls);
        watch.thread.start();
        return watch;
    }

    static URI endpoint(String controlPlane) throws IOException {
        return endpoint(controlPlane, false);
    }

    static URI endpoint(String controlPlane, boolean tls) throws IOException {
        if (controlPlane == null || controlPlane.isBlank()) {
            throw new IOException("no control-plane address");
        }
        var scheme = tls ? "https" : "http";
        var text = controlPlane.contains("://") ? controlPlane : scheme + "://" + controlPlane;
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("control-plane address: " + e.getMessage());
        }
        if (!scheme.equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IOException("control-plane address must be host:port" + (tls ? " (https with cpTls)" : ""));
        }
        if (!tls && !InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
            throw new IOException("plain control-plane watch is loopback-only; configure cpTls for a remote CP");
        }
        return URI.create(scheme + "://" + uri.getHost() + ":" + uri.getPort());
    }

    /** Canonical hash of the envelope last applied through this watch; null before the first apply. */
    public String appliedHash() {
        return this.applied.hash();
    }

    /** Highest version applied through this watch; 0 before the first apply. */
    public long appliedVersion() {
        return this.applied.version();
    }

    private void loop() {
        long backoff = BACKOFF_MIN_MS;
        boolean first = true;
        while (this.running) {
            try {
                long after = Math.max(this.applied.version(), this.skipped);
                var conn = request("GET", "/cp/v1/watch?after=" + after + "&waitMs=" + (first ? 0L : WAIT_MS));
                int status = conn.getResponseCode();
                if (status == 200) {
                    String body;
                    try (var in = conn.getInputStream()) {
                        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    report("connected");
                    offer(WatchWire.decode(body));
                } else if (status == 204) {
                    report("connected");
                } else {
                    throw new IOException("watch answered " + status);
                }
                first = false;
                backoff = BACKOFF_MIN_MS;
            } catch (IOException | RuntimeException e) {
                if (!this.running) {
                    return;
                }
                report("down");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException stop) {
                    return;
                }
                backoff = Math.min(BACKOFF_MAX_MS, backoff * 2);
            } finally {
                this.open = null;
            }
        }
    }

    private void offer(WatchWire.Message message) throws IOException {
        var snapshot = message.snapshot();
        if (this.apply == null) {
            try {
                this.apply = DataApply.trusted(this.pin, message, this.stateDir, this.nowMillis);
            } catch (IOException e) {
                System.err.println("ERROR: control-plane snapshot " + snapshot.version() + " rejected: " + e.getMessage());
                this.skipped = snapshot.version();
                return;
            }
            this.server.attachControlPlane(this.apply);
        }
        var result = this.server.applyRemote(snapshot);
        if (!result.applied()) {
            System.err.println("ERROR: control-plane snapshot " + snapshot.version() + " rejected: " + result.errors());
            this.skipped = snapshot.version();
            return;
        }
        this.applied = new Applied(snapshot.version(), snapshot.canonicalHashHex());
        report("connected");
        var ack = request("POST", "/cp/v1/watch/ack?version=" + snapshot.version());
        int status = ack.getResponseCode();
        if (status != 204) {
            throw new IOException("ack answered " + status);
        }
    }

    private HttpURLConnection request(String method, String pathAndQuery) throws IOException {
        var conn = openSocket(this.base.resolve(pathAndQuery), clientTls());
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout((int) (WAIT_MS + 10_000L));
        conn.setRequestProperty(WatchServer.NODE, this.nodeId);
        if (this.tokenId != null && this.secret != null) {
            conn.setRequestProperty(WatchServer.TOKEN_ID, this.tokenId);
            conn.setRequestProperty(WatchServer.SECRET, this.secret);
        }
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.getOutputStream().close();
        }
        this.open = conn;
        return conn;
    }

    /**
     * The client context, rebuilt when the cpTls files change (so a rotated node certificate or CA
     * is picked up on the next poll). A failed rebuild keeps the previous context.
     */
    private SSLContext clientTls() {
        if (this.cpTls == null) {
            return null;
        }
        var stamp = stamp(this.cpTls);
        if (!stamp.equals(this.clientStamp)) {
            try {
                this.clientTls = this.cpTls.client();
                this.clientStamp = stamp;
            } catch (IOException e) {
                System.err.println("WARN: cpTls reload failed, keeping the previous material: " + e.getMessage());
                this.clientStamp = stamp;
            }
        }
        return this.clientTls;
    }

    private String clientStamp = "";

    private static String stamp(CpTls t) {
        var out = new StringBuilder();
        for (var p : new Path[] {t.certPem(), t.keyPem(), t.caPem()}) {
            try {
                out.append(java.nio.file.Files.getLastModifiedTime(p).toMillis()).append(':')
                        .append(java.nio.file.Files.size(p)).append(';');
            } catch (IOException e) {
                out.append("absent;");
            }
        }
        return out.toString();
    }

    /**
     * The one place a connection to the control plane is created: plain HTTP when {@code tls} is
     * null, otherwise an {@code HttpsURLConnection} over TLS 1.3 with this node's certificate,
     * trusting only the pinned CA ({@link CpTls#client()} also checks the CP identity).
     */
    static HttpURLConnection openSocket(URI target, SSLContext tls) throws IOException {
        var conn = (HttpURLConnection) target.toURL().openConnection();
        if (tls == null) {
            return conn;
        }
        if (!(conn instanceof HttpsURLConnection https)) {
            throw new IOException("cpTls needs an https control-plane address");
        }
        https.setSSLSocketFactory(new Tls13Only(tls.getSocketFactory()));
        return conn;
    }

    /** Restricts every socket to TLS 1.3. */
    private static final class Tls13Only extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls13Only(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket only13(Socket s) {
            if (s instanceof SSLSocket ssl) {
                ssl.setEnabledProtocols(new String[] {"TLSv1.3"});
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return only13(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket() throws IOException {
            return only13(delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return only13(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return only13(delegate.createSocket(host, port, local, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return only13(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort)
                throws IOException {
            return only13(delegate.createSocket(address, port, local, localPort));
        }
    }

    /** Rewrites the status file only on a change. {@code down} maps to disconnected or pending. */
    private void report(String next) {
        long version = this.applied.version();
        var binding = StatusBinding.data("connected".equals(next), version > 0, version);
        var shown = binding.connectionState();
        if (shown.equals(this.state) && version == this.reportedVersion) {
            return;
        }
        this.state = shown;
        this.reportedVersion = version;
        if (this.enrollment == null) {
            this.own = binding;
            return;
        }
        try {
            this.enrollment.attach(binding);
        } catch (IOException | IllegalStateException e) {
            // Status file is advisory; a closed enrollment means shutdown is under way.
        }
    }

    /** The binding last reported, for tests and {@code --status}. */
    public ControlPlaneBinding binding() {
        return this.enrollment == null ? this.own : this.enrollment.binding();
    }

    @Override
    public void close() {
        this.running = false;
        var conn = this.open;
        if (conn != null) {
            conn.disconnect();
        }
        this.thread.interrupt();
        try {
            this.thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
