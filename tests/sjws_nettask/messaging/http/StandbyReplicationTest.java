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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;

/** Standby replication (durable-log-plan §3), primary and standby in process; the fetcher calls the primary's handler. */
class StandbyReplicationTest {

    @TempDir Path dir;
    static final String TOKEN = "s3cret";

    LogTopics primary() {
        var t = new LogTopics(this.dir.resolve("p"), Durability.ASYNC);
        t.replicaToken(TOKEN);
        return t;
    }

    LogTopics standby() {
        var t = new LogTopics(this.dir.resolve("s"), Durability.ASYNC);
        t.standbyTopics(Set.of("t"));
        return t;
    }

    static StandbyReplicator.Fetcher inProcess(MessagingHttp h, String token) {
        return (topic, offset, acked) -> {
            var headers = token == null ? RequestHeaders.of(Map.of()) : RequestHeaders.of(Map.of(StandbyReplicator.TOKEN_HEADER, List.of(token)));
            var r = h.handle(new Request("GET", "/messaging/v1/topics/" + topic + "/records?offset=" + offset
                    + "&maxBytes=" + StandbyReplicator.FETCH_BYTES + "&replicaAcked=" + acked, headers));
            var next = r.headers().get("next-offset");
            return new StandbyReplicator.Fetched(r.status(), next == null ? offset : Long.parseLong(next),
                    r.status() == 200 ? MessagingHttpTest.bytes(r) : new byte[0]);
        };
    }

    static byte[] all(MessagingHttp h) {
        var r = h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0&maxBytes=" + (8 << 20)));
        assertEquals(200, r.status());
        return MessagingHttpTest.bytes(r);
    }

    static Response produce(MessagingHttp h, String v, String durability) {
        return MessagingHttpTest.post(h, "t", MessagingHttpTest.recs(v), durability);
    }

    @Test
    void aReplicatedProduceWaitsForTheStandbyAndTheBytesAreIdentical() throws Exception {
        try (var p = primary(); var s = standby()) {
            var hp = new MessagingHttp(p);
            var hs = new MessagingHttp(s);
            for (int i = 0; i < 50; i++) assertEquals(200, produce(hp, "a" + i, "group").status());
            var rep = new StandbyReplicator(s, inProcess(hp, TOKEN), List.of("t"), 1_000_000);
            var waiting = CompletableFuture.supplyAsync(() -> produce(hp, "must-replicate", "replicated"));
            // not acknowledged before the standby has it
            assertThrows(java.util.concurrent.TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));
            rep.start();
            var r = waiting.get(20, TimeUnit.SECONDS);
            assertEquals(200, r.status());
            assertEquals("replicated", r.headers().get("durability-effective"));
            assertTrue(p.replicaAcks().acked("t") >= 51);
            rep.close();
            assertArrayEquals(all(hp), all(hs), "same frames at the same offsets");
            assertNull(rep.failure());
            // the standby refuses client produces on a replicated topic
            assertEquals(409, produce(hs, "x", null).status());
        }
    }

    @Test
    void withoutAStandbyOrToken() throws Exception {
        try (var p = primary()) {
            p.replicaTimeoutMs(100);
            var hp = new MessagingHttp(p);
            var r = produce(hp, "lonely", "replicated");
            assertEquals(503, r.status()); // durable locally, never reported replicated
            assertEquals(200, produce(hp, "plain", null).status());
            // an ack without the shared token is ignored
            inProcess(hp, "wrong").fetch("t", 0, 2);
            inProcess(hp, null).fetch("t", 0, 2);
            assertEquals(0, p.replicaAcks().acked("t"));
            inProcess(hp, TOKEN).fetch("t", 0, 99); // clamped to the committed end
            assertEquals(2, p.replicaAcks().acked("t"));
        }
        try (var t = new LogTopics(this.dir.resolve("n"), Durability.ASYNC)) {
            assertEquals(503, produce(new MessagingHttp(t), "x", "replicated").status()); // replication not configured
        }
        try (var t = new LogTopics(this.dir.resolve("f"), Durability.ASYNC)) { // topic policy forces replicated
            t.replicaToken(TOKEN);
            t.replicatedTopics(Set.of("t"));
            t.replicaTimeoutMs(50);
            assertEquals(503, produce(new MessagingHttp(t), "x", "none").status());
        }
    }

    @Test
    void theStandbyResumesAfterARestart() throws Exception {
        try (var p = primary()) {
            var hp = new MessagingHttp(p);
            for (int i = 0; i < 20; i++) produce(hp, "b" + i, "group");
            try (var s = standby()) {
                var rep = new StandbyReplicator(s, inProcess(hp, TOKEN), List.of("t"), 1_000_000);
                while (rep.pass()) { }
            }
            for (int i = 0; i < 20; i++) produce(hp, "c" + i, "group");
            try (var s = standby()) {
                var rep = new StandbyReplicator(s, inProcess(hp, TOKEN), List.of("t"), 1_000_000);
                while (rep.pass()) { }
                assertArrayEquals(all(hp), all(new MessagingHttp(s)));
                assertEquals(40, p.replicaAcks().acked("t"));
            }
        }
    }

    @Test
    void aDivergedStandbyStopsInsteadOfOverwriting() throws Exception {
        try (var p = primary(); var s = new LogTopics(this.dir.resolve("s"), Durability.ASYNC)) {
            var hp = new MessagingHttp(p);
            var hs = new MessagingHttp(s);
            assertEquals(200, MessagingHttpTest.post(hp, "t", MessagingHttpTest.recs("a", "b", "c"), "group").status()); // one batch 0..2
            assertEquals(200, produce(hs, "local-only", "group").status()); // standby wrote offset 0 on its own
            var rep = new StandbyReplicator(s, inProcess(hp, TOKEN), List.of("t"), 1_000_000);
            assertThrows(StandbyReplicator.Diverged.class, rep::pass); // primary batch 0 would land at local 1
            var bg = new StandbyReplicator(s, inProcess(hp, TOKEN), List.of("t"), 1_000_000).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (bg.failure() == null && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(bg.failure() != null && bg.failure().startsWith("diverged"), String.valueOf(bg.failure()));
            bg.close();
        }
    }
}
