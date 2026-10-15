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

    /** The CP-only Raft frame key, same value on every voter. Data nodes never hold it. */
    static final String RAFT_KEY_ENV = "SJWS_CP_RAFT_KEY";
    /** The previous Raft frame key, still accepted during a rotation. */
    static final String RAFT_KEY_PREVIOUS_ENV = "SJWS_CP_RAFT_KEY_PREVIOUS";

    /**
     * The voter frame key: {@value #RAFT_KEY_ENV} (and {@value #RAFT_KEY_PREVIOUS_ENV} during rotation), its own
     * secret, never derived from the HA cluster key that data nodes hold. Refused when missing, malformed, or equal
     * to {@code WEB_SERVER_HA_CLUSTER_KEY} (or its previous key). No value is ever printed.
     */
    static ClusterKey peerKey(Map<String, String> env) throws IOException {
        var raw = env.get(RAFT_KEY_ENV);
        if (raw == null || raw.isBlank()) {
            throw new IOException("cp.peers needs the CP-only Raft frame key: set " + RAFT_KEY_ENV
                    + " (same value on every voter, never the HA cluster key); refusing to start");
        }
        byte[] current;
        byte[] previous = null;
        try {
            current = ClusterKey.parse(raw);
            var prev = env.get(RAFT_KEY_PREVIOUS_ENV);
            if (prev != null) {
                if (prev.isBlank()) {
                    throw new IllegalArgumentException(RAFT_KEY_PREVIOUS_ENV + " is empty");
                }
                previous = ClusterKey.parse(prev);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(RAFT_KEY_ENV + " refused: " + e.getMessage(), e);
        }
        for (var haName : new String[] {ClusterKey.ENV_CURRENT, ClusterKey.ENV_PREVIOUS}) {
            var ha = env.get(haName);
            if (ha == null || ha.isBlank()) {
                continue;
            }
            byte[] haKey;
            try {
                haKey = ClusterKey.parse(ha);
            } catch (IllegalArgumentException e) {
                continue; // not a usable HA key, so it cannot equal ours
            }
            if (java.security.MessageDigest.isEqual(haKey, current)
                    || (previous != null && java.security.MessageDigest.isEqual(haKey, previous))) {
                throw new IOException(RAFT_KEY_ENV + " must differ from " + haName
                        + ": data nodes hold the HA key; refusing to start");
            }
        }
        return ClusterKey.of(current, previous);
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
     * {@code cp.tls.{cert,key,ca,adminCa,peerCert,peerKey,peerCa,revocations}}: the watch listener trusts {@code ca}
     * (data nodes), the admin listener {@code adminCa}. The voter links ({@code cp-peer}) use their own
     * {@code peerCert}/{@code peerKey} and trust {@code peerCa}; all three are required, with no fallback to the
     * shared cert or {@code ca}, and {@code peerCa} must not be the same CA as {@code ca} (data-node certs would
     * pass). An {@code adminCa} equal to {@code ca} is allowed but warned about. Paths only.
     */
    static CpListenerConfig listenerConfig(Object tls, Path base, String listener, boolean insecure)
            throws CpKeyException {
        if (!(tls instanceof Map<?, ?> map)) {
            throw new CpKeyException("cp.tls is required for " + listener);
        }
        for (var key : map.keySet()) {
            if (!java.util.Set.of("cert", "key", "ca", "adminCa", "peerCert", "peerKey", "peerCa", "revocations")
                    .contains(key)) {
                throw new CpKeyException("Unknown key cp.tls." + key);
            }
        }
        boolean peer = CpPeerSockets.LISTENER.equals(listener);
        var ca = switch (listener) {
            case "cp-admin" -> "adminCa";
            case CpPeerSockets.LISTENER -> "peerCa";
            default -> "ca";
        };
        if (peer) {
            for (var required : new String[] {"peerCert", "peerKey", "peerCa"}) {
                if (map.get(required) == null) {
                    throw new CpKeyException("cp.tls." + required + " is required with cp.peers (voter links have"
                            + " their own certificate and CA; no fallback to cp.tls.cert/ca)");
                }
            }
            if (sameCa(path(map, "peerCa", base), path(map, "ca", base))) {
                throw new CpKeyException("cp.tls.peerCa must not be the data-node CA cp.tls.ca: data-node"
                        + " certificates would pass on the voter links");
            }
        } else if ("cp-admin".equals(listener) && sameCa(path(map, "adminCa", base), path(map, "ca", base))) {
            System.getLogger(CpLaunch.class.getName()).log(System.Logger.Level.WARNING,
                    "cp.tls.adminCa is the same CA as cp.tls.ca: data-node certificates chain to the admin CA");
        }
        try {
            return new CpListenerConfig(listener, path(map, peer ? "peerCert" : "cert", base),
                    path(map, peer ? "peerKey" : "key", base), path(map, ca, base), path(map, "revocations", base),
                    insecure);
        } catch (IllegalArgumentException e) {
            throw new CpKeyException("cp.tls: " + e.getMessage(), e);
        }
    }

    /**
     * Same path, or the two bundles share a CA public key (re-encoded or re-issued PEMs of the same CA still
     * match; byte comparison missed those). Unreadable or unparsable files are left to the TLS loader, which
     * still fails closed: {@code AdminTls} refuses to start a listener whose CA bundle it cannot read or parse, so
     * {@code false} here never lets an unchecked CA through.
     */
    static boolean sameCa(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.normalize().equals(b.normalize())) {
            return true;
        }
        try {
            var keys = new java.util.HashSet<String>();
            for (var cert : AdminTls.trustCertificates(a)) {
                keys.add(java.util.HexFormat.of().formatHex(cert.getPublicKey().getEncoded()));
            }
            for (var cert : AdminTls.trustCertificates(b)) {
                if (keys.contains(java.util.HexFormat.of().formatHex(cert.getPublicKey().getEncoded()))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
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
