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

/**
 * Per-group log options. The lease is two heartbeats; an election waits for the lease and then this
 * timeout. The constructor that omits durability uses {@link Durability#RELAXED} (majority in memory).
 * Pass {@link Durability#STRICT} to opt into quorum commit plus fsync. A preferred node campaigns
 * sooner. Failback hysteresis keeps a demoted leader from campaigning again until that delay has
 * elapsed.
 */
public record GroupOptions(Durability durability, int maxBatchBytes, Duration electionTimeout, Duration heartbeat,
        long snapshotEveryEntries, boolean preferred, Duration failbackHysteresis) {

    public GroupOptions(Durability durability, int maxBatchBytes, Duration electionTimeout, Duration heartbeat,
            long snapshotEveryEntries) {
        this(durability, maxBatchBytes, electionTimeout, heartbeat, snapshotEveryEntries, false, Duration.ZERO);
    }

    /** Default ack mode: majority in memory. Fsync-before-ack is the {@link Durability} overload. */
    public GroupOptions(int maxBatchBytes, Duration electionTimeout, Duration heartbeat, long snapshotEveryEntries) {
        this(Durability.RELAXED, maxBatchBytes, electionTimeout, heartbeat, snapshotEveryEntries);
    }

    public GroupOptions {
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(electionTimeout, "electionTimeout");
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(failbackHysteresis, "failback");
        if (maxBatchBytes < 1) {
            throw new IllegalArgumentException("maxBatchBytes");
        }
        if (electionTimeout.isZero() || electionTimeout.isNegative() || heartbeat.isZero() || heartbeat.isNegative()) {
            throw new IllegalArgumentException("timeouts");
        }
        if (snapshotEveryEntries < 1) {
            throw new IllegalArgumentException("snapshotEveryEntries");
        }
        if (failbackHysteresis.isNegative()) {
            throw new IllegalArgumentException("failback");
        }
    }

    public long leaseNanos() {
        return heartbeat.toNanos() * 2;
    }

    public GroupOptions withPreferred(Duration failbackHysteresis) {
        return new GroupOptions(durability, maxBatchBytes, electionTimeout, heartbeat, snapshotEveryEntries, true,
                failbackHysteresis);
    }

    public GroupOptions withFailback(Duration failbackHysteresis) {
        return new GroupOptions(durability, maxBatchBytes, electionTimeout, heartbeat, snapshotEveryEntries, preferred,
                failbackHysteresis);
    }
}
