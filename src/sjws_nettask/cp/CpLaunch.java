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
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.SiteId;
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
        return CpHost.start(config, deps);
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

    /** The shipped dependencies. Key loading and listener TLS are Security's stubs today, so this fails closed. */
    static CpHostDeps production(Map<String, Object> yamlCp, Path configBase) {
        var tls = CpListenerConfig.fromYaml(yamlCp == null ? null : yamlCp.get("tls"), configBase);
        return new CpHostDeps() {
            @Override
            public Clock clock() {
                return Clock.SYSTEM;
            }

            @Override
            public ClusterTransport transport(CpConfig config) throws IOException {
                throw new IOException("cp.peers needs the voter network transport (TcpTransport, PR #158),"
                        + " which is not on master yet; refusing to start");
            }

            @Override
            public KeyRing loadKeys(CpKeyConfig config) throws CpKeyException {
                return KeyRing.load(config);
            }

            @Override
            public Listeners listeners() {
                return new Listeners() {
                    @Override
                    public Object prepareTls() throws java.security.GeneralSecurityException, IOException {
                        return CpTls.serverContext(tls);
                    }

                    @Override
                    public AutoCloseable openWatch(CpStore store, InetSocketAddress address, Object context)
                            throws IOException {
                        throw new IOException("mTLS watch listener is Security's (CpListenerConfig); not wired yet");
                    }

                    @Override
                    public AutoCloseable openAdmin(CpStore store, InetSocketAddress address, Object context)
                            throws IOException {
                        throw new IOException("mTLS admin listener is Security's (CpListenerConfig); not wired yet");
                    }

                    @Override
                    public void swapTls(Object context) {
                        // Security's reloadTls() installs the context.
                    }
                };
            }
        };
    }
}
