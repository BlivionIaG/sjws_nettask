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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.messaging.ledger.RecordBatch;

/**
 * Crash tests through the produce/fetch handler (docs/design/partition-log.md R10, gap G1b): kill -9 a producing
 * child JVM and require every record acked over {@code POST} to be fetchable, intact, after reopen; and recover a
 * truncated tail to a prefix that fetch serves and produce continues from.
 */
class MessagingHttpCrashTest {

    @TempDir Path dir;

    static final ReentrantLock OUT = new ReentrantLock();

    /** Child entry point: 4 producer threads POST until killed, printing "offset value" per acked record. */
    public static void main(String[] args) throws Exception {
        var topics = new LogTopics(Path.of(args[0]), Durability.ASYNC);
        var h = new MessagingHttp(topics);
        for (int t = 0; t < 4; t++) {
            int tid = t;
            Thread.ofPlatform().start(() -> {
                for (long i = 0; ; i++) {
                    long v = i * 4 + tid;
                    var r = MessagingHttpTest.post(h, "crash", value(v), v % 2 == 0 ? "group" : null);
                    if (r.status() != 200) continue;
                    var json = new String(MessagingHttpTest.bytes(r), StandardCharsets.US_ASCII);
                    long off = Long.parseLong(json.substring(json.indexOf(':') + 1, json.indexOf(',')));
                    OUT.lock();
                    try {
                        System.out.println(off + " " + v);
                        System.out.flush();
                    } finally {
                        OUT.unlock();
                    }
                }
            });
        }
        Thread.sleep(Long.MAX_VALUE);
    }

    /** Two records in one POST: the value (8 B), then {@code v % 300} padding bytes. */
    static byte[] value(long v) {
        var pad = new byte[(int) (v % 300)];
        return ByteBuffer.allocate(4 + 8 + 4 + pad.length).putInt(8).putLong(v).putInt(pad.length).put(pad).array();
    }

    static Map<Long, RecordBatch> fetchAll(MessagingHttp h, String topic) throws Exception {
        var out = new HashMap<Long, RecordBatch>();
        long next = 0;
        while (true) {
            var r = h.handle(new Request("GET", "/messaging/v1/topics/" + topic + "/records?offset=" + next));
            assertEquals(200, r.status());
            var bs = MessagingHttpTest.batches(MessagingHttpTest.bytes(r));
            if (bs.isEmpty()) return out;
            for (var b : bs) out.put(b.baseOffset(), b);
            next = Long.parseLong(r.headers().get("next-offset"));
        }
    }

    @RepeatedTest(3)
    void noAckedProduceLostAfterSigkill() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), MessagingHttpCrashTest.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        var acked = new HashMap<Long, Long>();
        try (var in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
            String line;
            while ((line = in.readLine()) != null) {
                var parts = line.split(" ");
                if (parts.length != 2 || !parts[0].matches("\\d+")) continue;
                acked.put(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
                if (System.nanoTime() > until && acked.size() > 50) break;
            }
            p.destroyForcibly(); // SIGKILL
            p.waitFor(10, TimeUnit.SECONDS);
        }
        assertTrue(acked.size() > 50, "child produced acks: " + acked.size());
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var got = fetchAll(new MessagingHttp(topics), "crash");
            for (var e : acked.entrySet()) {
                var b = got.get(e.getKey());
                assertTrue(b != null, "acked offset " + e.getKey() + " missing");
                assertEquals(2, b.count());
                assertEquals(e.getValue(), ByteBuffer.wrap(b.records().get(0)).getLong());
                assertEquals(e.getValue() % 300, b.records().get(1).length);
            }
        }
    }

    @Test
    void truncatedTailIsCutAndProduceContinues() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            for (int i = 0; i < 10; i++) assertEquals(200, MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("r" + i), "group").status());
        }
        var seg = dir.resolve("t").resolve(String.format("%020d.log", 0));
        long size = Files.size(seg);
        try (var ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
            ch.truncate(size - 3); // torn last frame
        }
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            var got = fetchAll(h, "t");
            assertEquals(9, got.size());
            assertArrayEquals("r8".getBytes(), got.get(8L).records().get(0));
            var r = MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("again"), "group");
            assertTrue(new String(MessagingHttpTest.bytes(r)).contains("\"baseOffset\":9"));
            assertEquals(10, fetchAll(h, "t").size());
        }
    }

    @Test
    void corruptTailByteIsCut() throws Exception {
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            for (int i = 0; i < 5; i++) MessagingHttpTest.post(h, "c", MessagingHttpTest.recs("v" + i), "group");
        }
        var seg = dir.resolve("c").resolve(String.format("%020d.log", 0));
        try (var ch = FileChannel.open(seg, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var b = ByteBuffer.allocate(1);
            long pos = ch.size() - 1;
            ch.read(b, pos);
            b.put(0, (byte) (b.get(0) ^ 0x5a)).position(0);
            ch.write(b, pos); // CRC mismatch in the last batch
        }
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            assertEquals(List.of(0L, 1L, 2L, 3L), fetchAll(new MessagingHttp(topics), "c").keySet().stream().sorted().toList());
        }
    }
}
