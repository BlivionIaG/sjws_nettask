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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import sjws_nettask.swarm.wire.StreamChunk;

/**
 * S2c PR 6b: the node-level stream-memory budget for credit-on token streams (#267, inceptor's ruling
 * issuecomment-6402985417; agent-swarm.md §5.2 <i>Node stream-memory budget</i>). Created only with
 * {@code sjws.swarm.stream.credit.enabled} on (always on with credit, no sub-flag); with credit off this class is not
 * loaded (pinned in {@code ClassLoadTest}).
 * <ul>
 * <li><b>Size:</b> {@code min(sjws.swarm.stream.budget.bytes if set, heapFraction × maxMemory())}, default fraction
 * 0.25, capped at {@code MAX_SESSIONS × 13 636 638} B. Startup refuses ({@code OptionalModule.Refused}) below
 * {@link #FLOOR}, with an unbounded heap and no {@code bytes}, or on a malformed setting.</li>
 * <li><b>One word:</b> {@link #charged} is the sum of request-scoped reservations ({@code :chunk} between its reserve
 * and its {@code finally}) and residency (retained and held chunk bytes, plus gap and {@code LAST} tickets). Every
 * charge has exactly one release; a release that would go below 0 is counted in {@link #underflows()} (a bug
 * counter, expected 0).</li>
 * <li><b>Refusal</b> (non-exempt chunks only): over the budget, or above the soft watermark from a session whose own
 * resident bytes would exceed {@link #fairShare()}. The liveness filler, a newer attempt's chunk and {@code LAST} are
 * never refused: tickets keep them inside the budget.</li>
 * <li><b>Session admission:</b> each session carries one {@code LAST} ticket ({@link #LAST_TICKET}); a new session is
 * refused (503) when it would take {@code charged} over the admit watermark.</li>
 * <li><b>Pressure trim:</b> above the soft watermark, read chunks are trimmed down to a resume tail of at least one
 * chunk and {@link #TRANSPORT_WINDOW} bytes; unread chunks never are.</li>
 * </ul>
 * O(1) on the check path, no lock: one {@code getAndAdd} to admit (undone on refusal), one to release.
 */
final class StreamBudget {

    /** L3 (#267 Q3): with credit on, a {@code LAST} chunk carries at most this many data bytes; larger is 413. */
    static final int LAST_MAX = 64 * 1024;
    /** The per-session {@code LAST} ticket: {@link #LAST_MAX} plus one chunk header. */
    static final long LAST_TICKET = LAST_MAX + StreamChunk.HEADER;
    /** Booked while a session holds out-of-order chunks: room for the never-refused hole filler. */
    static final long GAP_TICKET = SwarmHttp.MAX_REQUEST;
    /** Fairness floor (#267 Q6): 4 KiB plus one chunk header. */
    static final long MIN_SHARE = 4096 + StreamChunk.HEADER;
    /**
     * The resume tail's byte target (#267 Q5: max(one chunk, the h2/h3 initial flow-control window)): RFC 9113 §6.9.2's
     * default {@code SETTINGS_INITIAL_WINDOW_SIZE}, 65 535, which is above sjws's h3 {@code initial_max_stream_data}
     * (16 KiB). At least one read chunk is always kept as well.
     */
    static final long TRANSPORT_WINDOW = 65_535;
    /** #262's corrected per-session worst case with credit on, before this budget. */
    static final long SESSION_WORST_CASE = 13_636_638L;
    /** The optional ceiling of the ruling (Q1): above it the budget cannot be reached. */
    static final long CEILING = StreamHttp.MAX_SESSIONS * SESSION_WORST_CASE;
    /**
     * One session's non-evictable floor plus its tickets (#267 §3.4/§4.3): unread (the 4 MiB byte window, the filler,
     * one torn read and a {@code LAST}) plus the resume tail (at most {@link #TRANSPORT_WINDOW} plus one chunk), plus a
     * gap ticket and a {@code LAST} ticket. A budget below this cannot run one full session.
     */
    static final long FLOOR = StreamHttp.RETAIN_BYTES + 2L * SwarmHttp.MAX_REQUEST + LAST_TICKET
            + TRANSPORT_WINDOW + SwarmHttp.MAX_REQUEST + GAP_TICKET + LAST_TICKET;

