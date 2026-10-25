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
package sjws_nettask.swarm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import sjws_nettask.swarm.wire.Credit;

/**
 * S2c PR 6 CREDIT (docs/design/agent-swarm.md §5.2 <i>Backpressure</i>, §11.2; #247 ruling, #248 r3/r4/r5): server-side
 * send credit for streaming workers, derived from the session's published counters, never stored. Exists only when
 * {@code sjws.swarm.stream.credit.enabled} is on (beneath {@code sjws.swarm.stream.enabled}): {@link StreamHttp} then
 * creates one per node and one {@link State} per session; with the flag off this class is not loaded, no field below
 * exists and {@code :chunk} answers exactly as before (pinned in {@code ClassLoadTest}).
 * <ul>
 * <li><b>Credit</b> for the session's current attempt: {@code unreadChunks = max(0, maxSeq − readNext + 1) + held +
 * reservedChunks}, {@code unreadBytes = max(0, appendedBytes − readBytes) + heldBytes + reservedBytes}; {@code chunks =
 * max(0, sinkChunks − unreadChunks)}, {@code bytes = max(0, 4 MiB − unreadBytes)}. The read position counts as 0 when
 * the reader still publishes another attempt (RESET race).</li>
 * <li><b>Refusal:</b> {@code chunks == 0}, or {@code unreadChunks > 0} and the chunk's size exceeds {@code bytes}. Never
 * refused: the liveness filler ({@code chunkSeq == maxSeq + 1}, {@code held > 0}, {@code unreadIn == 0}) and a chunk
 * of a newer attempt than the session's (it RESETs the window).</li>
 * <li><b>Reservation:</b> one packed {@link State#reserved} word per session ({@code chunks} in the high 24 bits,
 * {@code bytes} in the low 40; fields are always extracted unsigned and the packed value is never compared). A check
 * claims its chunk with a CAS, re-reads and re-validates with the same test (ABA), and releases exactly once in the
 * request's {@code finally}; the release CAS never goes below zero and counts {@link #reserveUnderflows()}.</li>
 * <li><b>Read order</b> (sources before destinations): {@code reserved}; {@code readAttempt}, {@code readNext},
 * {@code readBytes}; {@code heldChunks}, {@code heldBytes}; {@code attempt}, {@code maxSeq}, {@code appendedBytes}.
 * Writers publish the attempt last: the reader writes {@code readBytes → readNext → readAttempt} (#257 R3, so a
 * position of one attempt is never read under another's {@code readAttempt}); the drain writes
 * {@code appendedBytes → maxSeq} and, on a RESET, {@code attempt} last; held chunks are lowered only after the drain
 * returns (as deltas, never stored).</li>
 * </ul>
 * Lock order: nothing here takes a lock on the check path; the held delta is applied on the offering thread after the
 * listener, still under the task's fence lock where there is one (fence lock → session lock, as before; no reverse
 * acquisition is added).
 */
final class StreamCredit {

    static final String TYPE = "https://sjws.dev/problems/credit";
    static final int CHUNK_SHIFT = 40;
    static final long BYTES_MASK = (1L << CHUNK_SHIFT) - 1;
    static final long CHUNKS_MAX = (1L << 24) - 1;

    /** Verdicts of {@link #verdict}. */
    static final int ADMIT = 0;
    static final int REFUSE = 1;
    static final int EXEMPT = 2;
    static final int NEWER = 3;

    final int sinkChunks;
    final long windowBytes;
    /** S2c PR 6b (#267): the node stream-memory budget; always present with credit on (no sub-flag). */
    final StreamBudget budget;
    private final ConcurrentHashMap<Key, StreamHttp.Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong refusals = new AtomicLong();
    private final AtomicLong unknown = new AtomicLong();
    private final AtomicLong underflows = new AtomicLong();
    /** Test hook (#248 r5 C3): runs between a check's read of {@code reserved} and its CAS. */
    volatile Runnable beforeReserve;
    /** Test hook (#248 r5 C3): runs after an offer's listener drain returned, before held is lowered. */
    volatile Runnable beforeHeldLowered;
    /** Test hook (C3, #257 R3): runs on the reader thread inside {@code publishRead}, before {@code readAttempt}. */
    volatile Runnable midReadPublish;

