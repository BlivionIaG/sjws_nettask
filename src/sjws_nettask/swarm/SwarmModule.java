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

import java.nio.file.Path;
import java.security.SecureRandom;
import sjws_nettask.OptionalModule;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;

/**
 * Entry point of the optional agent-swarm layer (docs/design/agent-swarm.md). Loaded only by name from
 * {@code sjws_nettask.OptionalModules} when the swarm is enabled; nothing outside this package imports it.
 * <p>
 * S1b: mounts the unary {@code /swarm/v1/*} endpoints ({@link SwarmHttp}) at startup. The services start
 * no thread and touch no file until the first request: partitions open on first use under
 * {@code -Dsjws.swarm.dir} / {@code SJWS_SWARM_DIR} (default {@code swarm-data} in the working directory),
 * and the reply topic is {@code reply.<sjws.swarm.node | SJWS_SWARM_NODE | swarm-<pid>>}.
 */
public final class SwarmModule implements OptionalModule, HandlerContributor {

    static final String DIR_PROPERTY = "sjws.swarm.dir";
    static final String DIR_ENV = "SJWS_SWARM_DIR";
    static final String NODE_PROPERTY = "sjws.swarm.node";
    static final String NODE_ENV = "SJWS_SWARM_NODE";
    static final String TRACE_PROPERTY = "sjws.swarm.trace.enabled";
    static final String TRACE_ENV = "SJWS_SWARM_TRACE_ENABLED";
    static final String STREAM_PROPERTY = "sjws.swarm.stream.enabled";
    static final String STREAM_ENV = "SJWS_SWARM_STREAM_ENABLED";

    private final Swarm swarm;
    private final SwarmGate gate;
    private final boolean trace;
    private volatile boolean closed;

    public SwarmModule() {
        this(Swarm.open(Path.of(setting(DIR_PROPERTY, DIR_ENV, "swarm-data")),
                setting(NODE_PROPERTY, NODE_ENV, "swarm-" + ProcessHandle.current().pid()), new SecureRandom().nextLong()),
                TokenGate.configured(System::getProperty, System.getenv(), System.err));
    }

    SwarmModule(Swarm swarm, SwarmGate gate) {
        this(swarm, gate, Boolean.parseBoolean(setting(TRACE_PROPERTY, TRACE_ENV, "false")));
    }

    SwarmModule(Swarm swarm, SwarmGate gate, boolean trace) {
        this(swarm, gate, trace, Boolean.parseBoolean(setting(STREAM_PROPERTY, STREAM_ENV, "false")));
    }

    SwarmModule(Swarm swarm, SwarmGate gate, boolean trace, boolean stream) {
        this(swarm, gate, trace, stream, multiProcessReason(System::getProperty, System.getenv(),
                sjws_nettask.OptionalModule.Launch.multiProcess()));
    }

    SwarmModule(Swarm swarm, SwarmGate gate, boolean trace, boolean stream, String multiProcess) {
        this.swarm = swarm;
        this.gate = gate;
        this.trace = trace;
        try {
            checkPeers(System::getProperty, System.getenv());
        } catch (sjws_nettask.OptionalModule.Refused refused) {
            swarm.close();
            throw refused;
        }
        if (stream && multiProcess != null) {
            // until Messaging Core's remote write path lands, chunks reach readers only through this process's
            // ledger: a stream whose worker or submitter is another process would lose its chunks (§5.2, §12)
            swarm.close();
            throw new sjws_nettask.OptionalModule.Refused(STREAM_PROPERTY + "=true needs a single-process deployment"
                    + " until cross-process stream delivery lands (" + STREAM_TRACKING + "), but " + multiProcess
                    + ". Turn the stream flag off or run this node alone.");
        }
        if (stream) {
            // S2c, off by default: the only place TokenStreams is created; read once at startup (shards fixed)
            TokenStreams.Config config;
            try {
                config = TokenStreams.Config.from(n -> setting(n, n.toUpperCase(java.util.Locale.ROOT)
                        .replace('.', '_'), null));
            } catch (sjws_nettask.OptionalModule.Refused e) {
                // a malformed stream setting (e.g. the credit sub-flag, #257 N-c) or window > sinkChunks (R2):
                // close the swarm first, as the multi-process refusal above does
                swarm.close();
                throw e;
            }
            var s = swarm.enableStreams(config);
            System.err.println("swarm: token streaming on, shards=" + s.config().shards());
        }
        if (trace) {
            // S2b, off by default: the only place the trace package is named outside SwarmHttp's guarded paths
            System.err.println("swarm: " + sjws_nettask.swarm.trace.SwarmTrace.describe());
        }
    }

