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
package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Body;
import sjws_nettask.core.Request;
import sjws_nettask.core.buf.BufferPool;
import sjws_nettask.core.buf.NoBufferLeaks;
import sjws_nettask.core.buf.PooledBuffer;
import sjws_nettask.messaging.ledger.Durability;

/** Messaging on the shared buffer pool's {@code messaging} budget (partition-log.md §2.4) and the metric hooks. */
@ExtendWith(NoBufferLeaks.class)
class MessagingBufferPoolTest {

    @TempDir Path dir;

    final BufferPool pool = NoBufferLeaks.watch(BufferPool.create(
            new BufferPool.Settings(true, 4L << 20, Map.of(BufferPool.MESSAGING, 0.5), true, 1, 0)));

    MessagingHttp handler(LogTopics topics) {
        return new MessagingHttp(topics, this.pool.budget(BufferPool.MESSAGING));
    }

    @Test
    void produceAndFetchUsePooledBuffersAndReleaseThem() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = handler(topics);
            for (int i = 0; i < 50; i++) assertEquals(200, MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("v" + i, "w" + i), null).status());
            var r = h.handle(new Request("GET", "/messaging/v1/topics/t/records"));
            assertTrue(r.body() instanceof Body.Stream, "pooled fetch streams");
            var data = MessagingHttpTest.bytes(r); // drains and closes: the buffer goes back
            assertEquals(50, MessagingHttpTest.batches(data).size());
            var seg = Files.readAllBytes(dir.resolve("t").resolve(String.format("%020d.log", 0)));
            assertArrayEquals(seg, data); // still byte-identical to the segment file
        }
    }

    @Test
    void framesLargerThanThePooledBufferAndMultiChunkResponses() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = handler(topics);
            var big = "x".repeat(BufferPool.MAX_CLASS + 1000);           // one frame bigger than any pooled class
            var mid = "y".repeat(BufferPool.MAX_CLASS / 3);              // several fill one buffer, then the next chunk
            assertEquals(200, MessagingHttpTest.post(h, "b", MessagingHttpTest.recs(big), null).status()); // body > MAX_CLASS: acquireLarge
            for (int i = 0; i < 7; i++) MessagingHttpTest.post(h, "b", MessagingHttpTest.recs(mid), null);
            var data = MessagingHttpTest.bytes(h.handle(new Request("GET", "/messaging/v1/topics/b/records?maxBytes=16777216")));
            var bs = MessagingHttpTest.batches(data);
            assertEquals(8, bs.size());
            assertEquals(big.length(), bs.get(0).records().get(0).length);
            assertArrayEquals(Files.readAllBytes(dir.resolve("b").resolve(String.format("%020d.log", 0))), data);
        }
    }

    @Test
    void fetchIsShedWith503WhenTheBudgetIsExhausted() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = handler(topics);
            MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("a"), null);
            var budget = this.pool.budget(BufferPool.MESSAGING);
            var held = new ArrayList<PooledBuffer>();
            try {
                PooledBuffer b;
                while ((b = budget.tryAcquire(BufferPool.MAX_CLASS)) != null) held.add(b);
                assertNull(budget.tryAcquire(16));
                var r = h.handle(new Request("GET", "/messaging/v1/topics/t/records"));
                assertEquals(503, r.status());
                assertEquals(MessagingHttp.RETRY_AFTER_SECONDS, r.headers().get("retry-after"));
                // produce never sheds: acquire falls back to an unpooled buffer
                assertEquals(200, MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("b"), null).status());
            } finally {
                held.forEach(PooledBuffer::release);
            }
            var again = h.handle(new Request("GET", "/messaging/v1/topics/t/records")); // budget back: served again
            assertEquals(200, again.status());
            assertEquals(2, MessagingHttpTest.batches(MessagingHttpTest.bytes(again)).size());
        }
    }

    @Test
    void bodyLengthMismatchIsRejected() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = handler(topics);
            var data = MessagingHttpTest.recs("abc");
            var shortBody = new sjws_nettask.core.RequestBody() {
                int pos;
                @Override public long length() { return data.length + 5; }
                @Override public int read(byte[] into, int off, int len) {
                    if (pos >= data.length) return -1;
                    int k = Math.min(len, data.length - pos);
                    System.arraycopy(data, pos, into, off, k);
                    pos += k;
                    return k;
                }
            };
            assertEquals(400, h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(shortBody)).status());
            var longBody = new sjws_nettask.core.RequestBody() {
                @Override public long length() { return 4; }
                @Override public int read(byte[] into, int off, int len) { into[off] = 0; return 1; }
            };
            assertEquals(400, h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(longBody)).status());
            var empty = sjws_nettask.core.RequestBody.EMPTY;
            assertEquals(400, h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(empty)).status());
        }
    }

    @Test
    void hooksSeeEveryProduceAndFetch() throws Exception {
        var seen = new ArrayList<String>();
        var hooks = new MessagingHooks() {
            @Override public void produced(String topic, int status, long bytes, long nanos) { seen.add("p " + topic + " " + status + " " + bytes + " " + (nanos >= 0)); }
            @Override public void fetched(String topic, int status, long bytes, long nanos) { seen.add("f " + topic + " " + status + " " + bytes + " " + (nanos >= 0)); }
        };
        var m = new MessagingModule(dir);
        try {
            m.hooks(hooks);
            var h = sjws_nettask.core.HandlerMounts.install(List.of(m), r -> null);
            var body = MessagingHttpTest.recs("hi");
            h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(MessagingHttpTest.body(body)));
            var r = h.handle(new Request("GET", "/messaging/v1/topics/t/records"));
            long len = MessagingHttpTest.bytes(r).length;
            h.handle(new Request("GET", "/messaging/v1/topics/none/records"));
            assertEquals(List.of("p t 200 " + body.length + " true", "f t 200 " + len + " true", "f none 404 " + seenLen(seen) + " true"), seen);
            MessagingHooks.NONE.produced("x", 200, 0, 0);
            MessagingHooks.NONE.fetched("x", 200, 0, 0);
        } finally {
            m.close();
        }
    }

    private static String seenLen(List<String> seen) {
        var last = seen.get(seen.size() - 1).split(" ");
        return last[3];
    }
}
