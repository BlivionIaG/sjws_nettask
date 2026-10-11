/*
 * Copyright (C) 2019 BlivionIaG <BlivionIaG at chenco.tk>
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
package sjws_nettask.HTTP;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import sjws_nettask.CONSTANTS;

/**
 * Maps a request target onto the document root and sends the matching
 * file, directory listing or error page.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class HTML {

    private final Path root;     // absolute, normalised
    private final Path realRoot; // symlinks resolved once at startup

    public HTML() {
        this(CONSTANTS.DEFAULT_HTML_PATH);
    }

    /**
     * @throws UncheckedIOException if the document root does not exist or is not a directory
     */
    public HTML(String htmlPath) {
        this.root = Path.of(htmlPath).toAbsolutePath().normalize();
        try {
            this.realRoot = this.root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("document root not accessible: " + this.root, e);
        }
        if (!Files.isDirectory(this.realRoot)) {
            throw new UncheckedIOException(new NotDirectoryException(this.root.toString()));
        }
    }

    public String getFolderPath() {
        return this.root.toString();
    }

    /** Convenience entry point: GET with keep-alive on a raw socket. */
    public void send(Socket client, String target) throws IOException {
        var out = new BufferedOutputStream(client.getOutputStream());
        send(out, target, false, true, null);
        out.flush();
    }

    public int send(OutputStream out, String target, boolean headOnly, boolean keepAlive) throws IOException {
        return send(out, target, headOnly, keepAlive, null);
    }

    /**
     * @param out response stream (caller flushes/closes)
     * @param target origin-form request-target, e.g. "/a%20b.txt?x=1"
     * @param headOnly HEAD request: send the head only
     * @param keepAlive value advertised in the Connection header
     * @param ifModifiedSince value of the If-Modified-Since header, or null
     * @return the HTTP status code sent
     * @throws IOException if the response could not be completed; the connection must then be closed
     */
    public int send(OutputStream out, String target, boolean headOnly, boolean keepAlive, String ifModifiedSince)
            throws IOException {
        int q = target.indexOf('?');
        int h = target.indexOf('#');
        int pathEnd = q < 0 ? (h < 0 ? target.length() : h) : (h < 0 ? q : Math.min(q, h));
        var rawPath = target.substring(0, pathEnd);
        var query = q >= 0 && (h < 0 || q < h) ? target.substring(q, h > q ? h : target.length()) : "";

        String decoded;
        try {
            // URLDecoder is form-decoding: protect literal '+' (it means '+' in a path).
            decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Page.sendError(out, 400, headOnly, keepAlive);
        }
        if (!decoded.startsWith("/") || decoded.indexOf('\0') >= 0) {
            return Page.sendError(out, 400, headOnly, keepAlive);
        }

        var file = resolve(decoded);
        if (file == null || !Files.exists(file)) {
            return Page.sendError(out, 404, headOnly, keepAlive);
        }

        try {
            if (Files.isDirectory(file)) {
                if (!rawPath.endsWith("/")) {
                    return Page.sendRedirect(out, rawPath + "/" + query, headOnly, keepAlive);
                }
                var index = file.resolve("index.html");
                if (Files.isRegularFile(index)) {
                    return Page.sendFile(out, index, headOnly, keepAlive, ifModifiedSince);
                }
                return Page.sendFolder(out, listVisible(file), decoded, headOnly, keepAlive);
            }
            if (!Files.isRegularFile(file)) {
                return Page.sendError(out, 404, headOnly, keepAlive);
            }
            return Page.sendFile(out, file, headOnly, keepAlive, ifModifiedSince);
        } catch (AccessDeniedException e) {
            // thrown while opening/listing, i.e. before anything was written
            return Page.sendError(out, 403, headOnly, keepAlive);
        }
    }

    /**
     * @return the file inside the document root, or null if the path escapes it
     * (including through symbolic links) or names a dotfile.
     */
    private Path resolve(String decodedPath) {
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
        return isInsideRoot(candidate) ? candidate : null;
    }

    /** @return true unless the path exists and its real location is outside the root */
    private boolean isInsideRoot(Path path) {
        if (!Files.exists(path)) {
            return !Files.isSymbolicLink(path); // dangling symlink: hide
        }
        try {
            return path.toRealPath().startsWith(this.realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> listVisible(Path folder) throws IOException {
        List<String> names = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            for (var p : (Iterable<Path>) entries::iterator) {
                var name = p.getFileName().toString();
                if (name.startsWith(".") || !isInsideRoot(p)) {
                    continue;
                }
                names.add(Files.isDirectory(p) ? name + "/" : name);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        names.sort(String::compareTo);
        return names;
    }
}
