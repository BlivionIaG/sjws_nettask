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
package sjws_nettask.clusterharness;

/**
 * One control-plane voter of Raft group {@code cp-config}.
 * {@link #adminPort()} is the bound loopback port of {@code GET /cp/v1/health} (design §14).
 */
public interface ControlNode {

    String id();

    /** Ephemeral admin listener. Readable after start, without parsing a log. */
    int adminPort();

    /** True only after this term's election {@code Noop} has been applied (design §6). */
    boolean leaderReady();

    /** Raft term. {@code 0} before the group has a term. */
    long term();
}
