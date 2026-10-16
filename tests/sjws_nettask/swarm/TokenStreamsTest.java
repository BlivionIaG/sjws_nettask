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
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.messaging.delivery.LeaseResult;
import sjws_nettask.messaging.delivery.RetryPolicy;
import sjws_nettask.swarm.wire.Frame;
import sjws_nettask.swarm.wire.StreamChunk;

class TokenStreamsTest {

    @TempDir
    Path dir;

    final AtomicLong now = new AtomicLong(1_000_000);
    Swarm swarm;
    TokenStreams streams;

    @BeforeEach
    void open() {
        this.swarm = Swarm.open(this.dir, "n1", 7, RetryPolicy.DEFAULT, this.now::get, 600_000);
    }

    @AfterEach
    void close() {
        this.swarm.close();
    }

    byte[] submit(byte[] id) {
        var env = new TaskEnvelope(id, id, this.swarm.replies().topic(), TaskEnvelope.NO_DEADLINE, "", "", "", "text/plain",
                new byte[] {1});
        this.swarm.tasks().submit("p", 1, ByteBuffer.wrap(id).getLong(8), env);
        return id;
    }

    static byte[] id(long n) {
        return ByteBuffer.allocate(16).putLong(42).putLong(n).array();
    }

    Task claim(long visibilityMs) {
        var got = this.swarm.tasks().claim("p", "w", 1, visibilityMs);
        assertEquals(1, got.size());
        return got.get(0);
    }

    @Test
    void streamingOffRefusesTheProducePath() {
        submit(id(1));
        var t = claim(30_000);
        var c = new StreamChunk(42, 1, 0, StreamChunk.FIRST, new byte[] {1});
        assertThrows(IllegalStateException.class, () -> this.swarm.tasks().streamChunk(t, 9, 1, c));
        assertThrows(IllegalStateException.class, () -> this.swarm.tasks().streamLast(t, 9, 1, c, "x", new byte[0]));
        assertNull(this.swarm.tasks().streams());
    }

    @Test
    void chunksReachTheLocalSinkInOrderThenLastAcksAfterTheReplyIsDurable() throws Exception {
        this.streams = this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        var cid = submit(id(2));
        var call = this.swarm.replies().call("p", "text/plain", new byte[0], this.now.get() + 60_000, "");
        var t = claim(30_000);
        // the call submitted its own task; ours is the one we claimed first
        try (var sink = this.streams.open(cid)) {
            var co = new TokenStream.Coalescer(42, 2, 1024, 20, System::nanoTime);
            var src = new byte[5000];
            new java.util.Random(3).nextBytes(src);
            long seq = 100;
            for (var c : co.append(src)) {
                assertEquals(LeaseResult.OK, this.swarm.tasks().streamChunk(t, 9, seq++, c));
            }
            var last = co.finish(0);
            var acked = this.swarm.tasks().streamLast(t, 9, seq, last, "text/plain", new byte[] {7});
            assertEquals(LeaseResult.OK, acked.get(5, TimeUnit.SECONDS));
            assertTrue(sink.complete());
            var got = new ByteArrayOutputStream();
            for (var c : sink.drain()) {
                got.writeBytes(c.bytes());
            }
            assertArrayEquals(src, got.toByteArray());
            assertEquals(0, this.streams.fenceEntries(), "settled");
            assertEquals(LeaseResult.NOT_IN_FLIGHT, this.swarm.tasks().ack(t), "acked by streamLast");
        }
        assertEquals(0, this.streams.openSinks());
        call.cancel(true);
        // the chunks are on the stream shard, with no DLQ partition
        var shard = this.swarm.topics().partitionWithoutDlq(this.streams.topicFor(42, 2));
        assertNull(shard.deadLetterQueue());
        shard.subscribe("g", true);
        var chunks = shard.poll("g", "c", 100, 1_000);
        assertEquals(5, chunks.size(), "4 full 1 KiB chunks + LAST(904 B)");
        var lastOnLedger = StreamChunk.decode(chunks.get(4).payload());
        assertTrue(lastOnLedger.last());
        assertEquals("stream.n1#0", this.streams.shardTopic(0));
    }

    @Test
    void aStaleAttemptCannotProduceAfterANewerOneHas() throws Exception {
        this.streams = this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        var cid = submit(id(3));
        var old = claim(1_000);
        var c0 = new StreamChunk(42, 3, 0, StreamChunk.FIRST, new byte[] {1});
        assertEquals(LeaseResult.OK, this.swarm.tasks().streamChunk(old, 9, 1, c0));
        this.now.addAndGet(5_000); // lease expires; the task is redelivered
        var fresh = claim(30_000);
        assertEquals(old.offset(), fresh.offset());
        assertTrue(fresh.attempt() > old.attempt());
        assertEquals(LeaseResult.OK, this.swarm.tasks().streamChunk(fresh, 9, 2, c0));
        var c1 = new StreamChunk(42, 3, 1, 0, new byte[] {2});
        assertEquals(LeaseResult.STALE_ATTEMPT, this.swarm.tasks().streamChunk(old, 9, 3, c1));
        var last = new StreamChunk(42, 3, 1, Frame.LAST, new byte[0]);
        assertEquals(LeaseResult.STALE_ATTEMPT,
                this.swarm.tasks().streamLast(old, 9, 4, last, "x", new byte[0]).get(5, TimeUnit.SECONDS));
        assertEquals(LeaseResult.OK, this.swarm.tasks().streamLast(fresh, 9, 5, last, "x", new byte[0]).get(5, TimeUnit.SECONDS));
        assertNotNull(cid);
    }

