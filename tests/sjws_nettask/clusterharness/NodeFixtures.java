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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import sjws_nettask.SjwsServer;
import sjws_nettask.cluster.FakeClock;
import sjws_nettask.config.ConfigLoader;
import sjws_nettask.cp.CpStore;
import sjws_nettask.cp.KeyRing;
import sjws_nettask.cp.LastGoodFiles;
import sjws_nettask.cp.WatchServer;
import sjws_nettask.service.ServiceLaunch;

/**
 * Bootstrap files for a control or data process, under a caller-supplied temp directory.
 * State, the pin, and the join token are not written into the working directory.
 *
 * <p><b>Pending node</b> ({@code connection=pending}, {@code config-version=0}).
 * {@link #pending(Path, String)} writes the files a process needs before it has joined.
 * Nothing here opens a socket. Start the process with {@code --bootstrap} and {@code --pid-file}.
 * Pass {@code --state-dir} (or {@code node.state_dir}) as another directory under the same temp
 * root so {@code config/last-good.json} stays out of the work tree. {@code --status} then shows
 * {@code connection=pending}. {@link sjws_nettask.service.ServiceLaunch} does not attach a binding.
 *
 * <pre>{@code
 * var node = NodeFixtures.pending(dir, "data");
 * // node.bootstrap()  mode 0600 bootstrap file
 * // node.stateDir()   dir/state (created when the file is loaded, mode 0700)
 * // node.joinToken()  mode 0600; secret is NodeFixtures.JOIN_SECRET, not a flag
 * //
 * // sjws_nettask.Sjws_nettask --role=data --bootstrap=<node.bootstrap()>
 * //     --pid-file=<dir/node.pid> --state-dir=<dir/config-state>
 * //     <port> <document root>
 * }</pre>
 *
 * <p>That is the layout
 * <a href="https://github.com/BlivionIaG/sjws_nettask/pull/95">sjws Architecture PR #95</a>
 * inlined in {@code tests/sjws_nettask/swarm/RoleClassLoadTest}. Call {@link #pending(Path, String)}
 * with role {@code "data"} instead of writing {@code root.pub}, {@code join.token}, and
 * {@code bootstrap} again. {@code ServiceDaemonTest} uses the same call.
 *
 * <p><b>Connected data node</b> ({@link ClusterHarness.DataRole#DATA}).
 * {@link #connected(Path)} starts an in-process single-voter {@code CpStore}, its loopback
 * {@code WatchServer} on an ephemeral port, and one data-role {@code SjwsServer} whose
 * {@code ServiceLaunch} hooks open the real data-node watch. The node reports
 * {@code connection=connected} and applies each pushed {@code SignedSnapshot} on its own; nothing
 * here attaches a {@code ControlPlaneBinding}. The four fault scenarios in
 * {@code ClusterScenariosTest} use the multi-voter {@link CpStoreControlPlane} instead.
 */
public final class NodeFixtures {

    /**
     * Join-token secret written into the temp token file. Tests assert it never appears on a
     * command line, in {@code --status}, or in a log. It is not a production secret.
     */
    public static final String JOIN_SECRET = "0123456789abcdef";

    private NodeFixtures() {
    }

