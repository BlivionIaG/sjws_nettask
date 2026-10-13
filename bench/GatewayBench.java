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
import java.nio.file.Path;
import java.util.Set;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;
import sjws_nettask.core.RequestFilters;
import sjws_nettask.gateway.GatewayFilters;

/**
 * Process entry for {@code tools/gateway-bench.sh}.
 *
 * <p>{@code standalone} and {@code gateway-off} both start {@link SjwsServer} with no
 * control-plane object and no {@code HaSettings}. That is the zero-cost path in
 * {@code docs/design/control-plane.md}
 * (https://github.com/BlivionIaG/sjws_nettask/pull/38): role {@code standalone} constructs
 * nothing from {@code sjws_nettask.cp}. When that package grows a startup role,
 * {@code gateway-off} must select standalone explicitly and must still take this path.
 *
 * <p>{@code rate-limit} is the only mode that opens chain control. It installs the gateway
 * factories and applies one {@code rate-limit} document ({@code by=ip}, {@code buckets=4096}).
 * {@code --capacity} and {@code --refill} set that document. {@code standalone} and
 * {@code gateway-off} never call {@code chainControl}.
 *
 * <p>Usage: {@code java -cp target/classes:target/lib/* bench/GatewayBench.java --mode=standalone|gateway-off|rate-limit --port=0 --root=DIR}
 * Prints {@code bench port=<n> mode=<mode>} after the listener is bound. Port 0 is ephemeral.
 */
public final class GatewayBench {

    private GatewayBench() {
    }

    public static void main(String[] args) throws Exception {
        var options = Options.parse(args);
        var config = ServerConfig.builder(Path.of(options.root())).port(options.port()).h3Enabled(false).build();
        // standalone and gateway-off leave the control plane detached. gateway-off is not a second code path.
        var server = switch (options.mode()) {
            case "standalone", "gateway-off", "rate-limit" -> SjwsServer.start(config);
            default -> throw new IllegalArgumentException("--mode=standalone|gateway-off|rate-limit");
        };
        if ("rate-limit".equals(options.mode())) {
            var registry = GatewayFilters.install(RequestFilters.create());
            var json = "{\"format\":1,\"version\":1,\"filters\":[{\"type\":\"rate-limit\",\"order\":0,"
                    + "\"params\":{\"by\":\"ip\",\"capacity\":\"" + options.capacity()
                    + "\",\"refill-per-second\":\"" + options.refill() + "\",\"buckets\":\"4096\"}}]}";
            server.chainControl(registry, Set.of()).apply(json);
        }
        System.out.println("bench port=" + server.tcpPort() + " mode=" + options.mode());
        System.out.flush();
        try {
            Thread.currentThread().join();
        } finally {
            server.close();
        }
    }

    public record Options(String mode, int port, String root, String capacity, String refill) {
        public static Options parse(String[] args) {
            String mode = "";
            int port = 0;
            String root = "";
            String capacity = "1000000";
            String refill = "1000000000";
            for (var arg : args) {
                if (arg.startsWith("--mode=")) {
                    mode = arg.substring("--mode=".length());
                } else if (arg.startsWith("--port=")) {
                    port = Integer.parseInt(arg.substring("--port=".length()));
                } else if (arg.startsWith("--root=")) {
                    root = arg.substring("--root=".length());
                } else if (arg.startsWith("--capacity=")) {
                    capacity = arg.substring("--capacity=".length());
                } else if (arg.startsWith("--refill=")) {
                    refill = arg.substring("--refill=".length());
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }
            if (!"standalone".equals(mode) && !"gateway-off".equals(mode) && !"rate-limit".equals(mode)) {
                throw new IllegalArgumentException("--mode=standalone|gateway-off|rate-limit");
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("--port out of range");
            }
            if (root.isBlank()) {
                throw new IllegalArgumentException("--root is required");
            }
            if (!capacity.chars().allMatch(Character::isDigit) || capacity.isEmpty()
                    || !refill.chars().allMatch(Character::isDigit) || refill.isEmpty()) {
                throw new IllegalArgumentException("--capacity and --refill must be digits");
            }
            return new Options(mode, port, root, capacity, refill);
        }
    }
}
