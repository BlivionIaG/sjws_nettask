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
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.security.auth.x500.X500Principal;
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
    private volatile Function<X509Certificate, String> leafCheck = leaf -> null;
    private volatile Runnable afterReload = () -> { };

    private CpServerTls(CpListenerConfig config, AdminTls tls) {
        this.config = config;
        this.tls = tls;
    }

    static CpServerTls open(CpListenerConfig config, AdminTls.Audit audit) throws CpKeyException {
        return open(config, audit, CpPeerSockets.LISTENER.equals(config.name()));
    }

    /** @param actsAsClient also dial with this context (peer links): same certificate, CA and revocations. */
    static CpServerTls open(CpListenerConfig config, AdminTls.Audit audit, boolean actsAsClient)
            throws CpKeyException {
        checkKey(config);
        try {
            return new CpServerTls(config, AdminTls.openListener(config.name(), config.certPem(), config.keyPem(), null,
                    config.caPem(), config.revocations(), audit, actsAsClient));
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

    /** This listener's own leaf certificate, from the loaded material this context presents. */
    java.security.cert.X509Certificate certificate() {
        return tls.leaf();
    }

    /**
     * A check every later {@link #reloadTls()} applies to the new leaf certificate before swapping it in: a non-null
     * answer refuses the reload and keeps the current material.
     */
    void checkLeafOnReload(Function<X509Certificate, String> check) {
        this.leafCheck = Objects.requireNonNull(check, "check");
    }

    /** Runs after a successful {@link #reloadTls()} (peer links use this to drop connections that fail policy). */
    public void afterReload(Runnable hook) {
        this.afterReload = Objects.requireNonNull(hook, "hook");
    }

    /** Successful loads so far, starting at 1. */
    public long generation() {
        return tls.generation();
    }

    /**
     * After a peer-link handshake (including a resumed one), or when re-checking an established peer
     * after reload, re-apply trust-anchor and revocation checks against the current material.
     * {@code peerIsClient} is true when this side accepted. Identity (SAN) binding is checked by the
     * peer socket layer after this returns.
     */
    public void revalidatePeer(SSLSocket socket, boolean peerIsClient) throws IOException {
        try {
            var presented = socket.getSession().getPeerCertificates();
            var chain = new X509Certificate[presented.length];
            for (int i = 0; i < presented.length; i++) {
                if (!(presented[i] instanceof X509Certificate x509)) {
                    throw new IOException(config.name() + " peer presented a non-X.509 certificate");
                }
                chain[i] = x509;
            }
            tls.revalidatePeer(chain, peerIsClient);
        } catch (SSLPeerUnverifiedException | CertificateException e) {
            throw new IOException(config.name() + " peer refused after handshake: " + e.getMessage(), e);
        }
    }

    /** Same checks as {@link #revalidatePeer(SSLSocket, boolean)} when only the peer chain is available (HTTPS exchanges). */
    public void revalidatePeer(X509Certificate[] chain, boolean peerIsClient) throws IOException {
        try {
            tls.revalidatePeer(chain, peerIsClient);
        } catch (CertificateException e) {
            throw new IOException(config.name() + " peer refused after handshake: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the new material from disk (key owner/mode check first), then swaps it in one step.
     * On any failure, including a refusal by the {@link #checkLeafOnReload} check, the current material stays in use.
     *
     * @throws CpKeyException why the reload was refused; the old material is still active
     */
    public void reloadTls() throws CpKeyException {
        checkKey(config);
        var why = tls.reload(leafCheck);
        if (why != null) {
            throw new CpKeyException(config.name() + " TLS reload refused, keeping previous material: " + why);
        }
        afterReload.run();
    }

    /** Subject and serial only, for WARN lines when a peer is refused (no fingerprint or PEM). */
    public static String peerLogId(X509Certificate leaf) {
        if (leaf == null) {
            return "unknown";
        }
        return leaf.getSubjectX500Principal().getName(X500Principal.RFC2253)
                + " serial:" + leaf.getSerialNumber().toString(16);
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
