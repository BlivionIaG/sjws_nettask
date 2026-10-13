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
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zero cost when off for a real {@code data}/{@code gateway} role process (docs/design/agent-swarm.md §12 S1,
 * decision b), in the shape of {@link ClassLoadTest}.
 */
class RoleClassLoadTest {

    private static final String SWARM = "sjws_nettask.swarm.";

    @TempDir
    Path dir;

    /**
     * A real {@code data}/{@code gateway} role process (§12 S1, decision b). It uses the bootstrap fixture shape of
     * {@code ServiceDaemonTest.bootstrap(.., "data")}. The cluster harness has no live control plane yet
     * ({@code clusterharness.PendingControlPlane.available() == false}), so the node starts with
     * {@code connection=pending}, which is the role's normal state before it joins.
     */
    @Test
    void dataGatewayRoleLoadsNoSwarmClass() throws Exception {
        var text = runDataGateway(List.of());
        assertFalse(text.contains(SWARM), text);
    }

    @Test
    void dataGatewayRoleEnabledLoadsTheSwarmModule() throws Exception {
        var text = runDataGateway(List.of("-Dsjws.swarm.enabled=true"));
        assertTrue(text.contains("sjws_nettask.swarm.SwarmModule"), text);
    }

    private String runDataGateway(List<String> flags) throws Exception {
        var root = Files.createDirectories(this.dir.resolve("gw-html"));
        var pin = this.dir.resolve("root.pub");
        var token = this.dir.resolve("join.token");
        var boot = this.dir.resolve("bootstrap-" + System.nanoTime());
        Files.writeString(pin, "test-root-public-key\n");
        Files.writeString(token, "token-id=node-1\nsecret=class-load-test-secret-0123\n");
        Files.setPosixFilePermissions(token, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.writeString(boot, "role=data\ndata-role=gateway\nnode-id=gw-1\ncontrol-plane=127.0.0.1:9\npin=" + pin
                + "\njoin-token=" + token + "\nstate-dir=" + this.dir.resolve("state") + "\n");
        Files.setPosixFilePermissions(boot, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        var text = run(flags, "Server started:", "sjws_nettask.Sjws_nettask", "--role=data", "--bootstrap=" + boot,
                "--pid-file=" + this.dir.resolve("gw-" + System.nanoTime() + ".pid"), Integer.toString(freePort()),
                root.toString());
        assertTrue(text.contains("control-plane role=data/gateway"), text);
        return text;
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private String run(List<String> jvmFlags, String ready, String... main) throws Exception {
        var log = this.dir.resolve("classes-" + System.nanoTime() + ".log");
        var cmd = new ArrayList<String>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xlog:class+load=info");
        cmd.addAll(jvmFlags);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.addAll(List.of(main));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().remove("SJWS_SWARM_ENABLED");
        pb.environment().remove("WEB_SERVER_DIR");
        pb.environment().remove("WEB_SERVER_PORT");
        pb.environment().remove("WEB_SERVER_ROLE");
        var process = pb.start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            var text = "";
            while (System.nanoTime() < deadline) {
                text = Files.exists(log) ? Files.readString(log) : "";
                if (text.contains(ready) || !process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            // The module starts right after "Server started:"; let its class load land in the log.
            Thread.sleep(300);
            text = Files.readString(log);
            assertTrue(text.contains(ready), text);
            return text;
        } finally {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }
}
