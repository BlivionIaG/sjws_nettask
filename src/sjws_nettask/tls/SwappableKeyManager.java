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
package sjws_nettask.tls;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * One server identity, swapped for new handshakes. BoringSSL asks the key manager on every
 * handshake, so replacing the material here changes the certificate without rebuilding the QUIC
 * context. Connections that already finished their handshake keep the previous one.
 */
public final class SwappableKeyManager extends X509ExtendedKeyManager {

    static final String ALIAS = "server";

    private final AtomicReference<TlsMaterial> material;

    public SwappableKeyManager(TlsMaterial initial) {
        this.material = new AtomicReference<>(initial);
    }

    public void set(TlsMaterial material) {
        this.material.set(material);
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        return alias(keyType);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return alias(keyType);
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        return ALIAS.equals(alias) ? this.material.get().chain() : null;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        return ALIAS.equals(alias) ? this.material.get().key() : null;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return alias(keyType) == null ? new String[0] : new String[] {ALIAS};
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
        return null;
    }

    private String alias(String keyType) {
        if (keyType == null) {
            return ALIAS;
        }
        var have = this.material.get().key().getAlgorithm().toUpperCase(Locale.ROOT);
        var want = keyType.toUpperCase(Locale.ROOT);
        if (have.equals(want)) {
            return ALIAS;
        }
        if (have.equals("EC") && (want.equals("ECDSA") || want.equals("EC_EC"))) {
            return ALIAS;
        }
        return null;
    }
}
