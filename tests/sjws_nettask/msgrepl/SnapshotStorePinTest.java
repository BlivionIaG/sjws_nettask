package sjws_nettask.msgrepl;

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
}
