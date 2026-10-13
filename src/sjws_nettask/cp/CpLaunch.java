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
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.TcpTransport;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.service.CpReloadable;

/**
 * Entry point of the CONTROL role, reached from {@code ServiceLaunch} by name only, so a data or standalone
 * process loads no cp class (control-plane-hosting.md §1, §5). Step 1 (config, then the fail-closed timing
 * check) happens here; a witness then goes to {@link CpWitnessHost}, a full voter to {@link CpHost}.
 */
public final class CpLaunch {

    private CpLaunch() {
    }

    /** Called reflectively by {@code sjws_nettask.service.ServiceLaunch}. */
    public static CpReloadable launch(Map<String, Object> yamlCp, Path configBase, PrintStream err)
            throws IOException {
        CpConfig config;
        try {
            config = CpConfig.fromSystem(yamlCp, configBase);
        } catch (IllegalArgumentException e) {
            throw new IOException("control role config: " + e.getMessage(), e);
        }
        return start(config, production(yamlCp, configBase), err);
    }

    public static CpReloadable start(CpConfig config, CpHostDeps deps, PrintStream err) throws IOException {
        for (var warning : config.warnings()) {
            err.println(warning);
        }
        try {
            CpTiming.check(CpGroup.options(), config.maxClockDriftRatio(), config.leaseSafetyMargin());
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (config.selfIsWitness()) {
            return CpWitnessHost.start(config, deps);
        }
        return CpHost.start(config, deps, err);
    }

    static NodeId nodeId(CpConfig.Peer peer) {
        return new NodeId(peer.wire(), peer.id());
    }

    static VoterSet voters(CpConfig config) {
        var members = new ArrayList<Member>();
        var ids = new HashSet<NodeId>();
        Member witness = null;
        var full = new ArrayList<Member>();
        for (var peer : config.peers()) {
            var address = peer.address().isUnresolved()
                    ? new InetSocketAddress(peer.address().getHostString(), peer.address().getPort())
                    : peer.address();
            var member = new Member(nodeId(peer), new SiteId("cp"), address, 1L, MemberState.ALIVE, peer.witness());
            members.add(member);
            ids.add(member.id());
            if (peer.witness()) {
                witness = member;
            } else {
                full.add(member);
            }
        }
        if (witness != null) {
            return VoterSet.dataAndWitness(full.get(0), full.get(1), witness);
        }
        return VoterSet.majority(members, ids);
    }

    /** Frames buffered per peer (and inbound) before a drop is counted; the Raft thread never blocks. */
    static final int PEER_QUEUE = 1024;

    /**
     * The voter links: {@link TcpTransport} on {@code self}'s {@code cp.peers} address, every socket from
     * {@link CpPeerSockets} over {@code peerTls}. Without TLS every peer must be loopback (tests, local dev).
     */
    static TcpTransport tcpTransport(CpConfig config, CpServerTls peerTls, ClusterKey key, Clock clock)
            throws IOException {
        var members = voters(config).members();
        TcpTransport.SocketFactory sockets;
        if (peerTls != null) {
            sockets = new CpPeerSockets(peerTls);
        } else {
            for (var member : members) {
                var host = member.address().getAddress();
                if (host == null || !host.isLoopbackAddress()) {
                    throw new IOException("cp.peers: voter links without cp.tls must be loopback, not "
                            + member.address());
                }
            }
            sockets = TcpTransport.SocketFactory.PLAIN;
        }
        return TcpTransport.open(nodeId(config.self()), members, key, clock, PEER_QUEUE, sockets);
    }

    /**
     * The voter frame key: {@code WEB_SERVER_HA_CLUSTER_KEY} (and {@code _PREVIOUS} during rotation), the same on
     * every voter. TcpTransport derives its own HKDF key from it, so it is not the heartbeat key. Never logged.
     */
    static ClusterKey peerKey(Map<String, String> env) throws IOException {
        var raw = env.get(ClusterKey.ENV_CURRENT);
        if (raw == null || raw.isBlank()) {
            throw new IOException("cp.peers needs the voter frame key: set " + ClusterKey.ENV_CURRENT
                    + " (same value on every voter); refusing to start");
        }
        try {
            return ClusterKey.fromEnvironment(env);
        } catch (IllegalArgumentException e) {
            throw new IOException("cp.peers voter frame key refused: " + e.getMessage(), e);
        }
    }

    /**
     * The shipped dependencies: {@link KeyRing#load} and {@link CpTls#serverContext} from Security (#160), and
     * {@link TcpTransport} (#158) over the {@code cp-peer} listener TLS for {@code cp.peers}.
     */
    static CpHostDeps production(Map<String, Object> yamlCp, Path configBase) {
        var tls = yamlCp == null ? null : yamlCp.get("tls");
        boolean insecure = yamlCp != null && Boolean.TRUE.equals(yamlCp.get("insecureSkipPermissionChecks"));
        return new CpHostDeps() {
            @Override
            public Clock clock() {
                return Clock.SYSTEM;
            }

            @Override
            public ClusterTransport transport(CpConfig config, CpServerTls peerTls) throws IOException {
                return tcpTransport(config, peerTls, peerKey(System.getenv()), Clock.SYSTEM);
            }

            @Override
            public KeyRing loadKeys(CpKeyConfig config) throws CpKeyException {
                return KeyRing.load(config);
            }

            @Override
            public CpServerTls serverTls(String listener) throws CpKeyException {
                return CpTls.serverContext(listenerConfig(tls, configBase, listener, insecure));
            }
        };
    }

    /**
     * {@code cp.tls.{cert,key,ca,adminCa,peerCa,revocations}}: the watch listener trusts {@code ca} (data nodes),
     * the admin listener {@code adminCa}, the voter links ({@code cp-peer}) {@code peerCa}, or {@code ca} when it
     * is not set. Paths only.
     */
    static CpListenerConfig listenerConfig(Object tls, Path base, String listener, boolean insecure)
            throws CpKeyException {
        if (!(tls instanceof Map<?, ?> map)) {
            throw new CpKeyException("cp.tls is required for " + listener);
        }
        for (var key : map.keySet()) {
            if (!java.util.Set.of("cert", "key", "ca", "adminCa", "peerCa", "revocations").contains(key)) {
                throw new CpKeyException("Unknown key cp.tls." + key);
            }
        }
        var ca = switch (listener) {
            case "cp-admin" -> "adminCa";
            case "cp-peer" -> map.get("peerCa") != null ? "peerCa" : "ca";
            default -> "ca";
        };
        try {
            return new CpListenerConfig(listener, path(map, "cert", base), path(map, "key", base),
                    path(map, ca, base), path(map, "revocations", base), insecure);
        } catch (IllegalArgumentException e) {
            throw new CpKeyException("cp.tls: " + e.getMessage(), e);
        }
    }

    private static Path path(Map<?, ?> map, String key, Path base) throws CpKeyException {
        var raw = map.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new CpKeyException("cp.tls." + key + " must be a path");
        }
        var p = Path.of(s);
        return p.isAbsolute() || base == null ? p : base.resolve(p);
    }
}
