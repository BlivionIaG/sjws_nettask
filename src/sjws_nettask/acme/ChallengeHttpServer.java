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
package sjws_nettask.acme;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * A small HTTP/1.1 listener for HTTP-01 when {@code acme.http_port} is not the main server port.
 * One request per connection, then close. The head is capped at 8 KiB and must arrive within
 * {@link #HEAD_DEADLINE} in total (not per read, so trickling one byte at a time does not extend
 * it). At most {@link #MAX_CONNECTIONS} connections are served at once; extra ones are closed on
 * accept, so this public port cannot pin the process's file descriptors (shared with the main
 * server). The CA retries failed validations, and one request at a time is all it needs.
 */
public final class ChallengeHttpServer implements AutoCloseable {

    static final int MAX_HEAD = 8192;
    static final int MAX_CONNECTIONS = 64;
    static final Duration HEAD_DEADLINE = Duration.ofSeconds(10);
    private static final byte[] CRLFCRLF = new byte[] {'\r', '\n', '\r', '\n'};

    private final ServerSocket socket;
    private final Thread thread;
    private volatile boolean closed;
    private final int maxConnections;
    private final Semaphore slots;
    private final long headDeadlineNanos;

    private ChallengeHttpServer(ServerSocket socket, Handler handler, int maxConnections, Duration headDeadline) {
        this.socket = socket;
        this.maxConnections = maxConnections;
        this.slots = new Semaphore(maxConnections);
        this.headDeadlineNanos = headDeadline.toNanos();
        this.thread = Thread.ofPlatform().daemon().name("acme-http-01").unstarted(() -> acceptLoop(handler));
        this.thread.start();
    }

    public static ChallengeHttpServer open(int port, ChallengeBoard board) throws IOException {
        return open(port, board, MAX_CONNECTIONS, HEAD_DEADLINE);
    }

    static ChallengeHttpServer open(int port, ChallengeBoard board, int maxConnections, Duration headDeadline)
            throws IOException {
        if (maxConnections < 1 || headDeadline.isNegative() || headDeadline.isZero()) {
            throw new IllegalArgumentException("maxConnections >= 1 and a positive head deadline required");
        }
        var socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port), 64);
        var handler = new AcmeChallengeHandler(board,
                request -> StaticFileHandler.error(404, request.headOnly()));
        return new ChallengeHttpServer(socket, handler, maxConnections, headDeadline);
    }

    public int port() {
        return this.socket.getLocalPort();
    }

    private void acceptLoop(Handler handler) {
        while (!this.closed) {
            Socket client;
            try {
                client = this.socket.accept();
            } catch (IOException e) {
                if (!this.closed) {
                    System.err.println("WARN: ACME HTTP-01 accept failed: " + e.getMessage());
                }
                return;
            }
            if (!this.slots.tryAcquire()) {
                closeQuietly(client); // over the cap: shed at once, the CA retries
                continue;
            }
            try {
                Thread.ofVirtual().start(() -> {
                    try (client) {
                        serve(client, handler, System.nanoTime() + this.headDeadlineNanos);
                    } catch (IOException e) {
                        // the CA retries
                    } finally {
                        this.slots.release();
                    }
                });
            } catch (RuntimeException | Error e) {
                this.slots.release();
                closeQuietly(client);
                throw e;
            }
        }
    }

    /** @return connections being served right now */
    int activeConnections() {
        return this.maxConnections - this.slots.availablePermits();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // nothing to do
        }
    }

    static void serve(Socket client, Handler handler) throws IOException {
        serve(client, handler, System.nanoTime() + HEAD_DEADLINE.toNanos());
    }

    /** @param deadline {@link System#nanoTime()} by which the whole head must have arrived */
    static void serve(Socket client, Handler handler, long deadline) throws IOException {
        byte[] head;
        try {
            head = readHead(client, deadline);
        } catch (SocketTimeoutException e) {
            return; // head deadline: close without an answer
        }
        var response = head == null
                ? StaticFileHandler.error(400, false)
                : handler.handle(request(head));
        write(client, response);
    }

    private static byte[] readHead(Socket client, long deadline) throws IOException {
        InputStream in = client.getInputStream();
        var data = new byte[MAX_HEAD];
        int n = 0;
        while (n < data.length) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new SocketTimeoutException("head deadline");
            }
            client.setSoTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, left / 1_000_000)));
            int read = in.read(data, n, data.length - n);
            if (read < 0) {
                return null;
            }
            int from = Math.max(0, n - (CRLFCRLF.length - 1)); // only rescan what this read could complete
            n += read;
            if (indexOf(data, from, n, CRLFCRLF) >= 0) {
                var head = new byte[n];
                System.arraycopy(data, 0, head, 0, n);
                return head;
            }
        }
        return null;
    }

    private static Request request(byte[] head) {
        var text = new String(head, StandardCharsets.ISO_8859_1);
        int line = text.indexOf("\r\n");
        if (line < 0) {
            return new Request("GET", "/", RequestHeaders.NONE);
        }
        var parts = text.substring(0, line).split(" ", 3);
        var method = parts.length > 0 ? parts[0] : "GET";
        var target = parts.length > 1 && parts[1].startsWith("/") ? parts[1] : "/";
        String host = null;
        int from = line + 2;
        while (from < text.length()) {
            int next = text.indexOf("\r\n", from);
            if (next < 0 || next == from) {
                break;
            }
            var header = text.substring(from, next);
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("host")) {
                host = header.substring(colon + 1).trim();
            }
            from = next + 2;
        }
        return new Request(method, target, new RequestHeaders(null, null, null, null, null, host));
    }

    private static void write(Socket client, Response response) throws IOException {
        byte[] body = bytes(response);
        var out = new ByteArrayOutputStream();
        var head = "HTTP/1.1 " + response.status() + " \r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        writeHeaders(out, response);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        if (response.sendsBody()) {
            out.write(body);
        }
        client.getOutputStream().write(out.toByteArray());
    }

    private static void writeHeaders(ByteArrayOutputStream out, Response response) throws IOException {
        for (var entry : response.headers().entries()) {
            var line = entry.getKey() + ": " + entry.getValue() + "\r\n";
            out.write(line.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private static byte[] bytes(Response response) {
        if (response.body() instanceof Body.Bytes bytes) {
            var data = bytes.data().duplicate();
            var out = new byte[data.remaining()];
            data.get(out);
            return out;
        }
        return new byte[0];
    }

    private static int indexOf(byte[] data, int from, int length, byte[] needle) {
        outer: for (int i = from; i + needle.length <= length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Override
    public void close() {
        this.closed = true;
        try {
            this.socket.close();
        } catch (IOException e) {
            // already closed
        }
        try {
            this.thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
