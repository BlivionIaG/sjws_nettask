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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Time limits that byte-level idle detection can't enforce. */
final class Deadlines {

    private Deadlines() {
    }

    /**
     * HTTP/2: closes a connection that has had no open stream for {@code idle}. Unlike an idle
     * timer on raw bytes, PING, SETTINGS, WINDOW_UPDATE or an unfinished header block don't keep it
     * alive, so a peer can't squat on a connection slot without making requests.
     */
    static final class StreamIdle extends ChannelInboundHandlerAdapter {
        private final Http2FrameCodec codec;
        private final long idleNanos;
        private long lastActive;
        private ScheduledFuture<?> check;

        StreamIdle(Http2FrameCodec codec, Duration idle) {
            this.codec = codec;
            this.idleNanos = idle.toNanos();
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.lastActive = System.nanoTime();
            long period = Math.max(10_000_000L, this.idleNanos / 4);
            this.check = ctx.executor().scheduleAtFixedRate(() -> tick(ctx), period, period, TimeUnit.NANOSECONDS);
        }

        void tick(ChannelHandlerContext ctx) {
            long now = System.nanoTime();
            if (this.codec.connection().numActiveStreams() > 0) {
                this.lastActive = now;
            } else if (now - this.lastActive >= this.idleNanos) {
                cancel();
                ctx.close(); // the codec sends GOAWAY(NO_ERROR) first
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                this.lastActive = System.nanoTime();
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            cancel();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            cancel();
            ctx.fireChannelInactive();
        }

        private void cancel() {
            if (this.check != null) {
                this.check.cancel(false);
            }
        }
    }

    /**
     * HTTP/1.x fallback (slowloris): once the first byte of a request arrives, the whole request
     * must follow within {@code limit}, as on the main HTTP/1.1 listener. {@link #front()} sits
     * before the HTTP codec and arms the timer on bytes; {@link #back()} sits after the aggregator
     * and disarms it on each complete request, or removes both on an upgrade to HTTP/2.
     */
    static final class HeadDeadline {
        private final long limitNanos;
        private ScheduledFuture<?> timer;
        private final ChannelInboundHandlerAdapter front = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (HeadDeadline.this.timer == null && msg instanceof ByteBuf b && b.isReadable()) {
                    HeadDeadline.this.timer = ctx.executor().schedule(() -> {
                        ctx.close();
                    }, HeadDeadline.this.limitNanos, TimeUnit.NANOSECONDS);
                }
                ctx.fireChannelRead(msg);
            }

            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) {
                disarm();
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                disarm();
                ctx.fireChannelInactive();
            }
        };
        private final ChannelInboundHandlerAdapter back = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof FullHttpRequest) {
                    disarm();
                }
                ctx.fireChannelRead(msg);
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                if (evt instanceof HttpServerUpgradeHandler.UpgradeEvent
                        || evt instanceof CleartextHttp2ServerUpgradeHandler.PriorKnowledgeUpgradeEvent) {
                    disarm();
                    var p = ctx.pipeline();
                    if (p.context(HeadDeadline.this.front) != null) {
                        p.remove(HeadDeadline.this.front);
                    }
                    p.remove(this);
                }
                ctx.fireUserEventTriggered(evt);
            }
        };

        HeadDeadline(Duration limit) {
            this.limitNanos = limit.toNanos();
        }

        ChannelInboundHandlerAdapter front() {
            return this.front;
        }

        ChannelInboundHandlerAdapter back() {
            return this.back;
        }

        boolean armed() {
            return this.timer != null;
        }

        private void disarm() {
            if (this.timer != null) {
                this.timer.cancel(false);
                this.timer = null;
            }
        }
    }
}
