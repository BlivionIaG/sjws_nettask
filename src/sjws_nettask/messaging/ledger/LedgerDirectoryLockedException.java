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
package sjws_nettask.messaging.ledger;

import java.io.IOException;
import java.nio.file.Path;

/** A ledger directory is already open, by another process or elsewhere in this JVM ({@link DirectoryLock}). */
public final class LedgerDirectoryLockedException extends IOException {

    private final transient Path dir;

    public LedgerDirectoryLockedException(Path dir) {
        super("ledger directory " + dir.toAbsolutePath() + " is already in use (locked by another process or an open "
                + "partition in this one; lock file " + DirectoryLock.FILE + "); point each node at its own directory");
        this.dir = dir;
    }

    public Path dir() {
        return this.dir;
    }
}
