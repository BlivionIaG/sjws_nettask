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
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterTransport;

/**
 * What a CONTROL-role host needs from outside (control-plane-hosting.md §1). {@link CpLaunch#production} is the
 * shipped set: {@link KeyRing#load} and {@link CpTls#serverContext} (Security, #160). Tests pass
 * {@link KeyRing#generate}, plain loopback listeners and a shared in-process transport.
 */
public interface CpHostDeps {

    Clock clock();

    /**
     * The voter transport for {@code cp.peers}. Called only when peers are configured. {@code peerTls} is what
     * {@link #serverTls} returned for {@code cp-peer}; null means plain sockets, accepted only when every peer
     * address is loopback. A transport that is {@link AutoCloseable} is owned and closed by the host.
     */
    ClusterTransport transport(CpConfig config, CpServerTls peerTls) throws IOException;

    /** Full voters only; a witness never calls this. */
    KeyRing loadKeys(CpKeyConfig config) throws CpKeyException;

    /**
     * Server TLS for one listener ({@code cp-watch}, {@code cp-admin}, {@code cp-peer} for the voter links). Null means a plain listener, which
     * {@link CpHost} accepts only on a loopback address (tests and local development).
     */
    CpServerTls serverTls(String listener) throws CpKeyException;
}
