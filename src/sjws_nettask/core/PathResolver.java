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
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;

/**
 * Maps decoded URL paths to files inside the document root. The root's real path is
 * resolved once; every lookup is re-checked with {@code toRealPath} (symlinks), never cached.
 */
public final class PathResolver {

    /** A file inside the root: the logical path (named by the URL) and its real location. */
    public record Resolved(Path logical, Path real) {
    }

    private final Path root;
    private final Path realRoot;

    /** @throws UncheckedIOException if the root does not exist or is not a directory */
    public PathResolver(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            this.realRoot = this.root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("document root not accessible: " + this.root, e);
        }
        if (!Files.isDirectory(this.realRoot)) {
            throw new UncheckedIOException(new NotDirectoryException(this.root.toString()));
        }
    }

    public Path root() {
        return this.root;
    }

    public Path realRoot() {
        return this.realRoot;
    }

    /**
     * Percent-decodes a raw URL path ('+' stays '+').
     *
     * @return the decoded path, or null if malformed (bad escape, NUL, not starting with '/')
     */
    public static String decode(String rawPath) {
        String decoded;
        try {
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!decoded.startsWith("/") || decoded.indexOf('\0') >= 0) {
            return null;
        }
        return decoded;
    }

    /**
     * @param decodedPath a path from {@link #decode}
     * @return the existing file inside the root, or null if it does not exist, escapes the root
     *         (also via symlinks), names a dotfile, or is a dangling symlink
     */
    public Resolved resolve(String decodedPath) {
        var candidate = logical(decodedPath);
        if (candidate == null) {
            return null;
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            return null; // missing, dangling symlink, or inaccessible parent
        }
        return real.startsWith(this.realRoot) ? new Resolved(candidate, real) : null;
    }

    /**
     * The lexical part of {@link #resolve} (no file-system access): normalizes the path below the
     * root and refuses anything escaping it or naming a hidden component.
     *
     * @return the normalized logical path, or null (the request is a 404)
     */
    public Path logical(String decodedPath) {
        Path candidate;
        try {
            candidate = this.root.resolve(decodedPath.substring(1)).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!candidate.startsWith(this.root)) {
            return null;
        }
        for (var part : this.root.relativize(candidate)) {
            if (part.toString().startsWith(".")) {
                return null; // hidden files and folders are never served
            }
        }
        return candidate;
    }

    /** @return true if a directory entry may be shown in a listing */
    public boolean isVisible(Path entry) {
        if (entry.getFileName().toString().startsWith(".")) {
            return false;
        }
        try {
            return entry.toRealPath().startsWith(this.realRoot);
        } catch (IOException e) {
            return false; // dangling symlink
        }
    }
}
