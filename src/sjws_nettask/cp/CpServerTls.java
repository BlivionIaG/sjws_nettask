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
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import sjws_nettask.config.ConfigException;
import sjws_nettask.config.SecureKeyFile;

/**
 * Server-side TLS for one CP listener: TLS 1.3 only, client certificate required. The
 * {@link SSLContext} is stable for the listener's life; {@link #reloadTls()} swaps the material
 * behind it.
 */
public final class CpServerTls {

    static final int MAX_KEY_BYTES = 64 * 1024;

    private final CpListenerConfig config;
    private final AdminTls tls;

    private CpServerTls(CpListenerConfig config, AdminTls tls) {
        this.config = config;
        this.tls = tls;
    }

    static CpServerTls open(CpListenerConfig config, AdminTls.Audit audit) throws CpKeyException {
        checkKey(config);
        try {
            return new CpServerTls(config, AdminTls.openListener(config.name(), config.certPem(), config.keyPem(), null,
                    config.caPem(), config.revocations(), audit, CpPeerSockets.LISTENER.equals(config.name())));
        } catch (IOException | RuntimeException e) {
            throw new CpKeyException(config.name() + " TLS material refused: " + e.getMessage(), e);
        }
    }

    /** Hand this to the listener factories; it never changes on reload. */
    public SSLContext context() {
        return tls.context();
    }

    /** TLS 1.3 only, {@code needClientAuth}. Apply to every accepted socket or engine. */
    public SSLParameters parameters() {
        return CpTls.parameters(tls.context(), true);
    }

    /**
     * This listener's own leaf certificate, read from its {@code certPem} (the first certificate in the file).
     *
     * @throws IOException unreadable, or no certificate in the file
     */
    java.security.cert.X509Certificate certificate() throws IOException {
        var certs = AdminTls.trustCertificates(config.certPem());
        if (certs.length == 0) {
            throw new IOException(config.name() + " certificate file holds no certificate");
        }
        return certs[0];
    }

    /** Successful loads so far, starting at 1. */
    public long generation() {
        return tls.generation();
    }

    /**
     * Builds the new material from disk (key owner/mode check first), then swaps it in one step.
     * On any failure the current material stays in use.
     *
     * @throws CpKeyException why the reload was refused; the old material is still active
     */
    public void reloadTls() throws CpKeyException {
        checkKey(config);
        var why = tls.reload();
        if (why != null) {
            throw new CpKeyException(config.name() + " TLS reload refused, keeping previous material: " + why);
        }
    }

    private static void checkKey(CpListenerConfig config) throws CpKeyException {
        if (config.insecureSkipPermissionChecks()) {
            return;
        }
        try {
            SecureKeyFile.read(config.keyPem(), config.name() + ".key", MAX_KEY_BYTES);
        } catch (ConfigException e) {
            throw new CpKeyException(e.getMessage(), e);
        }
    }
}
