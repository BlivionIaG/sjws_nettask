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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;

/** The shared file modes of {@link AcmeStore} cannot be widened by a caller. */
class AcmeStoreModesTest {

    @Test
    void sharedModeConstantsAreUnmodifiable() {
        for (var mode : java.util.List.of(AcmeStore.OWNER_RW, AcmeStore.OWNER_RWX)) {
            assertThrows(UnsupportedOperationException.class, () -> mode.add(PosixFilePermission.OTHERS_READ));
            assertThrows(UnsupportedOperationException.class, () -> mode.remove(PosixFilePermission.OWNER_READ));
            assertThrows(UnsupportedOperationException.class, mode::clear);
        }
        assertEquals("rw-------", PosixFilePermissions.toString(AcmeStore.OWNER_RW));
        assertEquals("rwx------", PosixFilePermissions.toString(AcmeStore.OWNER_RWX));
    }
}
