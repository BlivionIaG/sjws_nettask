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
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import sjws_nettask.tls.TlsMaterial;

/**
 * Opt-in mTLS for the node-to-CP watch channel ({@link WatchServer} and {@link DataWatch}). Null
 * means off: plain HTTP on loopback, exactly as before, and nothing here is loaded. When on, the
 * channel is TLS 1.3 only; the CP requires a client certificate issued by {@code caPem}; the node
 * trusts only {@code caPem} (a pinned CA, not the system store) and checks the CP certificate's
 * identity against {@code peerName}.
 *
 * <p>Identity binding: a node certificate names its node id as a URI SAN
 * {@code urn:sjws:node:<id>}, a DNS SAN, or the subject CN. The CP refuses a request whose
 * {@code X-Sjws-Node-Id} is not one of them.
 *
 * @param certPem this side's certificate chain (PEM)
 * @param keyPem this side's private key (PEM, unencrypted, mode 0600)
 * @param caPem the pinned CA bundle the peer must chain to
 * @param revocations CP side only: CRL or denylist file as for {@link AdminTls}; null for none
 * @param peerName node side only: the identity the CP certificate must carry (URI SAN, DNS SAN or
 *        CN); null to check the dialled host name instead
 * @param watchMillis CP side only: poll the files this often and reload on change; 0 disables
 */
public record CpTls(Path certPem, Path keyPem, Path caPem, Path revocations, String peerName, long watchMillis) {

    public static final String NODE_URI_PREFIX = "urn:sjws:node:";

    public CpTls {
        if (certPem == null || keyPem == null || caPem == null) {
            throw new IllegalArgumentException("cpTls requires cert, key and ca");
        }
        if (watchMillis < 0) {
            throw new IllegalArgumentException("cpTls watchMillis must be >= 0");
        }
    }

    /** Reloadable, revocation-checked server context; refusals are audited as {@code cp-tls.handshake}. */
    AdminTls server(AdminTls.Audit audit) throws IOException {
        return AdminTls.openListener("cp-tls", certPem, keyPem, null, caPem, revocations, audit);
    }

    /** Client context: this node's certificate, trusting only the pinned CA. */
    SSLContext client() throws IOException {
        var material = TlsMaterial.fromPem(certPem, keyPem, null);
        var anchors = AdminTls.trustCertificates(caPem);
        if (anchors.length == 0) {
            throw new IOException("cpTls CA bundle " + caPem + " contains no certificates");
        }
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            for (int i = 0; i < anchors.length; i++) {
                store.setCertificateEntry("ca" + i, anchors[i]);
            }
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            var tls = SSLContext.getInstance("TLSv1.3");
            X509ExtendedTrustManager pinned = null;
            for (var tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x) {
                    pinned = x;
                }
            }
            if (pinned == null) {
                throw new IOException("cpTls: no X.509 trust manager");
            }
            tls.init(material.keyManagerFactory().getKeyManagers(),
                    new TrustManager[] {new PeerCheck(pinned, peerName)}, null);
            return tls;
        } catch (GeneralSecurityException e) {
            throw new IOException("cpTls material rejected: " + e.getMessage(), e);
        }
    }

    /** Chain validation against the pinned CA, then the CP identity check when {@code peerName} is set. */
    private static final class PeerCheck extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager pinned;
        private final String peerName;

        PeerCheck(X509ExtendedTrustManager pinned, String peerName) {
            this.pinned = pinned;
            this.peerName = peerName;
        }

        private void identity(X509Certificate[] chain) throws CertificateException {
            if (peerName != null && (chain == null || chain.length == 0 || !carries(chain[0], peerName))) {
                throw new CertificateException("control-plane certificate does not carry " + peerName);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            pinned.checkServerTrusted(chain, authType, socket);
            identity(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            pinned.checkServerTrusted(chain, authType, engine);
            identity(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            pinned.checkServerTrusted(chain, authType);
            identity(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("node side does not accept TLS clients");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("node side does not accept TLS clients");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("node side does not accept TLS clients");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return pinned.getAcceptedIssuers();
        }
    }

    /** TLS 1.3 only, with or without required client authentication. */
    static SSLParameters parameters(SSLContext tls, boolean needClientAuth) {
        var ssl = tls.getDefaultSSLParameters();
        ssl.setProtocols(new String[] {"TLSv1.3"});
        ssl.setNeedClientAuth(needClientAuth);
        return ssl;
    }

    /** Every name the certificate can be identified by: URI SANs (node prefix stripped), DNS SANs, CN. */
    static List<String> names(X509Certificate cert) {
        var out = new ArrayList<String>();
        try {
            var sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (var san : sans) {
                    if (san.size() < 2 || !(san.get(1) instanceof String value)) {
                        continue;
                    }
                    int type = (Integer) san.get(0);
                    if (type == 6) {
                        out.add(value);
                        if (value.startsWith(NODE_URI_PREFIX)) {
                            out.add(value.substring(NODE_URI_PREFIX.length()));
                        }
                    } else if (type == 2) {
                        out.add(value);
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // fall through to the CN
        }
        try {
            for (var rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    out.add(String.valueOf(rdn.getValue()));
                }
            }
        } catch (InvalidNameException e) {
            // no CN
        }
        return out;
    }

    /** True when {@code cert} carries {@code identity} (exact match). */
    static boolean carries(X509Certificate cert, String identity) {
        return identity != null && !identity.isEmpty() && names(cert).contains(identity);
    }
}