    private record Key(long hi, long lo) { }

    /** Unit tests only: an unbounded budget. */
    StreamCredit(int sinkChunks, long windowBytes) {
        this(sinkChunks, windowBytes, StreamBudget.unbounded());
    }

    StreamCredit(int sinkChunks, long windowBytes, StreamBudget budget) {
        this.sinkChunks = sinkChunks;
        this.windowBytes = windowBytes;
        this.budget = budget;
    }

    static long chunksOf(long packed) {
        return packed >>> CHUNK_SHIFT;
    }

    static long bytesOf(long packed) {
        return packed & BYTES_MASK;
    }

    static long pack(long chunks, long bytes) {
        return chunks << CHUNK_SHIFT | bytes;
    }

    /** Non-LAST chunks answered 429 (including refusals on validation after the CAS). */
    long refusals() {
        return this.refusals.get();
    }

    /** Non-LAST chunks answered 204 because the session is not on this node (credit unknown, not refused). */
    long unknown() {
        return this.unknown.get();
    }

    /** Bug counter: a release that would have taken a {@code reserved} field below 0 (clamped); expected 0. */
    long reserveUnderflows() {
        return this.underflows.get();
    }

    void refused() {
        this.refusals.incrementAndGet();
    }

    void unknownSession() {
        this.unknown.incrementAndGet();
    }

    /**
     * Binds a new session's credit state; the session is found by its stream id (the task's correlation id). The
     * session takes over the {@code LAST} ticket its submit booked ({@link StreamBudget#admitSession}).
     */
    State register(StreamHttp.Session s) {
        var st = new State(this);
        st.lastTicket.set(true);
        s.credit = st;
        var b = java.nio.ByteBuffer.wrap(s.correlation);
        this.sessions.put(new Key(b.getLong(), b.getLong()), s);
        return st;
    }

    void unregister(StreamHttp.Session s) {
        var b = java.nio.ByteBuffer.wrap(s.correlation);
        this.sessions.remove(new Key(b.getLong(), b.getLong()), s);
    }

    /** This node's session for a stream, or null (another node's session, or gone). */
    StreamHttp.Session session(long streamHi, long streamLo) {
        return this.sessions.get(new Key(streamHi, streamLo));
    }

    /**
     * The credit test for a chunk of lease attempt {@code la}, {@code chunkSeq} {@code seq} and {@code size} data bytes,
     * with {@code reserved} (other requests' reservations, packed) already read. Reads the published counters in the
     * spec's order; O(1), no lock, no walk.
     */
    int verdict(StreamHttp.Session s, State st, long reserved, long la, long seq, long size) {
        long ra = st.readAttempt; // first (#257 R3): the reader publishes it last
        long rn = st.readNext;
        long rb = st.readBytes;
        long hc = Math.max(0, st.heldChunks.get());
        long hb = Math.max(0, st.heldBytes.get());
        long a = s.attempt;
        long ms = st.maxSeq;
        long ab = st.appendedBytes;
        if (a >= 0 && la > a) {
            return NEWER;
        }
        if (ra != a) { // the reader still publishes another attempt: nothing of this one was read
            rn = 0;
            rb = 0;
        }
        long unreadIn = Math.max(0, ms - rn + 1);
        if (la == a && seq == ms + 1 && hc > 0 && unreadIn == 0) {
            return EXEMPT;
        }
        long unreadChunks = unreadIn + hc + chunksOf(reserved);
        long unreadBytes = Math.max(0, ab - rb) + hb + bytesOf(reserved);
        long chunks = Math.max(0, this.sinkChunks - unreadChunks);
        long bytes = Math.max(0, this.windowBytes - unreadBytes);
        return chunks == 0 || unreadChunks > 0 && size > bytes ? REFUSE : ADMIT;
    }

