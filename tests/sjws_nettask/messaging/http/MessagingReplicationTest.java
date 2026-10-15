package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.cluster.FakeClock;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.MemoryLogStorage;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.msgrepl.RaftReplicator;

/**
 * Messaging replication phase A over {@link SimTransport}: two data nodes and a witness (2+1), one group per partition
 * plus {@code msg/__offsets}. Failover keeps every acknowledged write, followers serve fetches from applied data,
 * non-leaders redirect, stale terms are refused.
 */
@Timeout(60)
class MessagingReplicationTest {

    static final Duration ELECTION = Duration.ofMillis(100), HEARTBEAT = Duration.ofMillis(40);
    static final Set<String> TOPICS = Set.of("t");

    @TempDir Path dir;
    FakeClock clock;
    SimTransport net;
    List<ReplicatedNode> nodes;
    List<LogTopics> topics;
    List<MessagingHttp> http;

    void start() throws Exception {
        clock = new FakeClock(0L);
        net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        nodes = new ArrayList<>();
        topics = new ArrayList<>();
        http = new ArrayList<>();
        var urls = Map.of("n1", "http://n1.test", "n2", "http://n2.test");
        for (var m : members) nodes.add(ReplicatedNode.open(m.id(), voters, clock, new MemoryLogStorage(), net));
        for (int i = 0; i < 2; i++) {
            var t = new LogTopics(dir.resolve("n" + (i + 1)), Durability.ASYNC);
            t.replication(new RaftReplicator(nodes.get(i), TOPICS, urls, dir.resolve("state" + (i + 1)), ELECTION, HEARTBEAT, clock::nanoTime));
            topics.add(t);
            http.add(new MessagingHttp(t));
        }
        RaftReplicator.openForWitness(nodes.get(2), TOPICS, ELECTION, HEARTBEAT);
        clock.advance(ELECTION);
        drive();
        clock.advance(HEARTBEAT);
        drive();
    }

    @AfterEach
    void stop() {
        if (topics != null) topics.forEach(LogTopics::close);
        if (nodes != null) nodes.forEach(ReplicatedNode::close);
    }

    void drive() {
        ReplicatedNode.driveAll(nodes);
    }

