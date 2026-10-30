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
package sjws_nettask.msgrepl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/**
 * Snapshot and truncation of the {@code msg/*} groups (#270): the settings and the node-wide pieces every replica
 * shares. Built by the HA bootstrap only; a replicator without one never snapshots (the behaviour before #270).
 *
 * @param everyEntries  take a snapshot once this many entries were applied since the last one
 * @param tailEntries   consensus log entries kept behind the snapshot point, so a briefly lagging follower catches up
 *                      from the log instead of a full install (constraint 5)
 * @param chunkBytes    digest and transfer unit of segment data
 * @param bytesPerSecond   install-snapshot pacing on the sending node, shared by all its transfers (constraint 7)
 * @param check            how often each replica checks whether a snapshot is due (off the produce path)
 * @param maxInstallBytes  upper bound on the sum of segment bytes in one install; {@code 0} is the explicit unlimited
 *                         opt-in (#295)
 */
record Snapshots(long everyEntries, long tailEntries, int chunkBytes, long bytesPerSecond, Duration check,
        long maxInstallBytes) {

    static final long DEFAULT_EVERY_ENTRIES = 10_000;
    static final long DEFAULT_TAIL_ENTRIES = 1_000;
    static final int DEFAULT_CHUNK_BYTES = 1 << 20;
    static final long DEFAULT_BYTES_PER_SECOND = 64L << 20;
    static final Duration DEFAULT_CHECK = Duration.ofMillis(200);
    /**
     * Default install size bound (#295): four default ledger segments
     * ({@code 4 ×} {@link sjws_nettask.messaging.ledger.SegmentedLog#DEFAULT_SEGMENT_BYTES} = 4 GiB). Enough for a
     * multi-segment catch-up under typical retention; operators raise it for larger windows or set {@code 0} for
     * unlimited. Enforced while chunks stream, before disk writes.
     */
    static final long DEFAULT_MAX_INSTALL_BYTES = 4L << 30;
    /** Peer-round budget for a divergent or unavailable pin before the install gives up (#295). */
    static final int DEFAULT_MAX_INSTALL_PIN_ROUNDS = 16;

    Snapshots {
        if (everyEntries < 1 || tailEntries < 0 || bytesPerSecond < 1) throw new IllegalArgumentException("snapshot settings");
        if (chunkBytes < MsgSnapshot.MIN_CHUNK || chunkBytes > MsgSnapshot.MAX_CHUNK) throw new IllegalArgumentException("chunk bytes");
        Objects.requireNonNull(check, "check");
        if (check.isNegative() || check.isZero()) throw new IllegalArgumentException("check interval");
        if (maxInstallBytes < 0) throw new IllegalArgumentException("max install bytes");
    }

    static Snapshots defaults() {
        return new Snapshots(DEFAULT_EVERY_ENTRIES, DEFAULT_TAIL_ENTRIES, DEFAULT_CHUNK_BYTES, DEFAULT_BYTES_PER_SECOND,
                DEFAULT_CHECK, DEFAULT_MAX_INSTALL_BYTES);
    }

    /**
     * The runtime: pins under {@code <stateDir>/snap}, the transfer key, the other data nodes' transfer addresses, one
     * scheduler thread for every replica's checks, and the wall clock used for request timestamps.
     */
    static final class Runtime implements AutoCloseable {
        final Snapshots settings;
        final SnapshotStore store;
        final byte[] key;
        final Map<String, InetSocketAddress> peers;
        final ScheduledExecutorService exec;
        final LongSupplier clockMs;

        Runtime(Snapshots settings, Path stateDir, byte[] key, Map<String, InetSocketAddress> peers, LongSupplier clockMs)
                throws IOException {
            this.settings = Objects.requireNonNull(settings);
            this.store = new SnapshotStore(Files.createDirectories(stateDir).resolve("snap"));
            this.key = key.clone();
            this.peers = Map.copyOf(peers);
            this.clockMs = clockMs;
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon()
                    .name("msgrepl-snapshotter").priority(Thread.NORM_PRIORITY - 1).unstarted(r));
        }

        @Override
        public void close() {
            exec.shutdownNow();
        }
    }
}
