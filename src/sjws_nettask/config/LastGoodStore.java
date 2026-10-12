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
package sjws_nettask.config;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * The last config that was successfully applied, written beside the config file as
 * {@code <name>.last-good}. The bytes are canonical YAML (no key password). A data-plane
 * process whose config file is missing boots from this file. Standalone and control do not.
 */
public final class LastGoodStore {

    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_RW = PosixFilePermissions
            .fromString("rw-------");

    private LastGoodStore() {
    }

    /** Sibling of {@code configFile}. A path that already ends in {@code .last-good} is returned as-is. */
    public static Path beside(Path configFile) {
        var name = configFile.getFileName().toString();
        if (name.endsWith(".last-good")) {
            return configFile;
        }
        var sibling = Path.of(name + ".last-good");
        var parent = configFile.getParent();
        return parent == null ? sibling : parent.resolve(sibling);
    }

    /** No-op when the document was not loaded from a file. */
    public static void write(ConfigDocument document) throws IOException {
        if (document.configFile() == null) {
            return;
        }
        write(beside(document.configFile()), document);
    }

    public static void write(Path path, ConfigDocument document) throws IOException {
        var target = path.toAbsolutePath();
        var parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var text = ConfigCodec.text(document);
        Path tmp = null;
        try {
            tmp = temp(parent == null ? Path.of(".") : parent);
            Files.writeString(tmp, text);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (UnsupportedOperationException e) {
            Files.writeString(target, text);
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    /**
     * True when the file parses and {@code node.mode} is {@code data}. Any read or parse error is
     * false, so a corrupt sibling is not a boot source.
     */
    public static boolean dataPlane(Path path) {
        try {
            var root = YamlValues.parse(Files.readString(path), path.toString());
            if (!(root.get("node") instanceof Object node)) {
                return false;
            }
            var map = YamlValues.mapping(node, "node");
            return map.get("mode") instanceof String mode && mode.trim().equalsIgnoreCase("data");
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    private static Path temp(Path directory) throws IOException {
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                return Files.createTempFile(directory, ".cfg-", ".tmp",
                        PosixFilePermissions.asFileAttribute(OWNER_RW));
            } catch (UnsupportedOperationException e) {
                // the view was advertised, but this store cannot set a file mode
            }
        }
        return Files.createTempFile(directory, ".cfg-", ".tmp");
    }
}
