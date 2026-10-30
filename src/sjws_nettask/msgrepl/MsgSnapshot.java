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
package sjws_nettask.msgrepl;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The image a {@code msg/<topic>} group snapshots (#270): small, because the data is the topic's own segment files,
 * not a copy in the consensus log. It says where the snapshot point is and how to rebuild the ledger there:
 *
 * <ul>
 * <li>{@code index}, {@code term}: the consensus entry of the snapshot point; {@code endOffset}: the ledger offset right
 * after that entry, fsynced on the node that took it (constraint 1: the fsynced point, not the applied one). This
 * (index, term) to offset mapping is the durable one: it travels in the consensus snapshot file.
 * <li>{@code logStart}: the replicated log start (constraint 3); an installing follower keeps nothing below it.
 * <li>{@code sections}: application state that must survive truncation (constraint 2), as a list of typed sections
 * captured at {@code index} exactly and restored before the entry after it applies. {@link #APP_STATE} is the
 * replica's {@code ReplicatedState}; {@link #PRODUCERS} is reserved for Messaging Core's producer table (#278, its
 * own versioned encoding). A section a decoder does not know is skipped, unless it is marked {@link #REQUIRED}: then
 * the snapshot is refused rather than installed without it.
 * <li>{@code segments}: the segment files from the one holding {@code logStart} to the one holding {@code endOffset},
 * each with its byte length at the snapshot point, its compaction marker, and a SHA-256 per {@code chunkBytes} chunk.
 * A compacted file ships as the leader has it (constraint 4); the receiver checks each chunk against these digests,
 * never against its own files.
 * </ul>
 *
 * Wire layout (big-endian): {@code "MSN1" version:u8(=1) topic(len:u8, utf8) index term endOffset logStart
 * chunkBytes:int sections:int (id:u16 flags:u8 len:int bytes)* segments:int (base next bytes markerNext digests:int
 * sha256*)}. A later version may append fields; a decoder refuses a version it does not know. Decoding bounds every count and
 * length before allocating, and rejects a topic name that is not a plain topic name.
 */
record MsgSnapshot(String topic, long index, long term, long endOffset, long logStart, int chunkBytes,
        List<Section> sections, List<Seg> segments) {

    static final byte VERSION = 1;
    /** Section id: the replica's {@code ReplicaHooks.ReplicatedState} image. */
    static final int APP_STATE = 1;
    /** Section id reserved for the producer dedup table (Messaging Core, #278). */
    static final int PRODUCERS = 2;
    /** Section flag: a decoder that does not know the id must refuse the snapshot. */
    static final int REQUIRED = 1;
    static final int MAX_SECTIONS = 64;

    /** One typed state section; ids are unique within a snapshot. */
    record Section(int id, int flags, byte[] data) {
        Section {
            if (id < 1 || id > 0xFFFF || flags < 0 || flags > 0xFF) throw new IllegalArgumentException("bad section " + id);
            data = data == null ? new byte[0] : data;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Section x && id == x.id && flags == x.flags && Arrays.equals(data, x.data);
        }

        @Override
        public int hashCode() {
            return id * 31 + Arrays.hashCode(data);
        }
    }

    /** Ids this build understands. */
    static boolean known(int id) {
        return id == APP_STATE || id == PRODUCERS;
    }

    /** With {@code state} as the {@link #APP_STATE} section (none when null or empty). */
    MsgSnapshot(String topic, long index, long term, long endOffset, long logStart, int chunkBytes, byte[] state,
            List<Seg> segments) {
        this(topic, index, term, endOffset, logStart, chunkBytes,
                state == null || state.length == 0 ? List.of() : List.of(new Section(APP_STATE, 0, state)), segments);
    }

    /** The section's bytes, or null when absent. */
    byte[] section(int id) {
        for (var x : sections) if (x.id() == id) return x.data();
        return null;
    }

    /** The {@link #APP_STATE} section, or empty. */
    byte[] state() {
        var d = section(APP_STATE);
        return d == null ? new byte[0] : d;
    }

    static final int MAGIC = 0x4D534E31; // "MSN1"
    static final int SHA = 32;
    static final int MIN_CHUNK = 4 << 10;
    static final int MAX_CHUNK = 16 << 20;
    static final int MAX_STATE = 16 << 20;
    static final int MAX_SEGMENTS = 1 << 20;
    /** A segment file is at most 1 TiB (the ledger's own segment size is 1 GiB by default). */
    static final long MAX_SEGMENT_BYTES = 1L << 40;
    static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /**
     * One segment file at the snapshot point: offsets {@code [base, next)}, the first {@code bytes} bytes of
     * {@code <base>.log}, the {@code .compacted} marker value or -1, one SHA-256 per chunk.
     */
    record Seg(long base, long next, long bytes, long markerNext, byte[][] digests) {
        Seg {
            Objects.requireNonNull(digests, "digests");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Seg s && base == s.base && next == s.next && bytes == s.bytes && markerNext == s.markerNext
                    && Arrays.deepEquals(digests, s.digests);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(base) * 31 + Long.hashCode(bytes);
        }
    }

    MsgSnapshot {
        Objects.requireNonNull(topic, "topic");
        sections = List.copyOf(sections);
        segments = List.copyOf(segments);
        if (sections.size() > MAX_SECTIONS) throw new IllegalArgumentException("too many sections");
        for (int i = 0; i < sections.size(); i++) {
            for (int k = 0; k < i; k++) {
                if (sections.get(k).id() == sections.get(i).id()) throw new IllegalArgumentException("duplicate section " + sections.get(i).id());
            }
        }
    }

    static int chunks(long bytes, int chunkBytes) {
        return (int) ((bytes + chunkBytes - 1) / chunkBytes);
    }

    byte[] encode() {
        var t = topic.getBytes(StandardCharsets.UTF_8);
        long size = 4 + 1 + 1 + t.length + 8 * 4 + 4 + 4 + 4;
        for (var x : sections) size += 2 + 1 + 4 + x.data().length;
        for (var s : segments) size += 8 * 4 + 4 + (long) SHA * s.digests.length;
        if (size > Integer.MAX_VALUE - 8) throw new IllegalStateException("snapshot image too large");
        var b = ByteBuffer.allocate((int) size);
        b.putInt(MAGIC).put(VERSION).put((byte) t.length).put(t).putLong(index).putLong(term).putLong(endOffset)
                .putLong(logStart).putInt(chunkBytes).putInt(sections.size());
        for (var x : sections) b.putShort((short) x.id()).put((byte) x.flags()).putInt(x.data().length).put(x.data());
        b.putInt(segments.size());
        for (var s : segments) {
            b.putLong(s.base).putLong(s.next).putLong(s.bytes).putLong(s.markerNext).putInt(s.digests.length);
            for (var d : s.digests) b.put(d);
        }
        return b.array();
    }

    /** Parses and validates an image; {@link IllegalArgumentException} names the first problem. */
    static MsgSnapshot decode(byte[] image) {
        try {
            var b = ByteBuffer.wrap(image);
            if (b.getInt() != MAGIC) throw new IllegalArgumentException("not a messaging snapshot");
            int version = b.get();
            if (version != VERSION) throw new IllegalArgumentException("unknown snapshot version " + version);
            int tl = b.get() & 0xFF;
            var tb = new byte[tl];
            b.get(tb);
            var topic = new String(tb, StandardCharsets.UTF_8);
            if (!TOPIC.matcher(topic).matches() || topic.equals(".") || topic.equals("..")) {
                throw new IllegalArgumentException("bad topic name in snapshot");
            }
            long index = b.getLong(), term = b.getLong(), end = b.getLong(), start = b.getLong();
            int chunk = b.getInt();
            if (index < 0 || term < 0 || end < 0 || start < 0 || start > end) throw new IllegalArgumentException("bad snapshot point");
            if (chunk < MIN_CHUNK || chunk > MAX_CHUNK) throw new IllegalArgumentException("bad chunk size " + chunk);
            int ns = b.getInt();
            if (ns < 0 || ns > MAX_SECTIONS) throw new IllegalArgumentException("bad section count");
            var sections = new ArrayList<Section>(ns);
            long total = 0;
            for (int i = 0; i < ns; i++) {
                int id = b.getShort() & 0xFFFF, flags = b.get() & 0xFF, len = b.getInt();
                total += Math.max(0, len);
                if (len < 0 || len > MAX_STATE || total > MAX_STATE || len > b.remaining()) {
                    throw new IllegalArgumentException("bad state length in section " + id);
                }
                var data = new byte[len];
                b.get(data);
                if (!known(id)) {
                    if ((flags & REQUIRED) != 0) throw new IllegalArgumentException("unknown required section " + id);
                    continue; // a newer writer's optional state: skipped
                }
                for (var x : sections) if (x.id() == id) throw new IllegalArgumentException("duplicate section " + id);
                sections.add(new Section(id, flags, data));
            }
            int n = b.getInt();
            if (n < 1 || n > MAX_SEGMENTS || (long) n * 36 > b.remaining()) throw new IllegalArgumentException("bad segment count");
            var segs = new ArrayList<Seg>(n);
            long expect = -1;
            for (int i = 0; i < n; i++) {
                long base = b.getLong(), next = b.getLong(), bytes = b.getLong(), marker = b.getLong();
                int d = b.getInt();
                if (base < 0 || next < base || bytes < 0 || bytes > MAX_SEGMENT_BYTES || (expect >= 0 && base != expect)) {
                    throw new IllegalArgumentException("bad segment " + i);
                }
                if (marker != -1 && marker < next) throw new IllegalArgumentException("bad compaction marker in segment " + i);
                if (d != chunks(bytes, chunk) || (long) d * SHA > b.remaining()) throw new IllegalArgumentException("bad digests in segment " + i);
                var digests = new byte[d][];
                for (int k = 0; k < d; k++) {
                    digests[k] = new byte[SHA];
                    b.get(digests[k]);
                }
                segs.add(new Seg(base, next, bytes, marker, digests));
                expect = next;
            }
            if (b.hasRemaining()) throw new IllegalArgumentException("trailing bytes in snapshot");
            if (segs.getFirst().base > start || segs.getLast().next != end) {
                throw new IllegalArgumentException("segments do not cover [logStart, endOffset)");
            }
            return new MsgSnapshot(topic, index, term, end, start, chunk, sections, segs);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("truncated snapshot", e);
        }
    }

    /** Total bytes the segments ship. */
    long dataBytes() {
        long n = 0;
        for (var s : segments) n += s.bytes;
        return n;
    }
}