    /** Runs a (blocking) handler call while the simulated cluster advances. */
    Response call(Supplier<Response> s) throws Exception {
        var f = CompletableFuture.supplyAsync(s, r -> Thread.ofVirtual().start(r));
        for (int i = 0; i < 4000 && !f.isDone(); i++) {
            drive();
            if (i % 4 == 3) clock.advance(Duration.ofMillis(5));
            Thread.onSpinWait();
            if (!f.isDone()) Thread.sleep(0, 200_000);
        }
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            var sb = new StringBuilder("call stuck:");
            for (int i = 0; i < 3; i++) {
                var g = nodes.get(i).openGroup(RaftReplicator.group("t"), RaftReplicator.options(ELECTION, HEARTBEAT), null);
                sb.append(" n").append(i + 1).append(" term=").append(g.term()).append(" leader=").append(g.isLeader())
                        .append(" commit=").append(g.commitIndex()).append(" applied=").append(g.lastApplied()).append(" durable=")
                        .append(g.durableIndex()).append(" strict=").append(nodes.get(i).strictPending(RaftReplicator.group("t")));
            }
            throw new AssertionError(sb.toString(), e);
        }
    }

    int leader(String topic) throws Exception {
        for (int i = 0; i < 2; i++) if (topics.get(i).get(topic, false).replica().leaderReady()) return i;
        return -1;
    }

    static Request post(String topic, String data, Map<String, List<String>> headers) {
        return new Request("POST", "/messaging/v1/topics/" + topic + "/records", RequestHeaders.of(headers))
                .withBody(MessagingHttpTest.body(MessagingHttpTest.recs(data)));
    }

    static Request fetch(String topic, String q, Map<String, List<String>> headers) {
        return new Request("GET", "/messaging/v1/topics/" + topic + "/records?offset=0" + q, RequestHeaders.of(headers));
    }

    static List<String> records(Response r) throws Exception {
        var out = new ArrayList<String>();
        for (var b : MessagingHttpTest.batches(MessagingHttpTest.bytes(r)))
            for (var rec : b.records()) out.add(new String(rec, StandardCharsets.UTF_8));
        return out;
    }

    /** Waits until node {@code i}'s ledger reaches {@code end} (applies complete on the writer thread). */
    void awaitEnd(int i, String topic, long end) throws Exception {
        for (int k = 0; k < 2000; k++) {
            var l = topics.get(i).get(topic, false);
            if (l.writer().snapshot().nextOffset() >= end) return;
            if (k % 8 == 7) clock.advance(HEARTBEAT); // followers learn the commit index on the next heartbeat
            drive();
            Thread.sleep(1);
        }
        throw new AssertionError("n" + (i + 1) + " " + topic + " stuck at " + topics.get(i).get(topic, false).writer().snapshot().nextOffset());
    }

    @Test
    void followerFetchServesAppliedData() throws Exception {
        start();
        int l = leader("t");
        assertTrue(l >= 0, "no ready leader");
        int f = 1 - l;
        for (var mode : List.of("async", "group", "none", "group")) {
            var r = call(() -> http.get(l).handle(post("t", "v-" + mode, Map.of("durability", List.of(mode)))));
            assertEquals(200, r.status(), mode);
        }
        awaitEnd(f, "t", 4);
        assertEquals(List.of("v-async", "v-group", "v-none", "v-group"), records(http.get(f).handle(fetch("t", "", Map.of()))));
        assertEquals(records(http.get(l).handle(fetch("t", "", Map.of()))), records(http.get(f).handle(fetch("t", "", Map.of()))));

        // Offsets replicate through msg/__offsets: committed on the leader, readable on the follower.
        int ol = leader(LogOffsets.TOPIC);
        assertTrue(ol >= 0);
        var put = call(() -> LogOffsetsTest.put(http.get(ol), "t", "g", 3));
        assertEquals(200, put.status(), LogOffsetsTest.text(put));
        for (int k = 0; k < 2000 && topics.get(1 - ol).offsets().committed("t", "g") != 3; k++) {
            if (k % 8 == 7) clock.advance(HEARTBEAT);
            drive();
            Thread.sleep(1);
        }
        assertEquals(3, topics.get(1 - ol).offsets().committed("t", "g"));
        assertEquals(3, topics.get(ol).offsets().committed("t", "g"));
    }

    @Test
    void nonLeaderRedirectsAndLeaderReadsAreConfirmed() throws Exception {
        start();
        int l = leader("t");
        int f = 1 - l;
        var leaderName = "n" + (l + 1);
        var r = http.get(f).handle(post("t", "x", Map.of()));
        assertEquals(307, r.status());
        assertEquals("http://" + leaderName + ".test/messaging/v1/topics/t/records", r.headers().get("location"));
        assertEquals(leaderName, r.headers().get("sjws-leader"));
        assertEquals(Long.toString(topics.get(l).get("t", false).replica().term()), r.headers().get("sjws-term"));
        assertTrue(LogOffsetsTest.text(r).contains(MessagingHttp.NOT_LEADER_TYPE));

        var lr = http.get(f).handle(fetch("t", "&consistency=leader", Map.of()));
        assertEquals(307, lr.status());
        assertEquals(307, http.get(f).handle(fetch("t", "", Map.of("read-consistency", List.of("leader")))).status());
        assertEquals(200, http.get(f).handle(fetch("t", "", Map.of())).status()); // default: applied data, no redirect
        assertEquals(200, call(() -> http.get(l).handle(fetch("t", "&consistency=leader", Map.of()))).status());

        int ol = leader(LogOffsets.TOPIC);
        assertEquals(200, call(() -> http.get(l).handle(post("t", "y", Map.of()))).status());
        assertEquals(307, http.get(1 - ol).handle(new Request("PUT", "/messaging/v1/topics/t/offsets/g", RequestHeaders.of(Map.of()))
                .withBody(MessagingHttpTest.body("{\"offset\":0}".getBytes(StandardCharsets.UTF_8)))).status());
    }

    @Test
    void noLeaderIs503WithRetryAfter() throws Exception {
        start();
        // Cut every link and let terms move on: a candidate knows no leader to redirect to.
        for (int a = 0; a < 3; a++) for (int b = a + 1; b < 3; b++) net.cut(nodes.get(a).self(), nodes.get(b).self());
        for (int k = 0; k < 20; k++) {
            clock.advance(Duration.ofMillis(300));
            drive();
        }
        int seen503 = 0;
        for (int i = 0; i < 2; i++) {
            var rep = topics.get(i).get("t", false).replica();
            if (rep.leaderReady() || rep.leaderUrl() != null) continue;
            var r = http.get(i).handle(post("t", "x", Map.of()));
            assertEquals(503, r.status(), LogOffsetsTest.text(r));
            assertEquals("1", r.headers().get("retry-after"));
            assertTrue(LogOffsetsTest.text(r).contains(MessagingHttp.NOT_LEADER_TYPE));
            seen503++;
        }
        assertTrue(seen503 > 0, "every node still claims or knows a leader");
    }

    @Test
    void staleIfTermIsRefused() throws Exception {
        start();
        int l = leader("t");
        long term = topics.get(l).get("t", false).replica().term();
        var r = http.get(l).handle(post("t", "x", Map.of("if-term", List.of(Long.toString(term - 1)))));
        assertEquals(409, r.status());
        assertEquals(Long.toString(term), r.headers().get("sjws-term"));
        assertEquals(200, call(() -> http.get(l).handle(post("t", "y", Map.of("if-term", List.of(Long.toString(term)))))).status());
    }

    @Test
    void failoverKeepsEveryAckedWrite() throws Exception {
        start();
        int l = leader("t");
        int f = 1 - l;
        var acked = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            var v = "w" + i;
            var mode = i % 2 == 0 ? "group" : "async";
            var r = call(() -> http.get(l).handle(post("t", v, Map.of("durability", List.of(mode)))));
            if (r.status() == 200) acked.add(v);
        }
        assertEquals(20, acked.size());
        // The leader dies (cut from both peers); the other data node takes over with the witness's vote.
        net.cut(nodes.get(l).self(), nodes.get(f).self());
        net.cut(nodes.get(l).self(), nodes.get(2).self());
        int newLeader = -1;
        for (int k = 0; k < 40 && newLeader != f; k++) {
            clock.advance(Duration.ofMillis(100));
            drive();
            newLeader = topics.get(f).get("t", false).replica().leaderReady() ? f : -1;
        }
        assertEquals(f, newLeader, "follower did not take over");
        awaitEnd(f, "t", acked.size());
        assertEquals(acked, records(http.get(f).handle(fetch("t", "", Map.of()))), "acked write lost");
        // The old leader is fenced: it can no longer acknowledge.
        var stale = call(() -> http.get(l).handle(post("t", "lost", Map.of())));
        assertTrue(stale.status() == 307 || stale.status() == 503, "old leader acked: " + stale.status());
        var after = call(() -> http.get(f).handle(post("t", "after", Map.of("durability", List.of("async")))));
        assertEquals(200, after.status(), LogOffsetsTest.text(after));
        acked.add("after");
        // Heal: the old leader rejoins as a follower and converges to the same ledger, without the unacked write.
        net.heal(nodes.get(l).self(), nodes.get(f).self());
        net.heal(nodes.get(l).self(), nodes.get(2).self());
        for (int k = 0; k < 10; k++) {
            clock.advance(HEARTBEAT);
            drive();
        }
        awaitEnd(l, "t", acked.size());
        assertEquals(acked, records(http.get(l).handle(fetch("t", "", Map.of()))));
        assertArrayEquals(acked.toArray(), records(http.get(f).handle(fetch("t", "", Map.of()))).toArray());
    }

    static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 24_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }
}
