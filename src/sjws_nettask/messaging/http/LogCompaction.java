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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sjws_nettask.messaging.ledger.SegmentedLog;

/**
 * Key compaction for plain log topics (docs/design/durable-log-plan.md §1). A record's key is its leading
 * {@code [len:int][key]} field; a record too short for that field has no key and is always kept.
 *
 * <p>Only sealed segments are inputs or outputs: they were fsynced when sealed, so a key's newer value never lives only
 * in the page cache when its older value is dropped. The unit kept or dropped is the whole {@link
 * sjws_nettask.messaging.ledger.RecordBatch}, because a frame's offsets are contiguous and its CRC covers all its records:
 * a batch stays if any of its records is the last value of its key (or has no key). Rewrites reuse the delivery
 * partition's crash-tested protocol ({@link SegmentedLog#prepareCompaction} off-lock: copy, fsync, marker; then
 * {@link SegmentedLog#commitCompaction}: atomic rename), the second step under the caller's lock that excludes readers.
 */
public final class LogCompaction {

    /** Outcome of one pass over one topic. */
    public record Result(int segmentsRewritten, long bytesBefore, long bytesAfter, long batchesDropped) {
        static final Result NONE = new Result(0, 0, 0, 0);

        Result plus(Result o) {
            return new Result(segmentsRewritten + o.segmentsRewritten, bytesBefore + o.bytesBefore, bytesAfter + o.bytesAfter,
                    batchesDropped + o.batchesDropped);
        }
    }

    /** One prepared rewrite, committed by the caller under its write lock. */
    public record Prepared(long baseOffset, long bytesBefore, long bytesAfter, long batchesDropped) { }

    private LogCompaction() {
    }

    /** The record's key (a view of {@code record}), or null when the record has no {@code [len:int][key]} prefix. */
    public static ByteBuffer key(byte[] record) {
        if (record.length < 4) return null;
        int n = ByteBuffer.wrap(record).getInt(0);
        if (n < 0 || n > record.length - 4) return null;
        return ByteBuffer.wrap(record, 4, n).slice();
    }

    /**
     * Phase 1 for every sealed segment that would shrink: builds the last offset per key over the sealed segments, then
     * writes each shrinking segment's compacted copy and marker. Needs no lock against fetches or the writer; the caller
     * must keep retention and other compactions of this log out (a segment must not be deleted mid-pass).
     */
    public static List<Prepared> prepare(SegmentedLog log) throws IOException {
        return prepare(log, Long.MAX_VALUE);
    }

    /**
     * As {@link #prepare(SegmentedLog)}, limited to sealed segments that end at or below {@code limit} (the topic's
     * consumer-offset floor, durable-log-plan §1 retention interplay): a consumer that has not fetched past
     * {@code limit} never sees a key's history disappear under it.
     */
    public static List<Prepared> prepare(SegmentedLog log, long limit) throws IOException {
        var sealed = new ArrayList<SegmentedLog.SegmentInfo>();
        for (var s : log.segments()) if (!s.active() && s.nextOffset() <= limit) sealed.add(s);
        if (sealed.isEmpty()) return List.of();
        long end = sealed.getLast().nextOffset();
        Map<ByteBuffer, Long> last = new HashMap<>();
        try {
            log.scanFrom(sealed.getFirst().baseOffset(), rb -> {
                if (rb.baseOffset() >= end) throw new StopScan(); // the active segment is not an input
                for (int i = 0; i < rb.count(); i++) {
                    var k = key(rb.records().get(i));
                    if (k != null) last.put(k, rb.baseOffset() + i);
                }
            });
        } catch (StopScan e) {
            // reached the active segment
        }
        var out = new ArrayList<Prepared>();
        for (var seg : sealed) {
            Set<Long> keep = new HashSet<>();
            long[] batches = {0};
            try {
                log.scanFrom(seg.baseOffset(), rb -> {
                    if (rb.baseOffset() >= seg.nextOffset()) throw new StopScan();
                    batches[0]++;
                    for (int i = 0; i < rb.count(); i++) {
                        var k = key(rb.records().get(i));
                        if (k == null || last.get(k) == rb.baseOffset() + i) {
                            keep.add(rb.baseOffset());
                            return;
                        }
                    }
                });
            } catch (StopScan e) {
                // reached the next segment
            }
            if (keep.size() == batches[0]) continue;
            long after = log.prepareCompaction(seg.baseOffset(), keep::contains);
            out.add(new Prepared(seg.baseOffset(), seg.bytes(), after, batches[0] - keep.size()));
        }
        return out;
    }

    /** Ends a {@link SegmentedLog#scanFrom} at a segment boundary without reading the rest of the log. */
    private static final class StopScan extends RuntimeException {
        StopScan() {
            super(null, null, false, false);
        }
    }

    /**
     * Parses {@code sjws.messaging.compaction.topics}: a comma-separated list of topic names. Throws
     * {@link IllegalArgumentException} naming a bad entry.
     */
    static Set<String> parseTopics(String list) {
        var out = new HashSet<String>();
        for (var part : list.split(",")) {
            var t = part.trim();
            if (t.isEmpty()) continue;
            if (!LogTopics.validUserTopic(t)) throw new IllegalArgumentException("bad topic in compaction list: " + t);
            out.add(t);
        }
        return Set.copyOf(out);
    }
}
