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
package sjws_nettask.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * ACME (RFC 8555) settings. The default directory is Let's Encrypt staging so a typo cannot spend
 * production rate limits. Shape is validated even when {@code enabled} is false; email, domains
 * and {@code acceptTerms} are required only when issuance is on.
 *
 * @param directory resolved directory URL, or an {@code acme://} URI
 * @param httpPort {@code 0} serves HTTP-01 on the main listener
 */
public record AcmeSettings(boolean enabled, String directory, String email, List<String> domains, Path storage,
        int httpPort, boolean acceptTerms, Duration renewBefore) {

    public static final String STAGING = "https://acme-staging-v02.api.letsencrypt.org/directory";
    public static final String PRODUCTION = "https://acme-v02.api.letsencrypt.org/directory";
    public static final Duration DEFAULT_RENEW_BEFORE = Duration.ofDays(30);

    public AcmeSettings {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(email, "email");
        domains = List.copyOf(domains);
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(renewBefore, "renewBefore");
        if (httpPort < 0 || httpPort > 65535) {
            throw new IllegalArgumentException("httpPort out of range: " + httpPort);
        }
    }

    public static AcmeSettings disabled() {
        return new AcmeSettings(false, STAGING, "", List.of(), Path.of("acme"), 0, false, DEFAULT_RENEW_BEFORE);
    }

    /** Challenges are served by the main HTTP/1 listener (no second socket). */
    public boolean onMainListener(int serverPort) {
        return this.httpPort == 0 || this.httpPort == serverPort;
    }
}
