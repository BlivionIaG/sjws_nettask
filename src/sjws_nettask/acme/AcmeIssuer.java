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

import java.io.IOException;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import javax.net.ssl.SSLContext;
import org.shredzone.acme4j.Account;
import org.shredzone.acme4j.AccountBuilder;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import sjws_nettask.config.AcmeSettings;
import sjws_nettask.tls.TlsMaterial;

/** One HTTP-01 order: account, challenges on the board, certificate on disk and in memory. */
public final class AcmeIssuer {

    private static final Duration WAIT = Duration.ofMinutes(2);

    private AcmeIssuer() {
    }

    public static TlsMaterial issue(AcmeSettings settings, ChallengeBoard board, SSLContext ssl) throws IOException {
        var store = new AcmeStore(settings.storage());
        try {
            var session = AcmeSessions.open(settings.directory(), ssl);
            var accountKey = store.accountKey();
            Account account = null;
            URL existing = store.accountUrl(settings.directory());
            if (existing != null) {
                account = session.login(existing, accountKey).getAccount();
            }
            if (account == null) {
                account = new AccountBuilder().addEmail(settings.email()).agreeToTermsOfService()
                        .useKeyPair(accountKey).create(session);
                store.saveAccount(account.getLocation(), settings.directory());
            }
            var order = account.newOrder().domains(settings.domains()).create();
            var placed = new ArrayList<String>();
            try {
                for (var authorization : order.getAuthorizations()) {
                    if (authorization.getStatus() == Status.VALID) {
                        continue;
                    }
                    var challenge = http01(authorization);
                    board.put(challenge.getToken(), challenge.getAuthorization());
                    placed.add(challenge.getToken());
                    challenge.trigger();
                }
                for (var authorization : order.getAuthorizations()) {
                    if (authorization.getStatus() == Status.VALID) {
                        continue;
                    }
                    var status = http01(authorization).waitForCompletion(WAIT);
                    if (status != Status.VALID) {
                        throw new IOException("HTTP-01 failed for " + authorization.getIdentifier().getDomain()
                                + ": " + status);
                    }
                }
                var ready = order.waitUntilReady(WAIT);
                if (ready != Status.READY && ready != Status.VALID) {
                    throw new IOException("ACME order was not ready: " + ready);
                }
                var domainKey = store.domainKey();
                if (ready != Status.VALID) {
                    order.execute(domainKey);
                }
                if (order.getStatus() != Status.VALID) {
                    var done = order.waitForCompletion(WAIT);
                    if (done != Status.VALID) {
                        throw new IOException("ACME order failed: " + done);
                    }
                }
                var certificate = order.getCertificate();
                if (certificate == null) {
                    throw new IOException("ACME order completed without a certificate");
                }
                store.writeCertificate(certificate);
                var chain = certificate.getCertificateChain().toArray(X509Certificate[]::new);
                return new TlsMaterial(domainKey.getPrivate(), chain, false);
            } finally {
                for (var token : placed) {
                    board.remove(token);
                }
            }
        } catch (AcmeException e) {
            throw new IOException(safe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ACME issuance was interrupted");
        }
    }

    private static Http01Challenge http01(org.shredzone.acme4j.Authorization authorization) throws IOException {
        return authorization.findChallenge(Http01Challenge.class)
                .orElseThrow(() -> new IOException("no HTTP-01 challenge for " + authorization.getIdentifier()));
    }

    static String safe(Throwable error) {
        var message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline);
        }
        if (message.contains("PRIVATE KEY") || message.contains("BEGIN CERTIFICATE")) {
            return error.getClass().getSimpleName();
        }
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        return message;
    }
}
