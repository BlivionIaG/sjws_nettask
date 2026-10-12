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
import java.util.function.Supplier;

import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;
import sjws_nettask.http1.Http1Parser;
import sjws_nettask.http1.Http1Writer;
import sjws_nettask.http1.ParseError;

/**
 * HTTP/1.x adapter for one connection on a blocking {@link SocketChannel} (run on a virtual
 * thread): parses with {@link Http1Parser.HeadParser}, maps to a core {@link Request}, asks the
 * {@link StaticFileHandler} (or {@link #override}, the filter chain or another terminal handler,
 * when one was installed), serializes with {@link Http1Writer}. Deadlines (idle keep-alive,
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
    private final StaticFileHandler handler;
    /**
     * Null when the terminal is {@link #handler} and no filters are registered. {@link #apply} then
     * calls {@link StaticFileHandler#handle} and does not go through {@link Handler}.
     */
    private final Handler override;
    private final InetSocketAddress peer;
    /** Captured with {@link #peer} for the failure log; the channel may already be closed by then. */
    private final String remote;
    private final Supplier<ResponseHeaders> extraHeaders;
    private final ConnectionReaper reaper;
    private final Http1Parser.HeadParser parser = new Http1Parser.HeadParser(CONSTANTS.MAX_LINE_LENGTH,
            CONSTANTS.MAX_HEADERS);
    /** Unread input between position and limit; null until the first read. */
    private ByteBuffer in;

    /**
     * @param extraHeaders transport-level fields added to every response (e.g. Alt-Svc); called per response
     */
    Http1Connection(SocketChannel channel, StaticFileHandler handler, Supplier<ResponseHeaders> extraHeaders,
            ConnectionReaper reaper) {
        this(channel, handler, null, extraHeaders, reaper);
    }

    /**
     * @param override compiled filters, or another terminal handler, or null to call {@code handler}
     *        directly (no extra indirection on the hot path)
     */
    Http1Connection(SocketChannel channel, StaticFileHandler handler, Handler override,
            Supplier<ResponseHeaders> extraHeaders, ConnectionReaper reaper) {
        this.channel = channel;
        this.handler = handler;
        this.override = override;
        var remote = peerOf(channel);
        this.peer = remote.peer;
        this.remote = remote.label;
        this.extraHeaders = extraHeaders;
        this.reaper = reaper;
    }

    private record Peer(InetSocketAddress peer, String label) {
    }

    /** One {@code getRemoteAddress} for every request on this connection and for the failure log. */
    private static Peer peerOf(SocketChannel channel) {
        try {
            SocketAddress address = channel.getRemoteAddress();
            var peer = address instanceof InetSocketAddress inet ? inet : null;
            return new Peer(peer, String.valueOf(address));
        } catch (IOException e) {
            return new Peer(null, "(closed)");
        }
    }

    /**
     * No override: one null test, then the same {@link StaticFileHandler#handle} call as without a
     * chain. The test is constant for the life of the connection.
     */
    private Response apply(Request request) {
        var terminal = this.override;
        if (terminal == null) {
            return this.handler.handle(request);
        }
        return terminal.handle(request);
    }

    @Override
    public void run() {
        var timer = this.reaper.register(this.channel);
        var remote = this.remote; // captured at construction: the channel is closed by the time a failure is logged
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

        var core = new Request(request.method(), request.target(), RequestHeaders.of(request.headers()), this.peer,
                false);
        if (!core.headOnly() && !"GET".equals(core.method())) {
            // Known but unsupported method (the handler answers 405). Never read (possibly huge)
            // bodies just to reject them: keep the connection only if there is no body.
            boolean keepAlive = request.keepAlive() && request.contentLength() == 0;
            timer.processing();
            send(out, apply(core), keepAlive);
            return keepAlive;
        }

        boolean keepAlive = request.keepAlive();
        long length = request.contentLength();
        if (length > CONSTANTS.MAX_DRAINED_BODY) {
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

    private void send(ChannelOutput out, Response response, boolean keepAlive) throws IOException {
        Http1Writer.write(out, response, keepAlive, this.extraHeaders.get());
    }
}
