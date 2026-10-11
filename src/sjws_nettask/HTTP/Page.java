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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import sjws_nettask.core.Body;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Legacy API: writes complete HTTP/1.1 responses. Thin wrappers over the core
 * {@link StaticFileHandler} response builders and {@link Http1Writer}.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public final class Page {

    private Page() {
    }

    public static int sendFile(OutputStream out, Path file, boolean headOnly, boolean keepAlive) throws IOException {
        return sendFile(out, file, headOnly, keepAlive, null);
    }

    /** Sends the file without containment checks (callers resolve it). */
    public static int sendFile(OutputStream out, Path file, boolean headOnly, boolean keepAlive,
            String ifModifiedSince) throws IOException {
        return write(out, StaticFileHandler.fileResponse(file, headOnly, ifModifiedSince), keepAlive);
    }

    public static int sendRedirect(OutputStream out, String location, boolean headOnly, boolean keepAlive)
            throws IOException {
        return write(out, StaticFileHandler.redirect(location, headOnly), keepAlive);
    }

    /** @param names entries to list; directories end with "/" */
    public static int sendFolder(OutputStream out, List<String> names, String urlPath, boolean headOnly,
            boolean keepAlive) throws IOException {
        return write(out, StaticFileHandler.listing(names, urlPath, headOnly), keepAlive);
    }

    public static int sendError(OutputStream out, int code, boolean headOnly, boolean keepAlive) throws IOException {
        return write(out, StaticFileHandler.error(code, headOnly), keepAlive);
    }

    public static int sendBody(OutputStream out, int code, String mime, String body, boolean headOnly,
            boolean keepAlive, String... extraHeaders) throws IOException {
        var headers = ResponseHeaders.of()
                .add("accept-ranges", StaticFileHandler.ACCEPT_RANGES)
                .add("content-type", Header.withCharset(mime));
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            headers.add(extraHeaders[i], extraHeaders[i + 1]);
        }
        var response = new Response(code, headers, Body.Bytes.of(body.getBytes(StandardCharsets.UTF_8)), headOnly);
        return write(out, response, keepAlive);
    }

    static String escape(String s) {
        return StaticFileHandler.escape(s);
    }

    private static int write(OutputStream out, Response response, boolean keepAlive) throws IOException {
        Http1Writer.write(out, response, keepAlive);
        return response.status();
    }
}
