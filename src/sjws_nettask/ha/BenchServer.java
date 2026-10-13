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
package sjws_nettask.ha;

import java.nio.file.Path;
import java.util.Objects;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.cluster.ClusterKey;

/**
 * Process entry for {@code tools/ha-bench.sh}. Single-node leaves HA detached. Cluster mode
 * attaches {@link HaSettings} and runs a second node with UDP heartbeats. Blocks until killed.
 */
public final class BenchServer {

    private BenchServer() {
    }

    public static void main(String[] args) throws Exception {
        Options options;
        try {
            options = Options.parse(args);
        } catch (RuntimeException rejected) {
            System.err.println("ERROR: " + rejected.getMessage());
            System.exit(64);
            return;
        }
        Running running;
        try {
            running = options.cluster() ? startCluster(options) : launch(options);
        } catch (IllegalArgumentException rejected) {
            System.err.println("ERROR: " + rejected.getMessage());
            System.exit(64);
            return;
        }
        System.out.println("bench port=" + running.tcpPort() + " mode=" + running.mode());
        System.out.flush();
        try {
            Thread.currentThread().join();
        } finally {
            running.close();
        }
    }

    /** Cluster mode only. Resolves {@link ClusterKey} so a single-node launch does not load it. */
    private static Running startCluster(Options options) throws Exception {
        return launch(options, ClusterKey.fromEnvironment(System.getenv()));
    }

    /** Starts the server. Cluster mode without a key is refused. Caller closes it. */
    public static Running launch(Options options) throws Exception {
        if (options.cluster()) {
            throw new IllegalArgumentException(
                    "HA is enabled but no cluster key is set; set ha.cluster_key or WEB_SERVER_HA_CLUSTER_KEY");
        }
        return start(options, null, null);
    }

    /** Cluster mode with a key that did not come from the command line. Not public, so a single-node launch does not resolve it. */
    static Running launch(Options options, ClusterKey key) throws Exception {
        if (!options.cluster()) {
            throw new IllegalArgumentException("cluster key is only used when HA is enabled");
        }
        Objects.requireNonNull(key, "cluster key");
        return start(options, ClusterSide.start(key), HaSettings.defaults());
    }

    private static Running start(Options options, ClusterSide side, HaSettings ha) throws Exception {
        var config = ServerConfig.defaults(Path.of(options.root())).withPort(options.port());
        try {
            var server = SjwsServer.start(config, ha);
            return new Running(server, side, options.mode());
        } catch (RuntimeException | java.io.IOException failed) {
            if (side != null) {
                side.close();
            }
            throw failed;
        }
    }

    public static final class Running implements AutoCloseable {
        private final SjwsServer server;
        private final ClusterSide side;
        private final String mode;

        private Running(SjwsServer server, ClusterSide side, String mode) {
            this.server = server;
            this.side = side;
            this.mode = mode;
        }

        public int tcpPort() {
            return server.tcpPort();
        }

        public String mode() {
            return mode;
        }

        public int heartbeats() {
            return side == null ? 0 : side.received();
        }

        @Override
        public void close() {
            server.close();
            if (side != null) {
                side.close();
            }
        }
    }

    public record Options(String mode, int port, String root) {
        public boolean cluster() {
            return "cluster".equals(this.mode);
        }

        public static Options parse(String[] args) {
            String mode = "single";
            int port = 8080;
            String root = "HTML";
            for (var arg : args) {
                if (arg.startsWith("--mode=")) {
                    mode = arg.substring("--mode=".length());
                } else if (arg.startsWith("--port=")) {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } else if (arg.startsWith("--root=")) {
                    root = arg.substring("--root=".length());
                } else if (arg.startsWith("--cluster-key") || arg.startsWith("--ha-cluster-key")) {
                    throw new IllegalArgumentException(
                            "cluster key is not accepted on the command line; set WEB_SERVER_HA_CLUSTER_KEY");
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            if (!"single".equals(mode) && !"cluster".equals(mode)) {
                throw new IllegalArgumentException("--mode=single|cluster");
            }
            if (root.isBlank()) {
                throw new IllegalArgumentException("--root is required");
            }
            return new Options(mode, port, root);
        }
    }
}
