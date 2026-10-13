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
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterTransport;

/**
 * What a CONTROL-role host needs from outside (control-plane-hosting.md §1). {@link CpLaunch#production} is the
 * shipped set; tests pass loopback listeners, {@link KeyRing#generate} and a shared in-process transport.
 */
public interface CpHostDeps {

    Clock clock();

    /** The voter transport for {@code cp.peers}. Called only when peers are configured. */
    ClusterTransport transport(CpConfig config) throws IOException;

    /** Full voters only; a witness never calls this. */
    KeyRing loadKeys(CpKeyConfig config) throws CpKeyException;

    Listeners listeners();

    /** WatchServer and AdminServer binding. TLS material is Security's ({@link CpTls#serverContext}). */
    interface Listeners {

        /** Builds (but does not install) the listener TLS material. */
        Object prepareTls() throws GeneralSecurityException, IOException;

        AutoCloseable openWatch(CpStore store, InetSocketAddress address, Object tls) throws IOException;

        AutoCloseable openAdmin(CpStore store, InetSocketAddress address, Object tls) throws IOException;

        /** Installs material from {@link #prepareTls()} for new handshakes. Must not fail. */
        void swapTls(Object tls);
    }
}
