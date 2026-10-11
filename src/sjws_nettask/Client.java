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
package sjws_nettask;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import sjws_nettask.HTTP.HTML;
import sjws_nettask.HTTP.Page;

/**
 * Serves the HTTP/1.x requests of one connection (keep-alive aware).
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Client implements Runnable {

    /** Thrown when a request is malformed; carries the status code to answer with. */
    private static final class BadRequest extends Exception {

        final int status;

        BadRequest(int status) {
            super(null, null, false, false);
            this.status = status;
        }
    }

    private final Socket client;
    private final HTML html;

    public Client(Socket client, HTML html) {
        this.client = client;
        this.html = html;
    }

    @Override
    public void run() {
        try (var socket = this.client;
             var in = new BufferedInputStream(socket.getInputStream());
             var out = new BufferedOutputStream(socket.getOutputStream())) {
            while (handleRequest(in, out)) {
                // keep-alive: serve the next request on this connection
            }
        } catch (SocketTimeoutException e) {
            // idle keep-alive connection: just close it
        } catch (IOException | RuntimeException e) {
            if (!Thread.currentThread().isInterrupted()) { // interrupted = server shutting down
                System.err.println("Connection " + this.client.getRemoteSocketAddress() + " failed: " + e);
            }
        }
    }

    /**
     * Reads and answers one request.
     *
     * @return true if the connection should stay open for another request
     */
    private boolean handleRequest(InputStream in, OutputStream out) throws IOException {
        String requestLine;
        try {
            do {
                requestLine = readLine(in);
                if (requestLine == null) {
                    return false; // client closed the connection
                }
            } while (requestLine.isEmpty()); // RFC 9112 2.2: ignore leading empty lines

            if (requestLine.equalsIgnoreCase("STOP") || requestLine.equalsIgnoreCase("EXIT")) {
                return false; // legacy plain-text "close" command
            }

            var parts = requestLine.split(" ");
            if (parts.length != 3 || !parts[1].startsWith("/") || !parts[2].startsWith("HTTP/1.")) {
                throw new BadRequest(400);
            }
            var method = parts[0];
            var target = parts[1];

            boolean close = false;
            boolean keepAliveRequested = false;
            long contentLength = 0;
            boolean chunked = false;
            for (int count = 0;; count++) {
                var line = readLine(in);
                if (line == null) {
                    return false;
                }
                if (line.isEmpty()) {
                    break;
                }
                if (count >= CONSTANTS.MAX_HEADERS) {
                    throw new BadRequest(431);
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new BadRequest(400);
                }
                var name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                var value = line.substring(colon + 1).trim();
                switch (name) {
                    case "connection" -> {
                        var v = value.toLowerCase(Locale.ROOT);
                        close |= v.contains("close");
                        keepAliveRequested |= v.contains("keep-alive");
                    }
                    case "content-length" -> {
                        try {
                            contentLength = Long.parseLong(value);
                        } catch (NumberFormatException e) {
                            throw new BadRequest(400);
                        }
                        if (contentLength < 0) {
                            throw new BadRequest(400);
                        }
                    }
                    case "transfer-encoding" -> chunked = true;
                    default -> {
                    }
                }
            }
            if (chunked) {
                throw new BadRequest(501); // request bodies with chunked encoding are not supported
            }
            in.skipNBytes(contentLength); // never interpret a body as the next request

            boolean keepAlive = parts[2].equals("HTTP/1.0") ? keepAliveRequested && !close : !close;

            switch (method) {
                case "GET" -> this.html.send(out, target, false, keepAlive);
                case "HEAD" -> this.html.send(out, target, true, keepAlive);
                default -> Page.sendBody(out, 405, "text/html",
                        "<!DOCTYPE html><html><body><h1>405 Method Not Allowed</h1></body></html>",
                        false, keepAlive, "Allow", "GET, HEAD");
            }
            return keepAlive;
        } catch (BadRequest e) {
            Page.sendError(out, e.status, false, false);
            return false;
        }
    }

    /**
     * Reads one CRLF (or bare LF) terminated line as ISO-8859-1.
     *
     * @return the line without terminator, or null at end of stream
     */
    private static String readLine(InputStream in) throws IOException, BadRequest {
        var buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                var line = buf.toString(StandardCharsets.ISO_8859_1);
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            if (buf.size() >= CONSTANTS.MAX_LINE_LENGTH) {
                throw new BadRequest(414);
            }
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1);
    }
}
