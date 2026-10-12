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

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Leadership of the control-plane group, narrowed from the Raft API
 * ({@code isLeader}, {@code onLeaderChange(term, leaderId)}, and readiness after the
 * leader's election NOOP). Raft can implement this later. {@link LocalLeadership} is the
 * in-process stub. Callbacks run on the group thread, never on a content request thread.
 * {@code leaderId} is empty when the leader is not known. It is an id, not a secret.
 */
public interface ControlLeadership {

    boolean isLeader();

    /**
     * Registers {@code listener}. A leadership that already has a term invokes it once
     * with that term and leader id.
     */
    void onLeaderChange(BiConsumer<Long, Optional<String>> listener);

    /**
     * Registers {@code listener}. Invoked only after this leader's election NOOP has been
     * applied, with that term and the applied index. Not invoked merely because
     * {@link #isLeader()} became true.
     */
    void leaderReady(BiConsumer<Long, Long> listener);
}
