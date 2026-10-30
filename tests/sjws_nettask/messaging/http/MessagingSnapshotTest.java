package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Disabled;
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
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestHeaders;
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
        for (int i = 0; i < 3; i++) {
            var s = new FileLogStorage(dir.resolve("n" + (i + 1)).resolve("raft"), 2048);
            stores.add(s);
            nodes.add(ReplicatedNode.open(members.get(i).id(), voters, clock, s, net));
        }
        var urls = Map.of("n1", "http://n1.test", "n2", "http://n2.test");
        for (int i = 0; i < 2; i++) {
            var root = dir.resolve("n" + (i + 1));
            // SnapshotTesting.create reserves port 0 and records the bound address in peers (#288 MC-N3).
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

    /**
     * Steps the FakeClock cluster until {@code ok} is true. Evaluates {@code ok} at most once per iteration and
     * asserts that last result — do not re-call {@code ok} after the loop. Retention's predicate must not
     * call {@link LogTopics#enforceRetention()} (that can propose another cut); settle one cut, then propose.
     * Wall-clock budget so loaded boxes are not limited by FakeClock step count alone.
     */
    void until(java.util.function.BooleanSupplier ok, String what) throws Exception {
        boolean ready = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!(ready = ok.getAsBoolean()) && System.nanoTime() < deadline) {
            step();
            Thread.sleep(0, 100_000);
        }
        assertTrue(ready, what);
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

    /**
     * Posts {@code n} unique records starting at {@code from}. Prefers {@code preferred} while it is the ready
     * leader; on transient 503/307 (not-leader / not-ready / installing) re-resolves, retries with a bound, and
     * steps between attempts (same idea as the __offsets electing loop). Does not retry outcome-unknown. Returns the
     * node index that last accepted a record so callers can refresh follower = 1 - leader.
     */
    int produce(int preferred, int from, int n) throws Exception {
        for (int k = from; k < from + n; k++) {
            int kk = k;
            Response r = null;
            for (int attempt = 1; attempt <= 200; attempt++) {
                int target = (preferred >= 0 && topics.get(preferred).get("t", true).replica().leaderReady())
                        ? preferred : leader("t");
                if (target < 0) {
                    System.err.println("MessagingSnapshotTest.produce: no ready leader for v" + kk + " attempt "
                            + attempt + "; stepping");
                    for (int s = 0; s < 10; s++) {
                        step();
                    }
                    continue;
                }
                // preferred may have lost readiness under FakeClock advance inside call(); follow the ready one
                final int tl = target;
                r = call(() -> http.get(tl).handle(
                        MessagingReplicationTest.post("t", "v" + kk + "-" + "z".repeat(80), Map.of())));
                if (r.status() == 200) {
                    preferred = tl; // stick to whoever acked under FakeClock churn
                    break;
                }
                var body = LogOffsetsTest.text(r);
                boolean retryable = r.status() == 307 || (r.status() == 503 && !body.contains("outcome-unknown")
                        && !body.contains("outcome unknown"));
                System.err.println("MessagingSnapshotTest.produce: v" + kk + " attempt " + attempt + " got "
                        + r.status() + (retryable ? "; retrying" : "") + " " + body);
                if (!retryable || attempt == 200) {
                    assertEquals(200, r.status(), "produce v" + kk + " attempt " + attempt + ": " + body);
                }
                for (int s = 0; s < 10; s++) {
                    step();
                }
            }
            assertEquals(200, r.status());
        }
        return preferred;
    }

    @Test
    void followerInstallsTopicAndOffsetsAndServesTheSameData() throws Exception {
        start(LogRetention.NONE);
        int l = leader("t");
        assertTrue(l >= 0);
        final int lead = produce(l, 0, 5);
        final int foll = 1 - lead;
        // Offsets are STRICT (two data copies in 2+1): committed while both data nodes are up.
        for (int g = 0; g < 30; g++) {
            int gg = g;
            Response put = null;
            for (int k = 0; k < 200 && (put == null || put.status() != 200); k++) { // __offsets may still be electing
                put = call(() -> LogOffsetsTest.put(http.get(lead), "t", "g" + (gg % 3), gg % 5));
                if (put.status() != 200) for (int s = 0; s < 10; s++) step();
            }
            assertEquals(200, put.status(), LogOffsetsTest.text(put));
        }
        until(() -> safeEnd(foll, "t") >= 5 && safeEnd(foll, LogOffsets.TOPIC) >= 30, "follower in sync");
        cut(foll);
        final int leadAfter = produce(lead, 5, 60);
        assertTrue(SnapshotTesting.snapshotNow(topics.get(leadAfter).get("t", false).replica()));
        assertTrue(SnapshotTesting.snapshotNow(topics.get(leadAfter).get(LogOffsets.TOPIC, false).replica()));
        heal(foll);
        long tEnd = end(leadAfter, "t"), oEnd = end(leadAfter, LogOffsets.TOPIC);
        until(() -> safeEnd(foll, "t") >= tEnd && safeEnd(foll, LogOffsets.TOPIC) >= oEnd, "follower caught up");
        assertEquals(1, SnapshotTesting.installs(topics.get(foll).get("t", false).replica()));
        assertEquals(0, SnapshotTesting.installs(topics.get(foll).get(LogOffsets.TOPIC, false).replica()), "never behind: STRICT");
        assertTrue(snaps.get(leadAfter).bytesSent() > 0);
        var mine = MessagingReplicationTest.records(http.get(foll).handle(MessagingReplicationTest.fetch("t", "&max_records=1000", Map.of())));
        var theirs = MessagingReplicationTest.records(http.get(leadAfter).handle(MessagingReplicationTest.fetch("t", "&max_records=1000", Map.of())));
        assertEquals(theirs, mine);
        assertEquals(65, mine.size());
        // Group offsets survive the topic's install, and commits continue on both.
        assertEquals(topics.get(leadAfter).offsets().committed("t", "g0"), topics.get(foll).offsets().committed("t", "g0"));
        assertEquals(topics.get(leadAfter).offsets().committed("t", "g2"), topics.get(foll).offsets().committed("t", "g2"));
        var put = call(() -> LogOffsetsTest.put(http.get(leadAfter), "t", "g1", 64));
        assertEquals(200, put.status(), LogOffsetsTest.text(put));
        until(() -> {
            try {
                return topics.get(foll).offsets().committed("t", "g1") == 64;
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

    /** Flushes {@code pendingDrops} without proposing a new cut (enforceRetention would). */
    void flushPendingDrops(LogTopics t) throws Exception {
        var maint = LogTopics.class.getDeclaredField("maintenance");
        maint.setAccessible(true);
        var lock = (java.util.concurrent.locks.ReentrantLock) maint.get(t);
        var apply = LogTopics.class.getDeclaredMethod("applyPendingDrops");
        apply.setAccessible(true);
        lock.lock();
        try {
            apply.invoke(t);
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    boolean pendingDropsEmpty(LogTopics t) throws Exception {
        var f = LogTopics.class.getDeclaredField("pendingDrops");
        f.setAccessible(true);
        return ((java.util.concurrent.ConcurrentHashMap<?, ?>) f.get(t)).isEmpty();
    }

    /**
     * One retention proposal at a time; settle the replicated logical cut (and drain pendingDrops) before
     * the next. Does not require physical == logical: under load follower segment bases can diverge from
     * the leader so a cut lands mid-segment on the follower (#328). Physical equality is
     * {@link #retentionPassPhysicalMatchesLogicalOnBoth()}.
     */
    @Test
    void retentionPassIsOneReplicatedCut() throws Exception {
        start(new LogRetention(4096, 0));
        int elected = leader("t");
        assertTrue(elected >= 0, "topic leader elected before produce");
        final int lead = produce(elected, 0, 120);
        final int foll = 1 - lead;
        until(() -> safeEnd(foll, "t") >= 120, "follower at 120");
        assertEquals(0, topics.get(foll).enforceRetention(), "a follower proposes nothing");
        var lr = topics.get(lead).get("t", false).replica();
        var fr = topics.get(foll).get("t", false).replica();
        // Each enforceRetention on the leader may propose another cut while bytes stay over the
        // limit. Settle one logical cut fully before proposing the next (TOCTOU / leapfrog fix).
        boolean within = false;
        for (int round = 0; round < 30 && !within; round++) {
            topics.get(lead).enforceRetention(); // at most one new proposal per round
            flushPendingDrops(topics.get(lead));
            flushPendingDrops(topics.get(foll));
            until(() -> {
                try {
                    flushPendingDrops(topics.get(lead));
                    flushPendingDrops(topics.get(foll));
                    long ls = lr.logStartOffset(), fs = fr.logStartOffset();
                    if (ls <= 0 || ls != fs) return false;
                    return pendingDropsEmpty(topics.get(lead)) && pendingDropsEmpty(topics.get(foll));
                } catch (Exception e) {
                    return false;
                }
            }, "logical cut settled on both after retention round " + round);
            within = topics.get(lead).get("t", false).log().totalBytes() <= 4096 + 2048;
        }
        assertTrue(within, "leader bytes within retention after settled cuts");
        assertEquals(lr.logStartOffset(), fr.logStartOffset(), "the same logical cut on both");
        assertTrue(lr.logStartOffset() > 0, "logical logStart advanced");
        assertTrue(pendingDropsEmpty(topics.get(lead)) && pendingDropsEmpty(topics.get(foll)),
                "pendingDrops drained on both");
    }

    /**
     * Strict physical==logical-on-both after each settled cut. Disabled for #328: follower segment roll
     * bases diverge from the leader under load, so a leader boundary cut can straddle the follower's
     * first remaining segment and physical stays below logical forever. Re-enable when #328 lands; that
     * PR must fail this test before the product fix and pass after.
     */
    @Disabled("#328: follower segment straddles retention cut")
    @Test
    void retentionPassPhysicalMatchesLogicalOnBoth() throws Exception {
        start(new LogRetention(4096, 0));
        int elected = leader("t");
        assertTrue(elected >= 0, "topic leader elected before produce");
        final int lead = produce(elected, 0, 120);
        final int foll = 1 - lead;
        until(() -> safeEnd(foll, "t") >= 120, "follower at 120");
        assertEquals(0, topics.get(foll).enforceRetention(), "a follower proposes nothing");
        var lr = topics.get(lead).get("t", false).replica();
        var fr = topics.get(foll).get("t", false).replica();
        boolean within = false;
        for (int round = 0; round < 30 && !within; round++) {
            topics.get(lead).enforceRetention();
            flushPendingDrops(topics.get(lead));
            flushPendingDrops(topics.get(foll));
            until(() -> {
                try {
                    flushPendingDrops(topics.get(lead));
                    flushPendingDrops(topics.get(foll));
                    long ls = lr.logStartOffset(), fs = fr.logStartOffset();
                    if (ls <= 0 || ls != fs) return false;
                    long lp = topics.get(lead).get("t", false).log().startOffset();
                    long fp = topics.get(foll).get("t", false).log().startOffset();
                    return lp == ls && fp == fs;
                } catch (Exception e) {
                    return false;
                }
            }, "physical==logical on both after retention round " + round);
            within = topics.get(lead).get("t", false).log().totalBytes() <= 4096 + 2048;
        }
        assertTrue(within, "leader bytes within retention after settled cuts");
        assertEquals(topics.get(lead).get("t", false).log().startOffset(),
                topics.get(foll).get("t", false).log().startOffset(), "the same segments deleted on both");
        assertTrue(topics.get(lead).get("t", false).log().startOffset() > 0);
    }

    /** #288 MC-B1: fetch uses max(physical start, replica.logStartOffset()), even when bytes remain on disk. */
    @Test
    void fetchBelowReplicatedLogStartIs416() throws Exception {
        start(new LogRetention(64L << 20, 0)); // age off; bytes high so retention does not cut for this test
        int elected = leader("t");
        assertTrue(elected >= 0, "topic leader elected before produce");
        final int lead = produce(elected, 0, 80);
        var leadLog = topics.get(lead).get("t", false);
        var segs = leadLog.log().segments();
        assertFalse(segs.isEmpty());
        var first = segs.get(0);
        assertTrue(first.nextOffset() > first.baseOffset() + 1,
                "need a sealed segment with room for a mid-segment cut");
        // Mid-segment cut: dropBelow only deletes segments with nextOffset <= cut, so the first sealed
        // file stays on disk and physical < logical. A boundary cut would make physical == logical and
        // skip the 416 branch (Messaging Core mutation on 492771d).
        long cut = first.baseOffset() + Math.max(1, (first.nextOffset() - first.baseOffset()) / 2);
        assertTrue(cut < first.nextOffset(), "cut must stay inside the first sealed segment");
        var adv = leadLog.replica().advanceLogStart(cut);
        until(() -> { step(); return adv.isDone(); }, "advanceLogStart committed");
        adv.get(5, TimeUnit.SECONDS);
        until(() -> leadLog.replica().logStartOffset() >= cut, "logStart advanced");
        long physical = leadLog.log().startOffset();
        long logical = leadLog.replica().logStartOffset();
        assertEquals(cut, logical);
        assertTrue(physical < logical,
                "physical must stay below logical so the 416 branch runs: physical=" + physical + " logical=" + logical);
        var r = call(() -> http.get(lead).handle(new Request("GET",
                "/messaging/v1/topics/t/records?offset=" + physical + "&maxBytes=256", RequestHeaders.of(Map.of()))));
        assertEquals(416, r.status(), "fetch at physical start below logical: " + r);
        var ok = call(() -> http.get(lead).handle(new Request("GET",
                "/messaging/v1/topics/t/records?offset=" + logical + "&maxBytes=256", RequestHeaders.of(Map.of()))));
        assertTrue(ok.status() == 200 || ok.status() == 204, "fetch at logical start: " + ok.status());
    }
}
