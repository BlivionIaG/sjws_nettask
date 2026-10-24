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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Exclusive advisory lock on one ledger directory (#236 follow-up): a {@code .lock} file held with an OS file lock
 * for as long as the {@link SegmentedLog} is open, so a second process (or a second open in this JVM) pointing at the
 * same directory fails fast with {@link LedgerDirectoryLockedException} instead of interleaving appends. The OS drops
 * the lock if the process dies, so a crash never leaves a stale lock behind. Taken once per open, never on a hot path.
 */
final class DirectoryLock implements AutoCloseable {

    static final String FILE = ".lock";

    private final FileChannel channel;
    private final FileLock lock;

    private DirectoryLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static DirectoryLock acquire(Path dir) throws IOException {
        var ch = FileChannel.open(dir.resolve(FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock l;
        try {
            l = ch.tryLock();
        } catch (OverlappingFileLockException inThisJvm) {
            l = null;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        if (l == null) {
            ch.close();
            throw new LedgerDirectoryLockedException(dir);
        }
        return new DirectoryLock(ch, l);
    }

    @Override
    public void close() throws IOException {
        try {
            if (this.lock.isValid()) this.lock.release();
        } finally {
            this.channel.close();
        }
    }
}
