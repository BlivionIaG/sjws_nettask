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
import java.util.Objects;

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
 * {@link #connected(Path)} is the variant that starts one data-role node against a live
 * in-process control plane on ephemeral ports, then lets the test push a config version.
 * It throws {@link ControlPlanePendingException} today. {@code sjws_nettask.cp.CpStore} is a
 * single voter and {@code AdminServer} can bind an ephemeral admin port, but there is no
 * data-node watch client: {@code ServiceLaunch} does not dial the control plane, so enrollment
 * stays {@code connection=pending} and a pushed {@code SignedSnapshot} is not applied.
 * {@link PendingControlPlane#available()} is false, so {@link ClusterHarness#open} still refuses
 * {@link ClusterHarness.DataRole#DATA} and the four fault scenarios in {@code ClusterScenariosTest}
 * stay {@code @Disabled}. Do not attach a {@code ControlPlaneBinding} to pretend the node connected.
 * {@code ConnectedDataNodeTest} is {@code @Disabled} with {@link #CONNECTED_PENDING} until a watch
 * client reports {@code connection=connected} and applies the pushed version.
 */
public final class NodeFixtures {

    /**
     * Join-token secret written into the temp token file. Tests assert it never appears on a
     * command line, in {@code --status}, or in a log. It is not a production secret.
     */
    public static final String JOIN_SECRET = "0123456789abcdef";

    /**
     * Why {@link #connected(Path)} refuses, and why {@code ConnectedDataNodeTest} is disabled.
     * The message starts with {@code TODO} and names the missing watch client.
     */
    public static final String CONNECTED_PENDING = "TODO https://github.com/BlivionIaG/sjws_nettask/pull/38"
            + " — data-node watch client is missing. CpStore is a single voter and AdminServer can bind"
            + " an ephemeral port, but ServiceLaunch does not dial the control plane, so a data node stays"
            + " connection=pending and does not apply a pushed SignedSnapshot."
            + " Do not attach a ControlPlaneBinding to fake connection=connected."
            + " PendingControlPlane.available() is false.";

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
     * A {@link ClusterHarness.DataRole#DATA} node joined to a live control plane.
     * Throws until {@link #dataRoleCanJoin()} is true. Does not start a server and does not
     * attach a binding.
     */
    public static ConnectedDataNode connected(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        if (!dataRoleCanJoin()) {
            throw new ControlPlanePendingException(CONNECTED_PENDING);
        }
        return new ConnectedDataNode();
    }

    /**
     * True only when a data process can dial an in-process control plane, report
     * {@code connection=connected}, and apply a pushed snapshot on its own.
     * {@code sjws_nettask.cp} can sign and serve admin HTTP. It does not do this.
     */
    public static boolean dataRoleCanJoin() {
        return false;
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