    /**
     * Pin, join token, and bootstrap file for a node that has not joined.
     *
     * @param root temp directory that will hold {@code root.pub}, {@code join.token}, {@code bootstrap},
     *        and the {@code state} directory path (the directory itself is created on load)
     * @param role {@code data} (gateway, node {@code gw-1}, contact {@code 127.0.0.1:9443}) or
     *        {@code control} (node {@code cp-1}, listen {@code 127.0.0.1:9443}, not bound)
     */
    public static Pending pending(Path root, String role) throws IOException {
        Objects.requireNonNull(root, "root");
        if (!"data".equals(role) && !"control".equals(role)) {
            throw new IllegalArgumentException("role must be data or control");
        }
        var directory = root.toAbsolutePath();
        Files.createDirectories(directory);
        var pin = directory.resolve("root.pub");
        var token = directory.resolve("join.token");
        var state = directory.resolve("state");
        var boot = directory.resolve("bootstrap");
        Files.writeString(pin, "test-root-public-key\n");
        Files.writeString(token, "token-id=node-1\nsecret=" + JOIN_SECRET + "\n");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        String nodeId;
        String body;
        if ("control".equals(role)) {
            nodeId = "cp-1";
            body = "role=control\nnode-id=cp-1\npin=" + pin + "\njoin-token=" + token + "\nstate-dir=" + state
                    + "\nlisten=127.0.0.1:9443\n";
        } else {
            nodeId = "gw-1";
            body = "role=data\ndata-role=gateway\nnode-id=gw-1\ncontrol-plane=127.0.0.1:9443\npin=" + pin
                    + "\njoin-token=" + token + "\nstate-dir=" + state + "\n";
        }
        Files.writeString(boot, body);
        Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));
        return new Pending(boot, state, pin, token, role, nodeId);
    }

    /**
     * A {@link ClusterHarness.DataRole#DATA} node joined to a live in-process control plane over the
     * real watch. Returns once the node reports {@code connection=connected} (version 0). The data
     * node's {@code NodeEnrollment} is this process's only one, so one connected node at a time.
     */
    public static ConnectedDataNode connected(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        var directory = root.toAbsolutePath();
        Files.createDirectories(directory);
        long now = System.currentTimeMillis();
        var keys = KeyRing.generate("online-1", now + 86_400_000L, 0, Long.MAX_VALUE / 2);
        var clock = new FakeClock(0);
        var store = CpStore.open(directory.resolve("cp"), keys, clock);
        WatchServer watch = null;
        SjwsServer server = null;
        sjws_nettask.ServiceHooks hooks = null;
        try {
            clock.advance(Duration.ofMillis(100));
            store.drive();
            if (!store.leadership().leaderReady()) {
                throw new IOException("single voter did not become leaderReady");
            }
            var issued = store.issueJoinToken("node-1", JOIN_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    System.currentTimeMillis(), sjws_nettask.cp.JoinTokens.DEFAULT_TTL_MILLIS);
            if (issued.status() != 200) {
                throw new IOException("join token not issued: " + issued.status());
            }
            watch = WatchServer.start(store, 0, System::currentTimeMillis);
            var pin = directory.resolve("root.pub");
            LastGoodFiles.writePin(pin, keys.rootPublic(), keys.versionFloor());
            var token = directory.resolve("join.token");
            Files.writeString(token, "token-id=node-1\nsecret=" + JOIN_SECRET + "\n");
            Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
            var boot = directory.resolve("bootstrap");
            Files.writeString(boot, "role=data\ndata-role=gateway\nnode-id=gw-1\ncontrol-plane=127.0.0.1:"
                    + watch.port() + "\npin=" + pin + "\njoin-token=" + token + "\nstate-dir="
                    + directory.resolve("state") + "\n");
            Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));
            var html = Files.createDirectories(directory.resolve("html"));
            Files.writeString(html.resolve("index.html"), "connected");
            var yaml = directory.resolve("sjws.yaml");
            Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\n  state_dir: " + directory.resolve("config-state")
                    + "\nserver:\n  port: 0\n  root: " + html + "\n");
            server = SjwsServer.start(ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of()));
            hooks = ServiceLaunch.prepare(boot, "data");
            hooks.onListening(server, directory.resolve("node.pid"), Duration.ofSeconds(5));
            var node = new ConnectedDataNode(store, watch, server, hooks, html);
            node.awaitConnected(Duration.ofSeconds(20));
            return node;
        } catch (IOException | RuntimeException e) {
            if (hooks != null) {
                hooks.onShutdown();
            }
            if (server != null) {
                server.close();
            }
            if (watch != null) {
                watch.close();
            }
            store.close();
            throw e;
        }
    }

    /** True: a data process dials the in-process control plane and applies pushed snapshots itself. */
    public static boolean dataRoleCanJoin() {
        return true;
    }

    /**
     * Files from {@link #pending(Path, String)}. Paths are absolute. {@code stateDir} is not
     * created until bootstrap load.
     *
     * @param bootstrap mode 0600 bootstrap file
     * @param stateDir {@code <root>/state}, outside of which the pin and bootstrap sit
     * @param pin pinned root public key (a test string, not a real SPKI key)
     * @param joinToken mode 0600 token file
     * @param role {@code data} or {@code control}
     * @param nodeId {@code gw-1} or {@code cp-1}
     */
    public record Pending(Path bootstrap, Path stateDir, Path pin, Path joinToken, String role, String nodeId) {
    }
}
