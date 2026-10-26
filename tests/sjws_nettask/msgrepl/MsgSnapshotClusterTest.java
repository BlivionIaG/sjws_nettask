package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sjws_nettask.cluster.GroupOptions;

/**
 * #270 on a real 2+1 cluster: snapshot, truncate, tail catch-up, install-snapshot, retention, restarts and crashes at
 * every step. Each test names the binding constraint (C1-C7) or required scenario it pins.
 */
@Timeout(120)
class MsgSnapshotClusterTest {

    @TempDir Path dir;
    SnapshotCluster c;

    @AfterEach
    void stop() throws Exception {
        if (c != null) c.close();
    }

    SnapshotCluster start(long every, long tail) throws Exception {
        c = new SnapshotCluster(dir, SnapshotCluster.settings(every, tail), 2048, 2048);
        return c;
    }

    String payload(int n) {
        return "x".repeat(60) + n;
    }

    /** Produces {@code n} batches from producer {@code pid}, sequences {@code from..from+n-1}; returns the end. */
    long produce(String pid, long from, int n) throws Exception {
        long end = -1;
        for (int k = 0; k < n; k++) end = c.produce(pid, from + k, payload(k)) + 1;
        return end;
    }

    MsgSnapshot recorded(int i) throws IOException {
        var s = c.data[i].storage.loadSnapshot(SnapshotCluster.GROUP, GroupOptions.DEFAULT_MAX_SNAPSHOT_BYTES);
        return s == null ? null : MsgSnapshot.decode(s.data());
    }

    static byte[] fileBytes(Path p) throws IOException {
        return Files.readAllBytes(p);
    }

    // ---- C1: the snapshot point is fsynced; (index, term) -> offset is persisted

    @Test
    void snapshotIsAtTheFsyncedOffsetAndTheMappingIsDurable() throws Exception {
        start(20, 5);
        int l = c.leader();
        long end = produce("pid1", 0, 30);
        c.awaitEnd(1 - l, end);
        var a = c.data[l].replica.lastAppliedForTest();
        assertTrue(c.data[l].snapshot());
        assertTrue(c.data[l].writer.snapshot().durableOffset() >= a.end(), "ledger fsynced through the snapshot point");
        var m = recorded(l);
        assertNotNull(m, "the manifest is the group's durable snapshot");
        assertEquals(a.index(), m.index());
        assertEquals(a.term(), m.term());
        assertEquals(a.end(), m.endOffset(), "the persisted (index, term) -> offset mapping");
        assertEquals(end, m.endOffset());
    }

    // ---- C5 + required: the log stays bounded under sustained produce; a tail is kept behind the snapshot

    @Test
    void logStaysBoundedUnderSustainedProduceAndKeepsTheTail() throws Exception {
        start(20, 5);
        int l = c.leader();
        long produced = 0, maxEntryBytes = 0, maxRaftDir = 0;
        for (int round = 0; round < 24; round++) {
            long end = produce("pid1", round * 25L, 25);
            produced += 25 * 70L;
            c.awaitEnd(1 - l, end);
            assertTrue(c.data[l].snapshot());
            assertTrue(c.data[1 - l].snapshot());
            maxEntryBytes = Math.max(maxEntryBytes, c.data[l].storage.entryBytes(SnapshotCluster.GROUP));
            maxRaftDir = Math.max(maxRaftDir, SnapshotCluster.dirBytes(c.data[l].root.resolve("raft")));
        }
        assertTrue(produced > 40_000);
        assertTrue(maxEntryBytes < 12_000, "consensus log entries bounded: " + maxEntryBytes);
        assertTrue(maxRaftDir < 24_000, "consensus log files bounded: " + maxRaftDir);
        try (var s = Files.list(c.data[l].root.resolve("raft"))) {
            assertTrue(s.filter(p -> p.toString().endsWith(".seg")).count() <= 8, "sealed consensus files bounded");
        }
        // C5: exactly tailEntries kept behind the snapshot point.
        var m = recorded(l);
        assertNotEquals(0, c.data[l].storage.termAt(SnapshotCluster.GROUP, m.index() - 5 + 1));
        assertEquals(0, c.data[l].storage.termAt(SnapshotCluster.GROUP, m.index() - 5));
        // Pins bounded too: at most KEEP per topic.
        try (var s = Files.list(c.data[l].root.resolve("msgrepl").resolve("snap").resolve("t"))) {
            assertTrue(s.count() <= SnapshotStore.KEEP);
        }
    }