    final long budget;
    final String source;
    final double softWatermark;
    final double admitWatermark;
    private final long soft;
    private final long admit;
    private final AtomicLong charged = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();
    private final AtomicLong overshoot = new AtomicLong();
    private final AtomicInteger sessions = new AtomicInteger();
    private final AtomicLong nodeRefusals = new AtomicLong();
    private final AtomicLong sessionAdmitRefusals = new AtomicLong();
    private final AtomicLong trimChunks = new AtomicLong();
    private final AtomicLong trimBytes = new AtomicLong();
    private final AtomicLong gapTickets = new AtomicLong();
    private final AtomicLong lastTickets = new AtomicLong();
    private final AtomicLong underflows = new AtomicLong();
    /** Test hook (#267 §7): runs on the request thread just before its budget charge. */
    volatile Runnable beforeBudgetCharge;

    StreamBudget(long budget, String source, double softWatermark, double admitWatermark) {
        this.budget = budget;
        this.source = source;
        this.softWatermark = softWatermark;
        this.admitWatermark = admitWatermark;
        this.soft = (long) (softWatermark * budget);
        this.admit = (long) (admitWatermark * budget);
    }

    /** A budget nothing reaches: only for unit tests that build a {@link StreamCredit} by hand. */
    static StreamBudget unbounded() {
        return new StreamBudget(Long.MAX_VALUE / 4, "unbounded (test)", 1.0, 1.0);
    }

    /**
     * Resolves the budget of a credit-on config against {@code maxMemory} ({@code Runtime.maxMemory()}); refuses
     * ({@link sjws_nettask.OptionalModule.Refused}, so the process does not start) with an unbounded heap and no
     * {@code bytes}, or below {@link #FLOOR}.
     */
    static StreamBudget resolve(TokenStreams.Config c, long maxMemory) {
        boolean unboundedHeap = maxMemory == Long.MAX_VALUE;
        if (c.budgetBytes() == 0 && unboundedHeap) {
            throw new sjws_nettask.OptionalModule.Refused("sjws.swarm.stream.credit.enabled is on but the heap has no"
                    + " limit (Runtime.maxMemory() is Long.MAX_VALUE): set -Xmx or sjws.swarm.stream.budget.bytes");
        }
        long fromHeap = unboundedHeap ? Long.MAX_VALUE : (long) (c.heapFraction() * maxMemory);
        long b;
        String src;
        if (c.budgetBytes() > 0 && c.budgetBytes() <= fromHeap) {
            b = c.budgetBytes();
            src = "sjws.swarm.stream.budget.bytes" + (unboundedHeap ? ", heap unbounded"
                    : ", under " + c.heapFraction() + " of heap " + size(maxMemory));
        } else {
            b = fromHeap;
            src = c.heapFraction() + " of heap " + size(maxMemory);
        }
        if (b > CEILING) {
            b = CEILING;
            src += ", capped at " + StreamHttp.MAX_SESSIONS + " sessions x " + SESSION_WORST_CASE + " B";
        }
        if (b < FLOOR) {
            throw new sjws_nettask.OptionalModule.Refused("swarm: stream budget " + b + " B (" + src + ") is below one"
                    + " session's floor plus tickets, " + FLOOR + " B: raise -Xmx, sjws.swarm.stream.budget.heapFraction"
                    + " or sjws.swarm.stream.budget.bytes");
        }
        return new StreamBudget(b, src, c.softWatermark(), c.admitWatermark());
    }

    /** {@code 256 MiB} for whole MiB, else bytes. */
    static String size(long bytes) {
        return bytes % (1L << 20) == 0 ? (bytes >> 20) + " MiB" : bytes + " B";
    }