    /**
     * #236 PR 1: when a swarm peer map or peer key is set, run the peer startup checks and refuse to start on any
     * failure (fail closed). Only then does a {@code swarm.peer} class load, so a node without peers pays nothing.
     * Returns the one-line summary that was printed, or null when no peer setting is present.
     */
    static String checkPeers(java.util.function.Function<String, String> props, java.util.Map<String, String> env) {
        if (!peersConfigured(props, env)) {
            return null;
        }
        String self = props.apply(NODE_PROPERTY);
        if (self == null || self.isBlank()) {
            self = env.get(NODE_ENV);
        }
        try {
            var checked = sjws_nettask.swarm.peer.PeerStartup.check(props, env, self);
            var line = "swarm: " + checked.describe();
            System.err.println(line);
            return line;
        } catch (IllegalArgumentException refused) {
            throw new sjws_nettask.OptionalModule.Refused(refused.getMessage());
        }
    }

    /** A swarm peer map or peer key is set (#236): the peer startup checks run, fail closed. */
    static boolean peersConfigured(java.util.function.Function<String, String> props, java.util.Map<String, String> env) {
        for (var name : new String[] {"sjws.swarm.peers", "sjws.swarm.peer.keyFile", "sjws.swarm.peer.keyFilePrevious"}) {
            var v = props.apply(name);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        for (var name : new String[] {"SJWS_SWARM_PEERS", "SJWS_SWARM_PEER_KEY", "SJWS_SWARM_PEER_KEY_PREVIOUS"}) {
            var v = env.get(name);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Messaging Core's tracking issue for cross-process stream delivery. */
    static final String STREAM_TRACKING = "#236";

    /**
     * Why this process runs with others: the composition root's {@code launch} reason ({@code --bootstrap},
     * {@code cp.peers} in the config file), else a control-plane node id or peer list in {@code sjws.cp.*} /
     * {@code SJWS_CP_*}; null when standalone.
     */
    static String multiProcessReason(java.util.function.Function<String, String> props, java.util.Map<String, String> env,
            String launch) {
        if (launch != null) {
            return launch;
        }
        // #236 PR 1 forwards no swarm family yet, so a peer map with other nodes is a multi-process deployment
        var peers = props.apply("sjws.swarm.peers");
        if (peers == null || peers.isBlank()) {
            peers = env.get("SJWS_SWARM_PEERS");
        }
        if (peers != null && peers.contains(",")) {
            return "sjws.swarm.peers lists other nodes";
        }
        for (var name : new String[] {"sjws.cp.nodeId", "sjws.cp.peers"}) {
            var v = props.apply(name);
            if (v != null && !v.isBlank()) {
                return name + " is set";
            }
        }
        for (var name : new String[] {"SJWS_CP_NODE_ID", "SJWS_CP_PEERS"}) {
            var v = env.get(name);
            if (v != null && !v.isBlank()) {
                return name + " is set";
            }
        }
        return null;
    }

    boolean trace() {
        return this.trace;
    }

    SwarmGate gate() {
        return this.gate;
    }

    static String setting(String property, String env, String dflt) {
        var v = System.getProperty(property);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    public Swarm swarm() {
        return this.swarm;
    }

    @Override
    public void contribute(HandlerMounts mounts) {
        mounts.mount("/swarm/v1", new SwarmHttp(this.swarm, new SecureRandom().nextLong(), this.gate, this.trace));
    }

    public boolean closed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.swarm.close();
        }
    }
}
