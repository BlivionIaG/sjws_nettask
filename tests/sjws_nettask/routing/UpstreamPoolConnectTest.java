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
package sjws_nettask.routing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Connect clean-up in {@link UpstreamPool#acquire(Duration)}: an abandoned connect never leaves a socket open. */
class UpstreamPoolConnectTest {

    /**
     * Codex P2 on #477: a probe interrupted while it waits for a new connection (EndpointBalancer
     * close -> probeWorkers.shutdownNow()) must cancel the connect and close its channel, even though
     * the connect itself goes on to succeed. Before the fix the socket stayed open with nobody owning it.
     */
    @Test
    @Timeout(30)
    void interruptedConnectWaitClosesTheLateConnection() throws Exception {
        try (var server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                var pool = new UpstreamPool(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/"),
                        Duration.ofSeconds(5), 4, new ByteBudget(1 << 20))) {
            // Interrupted before the wait: acquire() starts the connect, then get() throws at once,
            // so the connect completes only after the caller gave up (the "slow connect" case).
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class, () -> pool.acquire(Duration.ofSeconds(2)));
            } finally {
                Thread.interrupted(); // keep the test thread clean whatever acquire() did with the flag
            }
            server.setSoTimeout(2000);
            Socket accepted;
            try {
                accepted = server.accept();
            } catch (SocketTimeoutException e) {
                return; // closed before the SYN went out: nothing leaked
            }
            try (accepted) {
                accepted.setSoTimeout(5000);
                int read;
                try {
                    read = accepted.getInputStream().read();
                } catch (SocketTimeoutException e) {
                    fail("the abandoned upstream connection is still open (orphaned socket)");
                    return;
                } catch (IOException reset) {
                    return; // RST: closed
                }
                assertEquals(-1, read, "the abandoned upstream connection should be closed");
            }
            assertEquals(0, pool.idleCount());
        }
    }
}
