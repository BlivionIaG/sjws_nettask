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
package sjws_nettask.core.buf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit extension: after each test, every watched pool (and {@link BufferPool#shared()}) must hold no buffers
 * (all budgets at 0 used bytes, no tracked buffer outstanding) and must not have counted a new leak.
 * <pre>{@code
 * @ExtendWith(NoBufferLeaks.class)
 * class MyTest {
 *     BufferPool pool = NoBufferLeaks.watch(BufferPool.create(debugSettings));
 * }
 * }</pre>
 * Run with {@code -Dsjws.buf.pool=on -Dsjws.buf.debug=true} to check code that uses the shared pool; the
 * failure message lists where each outstanding buffer was acquired.
 */
public final class NoBufferLeaks implements AfterEachCallback {

    private static final List<BufferPool> WATCHED = new CopyOnWriteArrayList<>();

    /** Checks {@code pool} after every test of a class that uses this extension; returns it. */
    public static BufferPool watch(BufferPool pool) {
        WATCHED.add(pool);
        return pool;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        try {
            assertNoLeaks(BufferPool.shared());
            for (var p : WATCHED) {
                assertNoLeaks(p);
            }
        } finally {
            WATCHED.clear();
        }
    }

    /** Fails when {@code pool} still holds a buffer or counted a leak (after giving the Cleaner a chance). */
    public static void assertNoLeaks(BufferPool pool) throws InterruptedException {
        if (!pool.enabled()) {
            return;
        }
        long leaked = pool.stats().leaked();
        for (int i = 0; i < 20 && (used(pool) != 0 || pool.trackedOutstanding() != 0); i++) {
            System.gc();
            Thread.sleep(25);
        }
        var where = new StringBuilder();
        for (var t : pool.outstanding()) {
            var w = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(w));
            where.append('\n').append(w);
        }
        assertEquals(0, pool.trackedOutstanding(), "buffers not released:" + where);
        assertEquals(0, used(pool), "bytes still charged to budgets: " + pool.stats().tenants());
        assertEquals(leaked, pool.stats().leaked(), "buffers leaked (released by the Cleaner, not the owner)");
    }

    private static long used(BufferPool pool) {
        long u = 0;
        for (var t : pool.stats().tenants()) {
            u += t.usedBytes();
        }
        return u;
    }
}
