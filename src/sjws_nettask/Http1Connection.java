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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
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
 * the {@link StaticFileHandler}, or a filter chain / router when one is installed), serializes with
 * {@link Http1Writer}. Deadlines (idle keep-alive,
 * request head, writes) are enforced by the shared {@link ConnectionReaper}, not by socket
 * timeouts.
 * <p>
 * Memory per idle keep-alive connection is kept small: the connection waits for the next request
 * directly in {@link #run()} (a shallow virtual-thread stack while parked) reading into a
 * {@link #IDLE_BUFFER}-byte array; the {@link #INPUT_BUFFER} array is only used while a read fills
 * the small one, and dropped again once idle. The previous request's head is forgotten after the
 * response. Output is unbuffered.
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

    /**
     * Input buffer while idle and for requests that fit in one small read (most heads). A read
     * that fills it switches to {@link #INPUT_BUFFER} for the rest of the request.
     */
    static final int IDLE_BUFFER = 256;

    private static final byte[] CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final SocketChannel channel;
    private final Handler handler;
    /**
     * Non-null only when {@link #handler} is the static file handler. {@link #apply} then calls
     * {@link StaticFileHandler#handle} directly and does not go through {@link Handler}.
     */
    private final StaticFileHandler files;
    private final InetSocketAddress peer;
    /** Captured with {@link #peer} for the failure log; the channel may already be closed by then. */
    private final String remote;
    private final Supplier<ResponseHeaders> extraHeaders;
    private final ConnectionReaper reaper;
    /**
     * Non-null on a server connection. {@link HandlerRef#get()} is the chain published when counters
     * are armed after start; null means this connection's {@link #handler}.
     */
    private final HandlerRef armed;
    /** Null when HA is off: the keep-alive decision is unchanged. */
    private final BooleanSupplier closeAfterResponse;
    private final Http1Parser.HeadParser parser = new Http1Parser.HeadParser(CONSTANTS.MAX_LINE_LENGTH,
            CONSTANTS.MAX_HEADERS);
    /** Discards chunked request bodies (GET/HEAD) up to {@link CONSTANTS#MAX_DRAINED_BODY}. */
    private final ChunkedDecoder chunks = new ChunkedDecoder(CONSTANTS.MAX_LINE_LENGTH, CONSTANTS.MAX_HEADERS,
            CONSTANTS.MAX_DRAINED_BODY);
    /** Unread input between position and limit; null until the first read. */
    private ByteBuffer in;

    /**
     * @param extraHeaders transport-level fields added to every response (e.g. Alt-Svc); called per response
     * @param handler static file handler, a filter chain, or another terminal. A
     *        {@link StaticFileHandler} is called directly ({@link #apply})
     */
    Http1Connection(SocketChannel channel, Handler handler, Supplier<ResponseHeaders> extraHeaders,
            ConnectionReaper reaper) {
        this(channel, handler, extraHeaders, reaper, null, null);
    }

    /**
     * Clustering off: no drain supplier, so keep-alive never calls one.
     *
     * @param armed handler published when counters are armed after start, or null when this
     *        connection's handler will not be replaced
     */
    Http1Connection(SocketChannel channel, Handler handler, Supplier<ResponseHeaders> extraHeaders,
            ConnectionReaper reaper, HandlerRef armed) {
        this(channel, handler, extraHeaders, reaper, armed, null);
    }

    /**
     * @param closeAfterResponse when non-null and true, this response is the last on the connection.
     *        Null (clustering off) is never invoked
     */
    Http1Connection(SocketChannel channel, Handler handler, Supplier<ResponseHeaders> extraHeaders,
            ConnectionReaper reaper, HandlerRef armed, BooleanSupplier closeAfterResponse) {
        this.channel = channel;
        this.handler = handler;
        this.files = handler instanceof StaticFileHandler direct ? direct : null;
        var peer = (InetSocketAddress) null;
        var remote = "(closed)";
        try {
            SocketAddress address = channel.getRemoteAddress();
            remote = String.valueOf(address);
            if (address instanceof InetSocketAddress inet) {
                peer = inet;
            }
        } catch (IOException e) {
            // already closed
        }
        this.peer = peer;
        this.remote = remote;
        this.extraHeaders = extraHeaders;
        this.reaper = reaper;
        this.armed = armed;
        this.closeAfterResponse = closeAfterResponse;
    }

    private boolean keep(boolean keepAlive) {
        if (!keepAlive) {
            return false;
        }
        var signal = this.closeAfterResponse;
        return signal == null || !signal.getAsBoolean();
    }

    /**
     * Static files: one null test, then {@link StaticFileHandler#handle}. Anything else (a filter
     * chain, a router, a test double) goes through {@link Handler}. The choice is fixed for the
     * life of the connection.
     */
    private Response apply(Request request) {
        var armed = this.armed;
        if (armed != null) {
            var live = armed.get();
            if (live != null) {
                return live.handle(request);
            }
        }
        var files = this.files;
        if (files != null) {
            return files.handle(request);
        }
        return this.handler.handle(request);
    }

    @Override
    public void run() {
        var timer = this.reaper.register(this.channel);
        var remote = this.remote; // captured at construction: the channel is closed by the time a failure is logged
        try (var ch = this.channel) {
            var out = new ChannelOutput(ch, timer);
            // Idle wait lives here, not in serveOne: a parked virtual thread keeps its frames, so
            // waiting in this shallow frame keeps the idle stack chunk small.
            do {
                timer.idle();
                if (!hasInput() && idleFill() < 0) {
                    break;
                }
            } while (serveOne(timer, out));
        } catch (IOException | RuntimeException e) {
            if (!isRoutine(e) && !Thread.currentThread().isInterrupted()) {
                System.err.println("Connection " + remote + " failed: " + e);
            }
        } finally {
            this.reaper.deregister(timer);
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

    /** The small buffer, kept for the connection's lifetime once allocated. */
    private ByteBuffer small;

    /**
     * Waits for the next request (keep-alive idle). Every buffered byte has been consumed, so a
     * large buffer is released and the read goes into the small one.
     */
    private int idleFill() throws IOException {
        if (this.small == null) {
            this.small = ByteBuffer.allocate(IDLE_BUFFER);
        }
        this.in = this.small;
        return read();
    }

    /**
     * Reads more input; only called (after {@link #idleFill()}) when every buffered byte has been consumed. When the last
     * read filled the small buffer, more is likely pending: switch to {@link #INPUT_BUFFER}.
     */
    private int fill() throws IOException {
        if (this.in == this.small && this.in.limit() == this.in.capacity()) {
            this.in = ByteBuffer.allocate(INPUT_BUFFER);
        }
        return read();
    }

    private int read() throws IOException {
        this.in.clear();
        int n = this.channel.read(this.in);
        this.in.flip();
        return n;
    }

    private boolean hasInput() {
        return this.in != null && this.in.hasRemaining();
    }

    /** @return true if the connection should stay open for another request */
    private boolean serveOne(ConnectionReaper.Timer timer, ChannelOutput out) throws IOException {
        try {
            return serveRequest(timer, out);
        } finally {
            this.parser.reset(); // do not keep the last head (strings, header map) while idle
        }
    }

    private boolean serveRequest(ConnectionReaper.Timer timer, ChannelOutput out) throws IOException {
        // run() waited for the first byte under the idle timeout; now the whole head (and any body to discard) must arrive before the head deadline.
        timer.head();
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
            lingerBeforeClose();
            return false;
        }

        var core = Http1Requests.toCore(request, this.peer);
        if (!core.headOnly() && !"GET".equals(core.method())) {
            // OPTIONS (200) or a known but unsupported method (405). Never read (possibly huge)
            // bodies just to reject them: keep the connection only if there is no body.
            boolean keepAlive = keep(request.keepAlive() && !request.hasBody());
            timer.processing();
            send(out, apply(core), keepAlive);
            return keepAlive;
        }

        boolean keepAlive = keep(request.keepAlive());
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

        send(out, apply(core), keepAlive);
        return keepAlive;
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
            lingerBeforeClose();
            return -1;
        }
    }

    /**
     * After an error response to a request whose input was not read whole: closing with unread
     * input makes the kernel send a reset, which can destroy the response before the client reads
     * it. Half-close, then discard up to {@link #LINGER_BYTES} until the client closes; the head
     * deadline (still armed) bounds the time.
     */
    private void lingerBeforeClose() {
        try {
            this.channel.shutdownOutput();
            long left = LINGER_BYTES;
            while (left > 0) {
                int n = read();
                if (n < 0) {
                    return;
                }
                left -= n;
            }
        } catch (IOException e) {
            // closing anyway
        }
    }

    /** Input discarded at most after an error response (see {@link #lingerBeforeClose()}). */
    static final int LINGER_BYTES = 64 * 1024;

    private void send(ChannelOutput out, Response response, boolean keepAlive) throws IOException {
        Http1Writer.write(out, response, keepAlive, this.extraHeaders.get());
    }
}