    @Test
    void argumentChecks() {
        this.streams = this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        submit(id(4));
        var t = claim(30_000);
        var last = new StreamChunk(42, 4, 0, Frame.LAST, new byte[0]);
        var mid = new StreamChunk(42, 4, 0, 0, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> this.swarm.tasks().streamChunk(t, 9, 1, last));
        assertThrows(IllegalArgumentException.class, () -> this.swarm.tasks().streamLast(t, 9, 1, mid, "x", new byte[0]));
        var wrong = new StreamChunk(1, 1, 0, 0, new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> this.swarm.tasks().streamChunk(t, 9, 1, wrong));
        assertThrows(IllegalArgumentException.class, () -> this.streams.open(new byte[15]));
        try (var s = this.streams.open(id(4))) {
            assertThrows(IllegalStateException.class, () -> this.streams.open(id(4)));
            assertEquals(0, s.conflicts());
        }
        var noReply = new Task("p", t.offset(), t.attempt(), t.leaseDeadlineMs(),
                new TaskEnvelope(id(4), id(4), "", TaskEnvelope.NO_DEADLINE, "", "", "", "x", new byte[0]), t.shard());
        assertThrows(IllegalArgumentException.class, () -> this.swarm.tasks().streamLast(noReply, 9, 1, last, "x", new byte[0]));
    }

    @Test
    void fullSinkDropsAndCountsConflictsAreCounted() throws Exception {
        this.streams = this.swarm.enableStreams(new TokenStreams.Config(2, 4, 2));
        var cid = submit(id(5));
        var t = claim(30_000);
        try (var sink = this.streams.open(cid)) {
            for (int i = 0; i < 4; i++) {
                this.swarm.tasks().streamChunk(t, 9, 10 + i, new StreamChunk(42, 5, i, 0, new byte[] {(byte) i}));
            }
            assertEquals(2, this.streams.sinkDrops());
            assertEquals(2, sink.drain().size());
            this.swarm.tasks().streamChunk(t, 9, 20, new StreamChunk(42, 5, 9, 0, new byte[0])); // beyond window: dropped
            assertEquals(3, this.streams.sinkDrops());
            this.swarm.tasks().streamLast(t, 9, 21, new StreamChunk(42, 5, 1, Frame.LAST, new byte[0]), "x", new byte[0])
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, sink.conflicts(), "LAST at a delivered seq");
            assertNull(sink.poll(1, TimeUnit.MILLISECONDS));
        }
        int s = TokenStream.shardOf(42, 5, 2);
        assertEquals("stream.n1#" + s, this.streams.topicFor(42, 5));
    }

    @Test
    void fenceEntriesArePurgedWhenIdle() {
        this.streams = this.swarm.enableStreams(TokenStreams.Config.DEFAULT);
        submit(id(6));
        var t = claim(30_000);
        assertEquals(LeaseResult.OK, this.streams.admit(t));
        assertEquals(1, this.streams.fenceEntries());
        this.now.addAndGet(TokenStreams.FENCE_TTL_MS + 1);
        for (int i = 0; i < TokenStreams.FENCE_PURGE_AT; i++) {
            this.streams.admit(new Task("q", i, 1, 0, t.envelope(), 0));
        }
        this.streams.admit(new Task("q", -1, 1, 0, t.envelope(), 0)); // triggers the purge of idle entries
        assertEquals(TokenStreams.FENCE_PURGE_AT + 1, this.streams.fenceEntries(), "the idle entry of t was purged");
        this.streams.settle(t);
    }

    @Test
    void configFromSettings() {
        assertEquals(TokenStreams.Config.DEFAULT, TokenStreams.Config.from(k -> null));
        assertEquals(1, TokenStreams.Config.DEFAULT.shards());
        var c = TokenStreams.Config.from(Map.of("sjws.swarm.stream.shards", "4", "sjws.swarm.stream.window", " 8 ",
                "sjws.swarm.stream.sinkChunks", "")::get);
        assertEquals(new TokenStreams.Config(4, 8, 256), c);
        assertThrows(IllegalArgumentException.class, () -> TokenStreams.Config.from(Map.of("sjws.swarm.stream.shards", "x")::get));
        assertThrows(IllegalArgumentException.class, () -> new TokenStreams.Config(0, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new TokenStreams.Config(65, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new TokenStreams.Config(1, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new TokenStreams.Config(1, 65_537, 8));
        assertThrows(IllegalArgumentException.class, () -> new TokenStreams.Config(1, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenStreams.Config(1, 8, 65_537));
    }

    @Test
    void moduleEnablesStreamsOnlyWithTheFlag() {
        var off = new SwarmModule(this.swarm, SwarmGate.DENY_ALL, false, false);
        assertNull(off.swarm().tasks().streams());
        var on = new SwarmModule(this.swarm, SwarmGate.DENY_ALL, false, true);
        assertNotNull(on.swarm().tasks().streams());
        assertEquals(1, on.swarm().tasks().streams().config().shards());
    }
}
