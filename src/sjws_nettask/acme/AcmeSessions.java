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
package sjws_nettask.acme;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import javax.net.ssl.SSLContext;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.connector.NetworkSettings;
import org.shredzone.acme4j.provider.AbstractAcmeProvider;

/** Opens an ACME session. A custom {@link SSLContext} is how tests trust Pebble's certificate. */
public final class AcmeSessions {

    private AcmeSessions() {
    }

    public static Session open(String directory) {
        return open(directory, null);
    }

    public static Session open(String directory, SSLContext ssl) {
        if (ssl == null) {
            return new Session(directory);
        }
        return new Session(URI.create(directory), new TrustProvider(ssl));
    }

    static final class TrustProvider extends AbstractAcmeProvider {
        private final SSLContext ssl;

        TrustProvider(SSLContext ssl) {
            this.ssl = ssl;
        }

        @Override
        public boolean accepts(URI serverUri) {
            var scheme = serverUri.getScheme();
            return "https".equals(scheme) || "http".equals(scheme);
        }

        @Override
        public URL resolve(URI serverUri) {
            if (!accepts(serverUri)) {
                throw new IllegalArgumentException("unsupported ACME directory " + serverUri);
            }
            try {
                return serverUri.toURL();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("unsupported ACME directory " + serverUri, e);
            }
        }

        @Override
        public HttpClient createHttpClient(NetworkSettings settings) {
            var builder = HttpClient.newBuilder().sslContext(this.ssl).followRedirects(HttpClient.Redirect.NORMAL);
            if (settings != null && settings.getTimeout() != null) {
                builder.connectTimeout(settings.getTimeout());
            }
            return builder.build();
        }
    }
}
