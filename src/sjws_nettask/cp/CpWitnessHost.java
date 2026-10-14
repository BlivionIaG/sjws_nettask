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
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.service.CpReloadable;

/**
 * A witness CONTROL-role process: joins {@code cp-config} as a vote-only voter. No key ring, no store, no
 * watch or admin listener (control-plane-hosting.md §6.1, §8.7). SIGHUP has nothing to reload.
 */
final class CpWitnessHost implements CpReloadable {

    private final CpWitness witness;
    private final ClusterTransport transport;
    private final CpServerTls peerTls;

    private CpWitnessHost(CpWitness witness, ClusterTransport transport, CpServerTls peerTls) {
        this.witness = witness;
        this.transport = transport;
        this.peerTls = peerTls;
    }

    static CpReloadable start(CpConfig config, CpHostDeps deps) throws IOException {
        CpServerTls peerTls;
        try {
            peerTls = deps.serverTls("cp-peer");
        } catch (CpKeyException e) {
            throw new IOException("control role: cp-peer TLS refused: " + e.getMessage(), e);
        }
        var transport = deps.transport(config, peerTls);
        try {
            var witness = CpWitness.open(config.keys().stateDir(), CpLaunch.nodeId(config.self()),
                    CpLaunch.voters(config), deps.clock(), transport);
            witness.start();
            return new CpWitnessHost(witness, transport, peerTls);
        } catch (IOException | RuntimeException e) {
            closeOwned(transport);
            throw e;
        }
    }

    CpWitness witness() {
        return witness;
    }

    /** SIGHUP: only the voter-link TLS, in place; the old material stays on failure. */
    @Override
    public String reload() {
        if (peerTls == null) {
            return null;
        }
        try {
            peerTls.reloadTls();
            return null;
        } catch (CpKeyException | RuntimeException e) {
            return "cp-peer TLS reload failed, keeping the current material: " + e.getMessage();
        }
    }

    @Override
    public void close() {
        witness.close();
        closeOwned(transport);
    }

    private static void closeOwned(ClusterTransport transport) {
        if (transport instanceof AutoCloseable owned) {
            try {
                owned.close();
            } catch (Exception e) {
                System.getLogger(CpWitnessHost.class.getName()).log(System.Logger.Level.WARNING,
                        "cp transport close failed: " + e.getMessage());
            }
        }
    }
}
