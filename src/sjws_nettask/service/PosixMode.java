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
package sjws_nettask.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Mode checks that fail closed when the filesystem cannot report POSIX permissions. */
final class PosixMode {

    static final Set<PosixFilePermission> OWNER_RW = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    static final Set<PosixFilePermission> OWNER_READ = EnumSet.of(PosixFilePermission.OWNER_READ);
    static final Set<PosixFilePermission> OWNER_RWX = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    /** Status file: no secrets, readable so an operator can run {@code --status}. */
    static final Set<PosixFilePermission> STATUS = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private PosixMode() {
    }

    static boolean supported(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    static void refuseSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new BootstrapException(path + " is a symlink");
        }
    }

    /** Token file: 0600 or 0400, nothing else. */
    static void requireOwnerOnly(Path path) throws IOException {
        var perms = permissions(path);
        if (!perms.equals(OWNER_RW) && !perms.equals(OWNER_READ)) {
            throw new BootstrapException(path + " must be mode 0600 or 0400");
        }
    }

    /**
     * Bootstrap file: owner read, optional owner write and group read. World access and group
     * write are rejected. The file holds paths, not the token secret.
     */
    static void requireBootstrap(Path path) throws IOException {
        var perms = permissions(path);
        if (!perms.contains(PosixFilePermission.OWNER_READ)) {
            throw new BootstrapException(path + " is not readable by its owner");
        }
        for (var perm : perms) {
            switch (perm) {
                case OWNER_READ, OWNER_WRITE, GROUP_READ -> {
                    // allowed
                }
                default -> throw new BootstrapException(
                        path + " must not be world-accessible or group-writable");
            }
        }
    }

    /** Directory that will hold {@code node.id}: 0700. */
    static void requirePrivateDir(Path dir) throws IOException {
        var perms = permissions(dir);
        if (!perms.equals(OWNER_RWX)) {
            throw new BootstrapException(dir + " must be mode 0700");
        }
    }

    private static Set<PosixFilePermission> permissions(Path path) throws IOException {
        if (!supported(path)) {
            throw new BootstrapException(path + " cannot be checked (filesystem has no POSIX permissions)");
        }
        try {
            return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .permissions();
        } catch (java.nio.file.NoSuchFileException e) {
            throw new BootstrapException(path + " does not exist");
        } catch (IOException e) {
            throw new BootstrapException(path + " is not a usable file");
        }
    }
}
