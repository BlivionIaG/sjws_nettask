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

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.observe.RequestObserver;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.http1.ChunkedDecoder;
import sjws_nettask.http1.Http1Parser;
import sjws_nettask.http1.Http1Requests;
import sjws_nettask.http1.Http1Writer;
import sjws_nettask.http1.ParseError;

/**
 * HTTP/1.x adapter for one connection on a blocking {@link SocketChannel} (run on a virtual
 * thread): parses with {@link Http1Parser.HeadParser}, maps to a core
 * {@link sjws_nettask.core.Request} ({@link Http1Requests}), asks the {@link Handler} (in production
 * the {@link StaticFileHandler}), serializes with {@link Http1Writer}. Deadlines (idle keep-alive,
 * request head, writes) are enforced by the shared {@link ConnectionReaper}, not by socket
 * timeouts.
 * <p>
 * Memory per idle keep-alive connection: the {@link #INPUT_BUFFER} input array (allocated at
 * the first read) and a small parser line buffer; output is unbuffered.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
final class Http1Connection implements Runnable {

    /**
     * Input buffer per connection. Heads are parsed incrementally, so this only bounds the bytes
     * read per syscall, not the head size (limits: {@link CONSTANTS#MAX_LINE_LENGTH},
     * {@link CONSTANTS#MAX_HEADERS}).
     */
    static final int INPUT_BUFFER = 2048;

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final SocketChannel channel;
    private final Handler handler;
    private final Supplier<ResponseHeaders> extraHeaders;
    private final ConnectionReaper reaper;
    private final RequestObserver observer;
    /** Numeric client address (no reverse lookup), cached for the life of the connection. */
    private final String clientHost;
    private final boolean loopback;
    private long startedAt;
    private String method = "-";
    private String target = "-";
    private String protocol = "HTTP/1.1";
    private int protocolId = RequestObserver.HTTP_1_1;
    private String referer = "-";
    private String userAgent = "-";
    private final Http1Parser.HeadParser parser = new Http1Parser.HeadParser(CONSTANTS.MAX_LINE_LENGTH,
            CONSTANTS.MAX_HEADERS);
    /** Discards chunked request bodies (GET/HEAD) up to {@link CONSTANTS#MAX_DRAINED_BODY}. */
    private final ChunkedDecoder chunks = new ChunkedDecoder(CONSTANTS.MAX_LINE_LENGTH, CONSTANTS.MAX_HEADERS,
            CONSTANTS.MAX_DRAINED_BODY);
    /** Unread input between position and limit; null until the first read. */
    private ByteBuffer in;

    /**
     * @param extraHeaders transport-level fields added to every response (e.g. Alt-Svc); called per response
     */
    Http1Connection(SocketChannel channel, Handler handler, Supplier<ResponseHeaders> extraHeaders,
            ConnectionReaper reaper) {
        this(channel, handler, extraHeaders, reaper, RequestObserver.NONE, "0.0.0.0", false);
    }

    /**
     * @param clientHost numeric peer address, captured once at accept (never a DNS name)
     * @param loopback peer is a loopback address; gates {@code /metrics} when it is localhost-only
     */
    Http1Connection(SocketChannel channel, Handler handler, Supplier<ResponseHeaders> extraHeaders,
            ConnectionReaper reaper, RequestObserver observer, String clientHost, boolean loopback) {
        this.channel = channel;
        this.handler = handler;
        this.extraHeaders = extraHeaders;
        this.reaper = reaper;
        this.observer = java.util.Objects.requireNonNull(observer, "observer");
        this.clientHost = java.util.Objects.requireNonNull(clientHost, "clientHost");
        this.loopback = loopback;
    }

    @Override
    public void run() {
        var timer = this.reaper.register(this.channel);
        var remote = remote(); // read now: the channel is closed by the time a failure is logged
        try (var ch = this.channel) {
            var out = new ChannelOutput(ch, timer);
            while (serveOne(timer, out)) {
                // keep-alive: next request on this connection
            }
        } catch (IOException | RuntimeException e) {
            if (!isRoutine(e) && !Thread.currentThread().isInterrupted()) {
                System.err.println("Connection " + remote + " failed: " + e);
            }
        } finally {
            this.reaper.deregister(timer);
        }
    }

    private String remote() {
        try {
            return String.valueOf(this.channel.getRemoteAddress());
        } catch (IOException e) {
            return "(closed)";
        }
    }

    /** Client went away, timed out or the server is shutting down: not worth logging. */
    static boolean isRoutine(Exception e) {
        if (e instanceof EOFException || e instanceof ClosedChannelException) { // incl. async close, interrupt
            return true;
        }
        if (e instanceof SocketException || e.getClass() == IOException.class) {
            var msg = String.valueOf(e.getMessage());
            return msg.contains("reset") || msg.contains("Broken pipe") || msg.contains("closed")
                    || msg.contains("Closed") || msg.contains("abort");
        }
        return false;
    }

    /** Reads more input; only called when every buffered byte has been consumed. */
    private int fill() throws IOException {
        if (this.in == null) {
            this.in = ByteBuffer.allocate(INPUT_BUFFER);
        } else {
            this.in.clear();
        }
        int n = this.channel.read(this.in);
        this.in.flip();
        return n;
    }

    private boolean hasInput() {
        return this.in != null && this.in.hasRemaining();
    }

    /** @return true if the connection should stay open for another request */
    private boolean serveOne(ConnectionReaper.Timer timer, ChannelOutput out) throws IOException {
        // Wait for the first byte of the next request with only the idle timeout ...
        timer.idle();
        if (!hasInput() && fill() < 0) {
            return false;
        }
        // ... then the whole head (and any body to discard) must arrive before the head deadline.
        timer.head();
        if (this.observer.recording()) {
            this.startedAt = System.nanoTime();
            this.protocolId = RequestObserver.HTTP_1_1;
        }
        if (this.observer.logging()) {
            this.method = "-";
            this.target = "-";
            this.protocol = "HTTP/1.1";
            this.referer = "-";
            this.userAgent = "-";
        }

        Http1Parser.Head request;
        try {
            this.parser.reset();
            while (true) {
                int used = this.parser.feed(this.in.array(), this.in.arrayOffset() + this.in.position(),
                        this.in.remaining());
                this.in.position(this.in.position() + used);
                if (this.parser.isComplete()) {
                    request = this.parser.head();
                    break;
                }
                if (fill() < 0) {
                    if (timer.headExpired()) {
                        send(out, StaticFileHandler.error(408, false), false);
                        return false;
                    }
                    request = this.parser.endOfInput(); // null (nothing started) or throws
                    if (request == null) {
                        return false;
                    }
                }
            }
        } catch (ParseError e) {
            send(out, StaticFileHandler.error(e.status(), false), false);
            return false;
        }

        var core = Http1Requests.toCore(request);
        note(request);
        if (!core.headOnly() && !"GET".equals(core.method())) {
            // OPTIONS (200) or a known but unsupported method (405). Never read (possibly huge)
            // bodies just to reject them: keep the connection only if there is no body.
            boolean keepAlive = request.keepAlive() && !request.hasBody();
            timer.processing();
            send(out, respond(core), keepAlive);
            return keepAlive;
        }

        boolean keepAlive = request.keepAlive();
        long length = request.contentLength();
        if (request.chunked()) {
            if (request.expectContinue()) {
                out.write(CONTINUE);
                timer.resumeHead();
            }
            int drained = drainChunked(out, timer);
            if (drained < 0) {
                return false; // malformed (answered 400), or the peer went away
            }
            keepAlive &= drained == 1;
        } else if (length > CONSTANTS.MAX_DRAINED_BODY) {
            keepAlive = false; // answer, then close instead of reading the body
        } else if (length > 0) {
            if (request.expectContinue()) {
                out.write(CONTINUE); // runs under the write deadline ...
                timer.resumeHead(); // ... then the body is bounded by the head deadline again
            }
            // GET/HEAD bodies have no meaning; discard them (the head deadline still applies)
            long remaining = length;
            while (remaining > 0) {
                if (!hasInput() && fill() < 0) {
                    if (timer.headExpired()) { // no final response was sent yet (100 is interim)
                        send(out, StaticFileHandler.error(408, false), false);
                    }
                    return false;
                }
                int n = (int) Math.min(remaining, this.in.remaining());
                this.in.position(this.in.position() + n);
                remaining -= n;
            }
        }
        timer.processing();

        send(out, respond(core), keepAlive);
        return keepAlive;
    }

    /** Copies the request into connection fields. Referer and User-Agent are read only when logged. */
    private void note(Http1Parser.Head request) {
        if (!this.observer.recording()) {
            return;
        }
        this.protocolId = RequestObserver.protocolId(request.version());
        if (!this.observer.logging()) {
            return;
        }
        this.method = request.method();
        this.target = request.target();
        this.protocol = request.version();
        if (this.observer.logHeaders()) {
            var ref = request.headers().get("referer");
            var ua = request.headers().get("user-agent");
            if (ref != null && !ref.isEmpty()) {
                this.referer = ref.get(0);
            }
            if (ua != null && !ua.isEmpty()) {
                this.userAgent = ua.get(0);
            }
        }
    }

    private Response respond(Request core) {
        var special = this.observer.intercept(core.method(), core.target(), this.loopback);
        return special != null ? special : this.handler.handle(core);
    }

    /**
     * Discards a chunked body under the head deadline.
     *
     * @return 1 when it was consumed whole, 0 when it exceeded {@link CONSTANTS#MAX_DRAINED_BODY}
     *         (answer, then close), -1 when the connection must close now (400/408 already sent, or EOF)
     */
    private int drainChunked(ChannelOutput out, ConnectionReaper.Timer timer) throws IOException {
        this.chunks.reset();
        try {
            while (true) {
                if (hasInput()) {
                    int used = this.chunks.feed(this.in.array(), this.in.arrayOffset() + this.in.position(),
                            this.in.remaining());
                    this.in.position(this.in.position() + used);
                    if (this.chunks.isComplete()) {
                        return 1;
                    }
                    if (this.chunks.overLimit()) {
                        return 0;
                    }
                }
                if (!hasInput() && fill() < 0) {
                    if (timer.headExpired()) {
                        send(out, StaticFileHandler.error(408, false), false);
                    }
                    return -1;
                }
            }
        } catch (ParseError e) {
            send(out, StaticFileHandler.error(e.status(), false), false);
            return -1;
        }
    }

    private void send(ChannelOutput out, Response response, boolean keepAlive) throws IOException {
        long bytes = 0;
        IOException error = null;
        try {
            Http1Writer.write(out, response, keepAlive, this.extraHeaders.get());
            bytes = response.sendsBody() ? Math.max(0L, response.contentLength()) : 0L;
        } catch (IOException e) {
            error = e;
        }
        if (this.observer.recording()) {
            long nanos = System.nanoTime() - this.startedAt;
            if (this.observer.logging()) {
                this.observer.complete(response.status(), this.protocolId, bytes, nanos, this.clientHost, this.method,
                        this.target, this.protocol, this.referer, this.userAgent);
            } else {
                this.observer.complete(response.status(), this.protocolId, bytes, nanos);
            }
        }
        if (error != null) {
            throw error;
        }
    }
}
