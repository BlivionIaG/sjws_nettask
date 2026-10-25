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
package sjws_nettask.swarm.peer;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import sjws_nettask.cp.CpKeyException;
import sjws_nettask.cp.CpListenerConfig;
import sjws_nettask.cp.CpServerTls;
import sjws_nettask.cp.CpTls;

/**
 * Fail-closed startup checks for the swarm peer links (#236 §9.7, T10). Loaded only when {@code sjws.swarm.peers} or
 * a swarm peer key is set; with neither, no class of this package loads. Refuses, naming the setting and never a
 * value:
 * <ul>
 * <li>a peer key with no peer map;</li>
 * <li>a map that is malformed or does not list this node;</li>
 * <li>with two or more nodes: no key, a key equal to the HA or CP key, or missing or unusable mTLS material
 * ({@code sjws.swarm.peer.tls.cert}, {@code .key}, {@code .ca}; {@code .revocations} optional), and a certificate
 * that does not carry this node's pinned SAN. mTLS is required whenever another node is listed, loopback or not
 * (r4 §9.5): there is no HMAC-only mode between peers.</li>
 * </ul>
 * A single-node map needs nothing and opens no transport.
 */
public final class PeerStartup {

    public static final String TLS_PREFIX = "sjws.swarm.peer.tls.";

    /** What the startup check found. {@code tls} and {@code key} are null for a single-node map. */
    public record Checked(PeerConfig config, sjws_nettask.cluster.ClusterKey key, CpServerTls tls) {
        public String describe() {
            return config.multiNode()
                    ? "peer map with " + config.nodes().size() + " nodes checked (mTLS 1.3, pinned SANs, peer key"
                            + (key.hasPrevious() ? " + previous" : "") + "); no swarm family is forwarded yet (#236)"
                    : "peer map lists only this node: no peer transport";
        }
    }

    private PeerStartup() {
    }

    /** Runs every check; {@link IllegalArgumentException} says what to fix. */
    public static Checked check(Function<String, String> props, Map<String, String> env, String selfName) {
        var raw = PeerConfig.setting(props, env, PeerConfig.PROPERTY, PeerConfig.ENV);
        if (raw == null) {
            if (PeerKeys.anySet(props, env)) {
                throw new IllegalArgumentException("a swarm peer key is set but " + PeerConfig.PROPERTY
                        + " is not; set the peer map or remove the key; refusing to start");
            }
            return null;
        }
        var config = PeerConfig.parse(raw, selfName);
        if (!config.multiNode()) {
            return new Checked(config, null, null);
        }
        var key = PeerKeys.load(props, env);
        var tls = tls(props, env, config);
        return new Checked(config, key, tls);
    }

    private static CpServerTls tls(Function<String, String> props, Map<String, String> env, PeerConfig config) {
        var cert = tlsPath(props, env, "cert");
        var keyPem = tlsPath(props, env, "key");
        var ca = tlsPath(props, env, "ca");
        var revocations = tlsPath(props, env, "revocations");
        if (cert == null || keyPem == null || ca == null) {
            throw new IllegalArgumentException(PeerConfig.PROPERTY + " lists more than this node, so mTLS is required:"
                    + " set " + TLS_PREFIX + "cert, " + TLS_PREFIX + "key and " + TLS_PREFIX + "ca (a CA that issues"
                    + " only swarm peer certificates); refusing to start");
        }
        CpServerTls tls;
        try {
            tls = CpTls.peerContext(new CpListenerConfig(PeerSockets.LISTENER, cert, keyPem, ca, revocations, false));
        } catch (CpKeyException | RuntimeException e) {
            throw new IllegalArgumentException("swarm peer TLS material refused: " + e.getMessage());
        }
        java.security.cert.X509Certificate leaf;
        try (var in = java.nio.file.Files.newInputStream(cert)) {
            leaf = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(in);
        } catch (java.io.IOException | java.security.cert.CertificateException e) {
            throw new IllegalArgumentException(TLS_PREFIX + "cert is unreadable");
        }
        if (!PeerSockets.sans(leaf).contains(config.self().san())) {
            throw new IllegalArgumentException(TLS_PREFIX + "cert does not carry this node's pinned SAN "
                    + config.self().san() + "; peers would refuse it; refusing to start");
        }
        return tls;
    }

    private static Path tlsPath(Function<String, String> props, Map<String, String> env, String name) {
        var v = PeerConfig.setting(props, env, TLS_PREFIX + name,
                "SJWS_SWARM_PEER_TLS_" + name.toUpperCase(java.util.Locale.ROOT));
        return v == null ? null : Path.of(v);
    }

    /** Opens this node's links with the checked material (mTLS whenever another node is listed). */
    public static PeerLink open(Checked checked, PeerLink.Owner owner, PeerLink.Limits limits) throws java.io.IOException {
        if (!checked.config().multiNode()) {
            throw new IllegalStateException("single-node peer map: nothing to open");
        }
        return PeerLink.open(checked.config(), checked.key(), new PeerSockets(checked.tls(), checked.config()), owner,
                limits);
    }
}
