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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoBufferLeaks.class)
class BufferPoolTest {

    static BufferPool pool(long max, double messaging, boolean debug, int sample, int magazine) {
        return NoBufferLeaks.watch(BufferPool.create(new BufferPool.Settings(true, max,
                messaging > 0 ? Map.of(BufferPool.MESSAGING, messaging) : Map.of(), debug, sample, magazine)));
    }

    @Test
    void disabledIsPlainHeap() {
        var off = BufferPool.disabled();
        assertFalse(off.enabled());
        assertSame(off, BufferPool.create(BufferPool.Settings.OFF));
        for (var tenant : List.of(BufferPool.HTTP, BufferPool.MESSAGING)) {
            var b = off.budget(tenant).acquire(100);
            assertFalse(b.pooled());
            assertFalse(b.buffer().isDirect());
            assertEquals(100, b.capacity());
            b.retain().release();
            b.release();
            assertThrows(IllegalStateException.class, b::release);
            assertNotNull(off.budget(tenant).tryAcquire(65536));
            assertFalse(off.budget(tenant).acquireLarge(1 << 20).pooled());
        }
        assertEquals(0, off.stats().reservedBytes());
        assertEquals(0, off.trim(0));
        assertThrows(IllegalArgumentException.class, () -> off.budget("nope"));
    }

    @Test
    void sharedPoolIsOffByDefault() {
        if (System.getProperty("sjws.buf.pool") == null) {
            assertSame(BufferPool.disabled(), BufferPool.shared());
        }
    }