    /** The advisory snapshot sent in the CREDIT frame: the same reads, other requests' reservations included. */
    Credit snapshot(StreamHttp.Session s, State st, long streamHi, long streamLo, long la) {
        long r = st.reserved.get();
        long ra = st.readAttempt; // first (#257 R3): the reader publishes it last
        long rn = st.readNext;
        long rb = st.readBytes;
        long hc = Math.max(0, st.heldChunks.get());
        long hb = Math.max(0, st.heldBytes.get());
        long a = s.attempt;
        long ms = st.maxSeq;
        long ab = st.appendedBytes;
        if (ra != a) {
            rn = 0;
            rb = 0;
        }
        long unreadChunks = Math.max(0, ms - rn + 1) + hc + chunksOf(r);
        long unreadBytes = Math.max(0, ab - rb) + hb + bytesOf(r);
        long chunks = Math.max(0, this.sinkChunks - unreadChunks);
        long bytes = Math.min(Math.max(0, this.windowBytes - unreadBytes), this.budget.advertise(st)); // #267 Q7
        return new Credit(streamHi, streamLo, la, Math.min(chunks, Credit.MAX_U32), Math.min(bytes, Credit.MAX_U32));
    }

    /**
     * Claims one chunk of {@code size} bytes for a request (#248 r3/r4): read {@code reserved}, test, CAS; a failed CAS
     * re-reads and re-tests; a successful one is validated with a re-read in the same order and the same test (an ABA
     * between the read and the CAS is caught there). Returns true when the request holds a reservation it must
     * {@link #release} exactly once; false when it was refused (nothing reserved, nothing written).
     */
    boolean reserve(StreamHttp.Session s, State st, long la, long seq, long size) {
        return reserveVerdict(s, st, la, seq, size) != REFUSE;
    }

    /**
     * {@link #reserve}, returning the validation's verdict ({@link #ADMIT}, {@link #EXEMPT} or {@link #NEWER}) when the
     * request now holds a reservation, or {@link #REFUSE} (nothing reserved).
     */
    int reserveVerdict(StreamHttp.Session s, State st, long la, long seq, long size) {
        while (true) {
            long r = st.reserved.get();
            long c = chunksOf(r);
            long b = bytesOf(r);
            if (c >= CHUNKS_MAX || b + size > BYTES_MASK || verdict(s, st, r, la, seq, size) == REFUSE) {
                return REFUSE; // the range guard is unreachable over HTTP (≤ window requests of ≤ 1 MiB); fail closed
            }
            var hook = this.beforeReserve;
            if (hook != null) {
                hook.run();
            }
            if (st.reserved.compareAndSet(r, pack(c + 1, b + size))) {
                break;
            }
        }
        long r2 = st.reserved.get(); // includes this request's own reservation: validate against the others'
        long others = pack(Math.max(0, chunksOf(r2) - 1), Math.max(0, bytesOf(r2) - size));
        int v = verdict(s, st, others, la, seq, size);
        if (v == REFUSE) {
            release(st, size);
        }
        return v;
    }

    /** The single release of a reservation: a CAS loop that never takes a field below 0 (clamped and counted). */
    void release(State st, long size) {
        while (true) {
            long r = st.reserved.get();
            long c = chunksOf(r);
            long b = bytesOf(r);
            boolean under = c < 1 || b < size;
            if (st.reserved.compareAndSet(r, pack(Math.max(0, c - 1), Math.max(0, b - size)))) {
                if (under) {
                    this.underflows.incrementAndGet();
                }
                return;
            }
        }
    }

