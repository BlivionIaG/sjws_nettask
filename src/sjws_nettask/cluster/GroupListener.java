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

/**
 * Per-group committed-entry stream. Equivalent to a state-machine callback: calls arrive strictly in
 * index order and never overlap, though not always on the same thread. A callback may append to the
 * group; the new entry is delivered after this callback returns (by the same draining loop), so a
 * callback must not block waiting for its own append's future, which would deadlock.
 * <p>
 * Delivery can lag: if the thread draining callbacks is interrupted while waiting for the delivery
 * lock, or the Raft thread finds another thread draining, the entries stay queued and are delivered
 * by the current drainer or by the next tick or frame, so at most about one tick later.
 */
@FunctionalInterface
public interface GroupListener {

    void onCommitted(CommittedEntry entry);
}
