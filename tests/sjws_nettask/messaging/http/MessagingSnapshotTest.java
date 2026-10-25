package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import sjws_nettask.cluster.FileLogStorage;
import sjws_nettask.cluster.Member;
import sjws_nettask.cluster.MemberState;
import sjws_nettask.cluster.NodeId;
import sjws_nettask.cluster.ReplicatedNode;
import sjws_nettask.cluster.SimTransport;
import sjws_nettask.cluster.SiteId;
import sjws_nettask.cluster.VoterSet;
import sjws_nettask.core.Response;
import sjws_nettask.messaging.ledger.Durability;
import sjws_nettask.msgrepl.RaftReplicator;
import sjws_nettask.msgrepl.SnapshotTesting;

/**
 * #270 through the messaging HTTP layer (2+1, file logs): a follower that missed truncated history installs the topic,
 * then serves the same records and the same committed group offsets ({@code __offsets} is STRICT, so it never lags in
 * 2+1 and is not installed here; the generic install path is in {@code MsgSnapshotClusterTest}); retention run by
 * {@link LogTopics#enforceRetention()} is one replicated cut applied on both data nodes.
 */
@Timeout(120)
class MessagingSnapshotTest {

    static final Duration ELECTION = Duration.ofMillis(100), HEARTBEAT = Duration.ofMillis(40);

    @TempDir Path dir;
    FakeClock clock = new FakeClock(0L);
    SimTransport net = new SimTransport();
    List<ReplicatedNode> nodes = new ArrayList<>();
    List<FileLogStorage> stores = new ArrayList<>();
    List<LogTopics> topics = new ArrayList<>();
    List<MessagingHttp> http = new ArrayList<>();
    List<SnapshotTesting> snaps = new ArrayList<>();
    List<Member> members;

    static Member member(int wire, boolean witness) {
        return new Member(new NodeId(wire, "n" + wire), new SiteId("msg"), new InetSocketAddress("127.0.0.1", 28_000 + wire),
                1L, MemberState.ALIVE, witness);
    }

    void start(LogRetention retention) throws Exception {
        members = List.of(member(1, false), member(2, false), member(3, true));
        var voters = VoterSet.dataAndWitness(members.get(0), members.get(1), members.get(2));
        var peers = new LinkedHashMap<String, InetSocketAddress>();
        for (int i = 0; i < 2; i++) peers.put("n" + (i + 1), new InetSocketAddress("127.0.0.1", SnapshotTesting.freePort()));
        for (int i = 0; i < 3; i++) {
            var s = new FileLogStorage(dir.resolve("n" + (i + 1)).resolve("raft"), 2048);
            stores.add(s);
            nodes.add(ReplicatedNode.open(members.get(i).id(), voters, clock, s, net));
        }
        var urls = Map.of("n1", "http://n1.test", "n2", "http://n2.test");
        for (int i = 0; i < 2; i++) {
            var root = dir.resolve("n" + (i + 1));
            var sn = SnapshotTesting.create(root.resolve("msgrepl"), peers, "n" + (i + 1), 20, 5, clock::currentTimeMillis);
            snaps.add(sn);
            var t = new LogTopics(root.resolve("msg"), Durability.ASYNC, Map.of(), 2048, retention, clock::currentTimeMillis);
            t.replication(sn.replicator(nodes.get(i), Set.of("t"), urls, root.resolve("msgrepl").resolve("apply"), ELECTION,
                    HEARTBEAT, clock::nanoTime));
            topics.add(t);
            http.add(new MessagingHttp(t));
        }
        RaftReplicator.openForWitness(nodes.get(2), Set.of("t"), ELECTION, HEARTBEAT);
        for (int k = 0; k < 400 && (leader("t") < 0 || leader(LogOffsets.TOPIC) < 0); k++) step();
    }

    @AfterEach
    void stop() {
        topics.forEach(LogTopics::close);
        snaps.forEach(SnapshotTesting::close);
        nodes.forEach(ReplicatedNode::close);
        for (var s : stores) {
            try {
                s.close();
            } catch (java.io.IOException ignored) {
                // test teardown
            }
        }
    }

    void step() {
        clock.advance(Duration.ofMillis(10));
        ReplicatedNode.driveAll(nodes);
    }

    int leader(String topic) throws Exception {
        for (int i = 0; i < 2; i++) {
            var l = topics.get(i).get(topic, true);
            if (l.replica().leaderReady()) return i;
        }
        return -1;
    }

    Response call(Supplier<Response> s) throws Exception {
        var f = CompletableFuture.supplyAsync(s, r -> Thread.ofVirtual().start(r));
        for (int i = 0; i < 8000 && !f.isDone(); i++) {
            step();
            if (!f.isDone()) Thread.sleep(0, 200_000);
        }
        return f.get(10, TimeUnit.SECONDS);
    }

    void until(java.util.function.BooleanSupplier ok, String what) throws Exception {
        for (int k = 0; k < 20_000 && !ok.getAsBoolean(); k++) {
            step();
            Thread.sleep(0, 100_000);
        }
        assertTrue(ok.getAsBoolean(), what);
    }