    @Test
    void sizeClassesArePowersOfTwo() {
        assertEquals(0, BufferPool.sizeClass(1));
        assertEquals(0, BufferPool.sizeClass(256));
        assertEquals(1, BufferPool.sizeClass(257));
        assertEquals(3, BufferPool.sizeClass(2048));
        assertEquals(8, BufferPool.sizeClass(65536));
        var p = pool(8 << 20, 0.5, false, 0, 0);
        var http = p.budget(BufferPool.HTTP);
        for (int n : new int[] {1, 256, 257, 2048, 2049, 65536}) {
            try (var b = http.acquire(n)) {
                assertTrue(b.pooled());
                assertTrue(b.buffer().isDirect());
                assertEquals(Integer.highestOneBit(Math.max(256, n) * 2 - 1), b.capacity());
                assertEquals(0, b.buffer().position());
                assertEquals(b.capacity(), b.buffer().limit());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> http.acquire(65537));
        assertThrows(IllegalArgumentException.class, () -> http.acquire(0));
    }

    @Test
    void releasedChunkIsReusedAndCleared() {
        var p = pool(4 << 20, 0.5, false, 0, 0);
        var http = p.budget(BufferPool.HTTP);
        ByteBuffer first;
        try (var b = http.acquire(1000)) {
            first = b.buffer();
            first.position(10).limit(20);
        }
        try (var b = http.acquire(1000)) {
            assertSame(first, b.buffer());
            assertEquals(0, b.buffer().position());
            assertEquals(1024, b.buffer().limit());
        }
        assertEquals(1, p.stats().slabs());
        assertEquals(BufferPool.SLAB_BYTES, p.stats().reservedBytes());
    }

    @Test
    void refcounting() {
        var p = pool(4 << 20, 0.5, true, 0, 0);
        var b = p.budget(BufferPool.HTTP).acquire(512);
        b.retain();
        b.release();
        assertNotNull(b.buffer());
        assertEquals(512, p.budget(BufferPool.HTTP).usedBytes());
        b.release();
        assertEquals(0, p.budget(BufferPool.HTTP).usedBytes());
        assertThrows(IllegalStateException.class, b::buffer);
        assertThrows(IllegalStateException.class, b::retain);
        assertThrows(IllegalStateException.class, b::release);
    }

    @Test
    void budgetsSplitTheCap() {
        var p = pool(4 << 20, 0.5, false, 0, 0); // messaging 2 MiB, http 2 MiB
        var msg = p.budget(BufferPool.MESSAGING);
        var http = p.budget(BufferPool.HTTP);
        assertEquals(2 << 20, msg.limitBytes());
        assertEquals(2 << 20, http.limitBytes());
        var held = new ArrayList<PooledBuffer>();
        for (int i = 0; i < 32; i++) {
            var b = msg.tryAcquire(65536);
            assertNotNull(b, "within budget: " + i);
            held.add(b);
        }
        assertNull(msg.tryAcquire(65536), "messaging budget exhausted");
        assertNull(msg.tryAcquire(256));
        var over = msg.acquire(65536);
        assertFalse(over.pooled(), "acquire falls back to unpooled");
        assertFalse(over.buffer().isDirect());
        over.release();
        try (var h = http.tryAcquire(65536)) {
            assertNotNull(h, "http is not starved by messaging");
            assertTrue(h.pooled());
        }
        var stats = p.stats().tenants().stream().filter(t -> t.name().equals(BufferPool.MESSAGING)).findFirst().orElseThrow();
        assertEquals(2, stats.denied());
        assertEquals(1, stats.fallbacks());
        assertEquals(32, stats.acquired());
        assertEquals(2 << 20, stats.usedBytes());
        held.forEach(PooledBuffer::release);
        var again = msg.tryAcquire(65536);
        assertNotNull(again);
        again.release();
    }

    @Test
    void globalCapLimitsSlabs() {
        var p = pool(1 << 20, 0, false, 0, 0); // one slab in total, all http
        var http = p.budget(BufferPool.HTTP);
        var a = http.acquire(256);
        assertTrue(a.pooled());
        assertNull(http.tryAcquire(512), "a second class needs a second slab: over the global cap");
        var b = http.acquire(512);
        assertFalse(b.pooled());
        a.release();
        b.release();
        assertEquals(1 << 20, p.stats().reservedBytes());
    }

    @Test
    void settingsValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new BufferPool.Settings(true, 64 << 20, Map.of("a", 0.6, "b", 0.6), false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BufferPool.Settings(true, 64 << 20, Map.of(BufferPool.HTTP, 0.5), false, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BufferPool.Settings(true, 1000, Map.of(), false, 0, 0));
        assertEquals(64L << 20, BufferPool.Settings.bytes("64m"));
        assertEquals(1024, BufferPool.Settings.bytes("1k"));
        assertEquals(3L << 30, BufferPool.Settings.bytes("3G"));
        var d = BufferPool.Settings.defaults();
        assertEquals(0.5, d.budgets().get(BufferPool.MESSAGING));
        var p = BufferPool.create(d);
        assertEquals(32L << 20, p.budget(BufferPool.HTTP).limitBytes());
        assertEquals(32L << 20, p.budget(BufferPool.MESSAGING).limitBytes());
    }

    @Test
    void cleanerReturnsALeakedBuffer() throws Exception {
        var p = BufferPool.create(new BufferPool.Settings(true, 4 << 20, Map.of(BufferPool.MESSAGING, 0.5), false, 1, 0));
        leakOne(p);
        for (int i = 0; i < 100 && p.stats().leaked() == 0; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(1, p.stats().leaked());
        assertEquals(0, p.budget(BufferPool.MESSAGING).usedBytes(), "the charge is refunded");
        assertEquals(0, p.trackedOutstanding());
        try (var again = p.budget(BufferPool.MESSAGING).acquire(4096)) {
            assertTrue(again.pooled());
        }
    }

    private static void leakOne(BufferPool p) {
        p.budget(BufferPool.MESSAGING).acquire(4096).buffer().putInt(0, 42); // dropped without release
    }

    @Test
    void debugModeTracksAndPoisons() throws Exception {
        var p = pool(4 << 20, 0.5, true, 0, 0);
        var b = p.budget(BufferPool.HTTP).acquire(256);
        var raw = b.buffer();
        raw.put(0, (byte) 1);
        assertEquals(1, p.outstanding().size());
        assertTrue(p.outstanding().get(0).getStackTrace()[0].toString().contains("BufferPool"));
        var extension = new AtomicReference<Throwable>();
        try {
            NoBufferLeaks.assertNoLeaks(p);
        } catch (AssertionError e) {
            extension.set(e);
        }
        assertNotNull(extension.get(), "a held buffer fails the leak check");
        assertTrue(extension.get().getMessage().contains("debugModeTracksAndPoisons"), extension.get().getMessage());
        b.release();
        assertEquals((byte) 0xDE, raw.get(0), "released memory is poisoned");
        assertEquals(0, p.outstanding().size());
    }

    @Test
    void largeBuffersAreTransientAndCharged() {
        var p = pool(16 << 20, 0.5, false, 0, 0);
        var msg = p.budget(BufferPool.MESSAGING);
        ByteBuffer first;
        try (var b = msg.acquireLarge(200_000)) {
            assertTrue(b.pooled());
            assertEquals(256 * 1024, b.capacity());
            assertEquals(256 * 1024, msg.usedBytes());
            first = b.buffer();
        }
        assertEquals(0, msg.usedBytes());
        try (var b = msg.acquireLarge(150_000)) {
            assertSame(first, b.buffer(), "cached for the next transient use");
        }
        try (var small = msg.acquireLarge(100)) {
            assertEquals(256, small.capacity());
        }
        try (var huge = msg.acquireLarge(BufferPool.MAX_LARGE + 1)) {
            assertFalse(huge.pooled());
        }
        // 8 MiB messaging budget: three 4 MiB large buffers don't fit
        var a = msg.acquireLarge(4 << 20);
        var b2 = msg.acquireLarge(4 << 20);
        var c = msg.acquireLarge(4 << 20);
        assertTrue(a.pooled());
        assertTrue(b2.pooled());
        assertFalse(c.pooled());
        a.release();
        b2.release();
        c.release();
        p.trim(0);
        assertEquals(0, p.stats().reservedBytes() - p.stats().slabs() * (long) BufferPool.SLAB_BYTES);
    }

    @Test
    void trimFreesIdleSlabs() {
        var p = pool(8 << 20, 0.5, false, 0, 0);
        var held = new ArrayList<PooledBuffer>();
        for (int i = 0; i < 40; i++) {
            held.add(p.budget(BufferPool.HTTP).acquire(65536)); // 16 per slab: 3 slabs
        }
        assertEquals(3, p.stats().slabs());
        assertEquals(0, p.trim(0), "nothing is free");
        held.forEach(PooledBuffer::release);
        assertEquals(0, p.trim(60_000_000_000L), "not idle long enough");
        assertEquals(3, p.trim(0));
        assertEquals(0, p.stats().slabs());
        assertEquals(0, p.stats().reservedBytes());
        assertEquals(3, p.stats().trimmedSlabs());
        try (var again = p.budget(BufferPool.HTTP).acquire(65536)) {
            assertTrue(again.pooled());
        }
    }

    @Test
    void virtualThreadsMigrateWhileHoldingBuffers() throws Exception {
        var p = pool(32 << 20, 0.5, true, 0, 8);
        var errors = new AtomicReference<Throwable>();
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 64; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    var rnd = ThreadLocalRandom.current();
                    for (int i = 0; i < 2000; i++) {
                        var budget = p.budget(rnd.nextBoolean() ? BufferPool.HTTP : BufferPool.MESSAGING);
                        var b = rnd.nextInt(4) == 0 ? budget.tryAcquire(1 + rnd.nextInt(65536)) : budget.acquire(1 + rnd.nextInt(65536));
                        if (b == null) {
                            continue;
                        }
                        var buf = b.buffer();
                        long mark = Thread.currentThread().threadId() * 1_000_003L + i;
                        buf.putLong(0, mark);
                        if (i % 7 == 0) {
                            LockSupport.parkNanos(10_000); // may resume on another carrier
                        }
                        if (buf.getLong(0) != mark) {
                            throw new AssertionError("buffer shared with another owner");
                        }
                        b.release();
                    }
                } catch (Throwable e) {
                    errors.compareAndSet(null, e);
                }
            }));
        }
        for (var t : threads) {
            t.join();
        }
        if (errors.get() != null) {
            throw new AssertionError(errors.get());
        }
    }

    @Test
    void platformThreadMagazinesAreReturnedWhenTheThreadEnds() throws Exception {
        var p = pool(8 << 20, 0.5, false, 0, 8);
        var errors = new AtomicReference<Throwable>();
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    for (int i = 0; i < 10_000; i++) {
                        var b = p.budget(BufferPool.MESSAGING).acquire(4096);
                        b.buffer().putInt(0, i);
                        if (b.buffer().getInt(0) != i) {
                            throw new AssertionError("shared");
                        }
                        b.release();
                    }
                } catch (Throwable e) {
                    errors.compareAndSet(null, e);
                }
            }));
        }
        for (var t : threads) {
            t.join();
        }
        assertNull(errors.get());
        threads.clear();
        // the dead threads' magazines go back to the shared stripes via the Cleaner; then the slab is trimmable
        for (int i = 0; i < 100 && p.stats().slabs() > 0; i++) {
            System.gc();
            Thread.sleep(20);
            p.trim(0);
        }
        assertEquals(0, p.stats().slabs());
    }

    @Test
    void handoffToAnotherThreadReleasesThere() throws Exception {
        var p = pool(4 << 20, 0.5, true, 0, 8);
        var b = p.budget(BufferPool.MESSAGING).acquire(1024);
        var t = Thread.ofPlatform().start(b::release);
        t.join();
        assertEquals(0, p.budget(BufferPool.MESSAGING).usedBytes());
    }

    @Test
    void creditParkedByOtherThreadsIsReclaimedSoTheLimitIsExact() throws Exception {
        var p = BufferPool.create(new BufferPool.Settings(true, 4 << 20, Map.of(BufferPool.MESSAGING, 0.5), false, 0, 0));
        var http = p.budget(BufferPool.HTTP);
        int n = (int) (http.limitBytes() / 65536);
        var workers = new ArrayList<Thread>();
        for (int t = 0; t < 8; t++) { // each leaves credit in its own stripe
            workers.add(Thread.ofPlatform().start(() -> http.acquire(65536).release()));
        }
        for (var w : workers) {
            w.join();
        }
        assertEquals(0, http.usedBytes(), "parked credit is not reported as used");
        var held = new ArrayList<PooledBuffer>();
        for (int i = 0; i < n; i++) {
            var b = http.tryAcquire(65536);
            assertNotNull(b, "buffer " + i + " of " + n);
            held.add(b);
        }
        assertNull(http.tryAcquire(65536), "limit reached");
        assertEquals(http.limitBytes(), http.usedBytes());
        held.forEach(PooledBuffer::release);
        assertEquals(0, http.usedBytes());
    }
}
