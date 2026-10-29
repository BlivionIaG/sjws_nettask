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
package sjws_nettask.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2StreamChannel;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Http2RequestBody} without a real multiplexed stream (#289): an {@link EmbeddedChannel}
 * stands in for the event loop and {@code autoRead} switch.
 */
class Http2RequestBodyTest {

    private static final long TIMEOUT = TimeUnit.MILLISECONDS.toNanos(150);

    private final EmbeddedChannel loop = new EmbeddedChannel();
    private final List<Object> resets = new ArrayList<>();
    private boolean active = true;
    private final List<ByteBuf> offered = new ArrayList<>();

    private Http2StreamChannel stream() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "eventLoop" -> this.loop.eventLoop();
            case "config" -> config();
            case "isActive" -> this.active;
            case "writeAndFlush" -> {
                if (args != null && args.length >= 1) {
                    this.resets.add(args[0]);
                }
                yield this.loop.newSucceededFuture();
            }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "stream";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Http2StreamChannel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Http2StreamChannel.class}, h);
    }

    private io.netty.channel.ChannelConfig config() {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "isAutoRead" -> this.loop.config().isAutoRead();
            case "setAutoRead" -> {
                this.loop.config().setAutoRead((Boolean) args[0]);
                yield proxy;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (io.netty.channel.ChannelConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {io.netty.channel.ChannelConfig.class}, h);
    }

    private ByteBuf bytes(int n) {
        var b = Unpooled.buffer(n).writeZero(n);
        this.offered.add(b);
        return b;
    }

    private void offer(Http2RequestBody body, int n) {
        var b = bytes(n);
        body.offer(b);
        b.release(); // frame's own reference
        this.loop.runPendingTasks();
    }

    @AfterEach
    void release() {
        for (var b : this.offered) {
            if (b.refCnt() > 0) {
                b.release(b.refCnt());
            }
        }
        this.offered.clear();
        this.loop.finishAndReleaseAll();
    }

    @Test
    void readDrainsAndEnds() throws Exception {
        var body = new Http2RequestBody(stream(), 5, TIMEOUT);
        offer(body, 3);
        offer(body, 2);
        body.end();
        var buf = new byte[8];
        assertEquals(3, body.read(buf, 0, 8));
        assertEquals(2, body.read(buf, 0, 8));
        assertEquals(-1, body.read(buf, 0, 8));
        assertEquals(5, body.length());
    }

    @Test
    void flowControlPausesAtHigh() {
        var body = new Http2RequestBody(stream(), -1, TIMEOUT);
        assertTrue(this.loop.config().isAutoRead());
        offer(body, Http2RequestBody.HIGH);
        this.loop.runPendingTasks();
        assertFalse(this.loop.config().isAutoRead(), "paused at HIGH");
    }

    @Test
    void abandonResetsWhenNotEnded() {
        var body = new Http2RequestBody(stream(), 100, TIMEOUT);
        offer(body, 10);
        body.abandon();
        this.loop.runPendingTasks();
        assertEquals(1, this.resets.size());
        assertTrue(this.resets.get(0) instanceof DefaultHttp2ResetFrame rst
                && rst.errorCode() == Http2Error.NO_ERROR.code());
        assertEquals(0, body.buffered());
    }

    @Test
    void mismatchAtEndThrows() {
        var body = new Http2RequestBody(stream(), 5, TIMEOUT);
        offer(body, 2);
        body.end();
        var buf = new byte[8];
        assertThrows(IOException.class, () -> {
            while (body.read(buf, 0, 8) >= 0) {
                // drain
            }
        });
    }

    @Test
    void unknownLengthEndsAtZeroWithoutData() {
        var body = new Http2RequestBody(stream(), -1, TIMEOUT);
        body.end();
        assertEquals(0, body.length());
    }
}