    /** The startup line ({@code ClassLoadTest} pins its start). */
    String describe() {
        return "swarm: stream budget " + size(this.budget) + " (" + this.source + "), soft " + this.softWatermark
                + ", admit " + this.admitWatermark;
    }

    // --- metrics (#267 §6)

    long budgetBytes() {
        return this.budget;
    }

    long charged() {
        return this.charged.get();
    }

    long peakCharged() {
        return this.peak.get();
    }

    /** The peak of {@code charged − budget} (0 when never over). */
    long transientOvershootMax() {
        return this.overshoot.get();
    }

    /** Sessions holding a {@code LAST} ticket (admitted and not yet left). */
    int openSessions() {
        return this.sessions.get();
    }

    /** Non-LAST chunks answered 429 with {@code "scope":"node"}. */
    long nodeRefusals() {
        return this.nodeRefusals.get();
    }

    /** New sessions answered 503 because the admit watermark was reached. */
    long sessionAdmitRefusals() {
        return this.sessionAdmitRefusals.get();
    }

    long pressureTrimChunks() {
        return this.trimChunks.get();
    }

    long pressureTrimBytes() {
        return this.trimBytes.get();
    }

    long gapTicketsOutstanding() {
        return this.gapTickets.get();
    }

    long lastTicketsOutstanding() {
        return this.lastTickets.get();
    }

    /** Bug counter: a release that would have taken {@code charged} or a session's resident bytes below 0. */
    long budgetUnderflows() {
        return this.underflows.get();
    }

    // --- the word

    private void charge(long n) {
        long c = this.charged.addAndGet(n);
        observe(c);
    }

    private void observe(long c) {
        if (c > this.peak.get()) {
            this.peak.accumulateAndGet(c, Math::max);
        }
        if (c > this.budget) {
            this.overshoot.accumulateAndGet(c - this.budget, Math::max);
        }
    }

    /** One release; a result below 0 is a double release: counted and put back to 0. */
    private void discharge(long n) {
        long c = this.charged.addAndGet(-n);
        if (c < 0) {
            this.underflows.incrementAndGet();
            this.charged.addAndGet(-c);
        }
    }

    /** Above the soft watermark: pressure trims run, and fairness applies to new chunks. */
    boolean pressured() {
        return this.charged.get() > this.soft;
    }

    /** {@code max(minShare, soft watermark × budget / openSessions)}. */
    long fairShare() {
        return Math.max(MIN_SHARE, this.soft / Math.max(1, this.sessions.get()));
    }

    // --- request scope (StreamCredit.reserve, released in the :chunk finally)

    /**
     * Charges a checked chunk's request-scoped reservation: {@code size}, plus a gap ticket when it would open a gap
     * ({@code opensGap}: out of order with nothing held). A non-{@code exempt} chunk is refused (the charge undone)
     * over the budget, or above the soft watermark when its session's resident bytes plus {@code size} exceed the fair
     * share. Returns the amount to {@link #release} exactly once, or -1 when refused (nothing charged).
     */
    long admit(StreamCredit.State st, long size, boolean exempt, boolean opensGap) {
        var hook = this.beforeBudgetCharge;
        if (hook != null) {
            hook.run();
        }
        long amount = size + (opensGap ? GAP_TICKET : 0);
        long c = this.charged.addAndGet(amount);
        if (!exempt && (c > this.budget || c > this.soft && st.resident.get() + size > fairShare())) {
            this.charged.addAndGet(-amount); // optimistic add-then-undo: concurrent refusers over-count, never under
            return -1; // counted by nodeRefused() when the answer is the 429 (a retried duplicate gets 200 instead)
        }
        observe(c);
        return amount;
    }

    void nodeRefused() {
        this.nodeRefusals.incrementAndGet();
    }

    void release(long amount) {
        discharge(amount);
    }

    /**
     * The node part of the CREDIT frame's bytes: never more than {@link #admit} would take for an in-order chunk
     * ({@code size ≤ budget − charged}, and below the soft watermark or within the fair share).
     */
    long advertise(StreamCredit.State st) {
        long c = this.charged.get();
        long fair = fairShare() - st.resident.get();
        return Math.max(0, Math.min(this.budget - c, Math.max(this.soft - c, fair)));
    }

