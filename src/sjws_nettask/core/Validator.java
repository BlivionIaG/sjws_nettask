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
package sjws_nettask.core;

import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Identity of a file version at the time it was resolved.
 *
 * @param fileKey {@link BasicFileAttributes#fileKey()} (dev+inode on POSIX); null where unsupported (Windows)
 * @param mtimeNanos last-modified time in nanoseconds since the epoch
 * @param size size in bytes
 */
public record Validator(Object fileKey, long mtimeNanos, long size) {

    public static Validator of(BasicFileAttributes attributes) {
        return new Validator(attributes.fileKey(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                attributes.size());
    }

    /** @return false if the attributes belong to a different file (e.g. swapped in by rename or symlink) */
    public boolean sameFile(BasicFileAttributes attributes) {
        return this.fileKey == null || Objects.equals(this.fileKey, attributes.fileKey());
    }

    /** @return true if same file, same mtime and same size */
    public boolean unchanged(BasicFileAttributes attributes) {
        return sameFile(attributes) && this.mtimeNanos == attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)
                && this.size == attributes.size();
    }
}
