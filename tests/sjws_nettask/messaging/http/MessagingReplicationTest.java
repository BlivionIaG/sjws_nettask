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
            try {
                t.replication(new RaftReplicator(nodes.get(i), TOPICS, urls, dir.resolve("state" + (i + 1)), ELECTION, HEARTBEAT, clock::nanoTime));
            } catch (RuntimeException e) {
                t.close();
                throw e;
            }
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
        topics = null;
        nodes = null;
    }

    /** Writes {@code data} to {@code topic} on node {@code i} with HA off (a plain local LogTopics), then closes it. */
    void writeLocally(int i, String topic, String... data) throws Exception {
        var t = new LogTopics(dir.resolve("n" + (i + 1)), Durability.ASYNC);
        try {
            var h = new MessagingHttp(t);
            for (var d : data) assertEquals(200, h.handle(post(topic, d, Map.of())).status(), d);
        } finally {
            t.close();
        }
    }

    /** Messaging Core's repro (#274 MC-B1): a has 3 records on t with HA off; turning replication on would fork. */
    @Test
    void firstReplicatedStartOnALocalTopicWithDataIsRefused() throws Exception {
        writeLocally(0, "t", "pre-0", "pre-1", "pre-2");
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, this::start);
        assertTrue(e.getMessage().contains("replicated topic t already holds 3 local record(s)"), e.getMessage());
        assertTrue(e.getMessage().contains("refusing to start") && e.getMessage().contains("empty log"), e.getMessage());
        stop();
        // Wiped (the supported migration: start replicated topics from empty logs), it starts, offsets from 0 on both.
        try (var walk = java.nio.file.Files.walk(dir.resolve("n1/t"))) {
            for (var p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
        }
        start();
        int l = leader("t");
        var r = call(() -> http.get(l).handle(post("t", "repl-0", Map.of())));
        assertEquals(200, r.status(), LogOffsetsTest.text(r));
        assertTrue(LogOffsetsTest.text(r).contains("\"baseOffset\":0"), LogOffsetsTest.text(r));
        awaitEnd(1 - l, "t", 1);
        assertEquals(List.of("repl-0"), records(http.get(0).handle(fetch("t", "", Map.of()))));
        assertEquals(List.of("repl-0"), records(http.get(1).handle(fetch("t", "", Map.of()))));
    }

    @Test
    void firstReplicatedStartWithLocalConsumerOffsetsIsRefused() throws Exception {
        var t = new LogTopics(dir.resolve("n2"), Durability.ASYNC);
        try {
            var h = new MessagingHttp(t);
            assertEquals(200, h.handle(post("u", "x", Map.of())).status());
            assertEquals(200, LogOffsetsTest.put(h, "u", "g", 1).status());
        } finally {
            t.close();
        }
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, this::start);
        assertTrue(e.getMessage().contains("consumer offsets (__offsets"), e.getMessage());
        assertTrue(e.getMessage().contains("already holds 1 local record(s)"), e.getMessage());
    }

    /** The rollback half (#274 MC-B1): on, clean stop, a local write with HA off, then on again must not fork. */
    @Test
    void reEnablingReplicationAfterALocalWriteIsRefused() throws Exception {
        start();
        int l = leader("t");
        for (var d : List.of("r-0", "r-1")) assertEquals(200, call(() -> http.get(l).handle(post("t", d, Map.of()))).status());
        awaitEnd(1 - l, "t", 2);
        stop();
        start(); // a clean stop and restart with nothing written in between lines up
        stop();
        writeLocally(0, "t", "local-after-rollback");
        var e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, this::start);
        assertTrue(e.getMessage().contains("replicated topic t ledger end is 3 but replication stopped cleanly at 2"),
                e.getMessage());
        assertTrue(e.getMessage().contains("written while replication was off"), e.getMessage());
    }

    /**
     * #274 MC-B2: a topic outside ha.topics keeps the plain local path on a replicating node: readable (existing
     * data included) and writable on every node, never a 503, with its consumer offsets local too.
     */
    @Test
    void topicsOutsideHaTopicsStayLocal() throws Exception {
        writeLocally(0, "legacy", "old-0", "old-1");
        start();
        int l = leader("t");
        int f = 1 - l;
        var legacy = http.get(0).handle(fetch("legacy", "", Map.of()));
        assertEquals(200, legacy.status());
        assertEquals(List.of("old-0", "old-1"), records(legacy));
        assertEquals(404, http.get(1).handle(fetch("legacy", "", Map.of())).status(), "local to n1");
        var more = http.get(0).handle(post("legacy", "new-2", Map.of()));
        assertEquals(200, more.status(), LogOffsetsTest.text(more));
        assertTrue(LogOffsetsTest.text(more).contains("\"baseOffset\":2"), LogOffsetsTest.text(more));
        for (int i : new int[] {l, f}) { // the follower too: no redirect, no Retry-After
            var r = http.get(i).handle(post("other", "o-" + i, Map.of()));
            assertEquals(200, r.status(), LogOffsetsTest.text(r));
            assertNull(r.headers().get("retry-after"));
            assertNull(topics.get(i).get("other", false).replica(), "not replicated");
            awaitEnd(i, "other", 1);
            assertEquals(List.of("o-" + i), records(http.get(i).handle(fetch("other", "", Map.of()))));
        }
        // Their commits stay local (a follower takes them), and never enter the replicated __offsets.
        long replicatedEnd = topics.get(f).get(LogOffsets.TOPIC, false).writer().snapshot().nextOffset();
        var put = http.get(f).handle(new Request("PUT", "/messaging/v1/topics/other/offsets/g", RequestHeaders.of(Map.of()))
                .withBody(MessagingHttpTest.body("{\"offset\":1}".getBytes(StandardCharsets.UTF_8))));
        assertEquals(200, put.status(), LogOffsetsTest.text(put));
        assertEquals(1, topics.get(f).offsets("other").committed("other", "g"));
        assertEquals(-1, topics.get(l).offsets("other").committed("other", "g"));
        assertEquals(replicatedEnd, topics.get(f).get(LogOffsets.TOPIC, false).writer().snapshot().nextOffset());
        assertNull(topics.get(f).get(LogOffsets.LOCAL_TOPIC, false).replica());
        assertTrue(topics.get(f).offsets("t") == topics.get(f).offsets(), "replicated topics keep __offsets");
        // Internal names stay refused.
        assertEquals(400, http.get(f).handle(post(LogOffsets.LOCAL_TOPIC, "x", Map.of())).status());
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

    static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("local"), new InetSocketAddress("127.0.0.1", 24_000 + wire), 1L,
                MemberState.ALIVE, witness);
    }
}
