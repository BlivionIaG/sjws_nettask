package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import sjws_nettask.cluster.LogStorage;
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
    List<MemoryLogStorage> stores;
    /** Node index whose log storage fails appends and truncates of {@link #faultGroup}; -1 = none. */
    volatile int faultyNode = -1;
    volatile sjws_nettask.cluster.GroupId faultGroup;

    /**
     * Wraps {@code d} so appends and truncates of {@link #faultGroup} on node {@code i} fail while armed: the next
     * batch cannot be written or rolled back, so that group fails closed (#245).
     */
    LogStorage faulty(int i, MemoryLogStorage d) {
        return (LogStorage) java.lang.reflect.Proxy.newProxyInstance(LogStorage.class.getClassLoader(),
                new Class<?>[] {LogStorage.class}, (proxy, m, args) -> {
                    if (faultyNode == i && args != null && args.length > 0 && args[0].equals(faultGroup)
                            && (m.getName().equals("append") || m.getName().equals("truncateSuffix"))) {
                        throw new java.io.IOException("injected " + m.getName() + " failure on /var/lib/sjws/secret.log");
                    }
                    try {
                        return m.invoke(d, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    void start() throws Exception {
        clock = new FakeClock(0L);
        net = new SimTransport();
        var members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        nodes = new ArrayList<>();
        topics = new ArrayList<>();
        http = new ArrayList<>();
        var urls = Map.of("n1", "http://n1.test", "n2", "http://n2.test");
        stores = new ArrayList<>();
        for (var m : members) {
            var store = new MemoryLogStorage();
            stores.add(store);
            nodes.add(ReplicatedNode.open(m.id(), voters, clock, faulty(stores.size() - 1, store), net));
        }
        for (int i = 0; i < 2; i++) {
            var t = new LogTopics(dir.resolve("n" + (i + 1)), Durability.ASYNC);
            t.replication(new RaftReplicator(nodes.get(i), TOPICS, urls, dir.resolve("state" + (i + 1)), ELECTION, HEARTBEAT, clock::nanoTime));
            topics.add(t);
            var h = new MessagingHttp(t);
            h.producerDedup(true);
            http.add(h);
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

    /**
     * #254, next to the 307 above: once the leader has the batch in its log, losing leadership is not "not leader".
     * The batch may still commit (here: under the node leadership went to), so the produce gets the typed
     * outcome-unknown 503 with the new leader as a hint, never a 307 that would resend the body.
     */
    @Test
    void leadershipLostWithTheBatchInTheLogIsOutcomeUnknownNot307() throws Exception {
        start();
        int l = leader("t");
        int f = 1 - l;
        var group = RaftReplicator.group("t");
        long before = stores.get(l).lastIndex(group);
        net.cut(nodes.get(l).self(), nodes.get(f).self());
        net.cut(nodes.get(l).self(), nodes.get(2).self());
        var pending = CompletableFuture.supplyAsync(() -> http.get(l).handle(post("t", "maybe", Map.of())),
                r -> Thread.ofVirtual().start(r));
        for (int k = 0; k < 10_000 && stores.get(l).lastIndex(group) == before && !pending.isDone(); k++) Thread.sleep(1);
        assertTrue(stores.get(l).lastIndex(group) > before, "the batch never reached the leader's log");
        assertFalse(pending.isDone(), "cut off, the leader cannot commit");
        nodes.get(l).openGroup(group, RaftReplicator.options(ELECTION, HEARTBEAT), null).transferLeadership(nodes.get(f).self());

        var r = pending.get(10, TimeUnit.SECONDS);
        var body = LogOffsetsTest.text(r);
        assertEquals(503, r.status(), body);
        assertNull(r.headers().get("location"), "never a redirect for a write that may commit");
        assertTrue(body.contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE), body);
        assertEquals("1", r.headers().get("retry-after"));
        var target = "n" + (f + 1);
        assertEquals(target, r.headers().get("sjws-leader"), "the hint is where leadership went");
        assertTrue(body.contains("\"leaderUrl\":\"http://" + target + ".test/messaging/v1/topics/t/records\""), body);
    }

    /**
     * #254: cluster and messaging each have an outcome-unknown type (messaging may not load cluster classes), joined at
     * exactly one boundary, RaftReplica.map. Pin that both reach the same typed 503 problem body, never a 307.
     */
    @Test
    void clusterAndMessagingOutcomeUnknownGiveTheSameTyped503() throws Exception {
        start();
        int l = leader("t");
        int f = 1 - l;
        var replica = topics.get(l).get("t", false).replica();
        var target = "/messaging/v1/topics/t/records";
        var hint = "n" + (f + 1);
        var cluster = new sjws_nettask.cluster.OutcomeUnknownException(RaftReplicator.group("t"), "stopped leading",
                java.util.Optional.of(nodes.get(f).self()), null);
        var viaBoundary = MessagingHttp.failed(new java.util.concurrent.ExecutionException(
                new java.util.concurrent.CompletionException(sjws_nettask.msgrepl.ReplicaErrors.map(replica, cluster))), target, true);
        var messaging = MessagingHttp.failed(new java.util.concurrent.ExecutionException(
                new sjws_nettask.messaging.ledger.WriteOutcomeUnknownException("other text", replica.term(), hint,
                        "http://" + hint + ".test", null)), target, true);
        for (var r : List.of(viaBoundary, messaging)) {
            assertEquals(503, r.status());
            assertNull(r.headers().get("location"), "never a redirect");
            assertEquals("1", r.headers().get("retry-after"));
            assertEquals(hint, r.headers().get("sjws-leader"));
            assertEquals(Long.toString(replica.term()), r.headers().get("sjws-term"));
        }
        assertEquals(LogOffsetsTest.text(messaging), LogOffsetsTest.text(viaBoundary), "the same problem body");
        assertTrue(LogOffsetsTest.text(viaBoundary).contains("\"type\":\"" + MessagingHttp.OUTCOME_UNKNOWN_TYPE + "\""));

        // Were the cluster type ever to skip the boundary, it would still be the typed outcome-unknown 503, not a 307.
        var raw = MessagingHttp.failed(new java.util.concurrent.ExecutionException(cluster), target, true);
        assertEquals(503, raw.status());
        assertNull(raw.headers().get("location"));
        assertTrue(LogOffsetsTest.text(raw).contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE));
    }

    /** #254 + #245 through the real handler: a batch the leader could neither write nor roll back. */
    @Test
    void produceOnAFailedClosedGroupIsOutcomeUnknownWithoutInternals() throws Exception {
        start();
        int l = leader("t");
        faultGroup = RaftReplicator.group("t");
        faultyNode = l;
        var r = call(() -> http.get(l).handle(post("t", "torn", Map.of())));
        var body = LogOffsetsTest.text(r);
        assertTrue(nodes.get(l).failedClosed(faultGroup), "the group must fail closed");
        assertEquals(503, r.status(), body);
        assertNull(r.headers().get("location"), body);
        assertTrue(body.contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE), body);
        assertFalse(body.contains("secret") || body.contains("/var/lib") || body.contains("IOException"), body);

        // Later writes on the fenced node are refusals (nothing appended), never outcome unknown.
        var again = http.get(l).handle(post("t", "again", Map.of()));
        assertFalse(LogOffsetsTest.text(again).contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE), LogOffsetsTest.text(again));
        assertTrue(again.status() == 307 || again.status() == 503, LogOffsetsTest.text(again));
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

    static Map<String, List<String>> producer(long id, long seq) {
        return producer(id, seq, "group");
    }

    static Map<String, List<String>> producer(long id, long seq, String durability) {
        return Map.of("producer-id", List.of(Long.toString(id)), "producer-seq", List.of(Long.toString(seq)),
                "durability", List.of(durability));
    }

    static String ack(long base) {
        return "{\"baseOffset\":" + base + ",\"count\":1}";
    }

    /** Drives until some data node is the ready leader of {@code topic}. */
    int awaitLeader(String topic) throws Exception {
        for (int k = 0; k < 200; k++) {
            int l = leader(topic);
            if (l >= 0) return l;
            clock.advance(Duration.ofMillis(50));
            drive();
            Thread.sleep(1);
        }
        throw new AssertionError("no ready leader for " + topic);
    }

    void assertSameProducerTables() throws Exception {
        assertArrayEquals(sjws_nettask.msgrepl.RaftReplicator.producerTable(topics.get(0).get("t", false).replica()),
                sjws_nettask.msgrepl.RaftReplicator.producerTable(topics.get(1).get("t", false).replica()),
                "every replica builds the same producer table");
    }

    /** M2: a batch acked by the old leader and retried on the new one is acknowledged with its offset, not appended again. */
    @Test
    void producerRetryAfterFailoverIsAppendedOnce() throws Exception {
        start();
        int l = leader("t");
        int f = 1 - l;
        for (int s = 1; s <= 2; s++) {
            long seq = s;
            var r = call(() -> http.get(l).handle(post("t", "p" + seq, producer(7, seq))));
            assertEquals(ack(seq - 1), LogOffsetsTest.text(r));
        }
        awaitEnd(f, "t", 2); // the follower holds both: the witness votes only for a candidate that is up to date
        net.cut(nodes.get(l).self(), nodes.get(f).self());
        net.cut(nodes.get(l).self(), nodes.get(2).self());
        for (int k = 0; k < 40 && !topics.get(f).get("t", false).replica().leaderReady(); k++) {
            clock.advance(Duration.ofMillis(100));
            drive();
        }
        assertTrue(topics.get(f).get("t", false).replica().leaderReady(), "follower did not take over");
        // async: with the old leader cut off, a STRICT (group) write would be refused for lack of a second copy.
        var retry = call(() -> http.get(f).handle(post("t", "p2", producer(7, 2, "async"))));
        assertEquals(200, retry.status(), LogOffsetsTest.text(retry));
        assertEquals(ack(1), LogOffsetsTest.text(retry), "the new leader knows the producer: original offset");
        assertEquals(ack(2), LogOffsetsTest.text(call(() -> http.get(f).handle(post("t", "p3", producer(7, 3, "async"))))));
        net.heal(nodes.get(l).self(), nodes.get(f).self());
        net.heal(nodes.get(l).self(), nodes.get(2).self());
        awaitEnd(l, "t", 3);
        for (int i = 0; i < 2; i++) assertEquals(List.of("p1", "p2", "p3"), records(http.get(i).handle(fetch("t", "", Map.of()))));
        assertSameProducerTables();
    }

    /**
     * M2 + #254: after an outcome-unknown 503 the client retries the same seq. Whether or not the first attempt
     * committed, the record is in the log exactly once and the retry is acknowledged with its offset.
     */
    @Test
    void producerRetryAfterOutcomeUnknownIsAppendedOnce() throws Exception {
        start();
        int l = leader("t");
        int f = 1 - l;
        var group = RaftReplicator.group("t");
        long before = stores.get(l).lastIndex(group);
        net.cut(nodes.get(l).self(), nodes.get(f).self());
        net.cut(nodes.get(l).self(), nodes.get(2).self());
        var pending = CompletableFuture.supplyAsync(() -> http.get(l).handle(post("t", "maybe", producer(5, 1))),
                r -> Thread.ofVirtual().start(r));
        for (int k = 0; k < 10_000 && stores.get(l).lastIndex(group) == before && !pending.isDone(); k++) Thread.sleep(1);
        assertTrue(stores.get(l).lastIndex(group) > before, "the batch never reached the leader's log");
        // The replica's own group handle: openGroup(..., null) would replace its listener and stop the applies on l.
        var handle = topics.get(l).get("t", false).replica().getClass().getDeclaredField("group");
        handle.setAccessible(true);
        ((sjws_nettask.cluster.ReplicationGroup) handle.get(topics.get(l).get("t", false).replica()))
                .transferLeadership(nodes.get(f).self());
        var first = pending.get(10, TimeUnit.SECONDS);
        assertEquals(503, first.status(), LogOffsetsTest.text(first));
        assertTrue(LogOffsetsTest.text(first).contains(MessagingHttp.OUTCOME_UNKNOWN_TYPE));

        net.heal(nodes.get(l).self(), nodes.get(f).self());
        net.heal(nodes.get(l).self(), nodes.get(2).self());
        int now = awaitLeader("t");
        var retry = call(() -> http.get(now).handle(post("t", "maybe", producer(5, 1))));
        assertEquals(ack(0), LogOffsetsTest.text(retry));
        var again = call(() -> http.get(now).handle(post("t", "maybe", producer(5, 1))));
        assertEquals(ack(0), LogOffsetsTest.text(again), "and every later retry");
        assertEquals(ack(1), LogOffsetsTest.text(call(() -> http.get(now).handle(post("t", "next", producer(5, 2))))));
        for (int i = 0; i < 2; i++) {
            awaitEnd(i, "t", 2);
            assertEquals(List.of("maybe", "next"), records(http.get(i).handle(fetch("t", "", Map.of()))));
        }
        assertSameProducerTables();
    }

    /** M2 problem types: sequence errors are 400 not-appended with the next seq, an old duplicate is 409. */
    @Test
    void producerSequenceErrorsAreTypedProblems() throws Exception {
        start();
        int l = leader("t");
        var h = http.get(l);
        assertEquals(200, call(() -> h.handle(post("t", "a", producer(3, 1)))).status());
        var gap = call(() -> h.handle(post("t", "b", producer(3, 3))));
        var gapBody = LogOffsetsTest.text(gap);
        assertEquals(400, gap.status(), gapBody);
        assertTrue(gapBody.contains(MessagingHttp.NOT_APPENDED_TYPE) && gapBody.contains("\"reason\":\"out_of_order\"")
                && gapBody.contains("\"expectedSeq\":2"), gapBody);
        assertEquals("2", gap.headers().get("producer-expected-seq"));
        var reused = call(() -> h.handle(new Request("POST", "/messaging/v1/topics/t/records", RequestHeaders.of(producer(3, 1)))
                .withBody(MessagingHttpTest.body(MessagingHttpTest.recs("a", "a2")))));
        assertEquals(400, reused.status(), LogOffsetsTest.text(reused));
        assertTrue(LogOffsetsTest.text(reused).contains("\"reason\":\"seq_reused\""), LogOffsetsTest.text(reused));
        for (int s = 2; s <= 1 + sjws_nettask.msgrepl.ProducerTable.WINDOW; s++) {
            long seq = s;
            assertEquals(200, call(() -> h.handle(post("t", "w" + seq, producer(3, seq)))).status());
        }
        var old = call(() -> h.handle(post("t", "a", producer(3, 1))));
        assertEquals(409, old.status(), LogOffsetsTest.text(old));
        assertTrue(LogOffsetsTest.text(old).contains(MessagingHttp.DUPLICATE_TYPE), LogOffsetsTest.text(old));

        for (var bad : List.of(Map.of("producer-id", List.of("0"), "producer-seq", List.of("1")),
                Map.of("producer-id", List.of("x"), "producer-seq", List.of("1")),
                Map.of("producer-id", List.of("4")),
                Map.of("producer-id", List.of("4"), "producer-seq", List.of("0")),
                Map.of("producer-id", List.of("-1"), "producer-seq", List.of("1")))) {
            assertEquals(400, h.handle(post("t", "bad", bad)).status(), bad.toString());
        }
        h.producerDedup(false);
        var off = h.handle(post("t", "off", producer(4, 1)));
        assertEquals(400, off.status());
        assertTrue(LogOffsetsTest.text(off).contains("not enabled"), LogOffsetsTest.text(off));
        long end = 1 + sjws_nettask.msgrepl.ProducerTable.WINDOW;
        awaitEnd(l, "t", end);
        assertEquals(end, topics.get(l).get("t", false).writer().snapshot().nextOffset(), "nothing refused was appended");

        // A local (not replicated) topic cannot dedup: refused, never a silent at-least-once.
        try (var local = new LogTopics(dir.resolve("local"), Durability.ASYNC)) {
            var lh = new MessagingHttp(local);
            lh.producerDedup(true);
            var r = lh.handle(post("t", "x", producer(1, 1)));
            assertEquals(400, r.status());
            assertTrue(LogOffsetsTest.text(r).contains("replicated"), LogOffsetsTest.text(r));
            assertEquals(200, lh.handle(post("t", "x", Map.of())).status());
        }
    }

    static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 24_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }
}