    /**
     * One session's published credit state. The volatiles are written under the session lock (drain side) or by the
     * single reader (reader side) and read lock-free by the check; held counters are atomics moved by deltas only.
     */
    static final class State {
        final StreamCredit owner;
        /** Packed reservations of in-flight checked requests: chunks {@code >>> 40}, bytes {@code & (2^40 − 1)}. */
        final AtomicLong reserved = new AtomicLong();
        /** Chunks (and their bytes) waiting in the sink's reorder buffer for the current attempt. */
        final AtomicLong heldChunks = new AtomicLong();
        final AtomicLong heldBytes = new AtomicLong();
        /** Drain side: highest in-order chunkSeq of the current attempt (−1 before its first chunk). */
        volatile long maxSeq = -1;
        /** Drain side: cumulative {@code StreamChunk.size()} of every chunk appended in the current attempt. */
        volatile long appendedBytes;
        /** Reader side: the next chunkSeq the session's reader sends, the bytes before it, and their attempt. */
        volatile long readNext;
        volatile long readBytes;
        volatile long readAttempt = -1;
        /** Guarded by the sink lock: bytes of the chunks held in the sink's reorder buffer. */
        long heldBytesLocked;
        /** S2c PR 6b: this session's resident bytes (retained plus held), moved by the budget's residency deltas. */
        final AtomicLong resident = new AtomicLong();
        /**
         * S2c PR 6b late-offer flag (#267 §3.3): set once, under the session lock and then the sink lock, when the
         * session leaves the table; a drain or offer that runs after it charges nothing (its release already ran).
         */
        volatile boolean closed;
        /**
         * The session still holds its {@code LAST} ticket (not yet converted); flipped by CAS, because a {@code LAST}
         * becomes resident either in the sink's reorder buffer (sink lock) or in the retained window (session lock).
         */
        final java.util.concurrent.atomic.AtomicBoolean lastTicket = new java.util.concurrent.atomic.AtomicBoolean();
        /** Guarded by the sink lock: a gap ticket is booked (the reorder buffer holds chunks). */
        boolean gapTicket;
        // guarded by the session lock: the cumulative byte offset of each retained chunk, parallel to Session.retained
        private long[] offsets = new long[16];
        private int head;
        private int count;

        State(StreamCredit owner) {
            this.owner = owner;
        }

        /** After an offer's listener drain returned: lowers held by the delta captured under the sink lock. */
        void lowerHeld(long packed) {
            var hook = this.owner.beforeHeldLowered;
            if (hook != null) {
                hook.run();
            }
            long k = chunksOf(packed);
            long b = bytesOf(packed);
            if (k != 0) {
                this.heldChunks.addAndGet(-k);
            }
            if (b != 0) {
                this.heldBytes.addAndGet(-b);
                this.owner.budget.resident(this, -b); // always: these bytes already left heldBytesLocked (#267 §3.3)
            }
        }

        // --- drain side, under the session lock

        void appended(long size, long seq) {
            pushOffset(this.appendedBytes);
            this.appendedBytes += size; // destination order: appendedBytes, then maxSeq
            this.maxSeq = seq;
        }

        void pushOffset(long offset) {
            if (this.count == this.offsets.length) {
                var n = new long[this.offsets.length * 2];
                for (int i = 0; i < this.count; i++) {
                    n[i] = this.offsets[(this.head + i) % this.offsets.length];
                }
                this.offsets = n;
                this.head = 0;
            }
            this.offsets[(this.head + this.count) % this.offsets.length] = offset;
            this.count++;
        }

        void trimmed() {
            if (this.count > 0) {
                this.head = (this.head + 1) % this.offsets.length;
                this.count--;
            }
        }

        /** A newer attempt: the window restarts (the session publishes its {@code attempt} after this). */
        void reset() {
            this.head = 0;
            this.count = 0;
            this.appendedBytes = 0;
            this.maxSeq = -1;
        }

        /** The stored offset of chunk {@code seq}; {@code firstSeq} is the oldest retained chunk's (−1: none). */
        long offsetOf(long seq, long firstSeq) {
            if (firstSeq < 0 || seq >= firstSeq + this.count) {
                return seq > this.maxSeq ? this.appendedBytes : 0;
            }
            if (seq < firstSeq) {
                return 0; // trimmed: the reader gets a gap and ends; counting nothing read is conservative
            }
            return this.offsets[(int) ((this.head + seq - firstSeq) % this.offsets.length)];
        }

        // --- reader side, written only by the session's single reader

        void publishRead(long attempt, long bytes, long next) {
            // #257 R3: the attempt is published last (as the drain does), the check reads it first: a check that sees
            // the new readAttempt then reads the new attempt's position; one that sees the old readAttempt with a
            // new position also sees the new Session.attempt (published by the drain before the reader learns of it),
            // so the mismatch rule counts nothing read. Within one attempt readBytes goes before readNext.
            this.readBytes = bytes;
            var hook = this.owner.midReadPublish;
            if (hook != null) {
                hook.run();
            }
            this.readNext = next;
            this.readAttempt = attempt;
        }
    }
}
