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
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import sjws_nettask.CONSTANTS;

/**
 * Maps a request target onto the document root and sends the matching
 * file, directory listing or error page.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class HTML {

    private Path root;

    public HTML() {
        this(CONSTANTS.DEFAULT_HTML_PATH);
    }

    public HTML(String htmlPath) {
        setFolderPath(htmlPath);
    }

    public String getFolderPath() {
        return this.root.toString();
    }

    public void setFolderPath(String path) {
        this.root = Path.of(path).toAbsolutePath().normalize();
    }

    /** Convenience entry point: GET with keep-alive on a raw socket. */
    public void send(Socket client, String target) throws IOException {
        var out = new BufferedOutputStream(client.getOutputStream());
        send(out, target, false, true);
        out.flush();
    }

    /**
     * @param out response stream (caller flushes/closes)
     * @param target request-target from the request line, e.g. "/a%20b.txt?x=1"
     * @param headOnly HEAD request: send the head only
     * @param keepAlive value advertised in the Connection header
     * @return the HTTP status code sent
     */
    public int send(OutputStream out, String target, boolean headOnly, boolean keepAlive) throws IOException {
        var urlPath = stripQuery(target);
        String decoded;
        try {
            // URLDecoder is form-decoding: protect literal '+' (it means '+' in a path).
            decoded = URLDecoder.decode(urlPath.replace("+", "%2B"), StandardCharsets.UTF_8);
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

        if (Files.isDirectory(file)) {
            var index = file.resolve("index.html");
            if (Files.isRegularFile(index)) {
                return Page.sendFile(out, index, headOnly, keepAlive);
            }
            return Page.sendFolder(out, file, decoded, headOnly, keepAlive);
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return Page.sendError(out, 404, headOnly, keepAlive);
        }
        return Page.sendFile(out, file, headOnly, keepAlive);
    }

    private static String stripQuery(String target) {
        int cut = target.length();
        int q = target.indexOf('?');
        int h = target.indexOf('#');
        if (q >= 0) {
            cut = q;
        }
        if (h >= 0 && h < cut) {
            cut = h;
        }
        return target.substring(0, cut);
    }

    /**
     * @return the file inside the document root, or null if the path escapes it
     * (including through symbolic links).
     */
    private Path resolve(String decodedPath) throws IOException {
        Path candidate;
        try {
            candidate = this.root.resolve(decodedPath.substring(1)).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!candidate.startsWith(this.root)) {
            return null;
        }
        if (Files.exists(candidate) && !candidate.toRealPath().startsWith(this.root.toRealPath())) {
            return null;
        }
        return candidate;
    }
}
