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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.cluster.SimTransport;

/** Loopback listeners, {@link KeyRing#generate} and one shared in-process transport. */
final class CpTestDeps implements CpHostDeps {

    final ClusterTransport net;
    final AtomicInteger keyLoads = new AtomicInteger();
    final AtomicReference<Object> tlsInstalled = new AtomicReference<>("tls-0");
    final AtomicInteger tlsBuilt = new AtomicInteger();
    volatile boolean failTls;
    volatile boolean failKeys;
    volatile boolean failAdmin;

    CpTestDeps(ClusterTransport net) {
        this.net = net;
    }

    CpTestDeps() {
        this(new SimTransport());
    }

    @Override
    public Clock clock() {
        return Clock.SYSTEM;
    }

    @Override
    public ClusterTransport transport(CpConfig config) {
        return net;
    }

    @Override
    public KeyRing loadKeys(CpKeyConfig config) throws CpKeyException {
        if (failKeys) {
            throw new CpKeyException("test: bad bundle");
        }
        keyLoads.incrementAndGet();
        return KeyRing.generate("online-" + keyLoads.get(), Long.MAX_VALUE, 0, Long.MAX_VALUE);
    }

    @Override
    public Listeners listeners() {
        return new Listeners() {
            @Override
            public Object prepareTls() throws java.security.GeneralSecurityException {
                if (failTls) {
                    throw new java.security.GeneralSecurityException("test: bad cert");
                }
                return "tls-" + tlsBuilt.incrementAndGet();
            }

            @Override
            public AutoCloseable openWatch(CpStore store, InetSocketAddress address, Object tls) throws IOException {
                return WatchServer.start(store, new InetSocketAddress("127.0.0.1", 0), System::currentTimeMillis,
                        null);
            }

            @Override
            public AutoCloseable openAdmin(CpStore store, InetSocketAddress address, Object tls) throws IOException {
                if (failAdmin) {
                    throw new IOException("test: admin bind failed");
                }
                return AdminServer.startPlainForTests(store, System.currentTimeMillis(), 60_000L);
            }

            @Override
            public void swapTls(Object tls) {
                tlsInstalled.set(tls);
            }
        };
    }
}
