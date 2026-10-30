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
package sjws_nettask;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * Resolves well-known test utilities to an absolute path so CodeQL
 * {@code java/relative-path-command} stays quiet without hard-coding one
 * install layout. Prefers {@code /usr/bin/<name>} then {@code /bin/<name>}.
 */
public final class TestCommands {

    private TestCommands() {
    }

    /**
     * First of {@code /usr/bin/<name>} and {@code /bin/<name>} that is executable,
     * or {@code null} when neither exists. {@code name} must be a simple basename.
     */
    public static String find(String name) {
        requireBasename(name);
        var usr = "/usr/bin/" + name;
        if (Files.isExecutable(Path.of(usr))) {
            return usr;
        }
        var bin = "/bin/" + name;
        if (Files.isExecutable(Path.of(bin))) {
            return bin;
        }
        return null;
    }

    /**
     * Like {@link #find(String)}, but aborts the current test (skipped, not failed)
     * when neither path exists.
     */
    public static String resolve(String name) {
        var path = find(name);
        if (path == null) {
            Assumptions.abort("no " + name + " at /usr/bin/" + name + " or /bin/" + name);
        }
        return path;
    }

    private static void requireBasename(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("command name must be a simple basename: " + name);
        }
    }
}
