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
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/**
 * Process entry for {@code tools/ha-bench.sh}. Single-node leaves HA detached. Cluster mode
 * attaches {@link HaSettings} so the request path pays for the snapshot read and the probes.
 * Blocks until the process is killed.
 */
public final class BenchServer {

    private BenchServer() {
    }

    public static void main(String[] args) throws Exception {
        var options = Options.parse(args);
        var config = ServerConfig.defaults(Path.of(options.root())).withPort(options.port());
        HaSettings ha = options.cluster() ? HaSettings.defaults() : null;
        var server = SjwsServer.start(config, ha);
        System.out.println("bench port=" + server.tcpPort() + " mode=" + options.mode());
        System.out.flush();
        try {
            Thread.currentThread().join();
        } finally {
            server.close();
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
