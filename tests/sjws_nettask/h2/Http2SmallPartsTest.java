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

import static org.junit.jupiter.api.Assertions.*;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;
import sjws_nettask.HandlerRef;
import sjws_nettask.core.Handler;

/** Deterministic coverage of small pieces the socket tests only reach by timing. */
class Http2SmallPartsTest {

    @Test
    void quietLeavesHttp2ErrorsToTheCodec() {
        var ch = new EmbeddedChannel(Http2Server.Quiet.INSTANCE);
        ch.pipeline().fireExceptionCaught(Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "x"));
        assertTrue(ch.isOpen());
        ch.pipeline().fireExceptionCaught(new java.io.IOException("reset"));
        assertFalse(ch.isOpen());
    }

    @Test
    void sharedPrefersThePublishedHandlerAndReusesTheBudget() {
        Handler startup = r -> null;
        Handler armed = r -> null;
        var ref = new HandlerRef();
        var shared = new Http2Server.Shared(startup, null, new Semaphore(1), new Semaphore(1), Duration.ofSeconds(1),
                new LongAdder(), Http2Server.Limits.DEFAULT, ref);
        assertSame(startup, shared.live());
        ref.set(armed);
        assertSame(armed, shared.live());
        var ch = new EmbeddedChannel();
        assertSame(shared.budget(ch), shared.budget(ch));
        ch.close();
    }
}
