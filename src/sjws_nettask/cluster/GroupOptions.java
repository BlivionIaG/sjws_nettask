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
package sjws_nettask.cluster;

import java.time.Duration;
import java.util.Objects;

/** Per-group knobs. The lease is two heartbeats; an election waits for the lease and then this timeout. */
public record GroupOptions(Durability durability, int maxBatchBytes, Duration electionTimeout, Duration heartbeat,
        long snapshotEveryEntries) {

    public GroupOptions {
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(electionTimeout, "electionTimeout");
        Objects.requireNonNull(heartbeat, "heartbeat");
        if (maxBatchBytes < 1) {
            throw new IllegalArgumentException("maxBatchBytes");
        }
        if (electionTimeout.isZero() || electionTimeout.isNegative() || heartbeat.isZero() || heartbeat.isNegative()) {
            throw new IllegalArgumentException("timeouts");
        }
        if (snapshotEveryEntries < 1) {
            throw new IllegalArgumentException("snapshotEveryEntries");
        }
    }

    public long leaseNanos() {
        return heartbeat.toNanos() * 2;
    }
}
