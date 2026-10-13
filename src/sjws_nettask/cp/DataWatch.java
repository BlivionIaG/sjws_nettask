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
    private final Thread thread;
    private volatile boolean running = true;
    private volatile HttpURLConnection open;
    private DataApply apply;
    private volatile long applied;
    private long skipped;
    private String state = "pending";
    private long reportedVersion;

    private DataWatch(URI base, String nodeId, String tokenId, byte[] secret, Path pin, Path stateDir,
            SjwsServer server, NodeEnrollment enrollment, LongSupplier nowMillis) {
        this.base = base;
        this.nodeId = nodeId;
        this.tokenId = tokenId;
        this.secret = Base64.getEncoder().encodeToString(secret);
        Arrays.fill(secret, (byte) 0);
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
        var watch = new DataWatch(endpoint(controlPlane), nodeId, tokenId, secret, pin, stateDir, server, enrollment,
                nowMillis);
        watch.thread.start();
        return watch;
    }

    static URI endpoint(String controlPlane) throws IOException {
        if (controlPlane == null || controlPlane.isBlank()) {
            throw new IOException("no control-plane address");
        }
        var text = controlPlane.contains("://") ? controlPlane : "http://" + controlPlane;
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("control-plane address: " + e.getMessage());
        }
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1) {
            throw new IOException("control-plane address must be host:port");
        }
        if (!InetAddress.getByName(uri.getHost()).isLoopbackAddress()) {
            throw new IOException("control-plane watch is loopback-only until the mTLS watch lands");
        }
        return URI.create("http://" + uri.getHost() + ":" + uri.getPort());
    }

    /** Highest version applied through this watch; 0 before the first apply. */
    public long appliedVersion() {
        return this.applied;
    }

    private void loop() {
        long backoff = BACKOFF_MIN_MS;
        boolean first = true;
        while (this.running) {
            try {
                long after = Math.max(this.applied, this.skipped);
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
        this.applied = snapshot.version();
        report("connected");
        var ack = request("POST", "/cp/v1/watch/ack?version=" + snapshot.version());
        int status = ack.getResponseCode();
        if (status != 204) {
            throw new IOException("ack answered " + status);
        }
    }

    private HttpURLConnection request(String method, String pathAndQuery) throws IOException {
        var conn = openSocket(this.base.resolve(pathAndQuery));
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout((int) (WAIT_MS + 10_000L));
        conn.setRequestProperty(WatchServer.NODE, this.nodeId);
        conn.setRequestProperty(WatchServer.TOKEN_ID, this.tokenId);
        conn.setRequestProperty(WatchServer.SECRET, this.secret);
        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.getOutputStream().close();
        }
        this.open = conn;
        return conn;
    }

    /**
     * The one place a connection to the control plane is created. Plain HTTP today; the opt-in mTLS
     * of the node-to-CP channel returns an {@code HttpsURLConnection} with its SSL context here.
     */
    static HttpURLConnection openSocket(URI target) throws IOException {
        return (HttpURLConnection) target.toURL().openConnection();
    }

    /** Rewrites the status file only on a change. {@code down} maps to disconnected or pending. */
    private void report(String next) {
        long version = this.applied;
        var binding = StatusBinding.data("connected".equals(next), version > 0, version);
        var shown = binding.connectionState();
        if (shown.equals(this.state) && version == this.reportedVersion) {
            return;
        }
        this.state = shown;
        this.reportedVersion = version;
        try {
            this.enrollment.attach(binding);
        } catch (IOException | IllegalStateException e) {
            // Status file is advisory; a closed enrollment means shutdown is under way.
        }
    }

    /** The binding last reported, for tests and {@code --status}. */
    public ControlPlaneBinding binding() {
        return this.enrollment.binding();
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
