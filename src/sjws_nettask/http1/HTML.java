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
package sjws_nettask.http1;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.file.Path;
import sjws_nettask.CONSTANTS;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Legacy API: serves a request-target over HTTP/1.1. Delegates every decision to the
 * core {@link StaticFileHandler} and serialization to {@link Http1Writer}.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class HTML {

    private final StaticFileHandler handler;

    public HTML() {
        this(CONSTANTS.DEFAULT_HTML_PATH);
    }

    /** @throws UncheckedIOException if the document root does not exist or is not a directory */
    public HTML(String htmlPath) {
        this.handler = new StaticFileHandler(Path.of(htmlPath));
    }

    public StaticFileHandler handler() {
        return this.handler;
    }

    public String getFolderPath() {
        return this.handler.root().toString();
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
     * @return the HTTP status code sent
     * @throws IOException if the response could not be completed; the connection must then be closed
     */
    public int send(OutputStream out, String target, boolean headOnly, boolean keepAlive, String ifModifiedSince)
            throws IOException {
        var request = new Request(headOnly ? "HEAD" : "GET", target, RequestHeaders.ifModifiedSince(ifModifiedSince));
        var response = this.handler.handle(request);
        Http1Writer.write(out, response, keepAlive, ResponseHeaders.of());
        return response.status();
    }
}
