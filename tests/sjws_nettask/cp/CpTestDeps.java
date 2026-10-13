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

import java.util.concurrent.atomic.AtomicInteger;
import sjws_nettask.cluster.Clock;
import sjws_nettask.cluster.ClusterTransport;
import sjws_nettask.cluster.SimTransport;

/** Loopback listeners, {@link KeyRing#generate} and one shared in-process transport. */
class CpTestDeps implements CpHostDeps {

    final ClusterTransport net;
    final AtomicInteger keyLoads = new AtomicInteger();
    volatile boolean failKeys;
    volatile boolean insecure;

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
        if (insecure) {
            try {
                return insecureRing();
            } catch (Exception e) {
                throw new CpKeyException("test", e);
            }
        }
        return KeyRing.generate("online-" + keyLoads.get(), Long.MAX_VALUE, 0, Long.MAX_VALUE);
    }

    /** Plain loopback listeners. */
    @Override
    public CpServerTls serverTls(String listener) throws CpKeyException {
        return null;
    }

    /** A ring as {@link KeyRing#load} would return it with {@code insecureSkipPermissionChecks}. */
    static KeyRing insecureRing() throws Exception {
        var gen = java.security.KeyPairGenerator.getInstance("Ed25519");
        var root = gen.generateKeyPair();
        var online = gen.generateKeyPair();
        var spki = root.getPublic().getEncoded();
        var key = new TrustKey("online-x", online.getPublic().getEncoded(), Long.MAX_VALUE, 0, Long.MAX_VALUE);
        var bundle = TrustBundle.parse(TrustBundle.sign(root.getPrivate(), spki, 1, 0, Long.MAX_VALUE, 0,
                java.util.List.of(key)), spki);
        return KeyRing.loaded(spki, online.getPrivate(), key, bundle, TrustBundle.fingerprint(spki), true);
    }
}
