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

/**
 * The single SIGHUP hook of a CONTROL-role process (control-plane-hosting.md §2.4). Lives outside
 * {@code sjws_nettask.cp} so {@link ServiceLaunch} can hold the host without naming a cp type.
 */
public interface CpReloadable extends AutoCloseable {

    /**
     * Atomic reload: build the new TLS context and the new key ring first, then swap both only if both
     * succeeded. On any failure the old material stays in use.
     *
     * @return null on success, otherwise the reason (no key bytes)
     */
    String reload();

    /** Ordered shutdown; never throws. */
    @Override
    void close();
}
