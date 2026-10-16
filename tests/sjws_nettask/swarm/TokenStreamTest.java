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
package sjws_nettask.swarm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import sjws_nettask.swarm.TokenStream.Coalescer;
import sjws_nettask.swarm.TokenStream.Reorder;
import sjws_nettask.swarm.TokenStream.Reorder.Offer;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

class TokenStreamTest {

    final AtomicLong now = new AtomicLong(1_000);

    Coalescer co(int bytes, long ms) {
        return new Coalescer(7, 9, bytes, ms, this.now::get);
    }

    @Test
    void sizeCutAtChunkBytesSplitsLargeTokens() {
        var c = co(1024, 20);
        assertTrue(c.append(new byte[1000]).isEmpty());
        var out = c.append(new byte[3000]);
        assertEquals(3, out.size());
        assertEquals(StreamChunk.FIRST, out.get(0).flags());
        assertEquals(0, out.get(1).flags());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, out.get(i).chunkSeq());
            assertEquals(1024, out.get(i).size());
        }
        assertEquals(928, c.buffered());
        assertEquals(3, c.nextSeq());
        var last = c.finish(0);
        assertEquals(Frame.LAST, last.flags());
        assertEquals(928, last.size());
        assertThrows(IllegalStateException.class, () -> c.append(new byte[1]));
        assertThrows(IllegalStateException.class, c::poll);
        assertThrows(IllegalStateException.class, () -> c.finish(0));
    }

    @Test
    void timeCutAfterFlushMsFromTheFirstBufferedByte() {
        var c = co(4096, 20);
        assertNull(c.poll(), "empty buffer never flushes");
        c.append(new byte[] {1});
        this.now.addAndGet(19_999_999);
        c.append(new byte[] {2});
        assertNull(c.poll());
        this.now.addAndGet(1);
        var ch = c.poll();
        assertArrayEquals(new byte[] {1, 2}, ch.bytes());
        assertNull(c.poll());
        c.append(new byte[0]);
        assertNull(c.poll(), "empty append starts no timer");
    }

    @Test
    void timerRestartsForTheRemainderAfterASizeCut() {
        var c = co(1024, 20);
        c.append(new byte[1000]);
        this.now.addAndGet(15_000_000);
        assertEquals(1, c.append(new byte[100]).size());
        this.now.addAndGet(10_000_000);
        assertNull(c.poll(), "the 76 remaining bytes are only 10 ms old");
        this.now.addAndGet(10_000_000);
        assertEquals(76, c.poll().size());
    }

    @Test
    void finishQualifiersAndSingleChunkStream() {
        var c = co(1024, 0);
        var only = c.finish(Frame.CANCELLED);
        assertEquals(StreamChunk.FIRST | Frame.LAST | Frame.CANCELLED, only.flags());
        assertEquals(0, only.size());
        var e = co(1024, 0);
        e.append(new byte[] {1});
        assertEquals(Frame.LAST | Frame.ERROR | StreamChunk.FIRST, e.finish(Frame.ERROR).flags());
        assertThrows(IllegalArgumentException.class, () -> co(1024, 0).finish(Frame.LAST));
    }

    @Test
    void coalescerValidation() {
        assertThrows(IllegalArgumentException.class, () -> co(1023, 20));
        assertThrows(IllegalArgumentException.class, () -> co(65537, 20));
        assertThrows(IllegalArgumentException.class, () -> co(1024, -1));
        assertNotNull(co(65536, 0));
    }

    static StreamChunk ch(long seq, int flags) {
        return new StreamChunk(7, 9, seq, flags, new byte[] {(byte) seq});
    }

    @Test
    void reorderDeliversInOrderExactlyOnce() {
        var r = new Reorder(7, 9, 4);
        var out = new ArrayList<StreamChunk>();
        assertEquals(Offer.BUFFERED, r.offer(ch(2, 0), out));
        assertEquals(Offer.DUPLICATE, r.offer(ch(2, 0), out), "already buffered");
        assertEquals(Offer.BUFFERED, r.offer(ch(1, 0), out));
        assertEquals(2, r.held());
        assertTrue(out.isEmpty());
        assertEquals(Offer.DELIVERED, r.offer(ch(0, StreamChunk.FIRST), out));
        assertEquals(List.of(0L, 1L, 2L), out.stream().map(StreamChunk::chunkSeq).toList());
        assertEquals(0, r.held());
        assertEquals(3, r.nextSeq());
        assertEquals(Offer.DUPLICATE, r.offer(ch(1, 0), out), "already delivered");
        assertEquals(Offer.OVERFLOW, r.offer(ch(7, 0), out), "window is 4 ahead of 3");
        assertEquals(Offer.BUFFERED, r.offer(ch(6, 0), out));
        assertEquals(Offer.WRONG_STREAM, r.offer(new StreamChunk(7, 8, 3, 0, new byte[0]), out));
        assertEquals(Offer.WRONG_STREAM, r.offer(new StreamChunk(6, 9, 3, 0, new byte[0]), out));
    }

    @Test
    void lastBoundsTheStream() {
        var r = new Reorder(7, 9, 8);
        var out = new ArrayList<StreamChunk>();
        assertEquals(Offer.BUFFERED, r.offer(ch(2, Frame.LAST), out));
        assertFalse(r.complete());
        assertEquals(Offer.AFTER_LAST, r.offer(ch(3, 0), out));
        r.offer(ch(0, StreamChunk.FIRST), out);
        assertFalse(r.complete());
        assertEquals(Offer.DELIVERED, r.offer(ch(1, 0), out));
        assertTrue(r.complete());
        assertEquals(3, out.size());
        var single = new Reorder(1, 1, 1);
        assertEquals(Offer.DELIVERED, single.offer(new StreamChunk(1, 1, 0, Frame.LAST, new byte[0]), out));
        assertTrue(single.complete());
        assertThrows(IllegalArgumentException.class, () -> new Reorder(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Reorder(0, 0, 65537));
    }

    @Test
    void shuffledDuplicatedRedeliveryReassemblesTheExactBytes() {
        var rnd = new Random(42);
        var src = new byte[50_000];
        rnd.nextBytes(src);
        var c = co(1024, 20);
        var chunks = new ArrayList<StreamChunk>();
        for (int off = 0; off < src.length;) {
            int n = Math.min(1 + rnd.nextInt(700), src.length - off);
            chunks.addAll(c.append(java.util.Arrays.copyOfRange(src, off, off + n)));
            off += n;
        }
        chunks.add(c.finish(0));
        var wire = new ArrayList<StreamChunk>(chunks);
        wire.addAll(chunks.subList(0, 10));
        java.util.Collections.shuffle(wire, rnd);
        var r = new Reorder(7, 9, 64);
        var out = new ArrayList<StreamChunk>();
        var pending = new ArrayList<>(wire);
        while (!r.complete()) {
            var retry = new ArrayList<StreamChunk>();
            for (var x : pending) {
                if (StreamChunk.decode(x.encode()) instanceof StreamChunk y && r.offer(y, out) == Offer.OVERFLOW) {
                    retry.add(y);
                }
            }
            pending = retry;
        }
        var got = new ByteArrayOutputStream();
        out.forEach(x -> got.writeBytes(x.bytes()));
        assertArrayEquals(src, got.toByteArray());
        assertEquals(chunks.size(), out.size());
    }

    @Test
    void shardOfIsStableNonNegativeAndSpreads() {
        assertEquals(0, TokenStream.shardOf(123, 456, 1));
        assertEquals(TokenStream.shardOf(-5, 77, 4), TokenStream.shardOf(-5, 77, 4));
        int[] hits = new int[4];
        var rnd = new Random(1);
        for (int i = 0; i < 4000; i++) {
            int s = TokenStream.shardOf(rnd.nextLong(), rnd.nextLong(), 4);
            assertTrue(s >= 0 && s < 4);
            hits[s]++;
        }
        for (int h : hits) {
            assertTrue(h > 800, java.util.Arrays.toString(hits));
        }
        assertThrows(IllegalArgumentException.class, () -> TokenStream.shardOf(1, 1, 0));
    }
}
