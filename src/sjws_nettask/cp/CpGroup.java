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

import java.time.Duration;
import sjws_nettask.cluster.Durability;
import sjws_nettask.cluster.GroupId;
import sjws_nettask.cluster.GroupOptions;

/**
 * The {@code cp-config} Raft group id and timings, in a class of its own so a witness host can join the
 * group without loading {@link CpStore} or {@link KeyRing} (control-plane-hosting.md §6.1).
 */
public final class CpGroup {

    public static final GroupId GROUP = new GroupId("cp-config");

    private CpGroup() {
    }

    /**
     * Automatic snapshot every this many committed entries once a {@code SnapshotTaker} is set (#341).
     * Matches the pre-#340 threshold; #340 turned auto-snapshots off because no taker existed yet.
     */
    public static final long SNAPSHOT_EVERY_ENTRIES = 10_000L;

    /**
     * Election 100 ms, heartbeat 40 ms (lease 80 ms). {@link CpTiming#check} runs against these at startup.
     * Automatic Raft snapshots fire every {@link #SNAPSHOT_EVERY_ENTRIES} with {@link CpStore}'s
     * {@code SnapshotTaker} (#341). Auto-snapshots advance {@code snapIndex} with the application image but
     * do not truncate the log; {@link CpStore#compactThrough} calls {@code truncateAndCompact} so a durable
     * image is persisted before the prefix is deleted ({@code persistSnapshotBeforePrefixDelete}).
     */
    public static GroupOptions options() {
        return new GroupOptions(Durability.RELAXED, 1024 * 1024, Duration.ofMillis(100), Duration.ofMillis(40),
                SNAPSHOT_EVERY_ENTRIES);
    }
}