    void cut(int i) {
        for (var m : members) if (!m.id().equals(members.get(i).id())) net.cut(members.get(i).id(), m.id());
    }

    void heal(int i) {
        for (var m : members) if (!m.id().equals(members.get(i).id())) net.heal(members.get(i).id(), m.id());
    }

    long end(int i, String topic) throws Exception {
        return topics.get(i).get(topic, true).writer().snapshot().nextOffset();
    }

    void produce(int l, int from, int n) throws Exception {
        for (int k = from; k < from + n; k++) {
            int kk = k;
            var r = call(() -> http.get(l).handle(MessagingReplicationTest.post("t", "v" + kk + "-" + "z".repeat(80), Map.of())));
            assertEquals(200, r.status());
        }
    }

    @Test
    void followerInstallsTopicAndOffsetsAndServesTheSameData() throws Exception {
        start(LogRetention.NONE);
        int l = leader("t");
        assertTrue(l >= 0);
        int f = 1 - l;
        produce(l, 0, 5);
        // Offsets are STRICT (two data copies in 2+1): committed while both data nodes are up.
        for (int g = 0; g < 30; g++) {
            int gg = g;
            Response put = null;
            for (int k = 0; k < 200 && (put == null || put.status() != 200); k++) { // __offsets may still be electing
                put = call(() -> LogOffsetsTest.put(http.get(l), "t", "g" + (gg % 3), gg % 5));
                if (put.status() != 200) for (int s = 0; s < 10; s++) step();
            }
            assertEquals(200, put.status(), LogOffsetsTest.text(put));
        }
        until(() -> safeEnd(f, "t") >= 5 && safeEnd(f, LogOffsets.TOPIC) >= 30, "follower in sync");
        cut(f);
        produce(l, 5, 60);
        assertTrue(SnapshotTesting.snapshotNow(topics.get(l).get("t", false).replica()));
        assertTrue(SnapshotTesting.snapshotNow(topics.get(l).get(LogOffsets.TOPIC, false).replica()));
        heal(f);
        long tEnd = end(l, "t"), oEnd = end(l, LogOffsets.TOPIC);
        until(() -> safeEnd(f, "t") >= tEnd && safeEnd(f, LogOffsets.TOPIC) >= oEnd, "follower caught up");
        assertEquals(1, SnapshotTesting.installs(topics.get(f).get("t", false).replica()));
        assertEquals(0, SnapshotTesting.installs(topics.get(f).get(LogOffsets.TOPIC, false).replica()), "never behind: STRICT");
        assertTrue(snaps.get(l).bytesSent() > 0);
        var mine = MessagingReplicationTest.records(http.get(f).handle(MessagingReplicationTest.fetch("t", "&max_records=1000", Map.of())));
        var theirs = MessagingReplicationTest.records(http.get(l).handle(MessagingReplicationTest.fetch("t", "&max_records=1000", Map.of())));
        assertEquals(theirs, mine);
        assertEquals(65, mine.size());
        // Group offsets survive the topic's install, and commits continue on both.
        assertEquals(topics.get(l).offsets().committed("t", "g0"), topics.get(f).offsets().committed("t", "g0"));
        assertEquals(topics.get(l).offsets().committed("t", "g2"), topics.get(f).offsets().committed("t", "g2"));
        var put = call(() -> LogOffsetsTest.put(http.get(l), "t", "g1", 64));
        assertEquals(200, put.status(), LogOffsetsTest.text(put));
        until(() -> {
            try {
                return topics.get(f).offsets().committed("t", "g1") == 64;
            } catch (java.io.IOException e) {
                return false;
            }
        }, "a commit after the install reaches the follower");
    }

    long safeEnd(int i, String topic) {
        try {
            return end(i, topic);
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void retentionPassIsOneReplicatedCut() throws Exception {
        start(new LogRetention(4096, 0));
        int l = leader("t"), f = 1 - l;
        produce(l, 0, 120);
        until(() -> safeEnd(f, "t") >= 120, "follower at 120");
        assertEquals(0, topics.get(f).enforceRetention(), "a follower proposes nothing");
        topics.get(l).enforceRetention();
        var lr = topics.get(l).get("t", false).replica();
        var fr = topics.get(f).get("t", false).replica();
        until(() -> lr.logStartOffset() > 0 && fr.logStartOffset() == lr.logStartOffset(), "the cut applied on both");
        until(() -> {
            try {
                topics.get(f).enforceRetention(); // applies any cut left pending behind a maintenance pass
                topics.get(l).enforceRetention();
                return topics.get(l).get("t", false).log().startOffset() == topics.get(f).get("t", false).log().startOffset()
                        && topics.get(l).get("t", false).log().startOffset() > 0;
            } catch (Exception e) {
                return false;
            }
        }, "the same segments deleted on both");
        assertTrue(topics.get(l).get("t", false).log().totalBytes() <= 4096 + 2048);
    }
}
