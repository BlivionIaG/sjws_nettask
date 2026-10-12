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
package sjws_nettask.h3;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import sjws_nettask.ServerConfig;

/**
 * Resource limits of one HTTP/3 server (security review M1/S1). Derived from the configuration;
 * tests shrink them through the package-private {@link Http3Server} seam.
 *
 * @param maxInflight requests being handled or sent at once, across all connections; more are
 *        refused with H3_REQUEST_REJECTED (covers listings and error pages too)
 * @param maxOpenFiles files open for sending at once, across all connections
 * @param filesPerConnection files open for sending at once on one QUIC connection
 * @param slotWait how long a response waits for a file slot before it is refused with H3_REQUEST_REJECTED
 * @param writeTimeout a response whose previous chunk was not taken for this long is reset; also the
 *        window over which {@code minBytesPerSecond} is averaged
 * @param minBytesPerSecond slower responses are reset (0 = no minimum)
 * @param drain how long {@link Http3Server#close()} lets in-flight responses finish after GOAWAY
 */
record Limits(int maxInflight, int maxOpenFiles, int filesPerConnection, Duration slotWait, Duration writeTimeout,
        long minBytesPerSecond, Duration drain) {

    static final int MAX_OPEN_FILES = 1024;
    static final int FILES_PER_CONNECTION = 16;
    static final long MIN_BYTES_PER_SECOND = 8 * 1024;
    static final Duration DRAIN = Duration.ofSeconds(2);

    static Limits of(ServerConfig config) {
        return new Limits(config.maxConnections(), Math.min(config.maxConnections(), MAX_OPEN_FILES),
                FILES_PER_CONNECTION, config.headerTimeout(), config.writeTimeout(), MIN_BYTES_PER_SECOND, DRAIN);
    }

    Limits {
        if (maxInflight < 1 || maxOpenFiles < 1 || filesPerConnection < 1 || minBytesPerSecond < 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }

    Semaphore newConnectionFiles() {
        return new Semaphore(this.filesPerConnection);
    }
}
