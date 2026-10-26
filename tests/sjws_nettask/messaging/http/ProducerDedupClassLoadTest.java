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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.MemoryLogStorage;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.QuorumPolicy;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.msgrepl.RaftReplicator;

/**
 * #278 B4: producer dedup costs nothing while it is off. A replicated topic, with the flag off, that produces and
 * fetches without a {@code Producer-Id} never loads the producer table or the producer result and refusal types.
 */
class ProducerDedupClassLoadTest {

    static final List<String> PRODUCER_CLASSES = List.of("sjws_nettask.msgrepl.ProducerTable",
            "sjws_nettask.messaging.ledger.ProducerSequenceException", "sjws_nettask.messaging.ledger.ProducerAppend");

    @TempDir Path dir;

    /** Child JVM: a one-node replicated topic; produce (no producer headers) and fetch through the handler. */
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        var clock = new FakeClock(0L);
        var id = new NodeId(1, "n1");
        var member = new Member(id, new SiteId("local"), new InetSocketAddress("127.0.0.1", 26_101), 1L, MemberState.ALIVE, false);
        try (var node = ReplicatedNode.open(id, List.of(member), Set.of(id), QuorumPolicy.majority(), clock,
                new MemoryLogStorage(), new SimTransport());
                var topics = new LogTopics(root.resolve("log"), Durability.ASYNC)) {
            topics.replication(new RaftReplicator(node, Set.of("t"), Map.of(), root.resolve("state"), Duration.ofMillis(100),
                    Duration.ofMillis(40), clock::nanoTime));
            var h = new MessagingHttp(topics); // the flag is off unless set
            var rep = topics.get("t", true).replica();
            for (int k = 0; k < 500 && !rep.leaderReady(); k++) {
                clock.advance(Duration.ofMillis(20));
                node.drive();
            }
            if (!rep.leaderReady()) throw new AssertionError("no leader");
            for (int i = 0; i < 3; i++) {
                var f = CompletableFuture.supplyAsync(() -> MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("a", "b"), "group"),
                        r -> Thread.ofVirtual().start(r));
                for (int k = 0; k < 20_000 && !f.isDone(); k++) {
                    clock.advance(Duration.ofMillis(1));
                    node.drive();
                    Thread.sleep(0, 100_000);
                }
                Response r = f.get(10, TimeUnit.SECONDS);
                if (r.status() != 200) throw new AssertionError("produce " + r.status() + " " + LogOffsetsTest.text(r));
            }
            if (h.handle(new Request("GET", "/messaging/v1/topics/t/records?offset=0")).status() != 200) throw new AssertionError("fetch");
            if (topics.get("t", false).writer().snapshot().nextOffset() != 6) throw new AssertionError("not applied");
        }
        System.out.println("CHILD-OK");
    }

    @Test
    void aReplicatedTopicWithTheFlagOffLoadsNoProducerClass() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var proc = new ProcessBuilder(java, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                ProducerDedupClassLoadTest.class.getName(), dir.toString()).redirectErrorStream(true).start();
        var out = new String(proc.getInputStream().readAllBytes());
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, proc.exitValue(), out.length() > 4000 ? out.substring(out.length() - 4000) : out);
        assertTrue(out.contains("CHILD-OK"));
        assertTrue(out.contains(" sjws_nettask.msgrepl.RaftReplica "), "the replicated path ran");
        var loaded = out.lines().filter(l -> PRODUCER_CLASSES.stream().anyMatch(c -> l.contains(" " + c + " "))).toList();
        assertTrue(loaded.isEmpty(), "producer classes loaded with the flag off: " + loaded);
    }
}