    // --- residency (under the session lock or the sink lock that already guards the matching counter)

    /** Retained or held bytes entered ({@code delta > 0}) or left session memory. */
    void resident(StreamCredit.State st, long delta) {
        if (delta == 0) {
            return;
        }
        if (delta > 0) {
            st.resident.addAndGet(delta);
            charge(delta);
            return;
        }
        long r = st.resident.addAndGet(delta);
        if (r < 0) {
            this.underflows.incrementAndGet();
            st.resident.addAndGet(-r);
        }
        discharge(-delta);
    }

    /** Under the sink lock: held went 0 → 1. Charged unconditionally: the filler it covers is never refused. */
    void bookGap(StreamCredit.State st) {
        if (!st.gapTicket) {
            st.gapTicket = true;
            this.gapTickets.incrementAndGet();
            charge(GAP_TICKET);
        }
    }

    /** Under the sink lock: held went to 0 (flush, RESET discard or close). */
    void releaseGap(StreamCredit.State st) {
        if (st.gapTicket) {
            st.gapTicket = false;
            this.gapTickets.decrementAndGet();
            discharge(GAP_TICKET);
        }
    }

    /**
     * The session's {@code LAST} became resident, buffered out of order (sink lock) or appended in order (session
     * lock), after its size was charged: the ticket is converted (released), once.
     */
    void convertLast(StreamCredit.State st) {
        if (st.lastTicket.compareAndSet(true, false)) {
            this.lastTickets.decrementAndGet();
            discharge(LAST_TICKET);
        }
    }

    /**
     * Under the sink lock: a newer attempt replaces the old one, whose {@code LAST} (if any) the RESET discards; the
     * new attempt's {@code LAST} needs room again. Booked before the old window is released by the drain (a transient
     * over-count, never an under-count).
     */
    void rebookLast(StreamCredit.State st) {
        if (st.lastTicket.compareAndSet(false, true)) {
            this.lastTickets.incrementAndGet();
            charge(LAST_TICKET);
        }
    }

    /** {@code submit}: books a new session's {@code LAST} ticket, or false (503) past the admit watermark. */
    boolean admitSession() {
        long c = this.charged.addAndGet(LAST_TICKET);
        if (c > this.admit) {
            this.charged.addAndGet(-LAST_TICKET);
            this.sessionAdmitRefusals.incrementAndGet();
            return false;
        }
        observe(c);
        this.sessions.incrementAndGet();
        this.lastTickets.incrementAndGet();
        return true;
    }

    /** A submit failed before its session existed: gives back the ticket {@link #admitSession} booked. */
    void abandonSession() {
        this.sessions.decrementAndGet();
        this.lastTickets.decrementAndGet();
        discharge(LAST_TICKET);
    }

    /** Pressure trim: {@code bytes} of read chunks left the retained window. */
    void trimmed(long bytes) {
        this.trimChunks.incrementAndGet();
        this.trimBytes.addAndGet(bytes);
    }

    /**
     * The session left the table ({@code forget}): under its session lock and then its sink lock, sets the late-offer
     * {@code closed} flag and releases, once, everything the session still holds: retained and held bytes, and its
     * gap and {@code LAST} tickets. A drain or an offer that runs later sees {@code closed} and charges nothing.
     */
    void close(StreamHttp.Session s) {
        var st = s.credit;
        s.lock.lock();
        try {
            s.sink.locked(() -> {
                if (st.closed) {
                    return;
                }
                st.closed = true;
                long held = st.heldBytesLocked;
                st.heldBytesLocked = 0;
                resident(st, -(s.retainedBytes + held));
                releaseGap(st);
                convertLast(st); // not a conversion: the session's unused ticket goes back
                this.sessions.decrementAndGet();
            });
        } finally {
            s.lock.unlock();
        }
    }
}
