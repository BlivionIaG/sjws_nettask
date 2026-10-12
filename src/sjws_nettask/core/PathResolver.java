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
    private final AccessPolicy policy;
    /** Offset of a child's relative path in {@code root.toString()} / {@code realRoot.toString()}. */
    private final int rootLength;
    private final int realRootLength;

    /** @throws UncheckedIOException if the root does not exist or is not a directory */
    public PathResolver(Path root) {
        this(root, AccessRules.DEFAULT);
    }

    /**
     * @param policy decides which paths may be served (lexically, and again on the real path)
     * @throws UncheckedIOException if the root does not exist or is not a directory
     */
    public PathResolver(Path root, AccessPolicy policy) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.root = root.toAbsolutePath().normalize();
        try {
            this.realRoot = this.root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("document root not accessible: " + this.root, e);
        }
        if (!Files.isDirectory(this.realRoot)) {
            throw new UncheckedIOException(new NotDirectoryException(this.root.toString()));
        }
        this.rootLength = childOffset(this.root);
        this.realRootLength = childOffset(this.realRoot);
    }

    private static int childOffset(Path dir) {
        var s = dir.toString();
        return s.length() + (s.isEmpty() || AccessRules.isSep(s.charAt(s.length() - 1)) ? 0 : 1);
    }

    public AccessPolicy policy() {
        return this.policy;
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
     *         (also via symlinks), is refused by the policy (e.g. a dotfile), or is a dangling symlink
     */
    public Resolved resolve(String decodedPath) {
        var candidate = logical(decodedPath);
        if (candidate == null) {
            return null;
        }
        var resolved = resolveLogical(candidate);
        return resolved != null && accessReal(resolved.real()) == 0 ? resolved : null;
    }

    /**
     * The file-system part of {@link #resolve} for a path from {@link #normalize}: follows symlinks
     * and checks containment, but not the policy (see {@link #access} and {@link #accessReal}).
     *
     * @return the file, or null if it does not exist, escapes the root or is a dangling symlink
     */
    public Resolved resolveLogical(Path candidate) {
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            return null; // missing, dangling symlink, or inaccessible parent
        }
        return real.startsWith(this.realRoot) ? new Resolved(candidate, real) : null;
    }

    /**
     * Lexical policy decision on a path from {@link #normalize}.
     *
     * @return 0, 403 or 404 (see {@link AccessPolicy#check})
     */
    public int access(Path logical) {
        return check(logical.toString(), this.rootLength);
    }

    /**
     * The same decision on a real path inside the real root, so that a symlink cannot reach a
     * refused path under another name (and a case-insensitive file system cannot dodge a rule:
     * real paths carry the on-disk case).
     */
    public int accessReal(Path real) {
        return check(real.toString(), this.realRootLength);
    }

    /**
     * {@link #accessReal} for a file from {@link #resolveLogical} whose logical path {@link #access}
     * already allowed: skipped (0) when the real path names the same file the same way (no symlink,
     * same case), the common case.
     */
    public int accessResolved(Resolved file) {
        var logical = file.logical().toString();
        var real = file.real().toString();
        int from = Math.min(this.rootLength, logical.length());
        int realFrom = Math.min(this.realRootLength, real.length());
        int length = logical.length() - from;
        if (real.length() - realFrom == length && real.regionMatches(realFrom, logical, from, length)) {
            return 0;
        }
        return check(real, realFrom);
    }

    private int check(String path, int from) {
        return this.policy.check(path, Math.min(from, path.length()));
    }

    /**
     * The lexical part of {@link #resolve} (no file-system access): normalizes the path below the
     * root and refuses anything escaping it or refused by the policy.
     *
     * @return the normalized logical path, or null (the request is a 404)
     */
    public Path logical(String decodedPath) {
        var candidate = normalize(decodedPath);
        return candidate != null && access(candidate) == 0 ? candidate : null;
    }

    /**
     * Normalizes a decoded path below the root (no policy, no file-system access).
     *
     * @return the normalized path, or null if it escapes the root or can't be represented
     */
    public Path normalize(String decodedPath) {
        Path candidate;
        try {
            candidate = this.root.resolve(decodedPath.substring(1)).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        return candidate.startsWith(this.root) ? candidate : null;
    }

    /** @return true if a directory entry may be shown in a listing (its real path is allowed) */
    public boolean isVisible(Path entry) {
        Path real;
        try {
            real = entry.toRealPath();
        } catch (IOException e) {
            return false; // dangling symlink
        }
        return real.startsWith(this.realRoot) && accessReal(real) == 0 && accessReal(entry) == 0;
    }

    /**
     * @param logicalDir the listed directory's path from {@link #normalize}
     * @param entry an entry of its real directory
     * @return true if the entry may be listed: allowed under its logical and its real name
     */
    public boolean isVisible(Path logicalDir, Path entry) {
        return access(logicalDir.resolve(entry.getFileName().toString())) == 0 && isVisible(entry);
    }
}
