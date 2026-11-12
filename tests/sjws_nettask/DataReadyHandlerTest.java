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
package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;

class DataReadyHandlerTest {

    @Test
    void recognisesReadyzAndLeavesOtherPathsToTheInnerHandler() {
        assertTrue(DataReadyHandler.isReadyz("/readyz"));
        assertTrue(DataReadyHandler.isReadyz("/readyz?x=1"));
        assertTrue(DataReadyHandler.isReadyz("/readyz#frag"));
        assertFalse(DataReadyHandler.isReadyz("/readyz-extra"));
        assertFalse(DataReadyHandler.isReadyz("/ready"));
        assertFalse(DataReadyHandler.isReadyz(null));
        assertFalse(DataReadyHandler.isReadyz(""));

        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var inner = (sjws_nettask.core.Handler) request -> {
            calls.incrementAndGet();
            return new Response(200, sjws_nettask.core.ResponseHeaders.of(), sjws_nettask.core.Body.Bytes.of(new byte[0]),
                    request.headOnly());
        };
        var handler = new DataReadyHandler(() -> inner, () -> false);
        assertEquals(503, handler.handle(new Request("GET", "/readyz")).status());
        assertEquals(503, handler.handle(new Request("HEAD", "/readyz?x=1")).status());
        assertTrue(handler.handle(new Request("HEAD", "/readyz")).headOnly());
        assertEquals(0, calls.get());
        handler = new DataReadyHandler(() -> inner, () -> true);
        assertEquals(200, handler.handle(new Request("GET", "/readyz")).status());
        assertEquals(200, handler.handle(new Request("HEAD", "/readyz")).status());
        assertTrue(handler.handle(new Request("HEAD", "/readyz")).headOnly());
        assertEquals(200, handler.handle(new Request("GET", "/index.html")).status());
        assertEquals(1, calls.get());
    }
}
