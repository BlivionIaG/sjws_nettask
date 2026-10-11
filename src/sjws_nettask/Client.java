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
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import sjws_nettask.HTTP.HTML;
import sjws_nettask.HTTP.Http1Parser;
import sjws_nettask.HTTP.Page;
import sjws_nettask.HTTP.ParseError;

/**
 * Serves the HTTP/1.x requests of one connection (keep-alive aware).
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Client implements Runnable {

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final Socket client;
    private final HTML html;
    private final ServerConfig config;

    public Client(Socket client, HTML html) {
        this(client, html, ServerConfig.defaults(html.getFolderPath()));
    }

    public Client(Socket client, HTML html, ServerConfig config) {
        this.client = client;
        this.html = html;
        this.config = config;
    }

    @Override
    public void run() {
        try (var socket = this.client;
             var timed = new TimedInputStream(socket.getInputStream(), socket, (int) this.config.idleTimeout().toMillis());
             var in = new BufferedInputStream(timed);
             var out = new BufferedOutputStream(socket.getOutputStream())) {
            while (serveOne(timed, in, out)) {
                // keep-alive: next request on this connection
            }
        } catch (IOException | RuntimeException e) {
            if (!isRoutine(e) && !Thread.currentThread().isInterrupted()) {
                System.err.println("Connection " + this.client.getRemoteSocketAddress() + " failed: " + e);
            }
        }
    }

    /** Client went away, timed out or the server is shutting down: not worth logging. */
    static boolean isRoutine(Exception e) {
        if (e instanceof EOFException || e instanceof SocketTimeoutException) {
            return true;
        }
        if (e instanceof SocketException) {
            var msg = String.valueOf(e.getMessage());
            return msg.contains("reset") || msg.contains("Broken pipe") || msg.contains("closed")
                    || msg.contains("Closed") || msg.contains("abort");
        }
        return false;
    }

    /** @return true if the connection should stay open for another request */
    private boolean serveOne(TimedInputStream timed, BufferedInputStream in, OutputStream out) throws IOException {
        // Wait for the first byte of the next request with only the idle timeout ...
        timed.clearDeadline();
        in.mark(1);
        if (in.read() == -1) {
            return false;
        }
        in.reset();
        // ... then the whole head must arrive before the deadline.
        timed.setDeadline(this.config.headerTimeout().toMillis());

        Http1Parser.Head request;
        try {
            request = Http1Parser.read(in, CONSTANTS.MAX_LINE_LENGTH, CONSTANTS.MAX_HEADERS);
        } catch (ParseError e) {
            Page.sendError(out, e.status(), false, false);
            return false;
        } catch (SocketTimeoutException e) {
            if (timed.deadlineExpired()) {
                Page.sendError(out, 408, false, false);
            }
            return false;
        }
        if (request == null) {
            return false;
        }

        var method = request.method();
        boolean head = request.headOnly();
        if (!head && !method.equals("GET")) {
            // Known but unsupported method. Never read (possibly huge) bodies just to reject them.
            boolean keepAlive = request.keepAlive() && request.contentLength() == 0;
            Page.sendBody(out, 405, "text/html",
                    "<!DOCTYPE html><html><body><h1>405 Method Not Allowed</h1></body></html>",
                    false, keepAlive, "Allow", "GET, HEAD");
            return keepAlive;
        }

        boolean keepAlive = request.keepAlive();
        long length = request.contentLength();
        if (length > CONSTANTS.MAX_DRAINED_BODY) {
            keepAlive = false; // answer, then close instead of reading the body
        } else if (length > 0) {
            if (request.expectContinue()) {
                out.write(CONTINUE);
                out.flush();
            }
            try {
                in.skipNBytes(length); // GET/HEAD bodies have no meaning; discard them (deadline still applies)
            } catch (SocketTimeoutException e) {
                return false;
            }
        }
        timed.clearDeadline();

        this.html.send(out, request.target(), head, keepAlive, request.ifModifiedSince());
        return keepAlive;
    }
}
