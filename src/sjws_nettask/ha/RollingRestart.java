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
package sjws_nettask.ha;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One node restarts only after every other node's {@code /readyz} returns 200. The caller
 * drains this process after {@link #requirePeersReady} returns.
 */
public final class RollingRestart {

    private final HttpClient client;
    private final Duration timeout;

    public RollingRestart() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), Duration.ofSeconds(2));
    }

    public RollingRestart(HttpClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (this.timeout.isZero() || this.timeout.isNegative()) {
            throw new IllegalArgumentException("timeout");
        }
    }

    public void requirePeersReady(List<InetSocketAddress> peers) throws IOException, InterruptedException {
        Objects.requireNonNull(peers, "peers");
        for (var peer : peers) {
            int status = status(peer);
            if (status != 200) {
                throw new IllegalStateException("peer not ready " + peer.getHostString() + ":" + peer.getPort());
            }
        }
    }

    public int status(InetSocketAddress peer) throws IOException, InterruptedException {
        Objects.requireNonNull(peer, "peer");
        if (peer.getPort() == 0) {
            throw new IllegalArgumentException("port");
        }
        var request = HttpRequest.newBuilder(URI.create("http://" + peer.getHostString() + ":" + peer.getPort() + "/readyz"))
                .timeout(this.timeout)
                .GET()
                .build();
        var response = this.client.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
