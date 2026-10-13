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
package sjws_nettask.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The chain closes streamed bodies that a filter drops, replaces or loses to an exception. */
class FilterChainDiscardTest {

    private static final class Terminal implements Handler {
        TestSource last;

        @Override
        public Response handle(Request request) {
            this.last = new TestSource(false, "body".getBytes());
            return new Response(200, ResponseHeaders.of(), new Body.Stream(4, this.last));
        }
    }

    @Test
    void replacedResponseIsClosed() {
        var t = new Terminal();
        var chain = RequestFilters.create().add((q, next) -> {
            next.proceed(q);
            return StaticFileHandler.error(403, false);
        }).build(t);
        assertEquals(403, chain.handle(new Request("GET", "/")).status());
        assertEquals(1, t.last.closes);
    }

    @Test
    void aFilterThatThrowsAfterProceedClosesTheBody() {
        var t = new Terminal();
        var chain = RequestFilters.create().add((q, next) -> {
            next.proceed(q);
            throw new IllegalStateException("boom");
        }).add((q, next) -> next.proceed(q)).build(t);
        assertEquals(500, chain.handle(new Request("GET", "/")).status());
        assertEquals(1, t.last.closes);
        var nulls = RequestFilters.create().add((q, next) -> {
            next.proceed(q);
            return null;
        }).build(t);
        assertEquals(500, nulls.handle(new Request("GET", "/")).status());
        assertEquals(1, t.last.closes);
    }

    @Test
    void passedThroughOrRewrappedBodiesStayOpen() {
        var t = new Terminal();
        var chain = RequestFilters.create().add((q, next) -> {
            var r = next.proceed(q);
            return new Response(r.status(), ResponseHeaders.of().add("x-wrapped", "1"), r.body());
        }).add((q, next) -> {
            var r = next.proceed(q);
            r.headers().add("x-seen", "1");
            return r;
        }).build(t);
        var r = chain.handle(new Request("GET", "/"));
        assertEquals("1", r.headers().get("x-wrapped"));
        assertEquals(0, t.last.closes);
        // the next request on the same thread starts clean
        chain.handle(new Request("GET", "/"));
        assertEquals(0, t.last.closes);
    }

    @Test
    void nestedChainsHandTheStreamOutward() {
        var t = new Terminal();
        var inner = RequestFilters.create().add((q, next) -> next.proceed(q)).build(t);
        var outer = RequestFilters.create().add((q, next) -> {
            var r = next.proceed(q);
            return r.status() == 200 ? StaticFileHandler.error(404, false) : r;
        }).build(inner);
        assertEquals(404, outer.handle(new Request("GET", "/")).status());
        assertEquals(1, t.last.closes);
    }

    @Test
    void discardIgnoresOtherBodiesAndCloseFailures() {
        Body.discard(Body.Empty.INSTANCE);
        Body.discard(new Body.Stream(-1, new Body.Stream.Source() {
            @Override
            public java.nio.ByteBuffer next() {
                return null;
            }

            @Override
            public void close() {
                throw new IllegalStateException("x");
            }
        }));
    }
}
