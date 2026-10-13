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
package sjws_nettask.clusterharness;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/**
 * N control nodes plus M data nodes.
 *
 * <p>Data nodes are in-process {@link SjwsServer}s on port 0, the same listener the
 * file-serving harness starts ({@code tests/sjws_nettask/e2e/FileServingEndToEndTest}).
 * {@link DataRole#STANDALONE} and {@link DataRole#GATEWAY_OFF} construct no control-plane
 * object: that is the zero-cost path. {@link DataRole#DATA} and a non-zero control-node
 * count need {@link ControlPlane#available()}. Until then {@link #open} throws
 * {@link ControlPlanePendingException} before it binds. A data process that has not joined
 * is {@link NodeFixtures#pending(java.nio.file.Path, String)}, which reports
 * {@code connection=pending}. {@link NodeFixtures#connected(java.nio.file.Path)} is the
 * live-control-plane variant and refuses until a data-node watch exists.
 */
public final class ClusterHarness implements AutoCloseable {

    /** How a data node is started. {@code DATA} is the control-plane role and is pending. */
    public enum DataRole {
        /** Today's server. No control-plane object. */
        STANDALONE,
        /**
         * Gateway mode explicitly off. Same process path as {@link #STANDALONE} until
         * {@code sjws_nettask.cp} has a startup role; that role must stay unconstructed.
         */
        GATEWAY_OFF,
        /** Data-plane role that watches {@code cp-config}. Pending with the control plane. */
        DATA
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        }
    };

    private final DataRole role;
    private final ClusterClock clock;
    private final ControlPlane control;
    private final List<DataNode> dataNodes;
    private final HttpClient client;

    private ClusterHarness(DataRole role, ClusterClock clock, ControlPlane control, List<DataNode> dataNodes,
            HttpClient client) {
        this.role = role;
        this.clock = clock;
        this.control = control;
        this.dataNodes = dataNodes;
        this.client = client;
    }

    /**
     * @param controlNodes 0, 3, or 5
     * @param dataNodes    at least 1
     */
    public static ClusterHarness open(int controlNodes, int dataNodes, Path root, DataRole role, ClusterClock clock)
            throws IOException {
        if (controlNodes != 0 && controlNodes != 3 && controlNodes != 5) {
            throw new IllegalArgumentException("control nodes must be 0, 3, or 5");
        }
        if (dataNodes < 1) {
            throw new IllegalArgumentException("data nodes must be >= 1");
        }
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(clock, "clock");
        ControlPlane control = newControlPlane();
        if ((role == DataRole.DATA || controlNodes > 0) && !control.available()) {
            throw new ControlPlanePendingException();
        }
        List<DataNode> nodes = new ArrayList<>(dataNodes);
        try {
            for (int i = 0; i < dataNodes; i++) {
                nodes.add(ServingNode.start("data-" + i, labelsFor(i), root));
            }
            if (controlNodes > 0) {
                control.start(controlNodes, clock);
            }
        } catch (RuntimeException | IOException e) {
            for (DataNode node : nodes) {
                node.close();
            }
            control.close();
            throw e;
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(DIRECT)
                .build();
        return new ClusterHarness(role, clock, control, List.copyOf(nodes), client);
    }

    /**
     * The control-plane factory. Returns {@link PendingControlPlane}.
     * {@code sjws_nettask.cp.CpStore} is a single voter with a fixed member address. It does not
     * implement {@link ControlPlane}: no 3 or 5 voters, no {@link ClusterClock}, no partition,
     * no leader kill, no per-node delay, and no push onto a data node. Replace this body when
     * that factory exists. Do not make {@link PendingControlPlane} pretend to commit.
     */
    static ControlPlane newControlPlane() {
        return new PendingControlPlane();
    }

    public DataRole role() {
        return this.role;
    }

    public ClusterClock clock() {
        return this.clock;
    }

    public ControlPlane control() {
        return this.control;
    }

    public List<DataNode> dataNodes() {
        return this.dataNodes;
    }

    public HttpResponse<String> get(DataNode node, String path) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + node.httpPort() + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return this.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        for (DataNode node : this.dataNodes) {
            node.close();
        }
        this.control.close();
        this.client.close();
    }

    private static Map<String, String> labelsFor(int index) {
        String track = index == 0 ? "canary" : "stable";
        return Map.of("role", "gateway", "track", track);
    }

    private static final class ServingNode implements DataNode {
        private final String id;
        private final Map<String, String> labels;
        private final SjwsServer server;

        private ServingNode(String id, Map<String, String> labels, SjwsServer server) {
            this.id = id;
            this.labels = labels;
            this.server = server;
        }

        static ServingNode start(String id, Map<String, String> labels, Path root) throws IOException {
            // Port 0: the kernel assigns the port, and tcpPort() is valid when start returns.
            // No HaSettings and no cp object. standalone and gateway-off share this path.
            var config = ServerConfig.builder(root).port(0).h3Enabled(false).build();
            return new ServingNode(id, labels, SjwsServer.start(config));
        }

        @Override
        public String id() {
            return this.id;
        }

        @Override
        public Map<String, String> labels() {
            return this.labels;
        }

        @Override
        public int httpPort() {
            return this.server.tcpPort();
        }

        @Override
        public long version() {
            // AppliedConfig.version is 0 for a snapshot that never came from the control plane.
            return 0L;
        }

        @Override
        public String contentHash() {
            return null;
        }

        @Override
        public SjwsServer server() {
            return this.server;
        }

        @Override
        public void close() {
            this.server.close();
        }
    }
}
