package sjws_nettask.msgrepl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.SegmentedLog;

/** #288 Codex findings on SnapshotStore pin cuts and SnapshotTransfer pin release. */
@Timeout(60)
class SnapshotStorePinTest {

    @TempDir Path dir;

    /**
     * Codex P1: cutting a compacted segment must keep a marker bounded to the snapshot end so recover()
     * advances across gaps (empty prefix → nextOffset = marker), not reopen at base.
     */
    @Test
    void cutOfCompactedSegmentKeepsBoundedMarker() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("ledger"), 512)) {
            for (int i = 0; i < 8; i++) {
                log.append(List.of(("r" + i + "-" + "x".repeat(80)).getBytes(StandardCharsets.UTF_8)), i);
            }
            log.force();
            while (log.segmentFiles().size() < 2) {
                log.append(List.of(("pad-" + log.nextOffset() + "-" + "x".repeat(80)).getBytes(StandardCharsets.UTF_8)), 0);
            }
            log.force();
            long base = log.segmentFiles().getFirst().baseOffset();
            long next = log.segmentFiles().getFirst().nextOffset();
            // Keep only the last offset of the first segment (Codex: [0,3) → keep 2).
            long keep = next - 1;
            log.prepareCompaction(base, o -> o == keep);
            log.commitCompaction(base);
            assertTrue(log.segments().getFirst().compacted());
            assertEquals(next, log.segments().getFirst().nextOffset());

            var store = new SnapshotStore(dir.resolve("snap"));
            // Pin ending at keep: cut falls at the sole remaining frame → empty prefix bytes.
            var snap = store.pin("t", log, 1, 1, keep, 0, List.of(), MsgSnapshot.MIN_CHUNK);
            assertNotNull(snap);
            var seg = snap.segments().getFirst();
            assertEquals(base, seg.base());
            assertEquals(keep, seg.next());
            assertEquals(keep, seg.markerNext(), "marker bounded to snapshot end, not cleared");
            assertEquals(0, seg.bytes(), "cut before the kept frame yields an empty file");

            // Install path: write marker + empty log, reopen as Segment would.
            var staged = dir.resolve("staged");
            Files.createDirectories(staged);
            Files.write(staged.resolve(SnapshotStore.name(base)), new byte[0]);
            Files.write(staged.resolve(String.format("%020d.compacted", base)),
                    ByteBuffer.allocate(8).putLong(seg.markerNext()).array());
            try (var installed = SegmentedLog.open(dir.resolve("installed"), 512)) {
                installed.replaceWith(staged);
                assertEquals(keep, installed.segments().getFirst().nextOffset(),
                        "recover with marker advances to snapshot end across the empty prefix");
            }
        }
    }
    /** #288 Codex P1: pinInstalled publishes the consensus manifest from a verified staging tree. */
    @Test
    void pinInstalledPublishesConsensusManifestFromStaging() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("ledger"), 4096)) {
            for (int i = 0; i < 30; i++) {
                log.append(List.of(("r" + i + "-" + "y".repeat(100)).getBytes(StandardCharsets.UTF_8)), i);
            }
            log.force();
            var store = new SnapshotStore(dir.resolve("snap"));
            var snap = store.pin("t", log, 5, 1, log.nextOffset(), 0, List.of(), MsgSnapshot.MIN_CHUNK);
            assertNotNull(snap);
            var staged = dir.resolve("staged");
            Files.createDirectories(staged);
            for (var seg : snap.segments()) {
                Files.copy(dir.resolve("snap").resolve("t").resolve("5").resolve(SnapshotStore.name(seg.base())),
                        staged.resolve(SnapshotStore.name(seg.base())));
                if (seg.markerNext() >= 0) {
                    Files.copy(dir.resolve("snap").resolve("t").resolve("5")
                            .resolve(String.format("%020d.compacted", seg.base())),
                            staged.resolve(String.format("%020d.compacted", seg.base())));
                }
            }
            store.dropAboveForTest("t", -1); // clear pins
            assertTrue(store.pinned("t").isEmpty());
            var published = store.pinInstalled(snap, staged);
            assertNotNull(published);
            assertEquals(List.of(5L), store.pinned("t"));
            assertArrayEquals(snap.encode(), Files.readAllBytes(dir.resolve("snap").resolve("t").resolve("5").resolve(SnapshotStore.MANIFEST)));
        }
    }


    /** #288 Codex P1: pin must not publish a manifest that decode would reject after retention deletes a prefix. */
    @Test
    void pinReturnsNullWhenRetentionDropsCoverage() throws Exception {
        try (var log = SegmentedLog.open(dir.resolve("cov"), 512)) {
            for (int i = 0; i < 20; i++) {
                log.append(List.of(("r" + i + "-" + "z".repeat(80)).getBytes(StandardCharsets.UTF_8)), i);
            }
            log.force();
            while (log.segmentFiles().size() < 2) {
                log.append(List.of(("pad-" + log.nextOffset() + "-" + "z".repeat(80)).getBytes(StandardCharsets.UTF_8)), 0);
            }
            log.force();
            long logStart = 0;
            long end = log.nextOffset();
            // Delete the first sealed segment as retention would.
            long base = log.segmentFiles().getFirst().baseOffset();
            log.deleteSegment(base);
            var store = new SnapshotStore(dir.resolve("cov-snap"));
            assertEquals(null, store.pin("t", log, 1, 1, end, logStart, List.of(), MsgSnapshot.MIN_CHUNK),
                    "incomplete coverage must not publish");
        }
    }

    /**
     * #288 Codex 4420344746: after hard-linking the active segment, a roll+compaction can replace the live
     * inode; cut sizing must scan the pinned link, not {@code log.positionOf} on the replacement.
     */
    @Test
    void activeCutUsesPinnedInodeNotLivePosition() throws Exception {
        byte[] rec = "r0".getBytes(StandardCharsets.UTF_8);
        long frameBytes;
        try (var probe = SegmentedLog.open(dir.resolve("probe"), 1 << 20)) {
            probe.append(List.of(rec), 0);
            probe.force();
            frameBytes = Files.size(probe.segmentFiles().getFirst().file());
        }
        // Exactly three frames fill the segment; the next append seals without writing into that inode.
        long segBytes = frameBytes * 3;
        try (var log = SegmentedLog.open(dir.resolve("active-cut"), segBytes)) {
            for (int i = 0; i < 3; i++) {
                log.append(List.of(("r" + i).getBytes(StandardCharsets.UTF_8)), i);
            }
            log.force();
            assertEquals(1, log.segmentFiles().size());
            assertTrue(log.segmentFiles().getFirst().active());
            assertEquals(3, log.nextOffset());
            long linkSizeBefore = Files.size(log.segmentFiles().getFirst().file());
            var store = new SnapshotStore(dir.resolve("active-cut-snap"));
            SnapshotStore.afterSegmentLink = () -> {
                try {
                    log.append(List.of("r3".getBytes(StandardCharsets.UTF_8)), 3); // rolls: sealed inode unchanged
                    log.force();
                    assertTrue(Files.size(log.segmentFiles().getFirst().file()) == linkSizeBefore
                            || !log.segmentFiles().getFirst().active());
                    log.prepareCompaction(0, o -> o == 2);
                    log.commitCompaction(0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
            try {
                var snap = store.pin("t", log, 1, 1, 3, 0, List.of(), MsgSnapshot.MIN_CHUNK);
                assertNotNull(snap);
                assertEquals(3, snap.endOffset());
                var seg = snap.segments().getFirst();
                assertEquals(0, seg.base());
                assertEquals(3, seg.next());
                var pinned = dir.resolve("active-cut-snap").resolve("t").resolve("1").resolve(SnapshotStore.name(0));
                var cut = Files.readAllBytes(pinned);
                var staged = dir.resolve("active-cut-staged");
                Files.createDirectories(staged);
                Files.write(staged.resolve(SnapshotStore.name(0)), java.util.Arrays.copyOf(cut, (int) seg.bytes()));
                try (var installed = SegmentedLog.open(dir.resolve("active-cut-installed"), segBytes)) {
                    installed.replaceWith(staged);
                    assertEquals(3, installed.nextOffset(), "pinned three-frame image must survive reopen");
                }
            } finally {
                SnapshotStore.afterSegmentLink = null;
            }
        }
    }

}
