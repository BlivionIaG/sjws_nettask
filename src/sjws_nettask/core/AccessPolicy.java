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

import java.io.IOException;
import java.nio.file.Path;

/**
 * Seam for "may this path be served?" decisions taken by {@link StaticFileHandler} before any
 * file is opened. {@link AccessRules} is the built-in implementation (path rules, dotfiles,
 * owner/permission policy); an authentication layer can wrap or replace it per request.
 * <p>
 * Implementations must be thread-safe and should not allocate on the request path.
 */
public interface AccessPolicy {

    /**
     * Lexical decision on a normalized path inside the document root.
     *
     * @param path a string holding the path; components are separated by '/' (or the platform
     *        separator), it has no leading separator, no "." or ".." components and is empty for the root
     * @param from index of the path's first character in {@code path}
     * @return 0 to allow, 403 (forbidden) or 404 (hidden: answered as if missing)
     */
    int check(CharSequence path, int from);

    /**
     * Decision on the real file about to be served (after symlinks are resolved).
     *
     * @return 0 to allow, or the status to answer (403)
     * @throws IOException if its attributes can't be read
     */
    default int checkFile(Path real) throws IOException {
        return 0;
    }

    /**
     * @return true if {@link #checkFile} can refuse anything; the handler then also calls it on
     *         small-file cache hits (one more {@code stat})
     */
    default boolean checksFiles() {
        return false;
    }
}
