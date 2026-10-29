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
package sjws_nettask.h3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http3.DefaultHttp3GoAwayFrame;
import io.netty.handler.codec.http3.DefaultHttp3SettingsFrame;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3SettingIdentifier;
import io.netty.handler.codec.quic.QuicStreamChannel;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Http3RequestBody} without QUIC (#281): an {@link EmbeddedChannel} stands in for the stream's event loop and
 * {@code autoRead} switch, so the flow-control hysteresis, the end/failure/abandon states and the waits are exact.
 * Also {@link Http3Server.PeerSettings} and {@link Http3Writer#fits}. No natives needed.
 */
class Http3RequestBodyTest {

    private static final long TIMEOUT = TimeUnit.MILLISECONDS.toNanos(150);

    private final EmbeddedChannel loop = new EmbeddedChannel();
    private final List<Integer> stopSending = new ArrayList<>();
    private boolean active = true;
    private final List<ByteBuf> offered = new ArrayList<>();

    /** A stream whose event loop, config and isActive are the embedded channel's; records shutdownInput(error). */
    private QuicStreamChannel stream() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "eventLoop" -> this.loop.eventLoop();
            case "config" -> config();
            case "isActive" -> this.active;
            case "shutdownInput" -> {
                if (args != null && args.length >= 1 && args[0] instanceof Integer code) {
                    this.stopSending.add(code);
                }
                yield null;
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "stream";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (QuicStreamChannel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {QuicStreamChannel.class}, h);
    }

    /** The stream config's autoRead switch is the embedded channel's. */
    private io.netty.handler.codec.quic.QuicStreamChannelConfig config() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "isAutoRead" -> this.loop.config().isAutoRead();
            case "setAutoRead" -> {
                this.loop.config().setAutoRead((Boolean) args[0]);
                yield proxy;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (io.netty.handler.codec.quic.QuicStreamChannelConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {io.netty.handler.codec.quic.QuicStreamChannelConfig.class}, h);
    }

    private ByteBuf bytes(int n) {
        var b = Unpooled.buffer(n).writeZero(n);
        this.offered.add(b);
        return b;
    }

    /** The event loop's side: offer, then drop the frame's own reference (as Http3RequestHandler does). */
    private void offer(Http3RequestBody body, int n) {
        var b = bytes(n);
        body.offer(b);
        b.release();
    }

    @AfterEach
    void noLeaks() {
        this.loop.runPendingTasks();
        this.loop.finishAndReleaseAll();
        for (var b : this.offered) {
            assertEquals(0, b.refCnt(), "every offered buffer released");
        }
    }

    @Test
    void readsInOrderPausesAtHighAndResumesAtLow() throws Exception {
        var body = new Http3RequestBody(stream(), -1, TIMEOUT);
        assertTrue(this.loop.config().isAutoRead());
        offer(body, Http3RequestBody.HIGH - 1);
        assertTrue(this.loop.config().isAutoRead(), "below HIGH: still reading");
        offer(body, 1);
        assertFalse(this.loop.config().isAutoRead(), "HIGH unread bytes: the stream stops reading");
        offer(body, 100); // already decoded in the same read: kept, still paused
        assertFalse(this.loop.config().isAutoRead());
        var buf = new byte[4096];
        int read = 0;
        while (body.buffered() > Http3RequestBody.LOW + 4096) {
            read += body.read(buf, 0, buf.length);
        }
        this.loop.runPendingTasks();
        assertFalse(this.loop.config().isAutoRead(), "above LOW: still paused");
        read += body.read(buf, 0, buf.length);
        assertTrue(body.buffered() <= Http3RequestBody.LOW);
        this.loop.runPendingTasks();
        assertTrue(this.loop.config().isAutoRead(), "at LOW: reading again");
        body.end();
        int n;
        while ((n = body.read(buf, 0, buf.length)) >= 0) {
            read += n;
        }
        assertEquals(Http3RequestBody.HIGH + 100, read);
        assertEquals(-1, body.length(), "content arrived without a content-length: unknown");
        assertEquals(-1, body.read(buf, 0, 0), "at the end even a zero-length read is -1 (as on HTTP/1.1)");
        body.abandon();
        this.loop.runPendingTasks();
        assertEquals(List.of(), this.stopSending, "the client had finished: no STOP_SENDING");
    }

    @Test
    void lengthWithoutContentLength() throws Exception {
        var ended = new Http3RequestBody(stream(), -1, TIMEOUT);
        ended.end();
        assertEquals(0, ended.length(), "ended with no content: 0, as an absent HTTP/1.1 body");
        offer(ended, 5); // after the end: ignored
        assertEquals(0, ended.length(), "fixed once given");
        assertEquals(-1, ended.read(new byte[4], 0, 4));
        var silent = new Http3RequestBody(stream(), -1, TIMEOUT);
        long t0 = System.nanoTime();
        assertEquals(-1, silent.length(), "nothing arrived within the timeout: unknown");
        assertTrue(System.nanoTime() - t0 >= TIMEOUT);
        assertEquals(42, new Http3RequestBody(stream(), 42, TIMEOUT).length());
        var interrupted = new Http3RequestBody(stream(), -1, TimeUnit.SECONDS.toNanos(30));
        Thread.currentThread().interrupt();
        try {
            assertEquals(-1, interrupted.length());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
        } finally {
            Thread.interrupted();
        }
        var failed = new Http3RequestBody(stream(), -1, TIMEOUT);
        failed.fail();
        assertEquals(-1, failed.length());
    }

    @Test
    void readFailures() throws Exception {
        var buf = new byte[16];
        var shortBody = new Http3RequestBody(stream(), 10, TIMEOUT);
        offer(shortBody, 5);
        assertEquals(5, shortBody.read(buf, 0, buf.length));
        shortBody.end();
        var e = assertThrows(IOException.class, () -> shortBody.read(buf, 0, buf.length));
        assertTrue(e.getMessage().contains("5 of 10"), e.getMessage());

        var reset = new Http3RequestBody(stream(), -1, TIMEOUT);
        reset.fail();
        offer(reset, 3); // after a failure: ignored
        assertTrue(assertThrows(IOException.class, () -> reset.read(buf, 0, 1)).getMessage().contains("closed"));

        var stalled = new Http3RequestBody(stream(), -1, TIMEOUT);
        assertEquals(0, stalled.read(buf, 0, 0), "a zero-length read doesn't wait");
        long t0 = System.nanoTime();
        assertTrue(assertThrows(IOException.class, () -> stalled.read(buf, 0, 1)).getMessage().contains("150 ms"));
        assertTrue(System.nanoTime() - t0 >= TIMEOUT);

        var cancelled = new Http3RequestBody(stream(), -1, TimeUnit.SECONDS.toNanos(30));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> cancelled.read(buf, 0, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertThrows(IndexOutOfBoundsException.class, () -> cancelled.read(buf, 10, 10));
    }

    @Test
    void aWaitingReaderWakesOnData() throws Exception {
        var body = new Http3RequestBody(stream(), -1, TimeUnit.SECONDS.toNanos(30));
        var got = new java.util.concurrent.CompletableFuture<Integer>();
        Thread.ofVirtual().start(() -> {
            try {
                got.complete(body.read(new byte[8], 0, 8));
            } catch (IOException e) {
                got.completeExceptionally(e);
            }
        });
        Thread.sleep(50);
        offer(body, 3);
        assertEquals(3, got.get(5, TimeUnit.SECONDS));
        body.end();
        body.abandon();
    }

    @Test
    void abandonReleasesResumesAndAsksTheClientToStop() throws Exception {
        var body = new Http3RequestBody(stream(), 100_000, TIMEOUT);
        offer(body, Http3RequestBody.HIGH);
        assertFalse(this.loop.config().isAutoRead());
        body.abandon();
        body.abandon(); // idempotent
        assertEquals(0, body.buffered(), "what was buffered is released");
        this.loop.runPendingTasks();
        assertTrue(this.loop.config().isAutoRead(), "late DATA is read and dropped");
        assertEquals(List.of((int) Http3ErrorCode.H3_NO_ERROR.code()), this.stopSending);
        offer(body, 10); // after abandon: dropped at once
        assertEquals(0, body.buffered());
        assertTrue(assertThrows(IOException.class, () -> body.read(new byte[1], 0, 1)).getMessage().contains("closed"));
        assertEquals(100_000, body.length());
        // the stream already gone: no STOP_SENDING
        this.stopSending.clear();
        this.active = false;
        var gone = new Http3RequestBody(stream(), -1, TIMEOUT);
        offer(gone, 0); // an empty DATA frame changes nothing
        assertEquals(0, gone.buffered());
        gone.abandon();
        this.loop.runPendingTasks();
        assertEquals(List.of(), this.stopSending);
    }

    @Test
    void peerSettingsRecordTheClientsFieldSectionLimit() {
        var connection = new Http3Server.Connection(new Semaphore(1));
        assertEquals(Long.MAX_VALUE, connection.peerMaxFieldSection, "unlimited until SETTINGS arrive");
        var ch = new EmbeddedChannel(new Http3Server.PeerSettings(connection));
        ch.writeInbound(new DefaultHttp3SettingsFrame()); // no MAX_FIELD_SECTION_SIZE: unchanged
        assertEquals(Long.MAX_VALUE, connection.peerMaxFieldSection);
        var settings = new DefaultHttp3SettingsFrame();
        settings.put(Http3SettingIdentifier.HTTP3_SETTINGS_MAX_FIELD_SECTION_SIZE.id(), 8192L);
        ch.writeInbound(settings);
        assertEquals(8192, connection.peerMaxFieldSection);
        ch.writeInbound(new DefaultHttp3GoAwayFrame(0)); // other control frames: released, nothing recorded
        ch.pipeline().fireExceptionCaught(new IllegalStateException("codec error")); // swallowed, no log
        assertEquals(8192, connection.peerMaxFieldSection);
        assertTrue(ch.inboundMessages().isEmpty());
        ch.checkException();
        ch.finishAndReleaseAll();
    }

    @Test
    void fitsCountsNamePlusValuePlus32() {
        var head = new io.netty.handler.codec.http3.DefaultHttp3HeadersFrame();
        head.headers().status("200").add("x-a", "12345");
        long size = (":status".length() + 3 + 32) + ("x-a".length() + 5 + 32);
        assertTrue(Http3Writer.fits(head, Long.MAX_VALUE));
        assertTrue(Http3Writer.fits(head, size));
        assertFalse(Http3Writer.fits(head, size - 1));
    }
}
