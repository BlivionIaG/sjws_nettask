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

    /** Election 100 ms, heartbeat 40 ms (lease 80 ms). {@link CpTiming#check} runs against these at startup. */
    public static GroupOptions options() {
        return new GroupOptions(Durability.RELAXED, 1024 * 1024, Duration.ofMillis(100), Duration.ofMillis(40), 10_000);
    }
}
