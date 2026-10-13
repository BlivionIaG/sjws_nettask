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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Body;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestBody;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.RecordBatch;

class MessagingHttpTest {

    @TempDir Path dir;

    static byte[] recs(String... rs) {
        int n = 0;
        for (var r : rs) n += 4 + r.getBytes(StandardCharsets.UTF_8).length;
        var b = ByteBuffer.allocate(n);
        for (var r : rs) { var x = r.getBytes(StandardCharsets.UTF_8); b.putInt(x.length).put(x); }
        return b.array();
    }

    static RequestBody body(byte[] data) {
        return new RequestBody() {
            int pos;
            @Override public long length() { return data.length; }
            @Override public int read(byte[] into, int off, int len) {
                if (pos >= data.length) return -1;
                int k = Math.min(len, data.length - pos);
                System.arraycopy(data, pos, into, off, k);
                pos += k;
                return k;
            }
        };
    }

    static Response post(MessagingHttp h, String topic, byte[] data, String durability) {
        var headers = durability == null ? RequestHeaders.of(Map.of()) : RequestHeaders.of(Map.of("durability", List.of(durability)));
        return h.handle(new Request("POST", "/messaging/v1/topics/" + topic + "/records", headers).withBody(body(data)));
    }

    static byte[] bytes(Response r) {
        var d = ((Body.Bytes) r.body()).data().duplicate();
        var out = new byte[d.remaining()];
        d.get(out);
        return out;
    }

    static List<RecordBatch> batches(byte[] frames) throws Exception {
        var in = ByteBuffer.wrap(frames);
        var out = new ArrayList<RecordBatch>();
        while (in.hasRemaining()) {
            int len = in.getInt(in.position());
            var f = in.slice(in.position(), 4 + len);
            out.add(RecordBatch.decode(f));
            in.position(in.position() + 4 + len);
        }
        return out;
    }

