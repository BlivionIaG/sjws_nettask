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
 * Per-group committed-entry stream. Equivalent to a state-machine callback: calls arrive strictly in index order,
 * never overlap, and always run on the group's single delivery thread (docs/design/raft-delivery-executor.md).
 * Append futures complete on that same thread, after the callbacks of every entry up to theirs.
 * <p>
 * Rules for implementations:
 * <ul>
 * <li><b>Be fast.</b> Every later callback and every append-future completion of the group waits behind this
 * one; while deliveries back up past the limit, {@code append()} futures fail with {@link ApplyLagException}.</li>
 * <li><b>No IO</b> (disk, network, pulls). Hand slow work to your own worker and return.</li>
 * <li><b>Never block on your own append's future</b> (or any later entry's): it completes on this thread,
 * after this callback returns, so waiting for it deadlocks the group's deliveries. Appending is fine; chain on
 * the future instead of joining it.</li>
 * <li>A callback that throws is logged and skipped; the stream continues.</li>
 * <li><b>State must be rebuildable by replaying the log.</b> {@code close()} drains for a bounded time, then may
 * interrupt a running callback mid-way and drop the rest (one WARN with the count and index range; their append
 * futures fail with {@link CommittedNotAppliedException}). Those entries are committed and are delivered again
 * when the group reopens and replays.</li>
 * </ul>
 */
@FunctionalInterface
public interface GroupListener {

    void onCommitted(CommittedEntry entry);
}