    // ---- required: a lagging follower catches up via install-snapshot; C2 dedup survives; C4 files ship as they are

    @Test
    void laggingFollowerCatchesUpViaInstallSnapshotAndKeepsDedup() throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 10);
        c.awaitEnd(f, end);
        c.cut(f);
        end = produce("pid1", 10, 80);
        assertTrue(c.data[l].snapshot());
        var m = recorded(l);
        assertTrue(c.data[l].storage.termAt(SnapshotCluster.GROUP, 11) == 0, "the follower's next entry was truncated");
        c.heal(f);
        c.awaitEnd(f, end);
        for (int k = 0; k < 2000 && c.data[f].replica.installs() == 0; k++) c.step();
        assertEquals(1, c.data[f].replica.installs(), "served by install-snapshot");
        assertTrue(c.data[l].server.bytesSent.get() > 0, "data came over the side channel");
        assertEquals(c.checksum(l, 0), c.checksum(f, 0), "same offsets, same records");
        // C4: the leader's files arrived as they are (compaction never re-done on the follower).
        for (var sf : c.data[l].log.segmentFiles()) {
            if (sf.active() || sf.nextOffset() > m.endOffset()) continue;
            assertArrayEquals(fileBytes(sf.file()), fileBytes(c.data[f].root.resolve("ledger").resolve(sf.file().getFileName())));
        }
        // C2: the install restored the dedup table (seq 0-89 were never applied on the follower one by one).
        assertTrue(c.data[f].dedup.duplicate("pid1", 89));
        assertTrue(c.data[f].dedup.restores >= 1);
        // Live again after the install: new entries apply on top.
        end = produce("pid1", 90, 3);
        c.awaitEnd(f, end);
        assertEquals(c.checksum(l, 0), c.checksum(f, 0));
        assertTrue(c.data[f].dedup.duplicate("pid1", 92));
        assertFalse(Files.exists(c.data[f].root.resolve("msgrepl").resolve("apply").resolve("t.install")));
        assertFalse(Files.exists(c.data[f].root.resolve("msgrepl").resolve("apply").resolve("t.staging")));
    }

    // ---- required + C5: a briefly lagging follower catches up from the tail, no install

    @Test
    void brieflyLaggingFollowerCatchesUpFromTheTail() throws Exception {
        start(20, 50);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 10);
        c.awaitEnd(f, end);
        c.cut(f);
        end = produce("pid1", 10, 20);
        assertTrue(c.data[l].snapshot());
        c.heal(f);
        c.awaitEnd(f, end);
        assertEquals(0, c.data[f].replica.installs());
        assertEquals(0, c.data[l].server.bytesSent.get());
        assertEquals(c.checksum(l, 0), c.checksum(f, 0));
    }

    // ---- required + C2: a restart after truncation keeps the same offsets, and dedup

    @Test
    void restartAfterTruncationKeepsOffsetsAndDedup() throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 40);
        c.awaitEnd(f, end);
        assertTrue(c.data[l].snapshot());
        assertTrue(c.data[f].snapshot());
        end = produce("pid1", 40, 4); // after the snapshot: replayed from the tail on restart
        c.awaitEnd(f, end);
        var before = c.checksum(l, 0);
        for (int i : new int[] {f, l}) {
            c.data[i].close();
            c.data[i].open();
            for (int k = 0; k < 400 && c.leader() < 0; k++) c.step();
            for (int k = 0; k < 2000 && !c.data[i].dedup.duplicate("pid1", 43); k++) c.step(); // replay is asynchronous
            assertTrue(c.data[i].end() >= end, "the ledger is not re-applied: " + c.data[i].end());
            assertEquals(before, c.checksum(i, 0));
            assertTrue(c.data[i].dedup.duplicate("pid1", 43), "snapshot state + tail replay rebuilt the table");
            assertTrue(c.data[i].dedup.restores >= 1);
        }
        long base = c.produce("pid1", 44, "after");
        assertEquals(end, base, "offsets continue where they were");
        c.awaitEnd(0, base + 1);
        c.awaitEnd(1, base + 1);
        assertEquals(c.checksum(0, 0), c.checksum(1, 0));
    }

    // ---- required: kill mid-snapshot

    @ParameterizedTest
    @ValueSource(strings = {"snapshot-pinned", "snapshot-recorded", "stamps-written"})
    void crashMidSnapshotRecovers(String point) throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 40);
        c.awaitEnd(f, end);
        c.data[l].replica.faultHook = at -> {
            if (at.equals(point)) throw new IllegalStateException("crash");
        };
        assertThrows(IOException.class, () -> c.data[l].snapshot());
        var before = c.checksum(l, 0);
        c.data[l].close();
        c.data[l].open();
        for (int k = 0; k < 400 && c.leader() < 0; k++) c.step();
        assertEquals(before, c.checksum(l, 0));
        long base = c.produce("pid1", 40, "after");
        assertEquals(end, base);
        int nl = c.leader();
        c.awaitEnd(1 - nl, base + 1);
        c.awaitEnd(nl, base + 1);
        boolean later = false;
        for (int k = 0; k < 2000 && !later; k++) { // the new entry's apply completes on the writer thread
            later = c.data[l].snapshot();
            if (!later) c.step();
        }
        assertTrue(later, "a later snapshot succeeds");
        assertEquals(c.checksum(0, 0), c.checksum(1, 0));
        assertTrue(c.data[l].dedup.duplicate("pid1", 40));
        try (var s = Files.list(c.data[l].root.resolve("msgrepl").resolve("snap").resolve("t"))) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no half-written pin");
        }
    }

    // ---- required: kill mid-install

    @ParameterizedTest
    @ValueSource(strings = {"install-marked", "install-fetched", "install-swapped", "stamps-written"})
    void crashMidInstallRedoesItOnRestart(String point) throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 10);
        c.awaitEnd(f, end);
        c.cut(f);
        end = produce("pid1", 10, 60);
        assertTrue(c.data[l].snapshot());
        var hit = new java.util.concurrent.atomic.AtomicBoolean();
        c.data[f].replica.faultHook = at -> {
            if (at.equals(point) && c.data[f].replica.installPending()) {
                hit.set(true);
                throw new IllegalStateException("crash");
            }
        };
        c.heal(f);
        for (int k = 0; k < 20_000 && !hit.get(); k++) {
            c.step();
            Thread.sleep(0, 100_000);
        }
        assertTrue(hit.get(), "the install reached " + point);
        assertTrue(c.data[f].replica.installPending(), "nothing applies on a half-installed ledger");
        assertTrue(Files.exists(c.data[f].root.resolve("msgrepl").resolve("apply").resolve("t.install")));
        c.data[f].close();
        c.data[f].open();
        c.awaitEnd(f, end);
        for (int k = 0; k < 2000 && c.data[f].replica.installPending(); k++) c.step();
        assertFalse(c.data[f].replica.installPending());
        assertEquals(c.checksum(l, 0), c.checksum(f, 0));
        assertTrue(c.data[f].dedup.duplicate("pid1", 60));
        end = produce("pid1", 70, 2);
        c.awaitEnd(f, end);
        assertEquals(c.checksum(l, 0), c.checksum(f, 0));
    }

    // ---- C3 + required: retention is one replicated decision, applied the same on every replica

    @Test
    void retentionIsDeterministicAcrossReplicas() throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 120);
        c.awaitEnd(f, end);
        var segs = c.data[l].log.segments();
        assertTrue(segs.size() > 4, "several segments: " + segs.size());
        long cut = segs.get(2).nextOffset();
        c.await(c.data[l].replica.advanceLogStart(cut));
        for (int k = 0; k < 2000 && c.data[f].replica.logStartOffset() < cut; k++) c.step();
        assertEquals(cut, c.data[l].replica.logStartOffset());
        assertEquals(cut, c.data[f].replica.logStartOffset());
        assertEquals(c.data[l].log.startOffset(), c.data[f].log.startOffset());
        assertEquals(cut, c.data[f].log.startOffset());
        assertEquals(segBases(l), segBases(f), "the same whole segments were deleted on both");
        assertEquals(c.checksum(l, cut), c.checksum(f, cut));
        // A follower does not decide: its own call is refused.
        assertTrue(c.data[f].replica.advanceLogStart(end).isCompletedExceptionally());
        // Restart keeps the decision.
        c.data[f].close();
        c.data[f].open();
        assertEquals(cut, c.data[f].replica.logStartOffset());
        // A lagging follower drops everything below the leader's log start on install.
        c.cut(f);
        end = produce("pid1", 120, 60);
        long cut2 = c.data[l].log.segments().get(3).nextOffset();
        c.await(c.data[l].replica.advanceLogStart(cut2));
        assertTrue(c.data[l].snapshot());
        c.heal(f);
        c.awaitEnd(f, end);
        for (int k = 0; k < 2000 && c.data[f].replica.installs() == 0; k++) c.step();
        assertEquals(1, c.data[f].replica.installs());
        assertEquals(cut2, c.data[f].replica.logStartOffset());
        assertEquals(c.data[l].log.startOffset(), c.data[f].log.startOffset());
        assertTrue(c.data[f].log.startOffset() >= cut2 - 30, "nothing far below the leader's log start is kept");
        assertEquals(c.checksum(l, 0), c.checksum(f, 0));
    }

    List<Long> segBases(int i) throws IOException {
        var out = new ArrayList<Long>();
        for (var s : c.data[i].log.segments()) out.add(s.baseOffset());
        return out;
    }

    // ---- C6 + required: the witness keeps term, vote and a cursor; no segments, no snapshot data

    @Test
    void witnessKeepsNoSegments() throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        long end = produce("pid1", 0, 60);
        c.awaitEnd(f, end);
        assertTrue(c.data[l].snapshot());
        assertTrue(c.data[f].snapshot());
        for (int k = 0; k < 50; k++) c.step();
        var w = c.witnessStorage;
        var g = SnapshotCluster.GROUP;
        assertEquals(0, w.lastIndex(g), "no entries");
        assertEquals(0, w.entryBytes(g));
        assertTrue(w.witnessIndex(g) >= recorded(l).index(), "its cursor is at or past the snapshot point");
        assertTrue(w.witnessTerm(g) > 0);
        assertTrue(w.hasHardState(g));
        assertEquals(null, w.loadSnapshot(g, GroupOptions.DEFAULT_MAX_SNAPSHOT_BYTES), "no snapshot image");
        var wdir = dir.resolve("w3");
        try (var s = Files.walk(wdir)) {
            var names = s.filter(Files::isRegularFile).map(p -> wdir.relativize(p).toString()).toList();
            assertTrue(names.stream().allMatch(n -> n.startsWith("raft")), "only the consensus state: " + names);
            assertTrue(names.stream().noneMatch(n -> n.endsWith(".log") && n.contains("ledger")), names.toString());
        }
        assertTrue(SnapshotCluster.dirBytes(wdir) < 8192, "a few cursors: " + SnapshotCluster.dirBytes(wdir));
    }

    /** A second state section (the slot #278's producer table takes): captured at the index, restored on install. */
    static final class Section2 implements sjws_nettask.messaging.ledger.ReplicaHooks.ReplicatedState {
        final java.util.concurrent.atomic.AtomicLong applied = new java.util.concurrent.atomic.AtomicLong();
        volatile byte[] restored;

        @Override public int section() { return MsgSnapshot.PRODUCERS; }
        @Override public byte[] snapshotState() { return Long.toString(applied.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        @Override public void restoreState(byte[] image) { restored = image; }
    }

    @Test
    void extraStateSectionIsCapturedAtTheIndexAndRestoredOnInstall() throws Exception {
        start(20, 5);
        int l = c.leader(), f = 1 - l;
        var lead = new Section2();
        var follow = new Section2();
        c.data[l].replica.addState(lead);
        c.data[f].replica.addState(follow);
        assertThrows(IllegalArgumentException.class, () -> c.data[l].replica.addState(new Section2()), "one state per section");
        long end = produce("pid1", 0, 10);
        c.awaitEnd(f, end);
        c.cut(f);
        end = produce("pid1", 10, 60);
        lead.applied.set(4242);
        assertTrue(c.data[l].snapshot());
        var m = recorded(l);
        assertEquals("4242", new String(m.section(MsgSnapshot.PRODUCERS), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(end, m.endOffset(), "the next offset at the index travels with the sections");
        assertNotNull(m.section(MsgSnapshot.APP_STATE));
        c.heal(f);
        c.awaitEnd(f, end);
        for (int k = 0; k < 2000 && follow.restored == null; k++) c.step();
        assertEquals("4242", new String(follow.restored, java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(c.data[f].dedup.duplicate("pid1", 69), "section 1 restored alongside");
    }
}