    @Test
    void produceThenFetchReturnsTheOnDiskFrames() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            var r = post(h, "orders", recs("a", "bb"), "group");
            assertEquals(200, r.status());
            assertEquals("{\"baseOffset\":0,\"count\":2}", new String(bytes(r), StandardCharsets.US_ASCII));
            assertEquals(200, post(h, "orders", recs("ccc"), null).status());
            var f = h.handle(new Request("GET", "/messaging/v1/topics/orders/records?offset=1"));
            assertEquals(200, f.status());
            assertEquals("application/vnd.sjws.batch", f.headers().get("content-type"));
            assertEquals("3", f.headers().get("next-offset"));
            var data = bytes(f);
            var bs = batches(data);
            assertEquals(2, bs.size());
            assertEquals(0, bs.get(0).baseOffset()); // the batch containing offset 1
            assertArrayEquals("bb".getBytes(), bs.get(0).records().get(1));
            assertArrayEquals("ccc".getBytes(), bs.get(1).records().get(0));
            // byte-identical to the segment file (wire format = on-disk format)
            var seg = Files.readAllBytes(dir.resolve("orders").resolve(String.format("%020d.log", 0)));
            assertArrayEquals(seg, data);
        }
    }

    @Test
    void maxBytesPagesButAlwaysReturnsOneBatch() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            for (int i = 0; i < 5; i++) post(h, "t", recs("x" + i), "group");
            var f = h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0&maxBytes=1"));
            assertEquals(1, batches(bytes(f)).size());
            assertEquals("1", f.headers().get("next-offset"));
            var end = h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=5"));
            assertEquals(0, bytes(end).length);
            assertEquals("5", end.headers().get("next-offset"));
        }
    }

    @Test
    void fetchBoundsNearIntMax() throws Exception {
        long max = Integer.MAX_VALUE;
        assertTrue(MessagingHttp.fits(0, max + 10, 1));             // the first frame always goes out
        assertTrue(MessagingHttp.fits(max - 1, 1, max));             // exactly at the limit
        assertTrue(!MessagingHttp.fits(max, 1, max));                // one past Integer.MAX_VALUE, no overflow to negative
        assertTrue(MessagingHttp.fits(max, 1, max + 1));
        assertTrue(!MessagingHttp.fits(max + 1, max, 2 * max));
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            post(h, "t", recs("a"), "group");
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/t/records?maxBytes=2147483647")).status());
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/t/records?maxBytes=2147483648")).status());
            assertEquals(200, h.handle(new Request("GET", "/messaging/v1/topics/t/records?maxBytes=" + MessagingHttp.MAX_FETCH_BYTES)).status());
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=9223372036854775808")).status());
        }
    }

    @Test
    void survivesReopen() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            post(new MessagingHttp(topics), "t", recs("keep"), "group");
        }
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            assertEquals(1, batches(bytes(h.handle(new Request("GET", "/messaging/v1/topics/t/records")))).size());
            assertTrue(new String(bytes(post(h, "t", recs("next"), null))).contains("\"baseOffset\":1"));
        }
    }

    @Test
    void errorsAreProblems() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            assertEquals(404, h.handle(new Request("GET", "/messaging/v1/topics/none/records")).status());
            assertEquals(404, h.handle(new Request("GET", "/messaging/v1/other")).status());
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/..%2Fx/records")).status());
            assertEquals(400, post(h, "t", new byte[] {0, 0, 0, 9, 1}, null).status());
            assertEquals(400, post(h, "t", new byte[0], null).status());
            assertEquals(400, post(h, "t", recs("a"), "maybe").status());
            var bad = h.handle(new Request("PUT", "/messaging/v1/topics/t/records"));
            assertEquals(405, bad.status());
            assertEquals("GET, POST", bad.headers().get("allow"));
            assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=x")).status());
            assertEquals("application/problem+json", bad.headers().get("content-type"));
        }
    }

    @Test
    void durabilityModesAndClosedTopics() throws Exception {
        var topics = new LogTopics(dir, Durability.ASYNC);
        var h = new MessagingHttp(topics);
        assertEquals(200, post(h, "t", recs("a"), "none").status());
        assertEquals(200, post(h, "t", recs("b"), " ASYNC ").status());
        assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=-1")).status());
        assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/t/records?&maxBytes=0&x")).status());
        assertEquals(200, h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=&maxBytes=")).status());
        var q = h.handle(new Request("GET", "/messaging/v1/\"q\u0001\\"));
        assertEquals(404, q.status());
        assertTrue(new String(bytes(q), StandardCharsets.UTF_8).contains("\\\"q\\u0001\\\\"));
        assertEquals(400, post(h, "t", new byte[] {0, 0}, null).status());
        assertEquals(400, post(h, "t", new byte[] {(byte) 0xff, 0, 0, 0}, null).status());
        var huge = new RequestBody() {
            @Override public long length() { return MessagingHttp.MAX_REQUEST + 1L; }
            @Override public int read(byte[] into, int off, int len) { return -1; }
        };
        assertEquals(400, h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(huge)).status());
        var failing = new RequestBody() {
            @Override public long length() { return -1; }
            @Override public int read(byte[] into, int off, int len) throws java.io.IOException { throw new java.io.IOException("boom"); }
        };
        assertEquals(400, h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(failing)).status());
        var endless = new RequestBody() {
            @Override public long length() { return -1; }
            @Override public int read(byte[] into, int off, int len) { return len; }
        };
        assertEquals(400, h.handle(new Request("POST", "/messaging/v1/topics/t/records").withBody(endless)).status());
        assertEquals(400, h.handle(new Request("GET", "/messaging/v1/topics/./records")).status());
        topics.close();
        assertEquals(503, post(h, "t", recs("c"), null).status());
        assertEquals(503, h.handle(new Request("GET", "/messaging/v1/topics/t/records")).status());
    }

    @Test
    void moduleMountsAtMessagingV1() throws Exception {
        var m = new MessagingModule(dir);
        try {
            var fallback = (sjws_nettask.core.Handler) r -> new Response(418, sjws_nettask.core.ResponseHeaders.of(), Body.Bytes.of(new byte[0]));
            var h = HandlerMounts.install(List.of(m), fallback);
            assertEquals(404, h.handle(new Request("GET", "/messaging/v1/topics/none/records")).status());
            assertEquals(418, h.handle(new Request("GET", "/index.html")).status());
        } finally {
            m.close();
        }
    }
}
