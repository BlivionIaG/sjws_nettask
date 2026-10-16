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
 * Application-supplied snapshot. Installed with {@link SnapshotControl#setSnapshotTaker}, it replaces
 * the built-in image (the concatenated user payloads) for automatic snapshots and for
 * {@link SnapshotControl#take}. It runs on the group's delivery thread after every committed entry
 * through {@code lastIncludedIndex} has been delivered to the {@link GroupListener} and before any
 * later one, so the application state it reads is exactly the state at that index.
 */
@FunctionalInterface
public interface SnapshotTaker {

    /**
     * The state image through {@code lastIncludedIndex}. A thrown exception (or null) skips this
     * snapshot; the log is kept and the next trigger tries again.
     */
    byte[] snapshot(GroupId group, long lastIncludedIndex) throws Exception;
}
