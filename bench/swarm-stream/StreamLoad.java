
/*
 * S2c PR 8 Java stream loadgen. JDK 25+, no extra deps.
 *   java StreamLoad.java --base URL --tokens-file PATH --out result.json [options]
 * Pin with taskset -c 0-1. Same semantics as drive.py for agg.py.
 */
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.*;

public class StreamLoad {
  static final String CT = "application/vnd.sjws.swarm.v1";
  static final int FRAME_HDR = 6, CHUNK_HDR = 26;
  static final int LAST = 0x01, FIRST = 0x08;
  static final int TYPE_TASK = 1, TYPE_LEASE = 3, TYPE_TOKEN = 6, TYPE_CREDIT = 9;
  static final double CREDIT_FLOOR_S = 0.005; // #261 Q9: 5 ms doubling ±20%, cap Retry-After
  /** Match StreamHttp.GRACE_MS — drain timeout is derived, never a bare 75. */
  static final long GRACE_MS = 60_000L;
  static final long DRAIN_SLACK_MS = 15_000L;

  /**
   * Fixed log2-µs histogram with linear sub-buckets (HdrHistogram-style).
   * SUB_BITS=6 → 64 steps per octave so §13 thresholds (p99<1000 / ceil 1500 µs) are not
   * crossed by a coarse power-of-two midpoint (Codex P2 on #459).
   */
  static final class LatBag {
    static final int SUB_BITS = 6;
    static final int SUB = 1 << SUB_BITS; // 64
    static final int BUCKETS = 64 * SUB;  // 4096
    // #415: instance histograms (LAT send stamp, LAT_CO scheduled send time, LAG schedule lag).
    // Striped shared counters instead of one 32 KiB array per virtual thread: 3 histograms x 2k VTs
    // were ~190 MiB of generator heap (Architecture review of 5f4805d6). 16 stripes = 512 KiB each.
    static final int STRIPES = 16;
    final AtomicLongArray[] st = new AtomicLongArray[STRIPES];
    LatBag() { for (int i = 0; i < STRIPES; i++) st[i] = new AtomicLongArray(BUCKETS); }

    static int index(long us) {
      if (us < 1) us = 1;
      int exp = 63 - Long.numberOfLeadingZeros(us);
      if (exp < SUB_BITS) {
        int b = (int) us;
        return b < BUCKETS ? b : BUCKETS - 1;
      }
      long base = 1L << exp;
      int sub = (int) (((us - base) << SUB_BITS) >> exp);
      if (sub >= SUB) sub = SUB - 1;
      if (sub < 0) sub = 0;
      int b = exp * SUB + sub;
      return b < BUCKETS ? b : BUCKETS - 1;
    }

    /**
     * Upper edge of bucket {@code b} in µs. Percentiles use the high edge so §13 gates
     * (p99<1000 / ceil 1500) cannot false-pass on a mid-bucket under-estimate.
     */
    static double upper(int b) {
      int exp = b / SUB;
      int sub = b % SUB;
      if (exp < SUB_BITS) return Math.max(1.0, b + 1);
      long base = 1L << exp;
      long width = base >> SUB_BITS;
      if (width < 1) width = 1;
      return base + (sub + 1.0) * width;
    }

    void add(double us) {
      long u = us < 1 ? 1 : (long) us;
      st[(int) (Thread.currentThread().threadId() & (STRIPES - 1))].incrementAndGet(index(u));
    }
    long[] sum() {
      long[] sum = new long[BUCKETS];
      for (AtomicLongArray a : st) for (int i = 0; i < BUCKETS; i++) sum[i] += a.get(i);
      return sum;
    }
    double pctFromBuckets(double p) {
      long[] sum = sum();
      long n = 0;
      for (long c : sum) n += c;
      if (n == 0) return Double.NaN;
      long need = (long) Math.ceil(p * n);
      if (need < 1) need = 1;
      long seen = 0;
      for (int b = 0; b < BUCKETS; b++) {
        seen += sum[b];
        if (seen >= need) return upper(b);
      }
      return upper(BUCKETS - 1);
    }
    long count() {
      long n = 0;
      for (long c : sum()) n += c;
      return n;
    }
    /** Sparse [[bucket, count], ...] JSON so several generator processes can be merged ((B) split). */
    String sparseJson() {
      long[] sum = sum();
      StringBuilder sb = new StringBuilder("[");
      boolean first = true;
      for (int i = 0; i < BUCKETS; i++) {
        if (sum[i] == 0) continue;
        if (!first) sb.append(',');
        first = false;
        sb.append('[').append(i).append(',').append(sum[i]).append(']');
      }
      return sb.append(']').toString();
    }
    void clearAll() {
      for (AtomicLongArray a : st) for (int i = 0; i < BUCKETS; i++) a.set(i, 0);
    }
  }
  /** Send-stamp chunk latency (actual send → consumer). Reported beside the gate p99 when paced. */
  static final LatBag LAT = new LatBag();
  /**
   * Scheduled-send-time chunk latency (slot → consumer). The gate p99 ({@code p99_us}) when paced
   * (inceptor Q1). Equal to LAT in closed loop, where the slot is the send time.
   */
  static final LatBag LAT_CO = new LatBag();
  /**
   * #502 A/A spread: scheduled latency per 1 s measure block (slot second since window start,
   * clamped to the last bin). One unstriped 64 x 4096 array (2 MiB), written beside LAT_CO.
   */
  static final int PER_S_BINS = 64;
  static final AtomicLongArray SCHED_PER_S = new AtomicLongArray(PER_S_BINS * LatBag.BUCKETS);
  static void schedAdd(Win w, long slotNs, double us) {
    LAT_CO.add(us);
    long sec = (slotNs - w.startNs) / 1_000_000_000L;
    int bin = (int) Math.max(0L, Math.min(PER_S_BINS - 1, sec));
    SCHED_PER_S.incrementAndGet(bin * LatBag.BUCKETS + LatBag.index(us < 1 ? 1 : (long) us));
  }
  static void clearPerS() { for (int i = 0; i < SCHED_PER_S.length(); i++) SCHED_PER_S.set(i, 0); }
  /** JSON [[sec, [[bucket,count],...]], ...] for non-empty seconds. */
  static String perSJson() {
    StringBuilder sb = new StringBuilder("[");
    boolean firstS = true;
    for (int sIdx = 0; sIdx < PER_S_BINS; sIdx++) {
      StringBuilder h = new StringBuilder();
      boolean first = true;
      for (int b = 0; b < LatBag.BUCKETS; b++) {
        long c = SCHED_PER_S.get(sIdx * LatBag.BUCKETS + b);
        if (c == 0) continue;
        if (!first) h.append(',');
        first = false;
        h.append('[').append(b).append(',').append(c).append(']');
      }
      if (first) continue;
      if (!firstS) sb.append(',');
      firstS = false;
      sb.append('[').append(sIdx).append(",[").append(h).append("]]");
    }
    return sb.append(']').toString();
  }
  /** #415 schedule lag (slot -> send start, µs) for in-window paced slots (Architecture item 5). */
  static final LatBag LAG = new LatBag();
  /** Smoke e: first measure second vs the rest, so a start-of-window catch-up burst is visible. */
  static final LatBag LAG_FIRST = new LatBag(), LAG_REST = new LatBag();
  /**
   * #502 pacer root cause (smoke7): split each in-window slot's lag by where it came from.
   * WAKE = producer reached the slot early and parked/spun (pacer precision); BEHIND = producer
   * reached the slot already late (previous POST's ack still outstanding, GC, preemption);
   * CAPWAIT = pipelined in-flight cap reached (server back-pressure). ACK_RTT = write -> response.
   */
  static final LatBag PACE_WAKE = new LatBag(), PACE_BEHIND = new LatBag(), PACE_CAPWAIT = new LatBag(),
      ACK_RTT = new LatBag();
  static final LongAdder PACE_BEHIND_N = new LongAdder(), PACE_CAPWAIT_N = new LongAdder(),
      PACE_WAKE_N = new LongAdder();
  static void recordPace(long arriveNs, long sendNs, long slotNs, long capWaitNs) {
    double lagUs = (sendNs - slotNs) / 1e3;
    if (arriveNs > slotNs) { PACE_BEHIND.add(lagUs); PACE_BEHIND_N.increment(); }
    else if (capWaitNs > 0) { PACE_CAPWAIT.add(lagUs); PACE_CAPWAIT_N.increment(); }
    else { PACE_WAKE.add(lagUs); PACE_WAKE_N.increment(); }
  }
  static long gcMillis() {
    long t = 0;
    for (var g : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, g.getCollectionTime());
    return t;
  }
  static long gcCount() {
    long t = 0;
    for (var g : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, g.getCollectionCount());
    return t;
  }
  /** #415 pacing: in-window slot sends, sends started > 1 ms after their slot, worst lag. */
  static final LongAdder SCHED_SENDS = new LongAdder(), SCHED_LATE = new LongAdder();
  static final LongAccumulator SCHED_LAG_MAX_NS = new LongAccumulator(Long::max, 0L);
  static final long LATE_NS = 1_000_000L;
  /**
   * #415 window accounting (Architecture item 1): a chunk belongs to the window by its SLOT time
   * (scheduled send; send time in closed loop), not by when it was received. Producers send every
   * slot in [start, end), consumers keep reading until LAST or the drain cutoff, and in-window slots
   * still unsent or unreceived at cutoff are recorded in LAT_CO at their age (cutoff - slot).
   */
  static final LongAdder SCHED_EXPECTED = new LongAdder(), SCHED_SENT = new LongAdder(),
      SCHED_RECEIVED = new LongAdder(), SCHED_UNRECEIVED = new LongAdder(),
      SCHED_PHANTOM_STREAMS = new LongAdder(), SCHED_STAMP_FALLBACK = new LongAdder(),
      SCHED_INFLIGHT_AT_END = new LongAdder(), SCHED_STREAMS_SHORT = new LongAdder(),
      SCHED_LATE_REGISTERED = new LongAdder();
  /** Every HTTP 503 on claim/:stream (including retries). Give-ups are HTTP503_GIVEUPS after 15 retries. */
  static final LongAdder HTTP503_RESPONSES = new LongAdder(), HTTP503_CLAIM = new LongAdder(),
      HTTP503_STREAM = new LongAdder(), HTTP503_BUDGET = new LongAdder(),
      HTTP503_CLAIM_GIVEUPS = new LongAdder(), HTTP503_STREAM_GIVEUPS = new LongAdder();

  /** First claim and first :stream response per pool (warmup + measure, separately). */
  static final ConcurrentLinkedQueue<String> FIRST_ADMIT_LOG = new ConcurrentLinkedQueue<>();
  static final ConcurrentLinkedQueue<String> FIRST_ADMIT_WARMUP = new ConcurrentLinkedQueue<>();
  static final ConcurrentHashMap<String, Boolean> FIRST_ADMIT_SEEN = new ConcurrentHashMap<>();

  static void resetFirstAdmit(boolean snapshotWarmup) {
    if (snapshotWarmup) {
      FIRST_ADMIT_WARMUP.clear();
      FIRST_ADMIT_WARMUP.addAll(FIRST_ADMIT_LOG);
    }
    FIRST_ADMIT_LOG.clear();
    FIRST_ADMIT_SEEN.clear();
  }

  static String bodySnippet(byte[] body, int n) {
    if (body == null || body.length == 0) return "";
    int m = Math.min(body.length, n);
    StringBuilder sb = new StringBuilder(m);
    for (int i = 0; i < m; i++) {
      int c = body[i] & 0xff;
      sb.append(c >= 32 && c < 127 ? (char) c : '.');
    }
    if (body.length > n) sb.append("...");
    return sb.toString();
  }

  static String headerMap(java.net.http.HttpHeaders h) {
    if (h == null) return "";
    StringBuilder sb = new StringBuilder();
    h.map().forEach((k, v) -> sb.append(k).append('=').append(String.join(",", v)).append(';'));
    return sb.toString();
  }

  /** Log the first claim or :stream response for this pool (request URL, status, headers, body snippet). */
  static void logFirstAdmit(String kind, String method, String url, String pool, int status,
      String headers, byte[] body, long elapsedMs) {
    String key = kind + ":" + pool;
    if (FIRST_ADMIT_SEEN.putIfAbsent(key, Boolean.TRUE) != null) return;
    String line = kind + " pool=" + pool + " " + method + " " + url
        + " status=" + status + " elapsed_ms=" + elapsedMs
        + " headers={" + headers + "} body=" + bodySnippet(body, 180);
    FIRST_ADMIT_LOG.add(line);
    System.err.println("StreamLoad: first-" + line);
  }

  static final AtomicBoolean CUTOFF_HIT = new AtomicBoolean();
  /** #415 catch-up period (window end -> last producer done): generator CPU seconds and wall seconds. */
  static volatile double CATCHUP_CPU_SEC = 0, CATCHUP_WALL_S = 0;
  /**
   * Architecture re-review of b47b7b26, item 2: every result.json carries the harness commit and this
   * validity marker. "q4" from the commit that folds in the stricter self_loaded check and
   * --rate-mib-s after #465 merged (agg.Q4_LANDED is True). agg.py rejects any window whose
   * marker is missing or not "q4", so a window run before that commit can never count later.
   */
  static final String VALIDITY_MARKER = "q4";

  record StreamKey(long hi, long lo) {}

  /** Per-stream schedule and counts (producer registers it after its claim, before any chunk). */
  static final class Track {
    final long phaseNs, intervalNs;
    final AtomicLong sent = new AtomicLong(), recv = new AtomicLong();
    Track(long phaseNs, long intervalNs) { this.phaseNs = phaseNs; this.intervalNs = intervalNs; }
  }

  /**
   * One wave (warmup or measure). The measure window is [startNs, endNs); unset = Long.MAX_VALUE.
   * Slot schedules are anchored at {@code epochNs} (wave creation, before the admit ramp), not at a
   * stream's claim, so every registered stream owns slots across the whole window whenever its claim
   * returned (Architecture re-review item 1: a late claim must not shrink its expected slots).
   */
  static final class Win {
    final boolean account;
    final int n;
    final long intervalNs;
    final long epochNs = System.nanoTime();
    final ConcurrentLinkedQueue<InputStream> consumerStreams = new ConcurrentLinkedQueue<>();
    volatile long startNs = Long.MAX_VALUE, endNs = Long.MAX_VALUE;
    final AtomicBoolean hardStop = new AtomicBoolean();
    final ConcurrentHashMap<StreamKey, Track> tracks = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<LeanHttp> consumerSockets = new ConcurrentLinkedQueue<>();
    volatile PacerLoop pacer; // #502: one pacing loop per process (--pacer-loop), else null
    Win(boolean account, int n, long intervalNs) { this.account = account; this.n = n; this.intervalNs = intervalNs; }
    boolean inWindow(long slotNs) { return slotNs >= startNs && slotNs < endNs; }
  }

  /**
   * Per-stream slot interval for a wave of {@code n} streams at {@code rateMibS} aggregate
   * (Architecture item 3: the wave's own stream count, so warmup also runs at the gate rate).
   */
  static long intervalNs(Args args, int n) {
    if (args.rateMibS <= 0) return 0L;
    return Math.max(1L, (long) (n * (double) args.chunkBytes * 1e9 / (args.rateMibS * 1048576.0)));
  }

  /** First slot phase + k*interval (k >= 0) at or after {@code t}. Mirrored in tests/test_window_accounting.py. */
  static long firstSlotAtOrAfter(long phase, long interval, long t) {
    return phase + Math.max(0L, Math.ceilDiv(t - phase, interval)) * interval;
  }

  /**
   * Per-stream deadline pacer: park until just before {@code deadlineNs}, then a short spin.
   * Full-remaining {@code parkNanos} oversleeps (Linux timer slack); smoke6 floor @32 had
   * lag_p99=3072 µs vs p99_send=520 µs, and @1 had lag_p99=307200 µs vs p99_send=150 µs.
   * Target: lag_p99 &lt; 10% of p99_send at 1 and 32 streams.
   * <p>Adaptive spin window per paced thread: park to (deadline - spin), then onSpinWait. The window
   * learns the thread's park overshoot (2x the last overshoot when it grows, slow decay otherwise),
   * clamped to [10 µs, 200 µs]. A fixed 200 µs window kept a 1-stream producer (97.7 µs slot)
   * spinning on its whole core, which would trip self_loaded (any proc > 80%).
   */
  static final long PACE_SPIN_MIN_NS = 10_000L, PACE_SPIN_MAX_NS = 200_000L, PACE_SPIN_INIT_NS = 50_000L;
  static final ThreadLocal<long[]> SPIN_WIN = ThreadLocal.withInitial(() -> new long[] {PACE_SPIN_INIT_NS});

  /** One park toward {@code deadlineNs} (at most {@code capNs}); false when inside the spin window. */
  static boolean parkStep(long deadlineNs, long capNs) {
    long[] sw = SPIN_WIN.get();
    long now = System.nanoTime();
    long rem = deadlineNs - now;
    if (rem <= sw[0]) return false;
    long req = Math.min(rem - sw[0], capNs);
    LockSupport.parkNanos(req);
    long over = Math.max(0L, System.nanoTime() - now - req);
    long target = Math.max(PACE_SPIN_MIN_NS, 2 * over);
    if (target > sw[0]) sw[0] = Math.min(PACE_SPIN_MAX_NS, target);
    else sw[0] -= (sw[0] - target) >> 6;
    return true;
  }

  /**
   * Best-effort: set this thread's timer slack to 1 µs (default 50 µs) via /proc/&lt;tid&gt;/timerslack_ns,
   * so parkNanos wakes closer to the deadline. Generator process only; no product setting.
   */
  static boolean setThreadTimerSlack(long ns) {
    try {
      Path link = Files.readSymbolicLink(Path.of("/proc/thread-self"));
      Path f = Path.of("/proc", link.getFileName().toString(), "timerslack_ns");
      Files.writeString(f, Long.toString(ns));
      return true;
    } catch (Exception e) {
      return false;
    }
  }
  static final LongAdder TIMER_SLACK_SET = new LongAdder();

  static void parkUntilDeadline(long deadlineNs) {
    while (parkStep(deadlineNs, Long.MAX_VALUE)) { /* parked; re-check */ }
    while (System.nanoTime() < deadlineNs) Thread.onSpinWait();
  }

  /**
   * Deadline-pacing self-test (no server, no load generator). Schedules {@code n} successive
   * deadlines {@code intervalNs} apart and records wake lag. Returns 0 on pass.
   */
  static int paceSelfTest(long intervalNs, int n, long p99LimitNs, String name) {
    setThreadTimerSlack(1_000L);
    long[] lags = new long[n];
    long next = System.nanoTime() + intervalNs;
    for (int i = 0; i < n; i++) {
      parkUntilDeadline(next);
      long now = System.nanoTime();
      lags[i] = now - next;
      next += intervalNs;
      if (next < now) next = now + intervalNs; // resync after a stall so later samples still test the pacer
    }
    long[] sorted = lags.clone();
    java.util.Arrays.sort(sorted);
    long p50 = sorted[n / 2];
    int i90 = Math.min(n - 1, Math.max(0, (int) Math.ceil(0.90 * n) - 1));
    int i99 = Math.min(n - 1, Math.max(0, (int) Math.ceil(0.99 * n) - 1));
    long p90 = sorted[i90];
    long p99 = sorted[i99];
    long pmax = sorted[n - 1];
    int early = 0, under = 0;
    for (long l : lags) {
      if (l < -1_000L) early++;
      if (l <= p99LimitNs) under++;
    }
    double frac = 100.0 * under / n;
    System.out.printf(Locale.ROOT,
        "pace-self-test %s n=%d interval_ns=%d p50=%d ns (%.1f us) p90=%d ns (%.1f us) p99=%d ns (%.1f us) max=%d ns early=%d under_limit=%.1f%% limit=%d ns%n",
        name, n, intervalNs, p50, p50 / 1e3, p90, p90 / 1e3, p99, p99 / 1e3, pmax, early, frac, p99LimitNs);
    // Typical-wake proof: p50 and p90 must be under the 10%-of-p99_send limit.
    // p99 is reported and is the smoke-floor target; a shared box may preempt a few samples.
    boolean ok = p50 <= p99LimitNs && p90 <= p99LimitNs && early == 0;
    if (!ok) System.err.println("self-test FAIL: " + name + " p50=" + p50 + " p90=" + p90 + " ns limit=" + p99LimitNs);
    return ok ? 0 : 1;
  }

  /** Slots k >= 0 with phase + k*interval in [start, end). Mirrored in tests/test_window_accounting.py. */
  static long slotsInWindow(long phase, long interval, long start, long end) {
    long kLo = Math.max(0L, Math.ceilDiv(start - phase, interval));
    long kHi = Math.max(0L, Math.ceilDiv(end - phase, interval));
    return Math.max(0L, kHi - kLo);
  }

  /**
   * After the wave: every in-window slot not received is recorded in LAT_CO at age cutoff - slot.
   * Every registered stream's phase is anchored before the window (Win.epochNs), so its expected
   * slots span the whole window however late its claim returned. Missing slots are taken as the
   * stream's LAST (youngest) ones, so each recorded age is a LOWER bound on the true age of the slot
   * that is actually missing (the true age is at least this, never less); every one is still
   * >= cutoff - end. Any shortfall fails the window in agg.py, which then reports no p99 at all.
   * A stream whose producer never registered (never claimed) contributes floor(window / interval)
   * phantom slots spread over the window.
   */
  static void accountWave(Win w, long cutoffNs) {
    if (!w.account || w.intervalNs <= 0 || w.startNs == Long.MAX_VALUE) return;
    long start = w.startNs, end = w.endNs, iv = w.intervalNs;
    List<Track> ts = new ArrayList<>(w.tracks.values()); // snapshot: tracks and phantoms from one view
    for (Track t : ts) {
      long exp = slotsInWindow(t.phaseNs, iv, start, end);
      SCHED_EXPECTED.add(exp);
      long missing = exp - t.recv.get();
      if (missing <= 0) continue;
      SCHED_STREAMS_SHORT.increment();
      SCHED_UNRECEIVED.add(missing);
      long kHi = Math.max(0L, Math.ceilDiv(end - t.phaseNs, iv));
      for (long j = 1; j <= missing; j++) {
        long slot = t.phaseNs + (kHi - j) * iv;
        schedAdd(w, slot, (cutoffNs - slot) / 1e3);
      }
    }
    long phantom = Math.max(0, w.n - ts.size());
    if (phantom > 0) {
      SCHED_PHANTOM_STREAMS.add(phantom);
      SCHED_STREAMS_SHORT.add(phantom);
      long per = (end - start) / iv;
      SCHED_EXPECTED.add(phantom * per);
      SCHED_UNRECEIVED.add(phantom * per);
      for (long p = 0; p < phantom; p++)
        for (long k = 0; k < per; k++) schedAdd(w, start + k * iv, (cutoffNs - (start + k * iv)) / 1e3);
    }
  }

  /**
   * One received TOKEN chunk; {@code off} is the chunk payload start ([2][hi8][lo8][attempt4][seq4]
   * [flags1][data]). Counts by slot time inside the window; the scheduled stamp falls back to the
   * send stamp only when unparsable (counted, flagged on paced windows).
   */
  static void onChunk(Win w, Track[] cache, byte[] b, int off, int len, long recvNs, LongAdder bytesRx) {
    if (!w.account || len < CHUNK_HDR + 20) return;
    long emit = asciiLong(b, off + CHUNK_HDR);
    if (emit <= 0) return;
    long slot = len >= CHUNK_HDR + 40 ? asciiLong(b, off + CHUNK_HDR + 20) : -1;
    if (slot <= 0) { SCHED_STAMP_FALLBACK.increment(); slot = emit; }
    if (!w.inWindow(slot)) return;
    LAT.add((recvNs - emit) / 1e3);
    schedAdd(w, slot, (recvNs - slot) / 1e3);
    bytesRx.add(len - CHUNK_HDR);
    SCHED_RECEIVED.increment();
    Track t = cache[0];
    if (t == null) {
      t = w.tracks.get(new StreamKey(getLong(b, off + 1), getLong(b, off + 9)));
      cache[0] = t;
    }
    if (t != null) t.recv.incrementAndGet();
  }

  static long getLong(byte[] b, int off) {
    long v = 0;
    for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xffL);
    return v;
  }

  static final class Args {
    String base, tokensFile, out, accept = "binary";
    double warmup = 5, secs = 20, drainTimeout = 75;
    int streams = 1000, chunkBytes = 1024, window = 8;
    /** Task groups for round-robin stream mapping; 0 ⇒ one group per stream (legacy). Architecture 2026-10-30 15:13. */
    int groups = 0;
    int serverPid = 0; // optional: sample server CPU from /proc/<pid>/stat
    int maxConnectionsPerIp = 0; // A1: recorded from gate harness (0 = unset)
    String heapFlags = ""; // inceptor 2026-10-30 15:14: record, never retune
    /**
     * Generator cores (taskset width). inceptor 2026-10-30 18:46: self_loaded iff
     * client_cpu_pct_of_wall > 80% × genCores. Default 2 (CPUs 0-1); (B) uses 3 (0-2).
     */
    int genCores = 2;
    /** If > 0, overrides 80%×genCores threshold. */
    double genSatPct = 0;
    /** Lean keep-alive Socket POST for :chunk (harness-only; claim/stream still HttpClient). */
    boolean leanHttp = true;
    /**
     * #502 pacer: max in-flight :chunk POSTs per paced lean stream (HTTP/1.1 pipelining on the
     * producer's keep-alive socket; the server answers in order). 1 (default, 2026-11-14 01:48) =
     * legacy one-POST-at-a-time; 0 = use --window. Pipelining per producer thread is opt-in only:
     * pacer-check showed its spin starving the consumer on the same pinned core.
     */
    int pipelineDepth = 1;
    /**
     * #502 pacer-check2: one pacing loop per generator process (wrk2 style). One platform thread
     * sends every paced lean stream's slots in deadline order over each stream's keep-alive socket,
     * with a bounded in-flight window per stream (--window, or --pipeline-depth if > 1). Producer
     * threads only claim, register and send LAST; consumers keep their own threads.
     */
    boolean pacerLoop = false;
    /** Max spin before a slot in the pacer loop (µs); the loop parks until then. */
    long pacerSpinUs = 20;
    /**
     * #415: aggregate offered rate in MiB/s. 0 = closed loop (legacy: every stream POSTs as fast as
     * its round trip allows). > 0 = each stream sends on a fixed schedule of
     * streams × chunkBytes / rate seconds per chunk (wrk2-style: late slots are caught up, never
     * dropped, so LAT_CO keeps the backlog).
     */
    double rateMibS = 0;
    /** #415: raw-socket :stream + replies GET for binary drains (HttpClient off the hot path). */
    boolean leanConsumer = true;
    boolean leanConsumerActive() { return leanHttp && leanConsumer && "binary".equals(accept); }
    /** Harness commit stamped into result.json (--harness-commit, else env HARNESS_COMMIT). */
    String harnessCommit = "";
    /** Test-only (#415 smoke): after a successful claim, sleep this many ms on every 11th stream. */
    long claimDelayMs = 0;
    /** Test-only (#415 smoke): every Nth consumer opens the session then does not read until cutoff. 0 = off. */
    int hangEvery = 0;
    /** #415 (B): first group id this process owns; pools are label+(groupOffset+i%gCount). */
    int groupOffset = 0;
    /** Seconds to wait for claims/:stream to finish before the measure window opens. */
    double admitTimeoutS = 30;
  }

  public static void main(String[] a) throws Exception {
    if (a.length > 0 && "--self-test".equals(a[0])) {
      int n = selfTest();
      System.out.println(n == 0 ? "self-test ok" : "self-test FAILED checks=" + n);
      System.exit(n);
    }
    Args args = parse(a);
    List<String> tokens = loadTokens(args.tokensFile);
    Result r = run(args, tokens);
    Files.writeString(Path.of(args.out), r.toJson());
    System.out.println(r.summary());
  }

  /**
   * Pure unit checks of slotsInWindow / firstSlotAtOrAfter / accountWave (no server, no load).
   * Mirrors tests/test_window_accounting.py. Returns the number of failed checks (0 = ok).
   */
  static int selfTest() {
    int fail = 0;
    fail += selfCheck("slots 0,10,0,100", slotsInWindow(0, 10, 0, 100) == 10);
    fail += selfCheck("slots 5,10,0,100", slotsInWindow(5, 10, 0, 100) == 10);
    fail += selfCheck("slots 5,10,6,96", slotsInWindow(5, 10, 6, 96) == 9);
    fail += selfCheck("slots 200,10,0,100", slotsInWindow(200, 10, 0, 100) == 0);
    fail += selfCheck("slots empty", slotsInWindow(0, 10, 100, 100) == 0);
    fail += selfCheck("first 5,10,0", firstSlotAtOrAfter(5, 10, 0) == 5);
    fail += selfCheck("first 5,10,6", firstSlotAtOrAfter(5, 10, 6) == 15);
    fail += selfCheck("first 5,10,15", firstSlotAtOrAfter(5, 10, 15) == 15);
    long iv = 9_000_000L, start = 10_000_000_000L, end = 30_000_000_000L, epoch = 0;
    for (long off : new long[]{0, iv / 3, iv - 1}) {
      long n = slotsInWindow(epoch + off, iv, start, end);
      long per = (end - start) / iv;
      fail += selfCheck("full window off=" + off, n == per || n == per + 1);
    }
    // accountWave: one registered stream missing its last 2 slots; expected = slots in window.
    SCHED_EXPECTED.reset(); SCHED_UNRECEIVED.reset(); SCHED_PHANTOM_STREAMS.reset();
    SCHED_STREAMS_SHORT.reset(); LAT_CO.clearAll();
    Win w = new Win(true, 1, iv);
    w.startNs = start; w.endNs = end;
    long phase = epoch + 123;
    Track t = new Track(phase, iv);
    long exp = slotsInWindow(phase, iv, start, end);
    t.recv.set(exp - 2);
    w.tracks.put(new StreamKey(1, 2), t);
    long cutoff = end + 75_000_000_000L;
    accountWave(w, cutoff);
    fail += selfCheck("account expected", SCHED_EXPECTED.sum() == exp);
    fail += selfCheck("account unreceived", SCHED_UNRECEIVED.sum() == 2);
    fail += selfCheck("account streams short", SCHED_STREAMS_SHORT.sum() == 1);
    fail += selfCheck("account no phantom", SCHED_PHANTOM_STREAMS.sum() == 0);
    fail += selfCheck("account lat samples", LAT_CO.count() == 2);
    // phantom: n=2, one unregistered
    SCHED_EXPECTED.reset(); SCHED_UNRECEIVED.reset(); SCHED_PHANTOM_STREAMS.reset();
    SCHED_STREAMS_SHORT.reset(); LAT_CO.clearAll();
    Win w2 = new Win(true, 2, iv);
    w2.startNs = start; w2.endNs = end;
    Track t2 = new Track(phase, iv);
    t2.recv.set(exp);
    w2.tracks.put(new StreamKey(1, 2), t2);
    accountWave(w2, cutoff);
    long per = (end - start) / iv;
    fail += selfCheck("phantom streams", SCHED_PHANTOM_STREAMS.sum() == 1);
    fail += selfCheck("phantom expected", SCHED_EXPECTED.sum() == exp + per);
    fail += selfCheck("phantom unreceived", SCHED_UNRECEIVED.sum() == per);
    fail += selfCheck("retry-after default", retryAfterMs(null, 1000) == 1000);
    fail += selfCheck("retry-after 1s", retryAfterMs("1", 1000) == 1000);
    fail += selfCheck("retry-after 0.2s min 50", retryAfterMs("0.02", 1000) == 50);
    fail += selfCheck("retry-after cap 5s", retryAfterMs("30", 1000) == 5000);
    fail += selfCheck("retry-after junk", retryAfterMs("Fri, 01 Jan", 1000) == 1000);
    // Pacer proof (no server). Intervals are 1-stream and 32-stream at 10 MiB/s, 1024-byte chunks.
    // Limits = 10% of smoke6 p99_send (150 µs at 1 stream, 520 µs at 32 streams).
    // Mechanism checks run unpinned on a shared box: up to 3 attempts, any clean attempt passes.
    int pf = 1;
    for (int att = 1; att <= 3 && pf != 0; att++) {
      pf = pipelineSelfTest();
      if (pf != 0) System.out.println("pipeline/pacer-loop self-test attempt " + att + " had " + pf + " failed check(s)");
    }
    fail += pf;
    fail += paceSelfTest(97_656L, 2000, 15_000L, "1-stream-10MiB/s");
    fail += paceSelfTest(3_125_000L, 800, 52_000L, "32-stream-10MiB/s");
    return fail;
  }

  /**
   * #502 pacer root cause proof without the sjws server or a load generator: an in-JVM fake HTTP/1.1
   * server answers each pipelined POST 200 after a fixed 250 µs ack delay (more than the 97.7 µs
   * 1-stream slot). The legacy one-POST-at-a-time loop must fall behind (lag = ack wait); the
   * pipelined producer must keep lag_p99 small. Returns failures.
   */
  static int pipelineSelfTest() {
    final long ackDelayNs = 250_000L, iv = 97_656L, windowNs = 400_000_000L;
    int fail = 0;
    try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
      Thread srv = Thread.ofPlatform().daemon(true).start(() -> {
        try {
          while (!ss.isClosed()) {
            Socket c = ss.accept();
            c.setTcpNoDelay(true);
            Thread.ofPlatform().daemon(true).start(() -> fakeChunkServer(c, ackDelayNs));
          }
        } catch (IOException ignored) {}
      });
      URI base = URI.create("http://127.0.0.1:" + ss.getLocalPort());
      pipelineRun(base, iv, 150_000_000L, 8); // JIT warm-up, unchecked
      pipelineRun(base, iv, 50_000_000L, 1);
      double[] sync = pipelineRun(base, iv, windowNs, 1);
      double[] pipe = pipelineRun(base, iv, windowNs, 8);
      System.out.printf(Locale.ROOT,
          "pipeline-self-test ack_delay=%dus interval=%.1fus sync: lag_p99=%.0fus behind=%.1f%% sent=%.0f | pipelined(8): lag_p99=%.0fus behind=%.1f%% capwait=%.1f%% sent=%.0f producer_cpu=%.0f%%%n",
          ackDelayNs / 1000, iv / 1e3, sync[0], sync[1], sync[3], pipe[0], pipe[1], pipe[2], pipe[3], pipe[5]);
      fail += selfCheck("pipeline: sync falls behind (lag_p99 > 1 ms)", sync[0] > 1000);
      // Mechanism check only (unpinned, shared box): the real-load bound is the #502 pacer-check.
      fail += selfCheck("pipeline: pipelined lag_p99 < 1% of sync lag_p99", pipe[0] < 0.01 * sync[0]);
      fail += selfCheck("pipeline: pipelined sent all slots", pipe[3] >= pipe[4] - 1);
      pacerLoopRun(base, iv * 11, 300_000_000L, 11); // JIT warm-up, unchecked
      for (int ns : new int[] {1, 11}) {
        double[] lp = pacerLoopRun(base, iv * ns, windowNs, ns);
        System.out.printf(Locale.ROOT,
            "pacer-loop-self-test streams=%d interval=%.1fus lag_p50=%.0fus lag_p99=%.0fus (first/rest %.0f/%.0f) behind=%.1f%% (p50 %.0fus) capwait=%.1f%% sent=%.0f/%.0f pacer_cpu=%.0f%%%n",
            ns, iv * ns / 1e3, lp[5], lp[0], lp[7], lp[8], lp[1], lp[9], lp[2], lp[3], lp[4], lp[6]);
        fail += selfCheck("pacer-loop " + ns + ": lag_p99 < 1% of sync lag_p99", lp[0] < 0.01 * sync[0]);
        fail += selfCheck("pacer-loop " + ns + ": sent all slots", lp[3] >= lp[4] - ns);
      }
      srv.interrupt();
    } catch (Exception e) {
      System.err.println("pipeline-self-test error: " + e);
      fail++;
    }
    for (LongAdder c : List.of(SCHED_SENDS, SCHED_LATE, SCHED_SENT, PACE_WAKE_N, PACE_BEHIND_N, PACE_CAPWAIT_N)) c.reset();
    LAG.clearAll(); LAG_FIRST.clearAll(); LAG_REST.clearAll(); PACE_WAKE.clearAll(); PACE_BEHIND.clearAll();
    PACE_CAPWAIT.clearAll(); ACK_RTT.clearAll(); SCHED_LAG_MAX_NS.reset();
    return fail;
  }

  /** Returns {lag_p99_us, behind%, capwait%, sent, expected}. depth 1 = legacy sync loop. */
  static double[] pipelineRun(URI base, long iv, long windowNs, int depth) throws Exception {
    for (LongAdder c : List.of(SCHED_SENDS, SCHED_LATE, SCHED_SENT, PACE_WAKE_N, PACE_BEHIND_N, PACE_CAPWAIT_N)) c.reset();
    LAG.clearAll(); LAG_FIRST.clearAll(); LAG_REST.clearAll(); PACE_WAKE.clearAll(); PACE_BEHIND.clearAll();
    PACE_CAPWAIT.clearAll(); ACK_RTT.clearAll();
    double[] res = new double[6];
    Thread t = Thread.ofPlatform().start(() -> {
      setThreadTimerSlack(1_000L);
      java.lang.management.ThreadMXBean tmx = java.lang.management.ManagementFactory.getThreadMXBean();
      long cpu0 = tmx.getCurrentThreadCpuTime(), wall0 = System.nanoTime();
      try (LeanHttp lean = new LeanHttp(base, 10_000)) {
        Win w = new Win(true, 1, iv);
        long start = System.nanoTime() + 20_000_000L;
        w.startNs = start; w.endNs = start + windowNs;
        Track track = new Track(start, iv);
        byte[] payload = new byte[1024];
        byte[] hdr = lean.head("POST", "/c", "t", CT, null, FRAME_HDR + CHUNK_HDR + payload.length);
        Lease lease = new Lease("x", 1, 2, 1);
        Args a = new Args();
        if (depth > 1) {
          producePipelined(a, lean, hdr, lease, payload, w, track, start, iv, start, true, depth,
              new AtomicBoolean(), new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(),
              new AtomicLong(), new AtomicLong(), new AtomicLong(), "p", java.util.concurrent.ThreadLocalRandom.current());
        } else {
          long next = start, seq = 0;
          byte[] frame = null;
          while (next < w.endNs) {
            long arrive = System.nanoTime(), now = arrive;
            while (now < next) { parkUntilDeadline(next); now = System.nanoTime(); }
            recordPace(arrive, now, next, 0);
            LAG.add((now - next) / 1e3);
            frame = tokenFrameInto(1, 2, 1, seq++, 0, payload, frame);
            LeanResp r = lean.post(hdr, frame);
            if (r.status == 200) SCHED_SENT.increment();
            next += iv;
          }
        }
        res[5] = 100.0 * (tmx.getCurrentThreadCpuTime() - cpu0) / Math.max(1, System.nanoTime() - wall0);
      } catch (Exception e) {
        System.err.println("pipelineRun: " + e);
      }
    });
    t.join();
    long n = PACE_WAKE_N.sum() + PACE_BEHIND_N.sum() + PACE_CAPWAIT_N.sum();
    res[0] = LAG.count() > 0 ? LAG.pctFromBuckets(0.99) : Double.NaN;
    res[1] = n > 0 ? 100.0 * PACE_BEHIND_N.sum() / n : 0;
    res[2] = n > 0 ? 100.0 * PACE_CAPWAIT_N.sum() / n : 0;
    res[3] = SCHED_SENT.sum();
    res[4] = windowNs / iv;
    return res;
  }

  /** Self-test: {lag_p99, behind%, capwait%, sent, expected, lag_p50, pacer_cpu%} for the pacer loop. */
  static double[] pacerLoopRun(URI base, long iv, long windowNs, int streams) throws Exception {
    for (LongAdder c : List.of(SCHED_SENDS, SCHED_LATE, SCHED_SENT, PACE_WAKE_N, PACE_BEHIND_N, PACE_CAPWAIT_N)) c.reset();
    LAG.clearAll(); LAG_FIRST.clearAll(); LAG_REST.clearAll(); PACE_WAKE.clearAll(); PACE_BEHIND.clearAll();
    PACE_CAPWAIT.clearAll(); ACK_RTT.clearAll();
    Args a = new Args();
    a.window = 8;
    Win w = new Win(true, streams, iv);
    long start = System.nanoTime() + 30_000_000L;
    w.startNs = start; w.endNs = start + windowNs;
    PacerLoop pl = new PacerLoop(a, w, new AtomicBoolean(), new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(),
        new AtomicLong(), new AtomicLong(), new AtomicLong());
    w.pacer = pl;
    pl.start();
    java.lang.management.ThreadMXBean tmx = java.lang.management.ManagementFactory.getThreadMXBean();
    List<PacerLoop.Stream> sts = new ArrayList<>();
    byte[] payload = new byte[1024];
    java.util.Random r = new java.util.Random(7);
    for (int i = 0; i < streams; i++) {
      LeanHttp lean = new LeanHttp(base, 10_000);
      long phase = start + (long) (r.nextDouble() * iv);
      PacerLoop.Stream st = new PacerLoop.Stream(lean, lean.head("POST", "/c", "t", CT, null, FRAME_HDR + CHUNK_HDR + payload.length),
          new Lease("x", 1, 2 + i, 1), payload, new Track(phase, iv), phase, phase, true, 8, "p");
      sts.add(st);
      pl.register(st);
    }
    long cpu0 = tmx.getThreadCpuTime(pl.thread.threadId()), wall0 = System.nanoTime();
    for (PacerLoop.Stream st : sts) st.awaitDone(w.hardStop);
    long cpu1 = tmx.getThreadCpuTime(pl.thread.threadId()), wall1 = System.nanoTime();
    pl.shutdown();
    for (PacerLoop.Stream st : sts) st.lean.close();
    long n = PACE_WAKE_N.sum() + PACE_BEHIND_N.sum() + PACE_CAPWAIT_N.sum();
    return new double[] {
        LAG.count() > 0 ? LAG.pctFromBuckets(0.99) : Double.NaN,
        n > 0 ? 100.0 * PACE_BEHIND_N.sum() / n : 0,
        n > 0 ? 100.0 * PACE_CAPWAIT_N.sum() / n : 0,
        SCHED_SENT.sum(), (double) streams * (windowNs / iv),
        LAG.count() > 0 ? LAG.pctFromBuckets(0.50) : Double.NaN,
        100.0 * (cpu1 - cpu0) / Math.max(1, wall1 - wall0),
        LAG_FIRST.count() > 0 ? LAG_FIRST.pctFromBuckets(0.99) : Double.NaN,
        LAG_REST.count() > 0 ? LAG_REST.pctFromBuckets(0.99) : Double.NaN,
        PACE_BEHIND.count() > 0 ? PACE_BEHIND.pctFromBuckets(0.50) : Double.NaN};
  }

  /** Fake :chunk endpoint: reads pipelined requests in order, answers each 200 after {@code delayNs}. */
  static void fakeChunkServer(Socket c, long delayNs) {
    try (c) {
      InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
      OutputStream out = new BufferedOutputStream(c.getOutputStream(), 8192);
      byte[] resp = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
      ArrayDeque<Long> due = new ArrayDeque<>();
      Object lock = new Object();
      Thread writer = Thread.ofPlatform().daemon(true).start(() -> {
        try {
          for (;;) {
            long d;
            synchronized (lock) {
              while (due.isEmpty()) lock.wait();
              d = due.peekFirst();
            }
            long now;
            while ((now = System.nanoTime()) < d) LockSupport.parkNanos(d - now);
            synchronized (lock) { due.pollFirst(); }
            out.write(resp);
            out.flush();
          }
        } catch (Exception ignored) {}
      });
      StringBuilder line = new StringBuilder();
      for (;;) {
        int len = -1;
        // headers
        for (;;) {
          line.setLength(0);
          int b;
          while ((b = in.read()) >= 0 && b != '\n') if (b != '\r') line.append((char) b);
          if (b < 0) { writer.interrupt(); return; }
          if (line.length() == 0) break;
          String l = line.toString().toLowerCase(Locale.ROOT);
          if (l.startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
        }
        if (len > 0) in.readNBytes(len);
        synchronized (lock) { due.addLast(System.nanoTime() + delayNs); lock.notifyAll(); }
      }
    } catch (Exception ignored) {}
  }

  static int selfCheck(String name, boolean ok) {
    if (!ok) System.err.println("self-test FAIL: " + name);
    return ok ? 0 : 1;
  }

  static Result run(Args args, List<String> tokens) throws Exception {
    double load1Pre = load1();
    HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();

    LongAdder bytesRx = new LongAdder();
    ConcurrentLinkedQueue<Double> creditWaits = new ConcurrentLinkedQueue<>();
    LAT.clearAll(); LAT_CO.clearAll();
    ConcurrentLinkedQueue<String> measureErr = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> teardownErr = new ConcurrentLinkedQueue<>();
    AtomicBoolean inTeardown = new AtomicBoolean();
    AtomicLong requests = new AtomicLong();
    AtomicLong credit429 = new AtomicLong();
    AtomicLong producerTransportRetries = new AtomicLong();
    AtomicLong consumerConnectRetries = new AtomicLong();
    AtomicLong groupOpenRefusals = new AtomicLong(); // claim/stream HTTP 503 (ledger budget) — not transport

    resetFirstAdmit(false);
    // Warmup (same group count; fewer streams)
    wave(http, args, tokens, Math.min(32, args.streams), args.warmup, "w", false,
        inTeardown, bytesRx, creditWaits,
        measureErr, teardownErr, requests, credit429, producerTransportRetries, consumerConnectRetries, groupOpenRefusals);
    // Warmup-to-measure writer release: wait for the existing /metrics gauge
    // sjws_swarm_stream_open_sessions to drain (there is no ledger-writer gauge on /metrics).
    // Do not change product config or the ledger budget. Timeout = GRACE+SLACK; returns early at 0.
    Map<String, Double> wdBefore = Map.of(), wdAfter = Map.of();
    long wdWaitMs = 0;
    boolean wdTimedOut = false;
    try {
      String mu = args.base + "/metrics";
      wdBefore = fetchMetrics(http, mu);
      long tWait = System.nanoTime();
      DrainOutcome wdrain = waitOpenSessions(http, mu, (GRACE_MS + DRAIN_SLACK_MS) / 1000.0);
      wdWaitMs = (System.nanoTime() - tWait) / 1_000_000L;
      wdAfter = wdrain.counters();
      wdTimedOut = wdrain.timedOut();
      System.out.printf(Locale.ROOT,
          "warmup-drain open_before=%s open_after=%s charged_before=%s charged_after=%s wait_ms=%d timed_out=%s%n",
          wdBefore.get("sjws_swarm_stream_open_sessions"),
          wdAfter.get("sjws_swarm_stream_open_sessions"),
          wdBefore.get("sjws_swarm_stream_charged_bytes"),
          wdAfter.get("sjws_swarm_stream_charged_bytes"),
          wdWaitMs, wdTimedOut);
    } catch (Exception e) {
      System.err.println("warmup-drain scrape failed: " + e);
    }
    bytesRx.reset(); LAT.clearAll(); LAT_CO.clearAll(); clearPerS(); LAG.clearAll(); LAG_FIRST.clearAll(); LAG_REST.clearAll(); creditWaits.clear();
    SCHED_SENDS.reset(); SCHED_LATE.reset(); SCHED_LAG_MAX_NS.reset(); measureErr.clear(); teardownErr.clear();
    PACE_WAKE.clearAll(); PACE_BEHIND.clearAll(); PACE_CAPWAIT.clearAll(); ACK_RTT.clearAll();
    PACE_WAKE_N.reset(); PACE_BEHIND_N.reset(); PACE_CAPWAIT_N.reset();
    for (LongAdder c : List.of(SCHED_EXPECTED, SCHED_SENT, SCHED_RECEIVED, SCHED_UNRECEIVED,
        SCHED_PHANTOM_STREAMS, SCHED_STAMP_FALLBACK, SCHED_INFLIGHT_AT_END, SCHED_STREAMS_SHORT,
        SCHED_LATE_REGISTERED)) c.reset();
    CUTOFF_HIT.set(false); CATCHUP_CPU_SEC = 0; CATCHUP_WALL_S = 0;
    requests.set(0); credit429.set(0);
    producerTransportRetries.set(0); consumerConnectRetries.set(0);
    groupOpenRefusals.set(0);
    for (LongAdder c : List.of(HTTP503_RESPONSES, HTTP503_CLAIM, HTTP503_STREAM, HTTP503_BUDGET,
        HTTP503_CLAIM_GIVEUPS, HTTP503_STREAM_GIVEUPS)) c.reset();
    resetFirstAdmit(true);

    // Measure + sample client (/proc/self) and server (/proc/<serverPid>) CPU over the window
    AtomicReference<double[]> cpuSample = new AtomicReference<>(new double[]{0,0,0,0}); // clientSec, clientPct, serverSec, serverPct
    Thread cpuMon = Thread.startVirtualThread(() -> {
      try {
        // Skip first 2s (JIT/ramp), then 1s slices through the rest of the measure window,
        // including the last seconds. Previously the deadline was (secs-3) after this skip, which
        // missed the last ~3.5 s of a 20 s window.
        Thread.sleep(2000);
        double cSecSum = 0, sSecSum = 0, wallSum = 0;
        int n = 0;
        long deadline = System.nanoTime() + (long)(Math.max(3, args.secs) * 1e9);
        while (System.nanoTime() < deadline) {
          long c0 = readProcJiffies("self");
          long s0 = args.serverPid > 0 ? readProcJiffies(Integer.toString(args.serverPid)) : -1;
          long t0 = System.nanoTime();
          Thread.sleep(1000);
          long c1 = readProcJiffies("self");
          long s1 = args.serverPid > 0 ? readProcJiffies(Integer.toString(args.serverPid)) : -1;
          double wallS = (System.nanoTime() - t0) / 1e9;
          cSecSum += (c1 - c0) / scClk();
          if (s0 >= 0 && s1 >= 0) sSecSum += (s1 - s0) / scClk();
          wallSum += wallS;
          n++;
        }
        double cPct = wallSum > 0 ? cSecSum / wallSum * 100.0 : 0;
        double sPct = wallSum > 0 && n > 0 ? sSecSum / wallSum * 100.0 : Double.NaN;
        cpuSample.set(new double[]{cSecSum, cPct, sSecSum, sPct});
      } catch (Exception ignored) {}
    });

    final long gcMs0 = gcMillis(), gcN0 = gcCount();
    long t0 = System.nanoTime();
    int[] streamToGroup = wave(http, args, tokens, args.streams, args.secs, "g", true,
        inTeardown, bytesRx, creditWaits,
        measureErr, teardownErr, requests, credit429, producerTransportRetries, consumerConnectRetries, groupOpenRefusals);
    double wall = (System.nanoTime() - t0) / 1e9;
    final long gcMsWin = gcMillis() - gcMs0, gcNWin = gcCount() - gcN0;
    cpuMon.join(4000); // last 1s slice may still be in flight at wave return
    double load1Post = load1();

    DrainOutcome drain = waitDrained(http, args.base + "/metrics", args.drainTimeout);
    Map<String, Double> counters = drain.counters;
    Result r = new Result();
    r.mode = args.accept; r.streams = args.streams; r.agents = tokens.size(); r.window = args.window;
    r.groups = Math.max(1, Math.min(args.groups > 0 ? args.groups : args.streams, args.streams));
    r.streamToGroup = streamToGroup;
    r.writerCount = -1; // one.sh fills from swarm dir listing when known
    r.heapFlags = args.heapFlags;
    r.maxConnectionsPerIp = args.maxConnectionsPerIp;
    r.drainTimedOut = drain.timedOut;
    r.gcMsWindow = gcMsWin; r.gcCountWindow = gcNWin;
    r.pipelineDepth = (args.rateMibS > 0 && args.leanHttp)
        ? (args.pacerLoop ? PacerLoop.depth(args) : (args.pipelineDepth > 0 ? args.pipelineDepth : Math.max(1, args.window))) : 1;
    r.pacerLoop = args.rateMibS > 0 && args.leanHttp && args.pacerLoop;
    r.timerSlackSet = TIMER_SLACK_SET.sum(); r.paceWakeN = PACE_WAKE_N.sum(); r.paceBehindN = PACE_BEHIND_N.sum(); r.paceCapwaitN = PACE_CAPWAIT_N.sum();
    r.paceWakeHist = PACE_WAKE.sparseJson(); r.paceBehindHist = PACE_BEHIND.sparseJson();
    r.paceCapwaitHist = PACE_CAPWAIT.sparseJson(); r.ackRttHist = ACK_RTT.sparseJson();
    r.paceWakeP99 = PACE_WAKE.count() > 0 ? PACE_WAKE.pctFromBuckets(0.99) : null;
    r.paceBehindP99 = PACE_BEHIND.count() > 0 ? PACE_BEHIND.pctFromBuckets(0.99) : null;
    r.paceCapwaitP99 = PACE_CAPWAIT.count() > 0 ? PACE_CAPWAIT.pctFromBuckets(0.99) : null;
    r.ackRttP50 = ACK_RTT.count() > 0 ? ACK_RTT.pctFromBuckets(0.50) : null;
    r.ackRttP99 = ACK_RTT.count() > 0 ? ACK_RTT.pctFromBuckets(0.99) : null;
    r.warmupDrainOpenBefore = wdBefore.get("sjws_swarm_stream_open_sessions");
    r.warmupDrainOpenAfter = wdAfter.get("sjws_swarm_stream_open_sessions");
    r.warmupDrainChargedBefore = wdBefore.get("sjws_swarm_stream_charged_bytes");
    r.warmupDrainChargedAfter = wdAfter.get("sjws_swarm_stream_charged_bytes");
    r.warmupDrainWaitMs = wdWaitMs;
    r.warmupDrainTimedOut = wdTimedOut;
    r.refusedPerIp = counters.getOrDefault("sjws_connections_refused_per_ip_total", 0.0).longValue();
    r.elapsedS = args.secs; r.wallS = wall; r.load1Pre = load1Pre; r.load1Post = load1Post;
    double[] cpu = cpuSample.get();
    r.clientCpuSec = cpu[0]; r.clientCpuPctOfWall = cpu[1];
    r.serverCpuSec = cpu[2]; r.serverCpuPctOfWall = cpu[3];
    // Mode B (inceptor 2026-11-13 21:32): self_loaded iff gen CPU > 80% of its cores
    // (or, after merge-gen, any process > 80%). load1_post is not this flag.
    double satLim = args.genSatPct > 0 ? args.genSatPct : 80.0 * args.genCores;
    boolean genSat = !Double.isNaN(r.clientCpuPctOfWall) && r.clientCpuPctOfWall > satLim;
    r.selfLoaded = genSat;
    r.generatorSaturated = genSat;
    r.genCores = args.genCores;
    r.genSatLimitPct = satLim;
    r.chunkSamples = (int) Math.min(Integer.MAX_VALUE, SCHED_RECEIVED.sum());
    r.bytes = bytesRx.sum();
    r.mibS = (r.bytes / (1024.0 * 1024.0)) / Math.max(1e-6, args.secs);
    // inceptor Q1 (2026-11-11): paced windows gate on latency from each chunk's SCHEDULED send time;
    // closed loop keeps the send stamp (identical there). Both are always reported side by side.
    boolean paced = args.rateMibS > 0;
    LatBag gateLat = paced ? LAT_CO : LAT;
    r.latencyBasis = paced ? "scheduled" : "send";
    r.p50 = gateLat.pctFromBuckets(0.50); r.p90 = gateLat.pctFromBuckets(0.90); r.p99 = gateLat.pctFromBuckets(0.99);
    r.p50Send = LAT.pctFromBuckets(0.50); r.p90Send = LAT.pctFromBuckets(0.90); r.p99Send = LAT.pctFromBuckets(0.99);
    r.p50Sched = LAT_CO.pctFromBuckets(0.50); r.p90Sched = LAT_CO.pctFromBuckets(0.90); r.p99Sched = LAT_CO.pctFromBuckets(0.99);
    r.latHistSend = LAT.sparseJson(); r.latHistSched = LAT_CO.sparseJson(); r.schedPerS = perSJson();
    if (paced && LAG.count() > 0) { r.lagP50 = LAG.pctFromBuckets(0.50); r.lagP99 = LAG.pctFromBuckets(0.99); }
    if (paced && LAG_FIRST.count() > 0) r.lagFirstP99 = LAG_FIRST.pctFromBuckets(0.99);
    if (paced && LAG_REST.count() > 0) r.lagRestP99 = LAG_REST.pctFromBuckets(0.99);
    r.lagHist = LAG.sparseJson(); r.lagFirstHist = LAG_FIRST.sparseJson(); r.lagRestHist = LAG_REST.sparseJson();
    LAT.clearAll(); LAT_CO.clearAll(); LAG.clearAll(); LAG_FIRST.clearAll(); LAG_REST.clearAll();
    r.schedExpected = SCHED_EXPECTED.sum(); r.schedSent = SCHED_SENT.sum();
    r.schedReceived = SCHED_RECEIVED.sum(); r.schedUnreceived = SCHED_UNRECEIVED.sum();
    r.schedPhantomStreams = SCHED_PHANTOM_STREAMS.sum(); r.schedStampFallback = SCHED_STAMP_FALLBACK.sum();
    r.schedInflightAtEnd = SCHED_INFLIGHT_AT_END.sum(); r.cutoffHit = CUTOFF_HIT.get();
    r.schedStreamsShort = SCHED_STREAMS_SHORT.sum();
    r.schedLateRegistered = SCHED_LATE_REGISTERED.sum();
    r.catchupS = CATCHUP_WALL_S;
    r.catchupCpuPct = CATCHUP_WALL_S > 0 ? CATCHUP_CPU_SEC / CATCHUP_WALL_S * 100.0 : 0;
    r.harnessCommit = args.harnessCommit;
    r.validityMarker = VALIDITY_MARKER;
    r.rateMibS = args.rateMibS;
    r.leanConsumer = args.leanConsumerActive();
    long ss = SCHED_SENDS.sum();
    r.schedSends = ss;
    // Architecture item 2 (+ re-review item 1, one basis everywhere): actual offered =
    // sched_sends (send STARTS of in-window slots, retries not counted) x chunk bytes / secs.
    r.offeredActualMibS = paced ? ss * (double) args.chunkBytes / 1048576.0 / Math.max(1e-6, args.secs) : 0;
    r.schedLatePct = ss == 0 ? 0 : 100.0 * SCHED_LATE.sum() / ss;
    r.schedLagMaxUs = SCHED_LAG_MAX_NS.get() / 1e3;
    List<Double> cw = new ArrayList<>(creditWaits); Collections.sort(cw);
    r.creditWaitN = cw.size(); r.creditWaitTotal = cw.stream().mapToDouble(d->d).sum();
    r.creditWaitP50 = pct(cw, 0.50); r.creditWaitP99 = pct(cw, 0.99);
    r.creditStalls = credit429.get();
    r.stallPct = r.chunkSamples == 0 ? 0 : 100.0 * r.creditStalls / r.chunkSamples;
    r.stallFlag = r.stallPct > 1.0;
    r.counters = counters;
    r.errorCount = measureErr.size(); r.teardownErrorCount = teardownErr.size();
    r.errors = measureErr.stream().limit(50).toList();
    r.teardownErrors = teardownErr.stream().limit(50).toList();
    // credit_stalls = every HTTP 429 on :chunk (counted before backoff; never suppressed)
    // transport/connect retries counted on EVERY failure (not only exhausted) — never hide server EOFs
    r.producerTransportRetries = producerTransportRetries.get();
    r.consumerConnectRetries = consumerConnectRetries.get();
    r.groupOpenRefusals = groupOpenRefusals.get();
    r.http503Responses = HTTP503_RESPONSES.sum();
    r.http503ClaimResponses = HTTP503_CLAIM.sum();
    r.http503StreamResponses = HTTP503_STREAM.sum();
    r.http503BudgetExhausted = HTTP503_BUDGET.sum();
    r.http503ClaimGiveups = HTTP503_CLAIM_GIVEUPS.sum();
    r.http503StreamGiveups = HTTP503_STREAM_GIVEUPS.sum();
    r.firstAdmit = List.copyOf(FIRST_ADMIT_LOG);
    r.firstAdmitWarmup = List.copyOf(FIRST_ADMIT_WARMUP);
    // 503 group-open refusals are NOT transport retries (Messaging Core #335 / steering 16:34).
    r.transportRetryFlag = r.producerTransportRetries > 0 || r.consumerConnectRetries > 0;
    r.requests = requests.get();
    // Hard zeros read ONLY after drain (B1). refused_per_ip is a hard zero (A2 / Architecture).
    Map<String, Double> hz = new LinkedHashMap<>();
    hz.put("sjws_swarm_stream_node_refusals_total", counters.getOrDefault("sjws_swarm_stream_node_refusals_total", Double.NaN));
    hz.put("sjws_swarm_stream_session_admit_refusals_total", counters.getOrDefault("sjws_swarm_stream_session_admit_refusals_total", Double.NaN));
    hz.put("sjws_swarm_stream_budget_underflows_total", counters.getOrDefault("sjws_swarm_stream_budget_underflows_total", Double.NaN));
    hz.put("sjws_swarm_stream_reserve_underflows_total", counters.getOrDefault("sjws_swarm_stream_reserve_underflows_total", Double.NaN));
    hz.put("sjws_swarm_stream_charged_bytes", counters.getOrDefault("sjws_swarm_stream_charged_bytes", Double.NaN));
    hz.put("sjws_swarm_stream_open_sessions", counters.getOrDefault("sjws_swarm_stream_open_sessions", Double.NaN));
    hz.put("sjws_connections_refused_per_ip_total", (double) r.refusedPerIp);
    r.hardZeros = hz;
    Double open = counters.get("sjws_swarm_stream_open_sessions");
    r.openSessionsAfterDrain = open == null ? -1 : open.longValue();
    return r;
  }

  static int[] wave(HttpClient http, Args args, List<String> tokens, int n, double duration, String label,
      boolean account, AtomicBoolean inTeardown,
      LongAdder bytesRx, ConcurrentLinkedQueue<Double> creditWaits,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429,
      AtomicLong producerTransportRetries, AtomicLong consumerConnectRetries,
      AtomicLong groupOpenRefusals) throws Exception {
    inTeardown.set(false);
    Win w = new Win(account, n, intervalNs(args, n));
    int gCount = Math.max(1, Math.min(args.groups > 0 ? args.groups : n, n));
    int[] mapping = new int[n];
    List<Thread> consumers = new ArrayList<>(n), producers = new ArrayList<>(n);
    // Wait for every claim/:stream to finish (or fail) BEFORE publishing startNs. Opening the
    // window while 503 retries or late claims are still in flight caused a start-of-window
    // catch-up burst (wrk2 send-as-fast-as-possible) and late_reg phantoms.
    // Latch is released when claim / :stream finishes (success or give-up), BEFORE any
    // claimDelayMs sleep and BEFORE the send/read loop. Counting down in the thread's
    // finally (after producer()/consumer() return) waited for the whole wave, so every
    // wave sat on admitTimeout (30 s). That is why late/mid claims had late_reg=0 and
    // why the LEAN_CONSUMER=0 hung leg's one.sh wall was 167 s (warmup 30+3+5 + measure
    // 30+8+75+5).
    CountDownLatch admit = new CountDownLatch(2 * n);
    if (args.rateMibS > 0 && args.leanHttp && args.pacerLoop && w.intervalNs > 0) {
      w.pacer = new PacerLoop(args, w, inTeardown, measureErr, teardownErr, requests, credit429, producerTransportRetries);
      w.pacer.start();
    }
    for (int i = 0; i < n; i++) {
      int gid = args.groupOffset + (i % gCount);
      mapping[i] = gid;
      String pool = label + gid; // Architecture 2026-10-30 15:13: G groups, streams round-robin
      String tok = tokens.get(i % tokens.size());
      final int idx = i;
      consumers.add(Thread.startVirtualThread(() -> {
        try { consumer(http, args, tok, pool, w, idx, inTeardown, bytesRx, measureErr, teardownErr, consumerConnectRetries, groupOpenRefusals, admit); }
        catch (Exception e) { note(inTeardown, measureErr, teardownErr, "consumer " + pool, e); }
      }));
      if (i % 8 == 0) Thread.sleep(20); // slower admit ramp at 1k (was 10ms/32)
      Runnable prod = () -> {
        if (args.rateMibS > 0 && setThreadTimerSlack(1_000L)) TIMER_SLACK_SET.increment();
        try { producer(http, args, tok, pool, w, idx, inTeardown, creditWaits, measureErr, teardownErr, requests, credit429, producerTransportRetries, groupOpenRefusals, admit); }
        catch (Exception e) { note(inTeardown, measureErr, teardownErr, "producer " + pool, e); }
      };
      // Paced waves use platform threads so the deadline pacer is not scheduled behind other VTs.
      if (args.rateMibS > 0) {
        Thread t = Thread.ofPlatform().name("sl-p-" + idx).daemon(true).unstarted(prod);
        t.start();
        producers.add(t);
      } else {
        producers.add(Thread.startVirtualThread(prod));
      }
    }
    long admitDl = System.nanoTime() + (long) (Math.max(5.0, args.admitTimeoutS) * 1e9);
    while (admit.getCount() > 0 && System.nanoTime() < admitDl) {
      admit.await(50, TimeUnit.MILLISECONDS);
    }
    // The window opens after admit completes. Chunks count by slot time in [start, end). startNs is
    // published 50 ms BEFORE the window opens, so a producer that reads it after reading the clock
    // sees it whenever its clock reading is inside the window (producer: catch up from start).
    long start = System.nanoTime() + 50_000_000L;
    w.endNs = start + (long) (duration * 1e9);
    w.startNs = start;
    Thread.sleep(Math.max(0L, (w.endNs - System.nanoTime()) / 1_000_000L));
    // Producers finish their in-window slots (a late stream catches up) and send LAST; consumers
    // read until LAST. Nothing is cut at the window end: the hard cutoff is end + drain timeout
    // on a measure wave. Warmup does not need that 75 s drain: 5 s of slack is enough, and
    // hangEvery is measure-only, so a hung warmup can no longer add 75 s to the wall.
    long drainNs = account
        ? (long) (Math.max(args.drainTimeout, (GRACE_MS + DRAIN_SLACK_MS) / 1000.0) * 1e9)
        : 5_000_000_000L;
    long cutoff = w.endNs + drainNs;
    if (account) SCHED_INFLIGHT_AT_END.add(Math.max(0L, SCHED_SENT.sum() - SCHED_RECEIVED.sum()));
    // Optional (re-review): generator CPU over the catch-up period (window end -> producers done) is
    // reported separately; cpuMon only samples inside the window.
    long cj0 = account ? readProcJiffies("self") : -1, ct0 = System.nanoTime();
    boolean producersDone = joinUntil(producers, cutoff);
    if (account && cj0 >= 0) {
      long cj1 = readProcJiffies("self");
      CATCHUP_WALL_S = (System.nanoTime() - ct0) / 1e9;
      CATCHUP_CPU_SEC = cj1 >= 0 ? (cj1 - cj0) / scClk() : 0;
    }
    boolean allDone = producersDone & joinUntil(consumers, cutoff);
    long cutoffNs = System.nanoTime();
    if (!allDone) {
      if (account) CUTOFF_HIT.set(true);
      inTeardown.set(true);
      w.hardStop.set(true);
      for (LeanHttp h : w.consumerSockets) h.close(); // unblock readers stuck in read()
      // Close HttpClient bodies in parallel: a blocking close() on the main thread (up to the
      // 200 s request timeout) is what made the LEAN_CONSUMER=0 hung leg run to 251 s.
      List<Thread> closers = new ArrayList<>();
      for (InputStream in : w.consumerStreams) {
        closers.add(Thread.startVirtualThread(() -> { try { in.close(); } catch (IOException ignored) {} }));
      }
      joinUntil(closers, System.nanoTime() + 2_000_000_000L);
      joinUntil(producers, System.nanoTime() + 5_000_000_000L);
      joinUntil(consumers, System.nanoTime() + 5_000_000_000L);
    }
    if (w.pacer != null) w.pacer.shutdown();
    inTeardown.set(true);
    if (!"sse".equals(args.accept)) accountWave(w, cutoffNs); // SSE frames carry no stream id
    return mapping;
  }

  /** Join threads until {@code deadlineNs}; true if all finished. */
  static boolean joinUntil(List<Thread> ts, long deadlineNs) throws InterruptedException {
    for (Thread t : ts) {
      long left = deadlineNs - System.nanoTime();
      if (left <= 0) return !ts.stream().anyMatch(Thread::isAlive);
      t.join(Duration.ofNanos(left));
    }
    return !ts.stream().anyMatch(Thread::isAlive);
  }

  static void producer(HttpClient http, Args args, String token, String pool,
      Win w, int idx, AtomicBoolean inTeardown,
      ConcurrentLinkedQueue<Double> creditWaits,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429, AtomicLong producerTransportRetries,
      AtomicLong groupOpenRefusals, CountDownLatch admit) throws Exception {
    boolean released = false;
    try {
    HttpResponse<byte[]> claim = null;
    int claimSc = 0;
    // #335 / smoke2 floor: 503 on claim is often "ledger writer budget exhausted" (Retry-After: 1),
    // a transient. Treating it as fatal left phantom streams; their cutoff-slot ages then became
    // the 28.6 s scheduled p99 at 10 MiB/s, where delivered p50 was 142 µs and lag p99 1.5 ms.
    for (int attempt = 0; ; attempt++) {
      String claimUrl = args.base + "/swarm/v1/tasks/" + enc(pool) + ":claim?visibilityMs=60000&waitMs=30000&max=1";
      long claimT0 = System.nanoTime();
      claim = post(http, claimUrl, token, new byte[0]);
      claimSc = claim.statusCode();
      logFirstAdmit("claim", "POST", claimUrl, pool, claimSc, headerMap(claim.headers()), claim.body(),
          (System.nanoTime() - claimT0) / 1_000_000L);
      if (claimSc != 503) break;
      record503(true, claim.body());
      if (!sleep503(w.hardStop, header(claim, "retry-after"), attempt, 15)) {
        groupOpenRefusals.incrementAndGet();
        HTTP503_CLAIM_GIVEUPS.increment();
        note(inTeardown, measureErr, teardownErr, "producer-claim-refused " + pool,
            new IOException("claim 503" + bodyDetail(claim.body())));
        return;
      }
    }
    if (claimSc != 200) throw new IOException("claim " + claimSc);
    // Admit is done: release BEFORE claimDelay so the window can open. Then wait for
    // startNs to be published and only then sleep, so a delayed claim is actually late.
    admit.countDown();
    released = true;
    if (w.account && args.claimDelayMs > 0 && idx % 11 == 0) {
      while (w.startNs == Long.MAX_VALUE && !w.hardStop.get()) LockSupport.parkNanos(1_000_000L);
      if (!w.hardStop.get()) Thread.sleep(args.claimDelayMs);
    }
    Lease lease = parseLease(claim);
    String url = args.base + "/swarm/v1/leases/" + enc(lease.id) + ":chunk";
    String path = "/swarm/v1/leases/" + enc(lease.id) + ":chunk";
    long seq = 0;
    int pad = args.chunkBytes - 20;
    if (pad < 0) throw new IllegalArgumentException("chunk-bytes");
    // Sync one-POST-at-a-time per stream (f9215f7). Match SDK #261 Q9 on 429.
    // Pad is zero-filled once; the frame carries the send stamp at data[0..20) and (#415) the
    // schedule stamp at data[20..40) (equal to the send stamp when unpaced).
    byte[] payload = new byte[args.chunkBytes]; // zeros = pad
    byte[] frameScratch = null; // reused after first tokenFrame size known
    int mode = 0; // 0=unknown slow-start, 1=credit, 2=none (204)
    int creditChunks = 0, creditBytes = 0;
    int zeroTries = 0;
    java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
    LeanHttp lean = null;
    byte[] dataHdr = null; // #415: request head for data frames, built once per stream (fixed length)
    // #415 pacing: per-stream slot = n × chunkBytes / rate for this wave's n streams (warmup runs
    // at the gate rate too); random phase so 1k streams do not fire in lockstep. Slots are never
    // skipped: a late stream catches up (one sync POST at a time). The producer stops at the first
    // slot at or after the window end, so every in-window slot is sent (or counted unsent).
    // Re-review item 1: the phase is anchored at the wave epoch (before the window), so expected
    // slots cover the whole window whenever the claim returned. A claim returning inside or after the
    // window starts at the first slot >= start and catches up (those slots are late, never dropped).
    final long intervalNs = w.intervalNs;
    final long phaseNs = intervalNs > 0 ? w.epochNs + rnd.nextLong(intervalNs) : 0L;
    long nextNs = 0L;
    if (intervalNs > 0) {
      long now0 = System.nanoTime();
      long s0 = w.startNs; // read AFTER the clock (see wave(): startNs is published before start)
      nextNs = firstSlotAtOrAfter(phaseNs, intervalNs, w.account ? Math.min(now0, s0) : now0);
    }
    final boolean coStamp = args.chunkBytes >= 40; // second 20-digit stamp fits in the payload
    final AtomicBoolean stop = w.hardStop;
    Track track = new Track(phaseNs, intervalNs);
    w.tracks.put(new StreamKey(lease.streamHi, lease.streamLo), track);
    // Optional: count claims that returned at or after the window start (late / mid-window).
    if (w.account) {
      long nowReg = System.nanoTime();
      long startReg = w.startNs; // after the clock; MAX_VALUE until the window is published
      if (startReg != Long.MAX_VALUE && nowReg >= startReg) SCHED_LATE_REGISTERED.increment();
    }
    try {
      if (args.leanHttp) lean = new LeanHttp(URI.create(args.base), 60_000);
      final int depth = args.pipelineDepth > 0 ? args.pipelineDepth : Math.max(1, args.window);
      final boolean viaLoop = lean != null && intervalNs > 0 && w.pacer != null;
      final boolean pipelined = viaLoop || (lean != null && intervalNs > 0 && depth > 1);
      if (viaLoop) {
        PacerLoop.Stream st = new PacerLoop.Stream(lean,
            lean.head("POST", path, token, CT, null, FRAME_HDR + CHUNK_HDR + payload.length),
            lease, payload, track, phaseNs, nextNs, coStamp, PacerLoop.depth(args), pool);
        w.pacer.register(st);
        st.awaitDone(stop);
        seq = st.seq;
      } else if (pipelined) {
        seq = producePipelined(args, lean, lean.head("POST", path, token, CT, null, FRAME_HDR + CHUNK_HDR + payload.length),
            lease, payload, w, track, phaseNs, intervalNs, nextNs, coStamp, depth,
            inTeardown, measureErr, teardownErr, requests, credit429, producerTransportRetries, pool, rnd);
      }
      while (!pipelined && !stop.get()) {
        if (mode == 1 && creditChunks < 1) {
          double wait = creditBackoffS(zeroTries++, 1.0, rnd);
          creditWaits.add(wait);
          Thread.sleep((long) Math.max(1, wait * 1000));
          if (stop.get()) break;
        }
        long slotNs;
        if (intervalNs > 0) {
          // Re-review fix: never catch up UNMEASURED slots (warmup, or measure-wave slots before the
          // window); a backlog there would end in a burst right before the window opens.
          if (!w.account || nextNs < w.startNs) {
            long now0 = System.nanoTime();
            long s0 = w.startNs; // after the clock
            if (nextNs < now0)
              nextNs = firstSlotAtOrAfter(phaseNs, intervalNs, w.account ? Math.min(now0, s0) : now0);
          }
          if (nextNs >= w.endNs) break; // all in-window slots done
          long now = System.nanoTime();
          final long arriveNs = now;
          while (now < nextNs && !stop.get()) {
            parkUntilDeadline(nextNs);
            now = System.nanoTime();
          }
          if (stop.get()) break;
          slotNs = nextNs;
          nextNs += intervalNs;
          if (w.account && w.inWindow(slotNs)) {
            recordPace(arriveNs, now, slotNs, 0);
            long lag = now - slotNs;
            SCHED_SENDS.increment();
            if (lag > LATE_NS) SCHED_LATE.increment();
            SCHED_LAG_MAX_NS.accumulate(lag);
            LAG.add(lag / 1e3);
            if (slotNs < w.startNs + 1_000_000_000L) LAG_FIRST.add(lag / 1e3);
            else LAG_REST.add(lag / 1e3);
          }
        } else {
          slotNs = System.nanoTime();
          if (slotNs >= w.endNs) break; // closed loop: send until the window end
        }
        long emitNs = System.nanoTime();
        if (intervalNs <= 0) slotNs = emitNs; // closed loop: schedule stamp == send stamp
        int flags = (seq == 0 ? FIRST : 0);
        byte[] frame = tokenFrameInto(lease.streamHi, lease.streamLo, lease.attempt, seq, flags, payload, frameScratch);
        frameScratch = frame;
        writeAsciiNs(frame, FRAME_HDR + CHUNK_HDR, emitNs); // actual send stamp (LAT)
        if (coStamp) writeAsciiNs(frame, FRAME_HDR + CHUNK_HDR + 20, slotNs); // scheduled send time (LAT_CO, paced gate p99)
        int tries = 0;
        while (!stop.get()) {
          try {
            int sc;
            byte[] body;
            String raHdr = null;
            if (lean != null) {
              if (dataHdr == null) dataHdr = lean.head("POST", path, token, CT, null, frame.length);
              LeanResp lr = lean.post(dataHdr, frame);
              sc = lr.status; body = lr.body; raHdr = lr.retryAfter;
              if (lr.recoveredAttempts > 0 && !inTeardown.get())
                producerTransportRetries.addAndGet(lr.recoveredAttempts);
            } else {
              HttpResponse<byte[]> r = post(http, url, token, frame);
              sc = r.statusCode(); body = r.body();
              raHdr = r.headers().firstValue("retry-after").orElse(null);
            }
            requests.incrementAndGet();
            if (intervalNs > 0 && w.account && w.inWindow(slotNs)) ACK_RTT.add((System.nanoTime() - emitNs) / 1e3);
            if (sc == 429) {
              credit429.incrementAndGet();
              double ra = retryAfterFrom(raHdr);
              double wait = creditBackoffS(tries, ra, rnd);
              creditWaits.add(wait);
              tries++;
              Thread.sleep((long) Math.max(1, wait * 1000));
              continue;
            }
            if (sc == 204) {
              mode = 2; creditChunks = 0; creditBytes = 0; zeroTries = 0;
              seq++;
              if (w.account && w.inWindow(slotNs)) { track.sent.incrementAndGet(); SCHED_SENT.increment(); }
              break;
            }
            if (sc != 200) throw new IOException("chunk " + sc);
            if (w.account && w.inWindow(slotNs)) { track.sent.incrementAndGet(); SCHED_SENT.increment(); }
            int[] snap = parseCreditChunksBytes(body);
            if (snap != null) {
              mode = 1;
              creditChunks = snap[0];
              creditBytes = snap[1];
              zeroTries = 0;
            }
            seq++;
            break;
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
          } catch (Exception e) {
            if (!inTeardown.get()) producerTransportRetries.incrementAndGet();
            if (tries >= 7) {
              note(inTeardown, measureErr, teardownErr, "producer-send " + pool, e);
              break;
            }
            try { Thread.sleep(5L << Math.min(tries, 6)); } catch (InterruptedException ie) {
              Thread.currentThread().interrupt(); return;
            }
            tries++;
          }
        }
      }
      byte[] last = tokenFrame(lease.streamHi, lease.streamLo, lease.attempt, seq, LAST, new byte[0]);
      for (int tries = 0; tries < 8; tries++) {
        try {
          int sc; String raHdr = null;
          if (lean != null) {
            LeanResp lr = lean.post(path, token, last);
            sc = lr.status; raHdr = lr.retryAfter;
            if (lr.recoveredAttempts > 0 && !inTeardown.get())
              producerTransportRetries.addAndGet(lr.recoveredAttempts);
          } else {
            HttpResponse<byte[]> r = post(http, url, token, last);
            sc = r.statusCode();
            raHdr = r.headers().firstValue("retry-after").orElse(null);
          }
          requests.incrementAndGet();
          if (sc == 200 || sc == 204) break;
          if (sc == 429) {
            double wait = creditBackoffS(tries, retryAfterFrom(raHdr), rnd);
            Thread.sleep((long) Math.max(1, wait * 1000));
            continue;
          }
          note(inTeardown, measureErr, teardownErr, "producer-last " + pool, new IOException("last " + sc));
          break;
        } catch (Exception e) {
          note(inTeardown, measureErr, teardownErr, "producer-last " + pool, e);
          break;
        }
      }
    } finally {
      if (lean != null) lean.close();
    }
    } finally {
      if (!released) admit.countDown();
    }
  }

  /**
   * #502 pacer-check2: one pacing loop per generator process. A single platform thread keeps every
   * registered paced stream in a deadline-ordered queue and sends each slot on that stream's
   * keep-alive socket (small write into an empty-enough socket buffer: the in-flight window per
   * stream is bounded, so the write does not block on the peer). Acks are read without blocking
   * (available() &gt; 0) in the slack before the next deadline. The loop parks until
   * {@code pacerSpinUs} before the slot and spins only that long, so nothing spins for long next
   * to the consumers, which keep their own threads. A stream whose window is full is set aside
   * (CAPWAIT) without blocking the others. Producer threads only claim, register, wait, and send
   * LAST. Lag split (WAKE / BEHIND / CAPWAIT), ACK_RTT and the measured slot counters are the same
   * as the per-thread producers.
   */
  static final class PacerLoop implements Runnable {
    static int depth(Args a) { return a.pipelineDepth > 1 ? a.pipelineDepth : Math.max(1, a.window); }

    static final class Stream {
      final LeanHttp lean; final byte[] dataHdr; final Lease lease; final byte[] payload; final Track track;
      final long phaseNs; long nextNs; final boolean coStamp; final int depth; final String pool;
      final ArrayDeque<Pending> inflight = new ArrayDeque<>();
      final ArrayDeque<byte[]> free = new ArrayDeque<>();
      final List<Pending> redo = new ArrayList<>();
      long seq; long capT0; boolean draining;
      final CountDownLatch done = new CountDownLatch(1);
      Stream(LeanHttp lean, byte[] dataHdr, Lease lease, byte[] payload, Track track, long phaseNs, long nextNs,
          boolean coStamp, int depth, String pool) {
        this.lean = lean; this.dataHdr = dataHdr; this.lease = lease; this.payload = payload; this.track = track;
        this.phaseNs = phaseNs; this.nextNs = nextNs; this.coStamp = coStamp; this.depth = depth; this.pool = pool;
      }
      void awaitDone(AtomicBoolean stop) throws InterruptedException {
        while (!done.await(50, TimeUnit.MILLISECONDS)) if (stop.get()) return;
      }
    }

    final Args args; final Win w; final AtomicBoolean inTeardown;
    final ConcurrentLinkedQueue<String> measureErr, teardownErr;
    final AtomicLong requests, credit429, producerTransportRetries;
    final ConcurrentLinkedQueue<Stream> incoming = new ConcurrentLinkedQueue<>();
    final PriorityQueue<Stream> ready = new PriorityQueue<>((a, b) -> Long.compare(a.nextNs, b.nextNs));
    final List<Stream> all = new ArrayList<>(), blocked = new ArrayList<>(), finishing = new ArrayList<>();
    final long spinNs;
    volatile boolean shut;
    Thread thread;

    PacerLoop(Args args, Win w, AtomicBoolean inTeardown, ConcurrentLinkedQueue<String> measureErr,
        ConcurrentLinkedQueue<String> teardownErr, AtomicLong requests, AtomicLong credit429,
        AtomicLong producerTransportRetries) {
      this.args = args; this.w = w; this.inTeardown = inTeardown; this.measureErr = measureErr;
      this.teardownErr = teardownErr; this.requests = requests; this.credit429 = credit429;
      this.producerTransportRetries = producerTransportRetries;
      this.spinNs = Math.max(0L, args.pacerSpinUs) * 1000L;
    }
    void start() { thread = Thread.ofPlatform().name("sl-pacer").daemon(true).start(this); }
    void register(Stream st) { incoming.add(st); LockSupport.unpark(thread); }
    void shutdown() {
      shut = true;
      LockSupport.unpark(thread);
      try { thread.join(5_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public void run() {
      if (setThreadTimerSlack(1_000L)) TIMER_SLACK_SET.increment();
      final java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
      final AtomicBoolean stop = w.hardStop;
      while (!shut && !stop.get()) {
        Stream st;
        while ((st = incoming.poll()) != null) { all.add(st); ready.add(st); }
        serviceBlocked();
        serviceFinishing();
        Stream head = ready.peek();
        if (head == null) {
          pollAcks(Long.MAX_VALUE);
          LockSupport.parkNanos(blocked.isEmpty() && finishing.isEmpty() ? 1_000_000L : 20_000L);
          continue;
        }
        // Unmeasured slots (warmup, or before the window) are skipped, never caught up.
        long now0 = System.nanoTime();
        if (!w.account || head.nextNs < w.startNs) {
          long s0 = w.startNs;
          if (head.nextNs < now0) {
            ready.poll();
            head.nextNs = firstSlotAtOrAfter(head.phaseNs, w.intervalNs, w.account ? Math.min(now0, s0) : now0);
            ready.add(head);
            continue;
          }
        }
        if (head.nextNs >= w.endNs) { ready.poll(); head.draining = true; finishing.add(head); continue; }
        final long slotNs = head.nextNs;
        final long arriveNs = now0;
        // Slack before the slot: read acks without blocking, then park, then a short spin.
        long rem = slotNs - System.nanoTime();
        if (rem > spinNs) {
          if (rem > spinNs + 30_000L) {
            pollAcks(slotNs - spinNs - 20_000L);
            rem = slotNs - System.nanoTime();
          }
          if (rem > spinNs) {
            LockSupport.parkNanos(Math.min(rem - spinNs, 1_000_000L));
            if (slotNs - System.nanoTime() > spinNs) continue; // early or new work: re-evaluate
          }
        }
        while (System.nanoTime() < slotNs) Thread.onSpinWait();
        ready.poll();
        if (head.inflight.size() >= head.depth) {
          try { while (head.lean.in != null && head.lean.in.available() > 0 && !head.inflight.isEmpty())
                  handleOne(head.lean, head.inflight, head.free, head.redo, requests, w, head.track); }
          catch (IOException e) { fail(head, e); }
          if (head.inflight.size() >= head.depth) {
            if (head.capT0 == 0) head.capT0 = System.nanoTime();
            blocked.add(head);
            continue;
          }
        }
        send(head, arriveNs, rnd);
        ready.add(head);
      }
      // Hard stop or shutdown: release every waiting producer.
      for (Stream s : all) s.done.countDown();
    }

    void send(Stream st, long arriveNs, java.util.concurrent.ThreadLocalRandom rnd) {
      if (!st.redo.isEmpty()) resendRedo(st, rnd);
      long sendNs = System.nanoTime();
      long slotNs = st.nextNs;
      long capWait = st.capT0 != 0 ? sendNs - st.capT0 : 0;
      st.capT0 = 0;
      st.nextNs += w.intervalNs;
      boolean counted = w.account && w.inWindow(slotNs);
      if (counted) {
        recordPace(capWait > 0 ? Math.min(arriveNs, slotNs) : arriveNs, sendNs, slotNs, capWait);
        long lag = sendNs - slotNs;
        SCHED_SENDS.increment();
        if (lag > LATE_NS) SCHED_LATE.increment();
        SCHED_LAG_MAX_NS.accumulate(lag);
        LAG.add(lag / 1e3);
        if (slotNs < w.startNs + 1_000_000_000L) LAG_FIRST.add(lag / 1e3);
        else LAG_REST.add(lag / 1e3);
      }
      byte[] buf = st.free.pollFirst();
      int flags = (st.seq == 0 ? FIRST : 0);
      byte[] frame = tokenFrameInto(st.lease.streamHi, st.lease.streamLo, st.lease.attempt, st.seq, flags, st.payload, buf);
      long emitNs = System.nanoTime();
      writeAsciiNs(frame, FRAME_HDR + CHUNK_HDR, emitNs);
      if (st.coStamp) writeAsciiNs(frame, FRAME_HDR + CHUNK_HDR + 20, slotNs);
      st.inflight.addLast(new Pending(st.seq, slotNs, frame, emitNs, counted));
      st.seq++;
      try {
        st.lean.send(st.dataHdr, frame);
      } catch (IOException e) {
        fail(st, e);
      }
    }

    /** Transport error: everything unacked is resent in seq order on a new connection (server dedups). */
    void fail(Stream st, IOException e) {
      if (!inTeardown.get()) producerTransportRetries.incrementAndGet();
      st.lean.closeQuiet();
      st.redo.addAll(st.inflight);
      st.inflight.clear();
      st.redo.sort(java.util.Comparator.comparingLong(Pending::seq));
    }

    void resendRedo(Stream st, java.util.concurrent.ThreadLocalRandom rnd) {
      try {
        resendSync(st.lean, st.dataHdr, st.redo, st.free, w, st.track, requests, credit429, producerTransportRetries,
            inTeardown, measureErr, teardownErr, st.pool, rnd);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }

    /** Non-blocking ack reads for every stream with requests in flight, until {@code untilNs}. */
    void pollAcks(long untilNs) {
      for (int i = 0; i < all.size(); i++) {
        if (System.nanoTime() >= untilNs) return;
        Stream st = all.get(i);
        if (st.inflight.isEmpty()) continue;
        try {
          while (!st.inflight.isEmpty() && st.lean.in != null && st.lean.in.available() > 0)
            handleOne(st.lean, st.inflight, st.free, st.redo, requests, w, st.track);
        } catch (IOException e) {
          fail(st, e);
        }
      }
    }

    void serviceBlocked() {
      if (blocked.isEmpty()) return;
      for (java.util.Iterator<Stream> it = blocked.iterator(); it.hasNext(); ) {
        Stream st = it.next();
        try {
          while (!st.inflight.isEmpty() && st.lean.in != null && st.lean.in.available() > 0)
            handleOne(st.lean, st.inflight, st.free, st.redo, requests, w, st.track);
        } catch (IOException e) {
          fail(st, e);
        }
        if (st.inflight.size() < st.depth) { it.remove(); ready.add(st); }
      }
    }

    void serviceFinishing() {
      if (finishing.isEmpty()) return;
      for (java.util.Iterator<Stream> it = finishing.iterator(); it.hasNext(); ) {
        Stream st = it.next();
        try {
          while (!st.inflight.isEmpty() && st.lean.in != null && st.lean.in.available() > 0)
            handleOne(st.lean, st.inflight, st.free, st.redo, requests, w, st.track);
        } catch (IOException e) {
          fail(st, e);
        }
        if (st.inflight.isEmpty()) {
          if (!st.redo.isEmpty()) resendRedo(st, java.util.concurrent.ThreadLocalRandom.current());
          it.remove();
          st.done.countDown(); // producer thread now owns the socket again (LAST)
        }
      }
    }
  }

  /** One in-flight pipelined :chunk. */
  record Pending(long seq, long slotNs, byte[] frame, long writeNs, boolean counted) {}

  /**
   * #502 pacer fix: paced lean producer with up to {@code depth} pipelined :chunk POSTs on its
   * keep-alive socket. The legacy loop sent one POST and waited for its ack before the next slot,
   * so any ack slower than the slot interval (97.7 µs at 1 stream / 10 MiB/s) put every later slot
   * behind and was counted as schedule lag. Here the slot is only delayed by the pacer itself
   * (WAKE), by the producer falling behind (BEHIND), or by a full in-flight cap (CAPWAIT, real
   * server back-pressure). Responses are read on this same thread while it waits for the next
   * slot (no extra thread on the generator's pinned core). A non-2xx answer or a transport error
   * falls back to an ordered synchronous resend (the server dedups :chunk retries, #248 r5 C2).
   * Returns the next seq (for LAST).
   */
  static long producePipelined(Args args, LeanHttp lean, byte[] dataHdr, Lease lease, byte[] payload, Win w,
      Track track, long phaseNs, long intervalNs, long nextNs0, boolean coStamp, int depth,
      AtomicBoolean inTeardown, ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429, AtomicLong producerTransportRetries, String pool,
      java.util.concurrent.ThreadLocalRandom rnd) throws InterruptedException {
    final AtomicBoolean stop = w.hardStop;
    final ArrayDeque<Pending> inflight = new ArrayDeque<>(depth + 1);
    final ArrayDeque<byte[]> free = new ArrayDeque<>(depth + 1);
    final List<Pending> redo = new ArrayList<>();
    long seq = 0, nextNs = nextNs0;
    while (!stop.get()) {
      if (!redo.isEmpty()) {
        try {
          drainAll(lean, inflight, free, redo, requests, w, track);
        } catch (IOException e) {
          if (!inTeardown.get()) producerTransportRetries.incrementAndGet();
          lean.closeQuiet();
          redo.addAll(inflight);
          inflight.clear();
        }
        resendSync(lean, dataHdr, redo, free, w, track, requests, credit429, producerTransportRetries,
            inTeardown, measureErr, teardownErr, pool, rnd);
        if (stop.get()) break;
      }
      if (!w.account || nextNs < w.startNs) {
        long now0 = System.nanoTime();
        long s0 = w.startNs;
        if (nextNs < now0) nextNs = firstSlotAtOrAfter(phaseNs, intervalNs, w.account ? Math.min(now0, s0) : now0);
      }
      if (nextNs >= w.endNs) break;
      final long arriveNs = System.nanoTime();
      // Wait for the slot; read acks that are already here instead of sleeping on them.
      try {
        for (;;) {
          long now = System.nanoTime();
          long rem = nextNs - now;
          if (rem <= 0 || stop.get()) break;
          if (!inflight.isEmpty() && lean.in != null && lean.in.available() > 0) {
            handleOne(lean, inflight, free, redo, requests, w, track);
            continue;
          }
          if (!parkStep(nextNs, 1_000_000L)) Thread.onSpinWait();
        }
        if (stop.get()) break;
        long capT0 = System.nanoTime();
        boolean full = inflight.size() >= depth;
        while (inflight.size() >= depth) handleOne(lean, inflight, free, redo, requests, w, track);
        long sendNs = System.nanoTime();
        long capWait = full ? sendNs - capT0 : 0;
        long slotNs = nextNs;
        nextNs += intervalNs;
        boolean counted = w.account && w.inWindow(slotNs);
        if (counted) {
          recordPace(arriveNs, sendNs, slotNs, capWait);
          long lag = sendNs - slotNs;
          SCHED_SENDS.increment();
          if (lag > LATE_NS) SCHED_LATE.increment();
          SCHED_LAG_MAX_NS.accumulate(lag);
          LAG.add(lag / 1e3);
          if (slotNs < w.startNs + 1_000_000_000L) LAG_FIRST.add(lag / 1e3);
          else LAG_REST.add(lag / 1e3);
        }
        byte[] buf = free.pollFirst();
        int flags = (seq == 0 ? FIRST : 0);
        byte[] frame = tokenFrameInto(lease.streamHi, lease.streamLo, lease.attempt, seq, flags, payload, buf);
        long emitNs = System.nanoTime();
        writeAsciiNs(frame, FRAME_HDR + CHUNK_HDR, emitNs);
        if (coStamp) writeAsciiNs(frame, FRAME_HDR + CHUNK_HDR + 20, slotNs);
        Pending pd = new Pending(seq, slotNs, frame, emitNs, counted);
        inflight.addLast(pd);
        seq++;
        lean.send(dataHdr, frame);
      } catch (IOException e) {
        if (!inTeardown.get()) producerTransportRetries.incrementAndGet();
        lean.closeQuiet();
        // Everything not yet acked is resent in seq order on a new connection (server dedups).
        redo.addAll(inflight);
        inflight.clear();
        redo.sort(java.util.Comparator.comparingLong(Pending::seq));
      }
    }
    try {
      drainAll(lean, inflight, free, redo, requests, w, track);
    } catch (IOException e) {
      lean.closeQuiet();
      redo.addAll(inflight);
      inflight.clear();
      redo.sort(java.util.Comparator.comparingLong(Pending::seq));
    }
    if (!redo.isEmpty())
      resendSync(lean, dataHdr, redo, free, w, track, requests, credit429, producerTransportRetries,
          inTeardown, measureErr, teardownErr, pool, rnd);
    return seq;
  }

  static void drainAll(LeanHttp lean, ArrayDeque<Pending> inflight, ArrayDeque<byte[]> free, List<Pending> redo,
      AtomicLong requests, Win w, Track track) throws IOException {
    while (!inflight.isEmpty()) handleOne(lean, inflight, free, redo, requests, w, track);
  }

  /** Read one in-order response for the oldest in-flight :chunk. */
  static void handleOne(LeanHttp lean, ArrayDeque<Pending> inflight, ArrayDeque<byte[]> free, List<Pending> redo,
      AtomicLong requests, Win w, Track track) throws IOException {
    LeanResp r = lean.readResponse(true);
    Pending pd = inflight.pollFirst();
    if (pd == null) throw new IOException("pipelined response without request");
    requests.incrementAndGet();
    if (pd.counted()) ACK_RTT.add((System.nanoTime() - pd.writeNs()) / 1e3);
    if (r.status == 200 || r.status == 204) {
      if (pd.counted()) { track.sent.incrementAndGet(); SCHED_SENT.increment(); }
      free.addLast(pd.frame());
    } else {
      redo.add(pd); // 429 / 5xx: ordered sync resend after the pipe drains
    }
  }

  /** Ordered synchronous resend of refused or unacked frames (legacy 429 backoff semantics). */
  static void resendSync(LeanHttp lean, byte[] dataHdr, List<Pending> redo, ArrayDeque<byte[]> free, Win w, Track track,
      AtomicLong requests, AtomicLong credit429, AtomicLong producerTransportRetries, AtomicBoolean inTeardown,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr, String pool,
      java.util.concurrent.ThreadLocalRandom rnd) throws InterruptedException {
    redo.sort(java.util.Comparator.comparingLong(Pending::seq));
    for (Pending pd : redo) {
      int tries = 0;
      while (!w.hardStop.get()) {
        try {
          LeanResp lr = lean.post(dataHdr, pd.frame());
          if (lr.recoveredAttempts > 0 && !inTeardown.get()) producerTransportRetries.addAndGet(lr.recoveredAttempts);
          requests.incrementAndGet();
          if (lr.status == 429) {
            credit429.incrementAndGet();
            double wait = creditBackoffS(tries++, retryAfterFrom(lr.retryAfter), rnd);
            Thread.sleep((long) Math.max(1, wait * 1000));
            continue;
          }
          if (lr.status == 200 || lr.status == 204) {
            if (pd.counted()) { track.sent.incrementAndGet(); SCHED_SENT.increment(); }
          } else {
            note(inTeardown, measureErr, teardownErr, "producer-send " + pool, new IOException("chunk " + lr.status));
          }
          break;
        } catch (IOException e) {
          if (!inTeardown.get()) producerTransportRetries.incrementAndGet();
          if (tries++ >= 7) { note(inTeardown, measureErr, teardownErr, "producer-send " + pool, e); break; }
          Thread.sleep(5L << Math.min(tries, 6));
        }
      }
      free.addLast(pd.frame());
    }
    redo.clear();
  }

  static double retryAfterFrom(String v) {
    if (v == null || v.isBlank()) return 1.0;
    try { return Math.max(0.0, Double.parseDouble(v.trim())); }
    catch (NumberFormatException e) { return 1.0; }
  }

  /** Build TOKEN frame; reuse {@code into} when length matches (same layout as tokenFrame). */
  static byte[] tokenFrameInto(long hi, long lo, long attempt, long seq, int flags, byte[] payload, byte[] into) {
    int need = FRAME_HDR + CHUNK_HDR + payload.length;
    byte[] out = (into != null && into.length == need) ? into : new byte[need];
    // chunk body: kind=2 + hi + lo + attempt + seq + flags + data  (see tokenFrame)
    ByteBuffer bb = ByteBuffer.wrap(out, FRAME_HDR, CHUNK_HDR + payload.length);
    bb.put((byte)2).putLong(hi).putLong(lo).putInt((int)attempt).putInt((int)seq).put((byte)flags).put(payload);
    // frame header at [0..6): ver=1, type=TOKEN, flags, 24-bit length
    int fl = flags & LAST;
    int len = CHUNK_HDR + payload.length;
    out[0] = 1;
    out[1] = (byte) TYPE_TOKEN;
    out[2] = (byte) fl;
    out[3] = (byte) ((len >> 16) & 0xff);
    out[4] = (byte) ((len >> 8) & 0xff);
    out[5] = (byte) (len & 0xff);
    return out;
  }

  static double creditBackoffS(int tries, double retryAfter, java.util.concurrent.ThreadLocalRandom rnd) {
    double base = CREDIT_FLOOR_S * (1L << Math.min(tries, 20));
    return Math.min(retryAfter, base * (0.8 + 0.4 * rnd.nextDouble()));
  }
  static double retryAfterS(HttpResponse<?> r) {
    return r.headers().firstValue("retry-after").map(v -> {
      try { return Math.max(0.0, Double.parseDouble(v.trim())); }
      catch (NumberFormatException e) { return 1.0; }
    }).orElse(1.0);
  }
  /** @return int[]{chunks,bytes} or null if no CREDIT frame */
  static int[] parseCreditChunksBytes(byte[] body) {
    if (body == null || body.length < FRAME_HDR) return null;
    try {
      ByteBuffer buf = ByteBuffer.wrap(body);
      while (buf.remaining() >= FRAME_HDR) {
        int ver = buf.get() & 0xff, type = buf.get() & 0xff; buf.get(); // flags
        int len = ((buf.get() & 0xff) << 16) | ((buf.get() & 0xff) << 8) | (buf.get() & 0xff);
        if (buf.remaining() < len) return null;
        if (type == TYPE_CREDIT && len == 29) {
          int pos = buf.position();
          if ((buf.get(pos) & 0xff) != 1) { buf.position(pos + len); continue; }
          // version(1)+stream(16)+attempt(4)+chunks(4)+bytes(4)
          int chunks = buf.getInt(pos + 1 + 16 + 4);
          int nbytes = buf.getInt(pos + 1 + 16 + 4 + 4);
          return new int[]{chunks, nbytes};
        }
        buf.position(buf.position() + len);
      }
    } catch (Exception ignored) {}
    return null;
  }

  /** Write 20 ASCII decimal digits of {@code ns} into {@code dst[off..off+20)}. */
  static void writeAsciiNs(byte[] dst, int off, long ns) {
    for (int i = 19; i >= 0; i--) {
      dst[off + i] = (byte)('0' + (int)(ns % 10));
      ns /= 10;
    }
  }

  static void consumer(HttpClient http, Args args, String token, String pool,
      Win w, int idx, AtomicBoolean inTeardown, LongAdder bytesRx,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong consumerConnectRetries, AtomicLong groupOpenRefusals, CountDownLatch admit) throws Exception {
    if (args.leanConsumerActive()) {
      consumerLean(args, token, pool, w, idx, inTeardown, bytesRx, measureErr, teardownErr, consumerConnectRetries, groupOpenRefusals, admit);
      return;
    }
    boolean released = false;
    String cancelId = null;
    try {
    AtomicBoolean stop = w.hardStop;
    byte[] task = frame(TYPE_TASK, 0, "gate".getBytes(StandardCharsets.UTF_8));
    Exception last = null;
    boolean sessionOpened = false; // 201 received: never POST :stream again (Architecture: no second session)
    for (int attempt = 0, n503 = 0; attempt < 3 && !stop.get(); ) {
      try {
        if (sessionOpened) throw last != null ? last : new IOException("stream already opened");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(args.base + "/swarm/v1/tasks/" + enc(pool) + ":stream?deadlineMs=180000"))
            .timeout(Duration.ofSeconds(200))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", CT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(task));
        if ("binary".equals(args.accept) || "chunked".equals(args.accept))
          b.header("Accept", CT);
        else
          b.header("Accept", "text/event-stream");
        String streamUrl = args.base + "/swarm/v1/tasks/" + enc(pool) + ":stream?deadlineMs=180000";
        long streamT0 = System.nanoTime();
        HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        int streamSc = resp.statusCode();
        logFirstAdmit("stream", "POST", streamUrl, pool, streamSc, headerMap(resp.headers()), resp.body(),
            (System.nanoTime() - streamT0) / 1_000_000L);
        if (streamSc == 503) {
          record503(false, resp.body());
          if (sleep503(stop, header(resp, "retry-after"), n503++, 15)) continue;
          groupOpenRefusals.incrementAndGet();
          HTTP503_STREAM_GIVEUPS.increment();
          note(inTeardown, measureErr, teardownErr, "consumer-stream-refused " + pool,
              new IOException("stream 503" + bodyDetail(resp.body())));
          return;
        }
        if (streamSc != 201 && streamSc != 200)
          throw new IOException("stream " + streamSc);
        sessionOpened = true; // from here a resend would open a second session
        String loc = resp.headers().firstValue("location").orElse(null);
        if (loc == null) {
          Frame f = decodeFrame(ByteBuffer.wrap(resp.body()));
          loc = "/swarm/v1/replies/" + hex(f.payload);
        }
        int slash = loc.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < loc.length()) cancelId = loc.substring(slash + 1);
        if (!loc.startsWith("http")) loc = args.base + loc;
        HttpRequest.Builder gb = HttpRequest.newBuilder(URI.create(loc))
            .timeout(Duration.ofSeconds(200))
            .header("Authorization", "Bearer " + token)
            .GET();
        if ("binary".equals(args.accept) || "chunked".equals(args.accept))
          gb.header("Accept", CT);
        HttpResponse<InputStream> get = http.send(gb.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (get.statusCode() != 200) throw new IOException("replies " + get.statusCode());
        try (InputStream in = get.body()) {
          w.consumerStreams.add(in); // closed at the drain cutoff (re-review optional)
          if (!released) { admit.countDown(); released = true; }
          if (w.hardStop.get()) return;
          if (w.account && args.hangEvery > 0 && idx % args.hangEvery == 0) { // test-only, measure wave only
            while (!w.hardStop.get()) LockSupport.parkNanos(100_000_000L);
            return;
          }
          if ("sse".equals(args.accept)) readSse(in, w, bytesRx);
          else readBinary(in, w, bytesRx);
        }
        return;
      } catch (Exception e) {
        if (w.hardStop.get()) return; // closed by the drain cutoff: unreceived slots are aged
        last = e;
        // Count every failed connect/setup attempt (not only the final throw into error_count).
        if (!inTeardown.get()) consumerConnectRetries.incrementAndGet();
        if (sessionOpened) break; // 201 already: never a second :stream (matches consumerLean)
        attempt++;
        if (attempt < 3 && !stop.get()) {
          try { Thread.sleep(20L << Math.min(attempt - 1, 6)); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); throw ie;
          }
          continue;
        }
        break;
      }
    }
    if (last != null) throw last;
    } finally {
      cancelSession(http, args, token, cancelId);
      if (!released) admit.countDown();
    }
  }

  /**
   * #415 lean consumer: :stream POST and the replies GET on one raw keep-alive socket; frames are
   * decoded from a reused buffer (no HttpClient selector hop, no per-frame byte[] or String).
   * Only the connect/POST step is retried, and only while no session exists: once :stream answered
   * 201 a failure is an error, never a second :stream (Architecture review: no second session).
   */
  static void consumerLean(Args args, String token, String pool,
      Win w, int idx, AtomicBoolean inTeardown, LongAdder bytesRx,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong consumerConnectRetries, AtomicLong groupOpenRefusals, CountDownLatch admit) throws Exception {
    byte[] task = frame(TYPE_TASK, 0, "gate".getBytes(StandardCharsets.UTF_8));
    String spath = "/swarm/v1/tasks/" + enc(pool) + ":stream?deadlineMs=180000";
    LeanHttp lh = null;
    LeanResp sr = null;
    Exception last = null;
    boolean released = false;
    try {
      for (int attempt = 0; attempt < 3 && !w.hardStop.get(); attempt++) {
        boolean written = false;
        try {
          if (lh != null) lh.close();
          lh = new LeanHttp(URI.create(args.base), 200_000);
          byte[] head = lh.head("POST", spath, token, CT, CT, task.length);
          written = true; // from here a resend could open a second session
          sr = lh.postOnce(head, task);
          break;
        } catch (IOException e) {
          last = e;
          if (!inTeardown.get()) consumerConnectRetries.incrementAndGet();
          if (written) throw e; // the request may have reached the server: never resend :stream
          if (attempt < 2) Thread.sleep(20L << attempt);
        }
      }
      if (sr == null) throw last != null ? last : new IOException("stream not opened");
      logFirstAdmit("stream", "POST", args.base + spath, pool, sr.status,
          "retry-after=" + sr.retryAfter + ";location=" + sr.location,
          sr.body, 0);
      if (sr.status == 503) {
        record503(false, sr.body);
        boolean retried = false;
        for (int n503 = 0; n503 < 15 && !w.hardStop.get(); n503++) {
          if (!sleep503(w.hardStop, sr.retryAfter, n503, 15)) break;
          // A 503 means no session was created; it is safe to POST :stream again.
          sr = lh.postOnce(lh.head("POST", spath, token, CT, CT, task.length), task);
          if (sr.status != 503) { retried = true; break; }
          record503(false, sr.body);
        }
        if (sr.status == 503) {
          groupOpenRefusals.incrementAndGet();
          HTTP503_STREAM_GIVEUPS.increment();
          note(inTeardown, measureErr, teardownErr, "consumer-stream-refused " + pool,
              new IOException("stream 503" + bodyDetail(sr.body)));
          return;
        }
      }
      if (sr.status != 201 && sr.status != 200) throw new IOException("stream " + sr.status);
      String loc = sr.location;
      if (loc == null) {
        Frame f = decodeFrame(ByteBuffer.wrap(sr.body));
        loc = "/swarm/v1/replies/" + hex(f.payload);
      }
      if (loc.startsWith("http")) {
        URI u = URI.create(loc);
        loc = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
      }
      w.consumerSockets.add(lh);
      int gsc = lh.startGet(lh.head("GET", loc, token, null, CT, -1));
      if (gsc != 200) throw new IOException("replies " + gsc);
      if (!released) { admit.countDown(); released = true; }
      if (w.account && args.hangEvery > 0 && idx % args.hangEvery == 0) { // test-only, measure wave only
        while (!w.hardStop.get()) LockSupport.parkNanos(100_000_000L);
        return;
      }
      try {
        readBinaryLean(lh.bodyStream(), w, bytesRx);
      } catch (IOException e) {
        if (!w.hardStop.get()) throw e; // closed by the drain cutoff: unreceived slots are aged
      }
    } finally {
      if (lh != null) lh.close();
      if (!released) admit.countDown();
    }
  }


  /** Strip the optional single space after SSE "data:" (StreamHttp emits "data: "). */
  static String sseDataField(String line) {
    String v = line.substring(5);
    if (!v.isEmpty() && v.charAt(0) == ' ') v = v.substring(1);
    return v;
  }

  static void readBinary(InputStream in, Win w, LongAdder bytesRx) throws IOException {
    byte[] hdr = new byte[FRAME_HDR];
    Track[] cache = new Track[1];
    while (!w.hardStop.get()) {
      if (!readFully(in, hdr)) break;
      int type = hdr[1] & 0xff, flags = hdr[2] & 0xff;
      int len = ((hdr[3] & 0xff) << 16) | ((hdr[4] & 0xff) << 8) | (hdr[5] & 0xff);
      byte[] payload = new byte[len];
      if (!readFully(in, payload)) break;
      if (type != TYPE_TOKEN) continue;
      if (len < CHUNK_HDR + 20) {
        if ((flags & LAST) != 0) break;
        continue;
      }
      onChunk(w, cache, payload, 0, len, System.nanoTime(), bytesRx);
      if ((payload[CHUNK_HDR - 1] & LAST) != 0 || (flags & LAST) != 0) break;
    }
  }

  /**
   * #415 binary drain over {@link LeanHttp#bodyStream()}: one reused payload buffer, stamps parsed
   * from bytes. Same accounting as {@link #readBinary} (via {@link #onChunk}). Reads until LAST/EOF;
   * the drain cutoff closes the socket to stop it.
   */
  static void readBinaryLean(InputStream in, Win w, LongAdder bytesRx) throws IOException {
    byte[] hdr = new byte[FRAME_HDR];
    byte[] buf = new byte[4096];
    Track[] cache = new Track[1];
    while (true) {
      if (!readFully(in, hdr, hdr.length)) break;
      int type = hdr[1] & 0xff, flags = hdr[2] & 0xff;
      int len = ((hdr[3] & 0xff) << 16) | ((hdr[4] & 0xff) << 8) | (hdr[5] & 0xff);
      if (len > buf.length) buf = new byte[Math.max(len, buf.length * 2)];
      if (!readFully(in, buf, len)) break;
      if (type != TYPE_TOKEN) continue;
      if (len < CHUNK_HDR + 20) {
        if ((flags & LAST) != 0) break;
        continue;
      }
      onChunk(w, cache, buf, 0, len, System.nanoTime(), bytesRx);
      if ((buf[CHUNK_HDR - 1] & LAST) != 0 || (flags & LAST) != 0) break;
    }
  }

  /** 20 ASCII digits at {@code off} (as written by {@link #writeAsciiNs}); -1 if any is not a digit. */
  static long asciiLong(byte[] b, int off) {
    long v = 0;
    for (int i = 0; i < 20; i++) {
      int d = b[off + i] - '0';
      if (d < 0 || d > 9) return -1;
      v = v * 10 + d;
    }
    return v;
  }

  /** SSE drain (diagnostic only): slot-time window counting, no per-stream accounting (no stream id). */
  static void readSse(InputStream in, Win w, LongAdder bytesRx) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line; StringBuilder data = new StringBuilder(); String event = "";
    while (!w.hardStop.get() && (line = br.readLine()) != null) {
      if (line.startsWith("event:")) event = line.substring(6).trim();
      else if (line.startsWith("data:")) data.append(sseDataField(line)).append('\n');
      else if (line.isEmpty()) {
        if ("end".equals(event)) break;
        if ("chunk".equals(event) || data.length() > 0) {
          String d = data.toString();
          if (d.length() >= 20 && w.account) {
            try {
              long emit = Long.parseLong(d.substring(0, 20).trim());
              long recv = System.nanoTime();
              long slot = -1;
              if (d.length() >= 40) {
                try { slot = Long.parseLong(d.substring(20, 40).trim()); } catch (NumberFormatException ignored) {}
              }
              if (slot <= 0) { SCHED_STAMP_FALLBACK.increment(); slot = emit; }
              if (w.inWindow(slot)) {
                LAT.add((recv - emit) / 1e3);
                schedAdd(w, slot, (recv - slot) / 1e3);
                bytesRx.add(d.length());
                SCHED_RECEIVED.increment();
              }
            } catch (NumberFormatException ignored) {}
          }
        }
        data.setLength(0); event = "";
      }
    }
  }

  // ---- wire helpers ----
  static byte[] frame(int type, int flags, byte[] payload) {
    byte[] out = new byte[FRAME_HDR + payload.length];
    out[0] = 1; out[1] = (byte) type; out[2] = (byte) flags;
    int n = payload.length;
    out[3] = (byte)(n >>> 16); out[4] = (byte)(n >>> 8); out[5] = (byte) n;
    System.arraycopy(payload, 0, out, FRAME_HDR, n);
    return out;
  }
  static byte[] tokenFrame(long hi, long lo, long attempt, long seq, int flags, byte[] data) {
    byte[] chunk = new byte[CHUNK_HDR + data.length];
    ByteBuffer bb = ByteBuffer.wrap(chunk);
    bb.put((byte)2).putLong(hi).putLong(lo).putInt((int)attempt).putInt((int)seq).put((byte)flags).put(data);
    return frame(TYPE_TOKEN, flags & (LAST), chunk);
  }
  static Frame decodeFrame(ByteBuffer in) {
    if (in.remaining() < FRAME_HDR) throw new IllegalArgumentException("short");
    int ver = in.get() & 0xff; int type = in.get() & 0xff; int flags = in.get() & 0xff;
    int len = ((in.get() & 0xff) << 16) | ((in.get() & 0xff) << 8) | (in.get() & 0xff);
    byte[] p = new byte[len]; in.get(p);
    return new Frame(type, flags, p);
  }
  record Frame(int type, int flags, byte[] payload) {}
  record Lease(String id, long streamHi, long streamLo, long attempt) {}

  static Lease parseLease(HttpResponse<byte[]> claim) {
    ByteBuffer buf = ByteBuffer.wrap(claim.body());
    Frame f = decodeFrame(buf);
    if (f.type != TYPE_LEASE) throw new IllegalArgumentException("not LEASE");
    ByteBuffer p = ByteBuffer.wrap(f.payload);
    int n = p.getShort() & 0xffff;
    byte[] idb = new byte[n]; p.get(idb);
    String id = new String(idb, StandardCharsets.UTF_8);
    p.getLong(); // deadline
    // stream from header
    String streamHdr = claim.headers().firstValue("sjws-lease-stream").orElse(
        String.join(",", claim.headers().allValues("sjws-lease-stream")));
    // format: hex32/attempt (Q1); accept '.' as legacy
    String entry = streamHdr.split(",")[0].trim();
    if (entry.isEmpty() || "-".equals(entry)) throw new IllegalArgumentException("no stream header: " + streamHdr);
    int sep = entry.lastIndexOf('/');
    if (sep < 0) sep = entry.lastIndexOf('.');
    String hex = sep < 0 ? entry : entry.substring(0, sep);
    long attempt = sep < 0 ? 1 : Long.parseLong(entry.substring(sep + 1));
    if (hex.length() != 32) throw new IllegalArgumentException("stream id " + entry);
    long hi = Long.parseUnsignedLong(hex.substring(0, 16), 16);
    long lo = Long.parseUnsignedLong(hex.substring(16), 16);
    return new Lease(id, hi, lo, attempt);
  }

  /** Retry-After in ms: numeric seconds, default {@code dflt}, floor 50 ms, cap 5 s. HTTP-dates use {@code dflt}. */
  static long retryAfterMs(String hdr, long dflt) {
    if (hdr == null || hdr.isBlank()) return dflt;
    try {
      double s = Double.parseDouble(hdr.trim());
      long ms = (long) (s * 1000.0);
      return Math.min(5_000L, Math.max(50L, ms));
    } catch (NumberFormatException e) {
      return dflt;
    }
  }

  /** Sleep for a 503 Retry-After. False if attempts are exhausted or {@code stop} is set. */
  static boolean sleep503(AtomicBoolean stop, String retryAfter, int attempt, int max) throws InterruptedException {
    if (attempt >= max || (stop != null && stop.get())) return false;
    Thread.sleep(retryAfterMs(retryAfter, 1000));
    return stop == null || !stop.get();
  }

  /** Record one HTTP 503 (retry or final). {@code claim} selects the claim vs :stream counter. */
  static void record503(boolean claim, byte[] body) {
    HTTP503_RESPONSES.increment();
    (claim ? HTTP503_CLAIM : HTTP503_STREAM).increment();
    if (body != null) {
      String s = new String(body, java.nio.charset.StandardCharsets.UTF_8);
      if (s.contains("ledger writer budget exhausted")) HTTP503_BUDGET.increment();
    }
  }

  static void record503(boolean claim, String body) {
    record503(claim, body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static String header(HttpResponse<?> r, String name) {
    return r.headers().firstValue(name).orElse(null);
  }

  static String bodyDetail(byte[] body) {
    if (body == null || body.length == 0) return "";
    String s = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
    return s.isEmpty() ? "" : ": " + s;
  }

  /** Best-effort session cancel so a finished/aborted HttpClient consumer releases server writers. */
  static void cancelSession(HttpClient http, Args args, String token, String sessionId) {
    if (sessionId == null || sessionId.isEmpty()) return;
    try {
      post(http, args.base + "/swarm/v1/tasks/" + sessionId + ":cancel", token, new byte[0]);
    } catch (Exception ignored) {}
  }

  static HttpResponse<byte[]> post(HttpClient http, String url, String token, byte[] body) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", CT)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
  }

  /**
   * Wait until {@code sjws_swarm_stream_open_sessions == 0} (existing /metrics gauge;
   * proxy for leftover warmup sessions that still hold ledger writers). Timeout in seconds;
   * returns early when the gauge is 0 or absent. Does not depend on CUTOFF_HIT.
   */
  static DrainOutcome waitOpenSessions(HttpClient http, String metricsUrl, double timeoutS) throws Exception {
    long timeoutMs = Math.max(1_000L, (long) (timeoutS * 1000));
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    Map<String, Double> last = Map.of();
    while (System.nanoTime() < deadline) {
      last = fetchMetrics(http, metricsUrl);
      Double open = last.get("sjws_swarm_stream_open_sessions");
      if (open == null || open == 0.0)
        return new DrainOutcome(last, false);
      Thread.sleep(250);
    }
    last = fetchMetrics(http, metricsUrl);
    Double open = last.get("sjws_swarm_stream_open_sessions");
    return new DrainOutcome(last, open != null && open != 0.0);
  }

  /**
   * B1 (Architecture 2026-10-30): drain until open_sessions==0, timeout = max(args, GRACE_MS+15s).
   * Hard zeros are read only from the post-drain scrape. Timeout ⇒ drainTimedOut (window invalid).
   */
  static DrainOutcome waitDrained(HttpClient http, String metricsUrl, double timeoutS) throws Exception {
    long timeoutMs = Math.max((long)(timeoutS * 1000), GRACE_MS + DRAIN_SLACK_MS);
    // Hung-stream smoke: sockets are already closed at the measure cutoff. Don't wait another
    // 75 s for open_sessions==0 (they stay up for hung readers). End by cutoff+5 s.
    if (CUTOFF_HIT.get()) timeoutMs = Math.min(timeoutMs, 5_000L);
    long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
    Map<String, Double> last = Map.of();
    while (System.nanoTime() < deadline) {
      last = fetchMetrics(http, metricsUrl);
      Double open = last.get("sjws_swarm_stream_open_sessions");
      if (open == null || open == 0.0)
        return new DrainOutcome(last, false);
      Thread.sleep(500);
    }
    last = fetchMetrics(http, metricsUrl);
    return new DrainOutcome(last, true);
  }
  record DrainOutcome(Map<String, Double> counters, boolean timedOut) {}
  static Map<String, Double> fetchMetrics(HttpClient http, String url) throws Exception {
    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    Map<String, Double> out = new LinkedHashMap<>();
    for (String line : r.body().split("\n")) {
      if (line.isEmpty() || line.startsWith("#")) continue;
      int sp = line.indexOf(' ');
      if (sp < 0) sp = line.indexOf('\t');
      if (sp < 0) continue;
      String name = line.substring(0, sp);
      // keep unlabeled (or strip labels) so result.json keys stay JSON-safe
      int brace = name.indexOf('{');
      if (brace >= 0) name = name.substring(0, brace);
      try { out.put(name, Double.parseDouble(line.substring(sp + 1).trim())); }
      catch (NumberFormatException ignored) {}
    }
    return out;
  }

  static boolean readFully(InputStream in, byte[] buf) throws IOException {
    return readFully(in, buf, buf.length);
  }
  static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
    int o = 0;
    while (o < len) {
      int n = in.read(buf, o, len - o);
      if (n < 0) return false;
      o += n;
    }
    return true;
  }
  static void note(AtomicBoolean teardown, ConcurrentLinkedQueue<String> m, ConcurrentLinkedQueue<String> t, String where, Exception e) {
    String msg = where + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
    (teardown.get() ? t : m).add(msg);
  }
  static String enc(String s) {
    try {
      return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    } catch (Exception e) { return s; }
  }
  static String hex(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte x : b) sb.append(String.format("%02x", x));
    return sb.toString();
  }
  static double load1() throws IOException {
    String s = Files.readString(Path.of("/proc/loadavg"));
    return Double.parseDouble(s.split("\s+")[0]);
  }
  static long readProcJiffies(String pidOrSelf) throws IOException {
    // /proc/<pid>/stat utime+stime after comm (USER_HZ ticks)
    String raw = Files.readString(Path.of("/proc/" + pidOrSelf + "/stat"));
    int rp = raw.lastIndexOf(')');
    String[] rest = raw.substring(rp + 2).split("\s+");
    return Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
  }
  static double scClk() {
    return 100.0; // Linux USER_HZ typically 100
  }
  static Double pct(List<Double> s, double q) {
    if (s.isEmpty()) return null;
    int i = Math.min(s.size() - 1, (int)(q * s.size()));
    return s.get(i);
  }
  static List<String> loadTokens(String path) throws IOException {
    List<String> out = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of(path))) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] p = line.split("\s+");
      if (p.length >= 2) out.add(p[1]);
    }
    if (out.isEmpty()) throw new IllegalArgumentException("no tokens");
    return out;
  }

  /**
   * Harness-only keep-alive HTTP/1.1 client (inceptor 2026-10-30 18:46 (A); #415 slim-down).
   * One Socket per producer VT and (with --lean-consumer) one per consumer VT; only claim and the
   * /metrics scrape still use java.net.http.HttpClient. #415: request heads are built once per
   * stream ({@link #head}) and responses are parsed from bytes (no per-request String split,
   * lower-casing or header map).
   */
  static final class LeanHttp implements AutoCloseable {
    static final byte[] H_CONTENT_LENGTH = "content-length".getBytes(StandardCharsets.US_ASCII);
    static final byte[] H_TRANSFER_ENCODING = "transfer-encoding".getBytes(StandardCharsets.US_ASCII);
    static final byte[] H_RETRY_AFTER = "retry-after".getBytes(StandardCharsets.US_ASCII);
    static final byte[] H_LOCATION = "location".getBytes(StandardCharsets.US_ASCII);
    static final byte[] V_CHUNKED = "chunked".getBytes(StandardCharsets.US_ASCII);
    final String host;
    final int port;
    final int soTimeoutMs;
    Socket sock;
    OutputStream out;
    InputStream in;
    byte[] line = new byte[512];
    int lineLen;
    /** Set by {@link #startGet}: body framing of the streaming response. */
    boolean bodyChunked;
    long bodyLength = -1;

    LeanHttp(URI base, int soTimeoutMs) throws IOException {
      this.host = base.getHost();
      int p = base.getPort();
      this.port = p > 0 ? p : ("https".equalsIgnoreCase(base.getScheme()) ? 443 : 80);
      this.soTimeoutMs = soTimeoutMs;
      reconnect();
    }
    void reconnect() throws IOException {
      closeQuiet();
      sock = new Socket();
      sock.setTcpNoDelay(true);
      sock.setSoTimeout(soTimeoutMs);
      sock.connect(new InetSocketAddress(host, port), 10_000);
      // Buffered I/O: byte-at-a-time readLine was saturating gen CPUs at N=16.
      out = new BufferedOutputStream(sock.getOutputStream(), 8192);
      in = new BufferedInputStream(sock.getInputStream(), 16384);
    }
    void closeQuiet() {
      try { if (sock != null) sock.close(); } catch (IOException ignored) {}
      sock = null; out = null; in = null;
    }
    @Override public void close() { closeQuiet(); }

    /** Request head (request line + headers + blank line). {@code contentLength < 0}: no body. */
    byte[] head(String method, String path, String token, String contentType, String accept, int contentLength) {
      StringBuilder sb = new StringBuilder(256);
      sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        .append("Host: ").append(host).append(':').append(port).append("\r\n")
        .append("Authorization: Bearer ").append(token).append("\r\n");
      if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n");
      if (accept != null) sb.append("Accept: ").append(accept).append("\r\n");
      if (contentLength >= 0) sb.append("Content-Length: ").append(contentLength).append("\r\n");
      sb.append("Connection: keep-alive\r\n\r\n");
      return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Legacy entry (LAST frame): builds the head per call. */
    LeanResp post(String path, String token, byte[] body) throws IOException {
      return post(head("POST", path, token, CT, null, body.length), body);
    }

    /**
     * POST with a prebuilt head; one reconnect-and-resend on IOException (counted in
     * recoveredAttempts, Codex P2 #459). Only for :chunk, whose retry the server dedups (#248 r5 C2).
     */
    LeanResp post(byte[] head, byte[] body) throws IOException {
      int recovered = 0;
      for (int attempt = 0; attempt < 2; attempt++) {
        try {
          if (sock == null || sock.isClosed() || !sock.isConnected()) reconnect();
          out.write(head);
          out.write(body);
          out.flush();
          LeanResp r = readResponse(true);
          return recovered == 0 ? r : new LeanResp(r.status, r.body, r.retryAfter, r.location, recovered);
        } catch (IOException e) {
          closeQuiet();
          if (attempt == 1) throw e;
          recovered++;
        }
      }
      throw new IOException("lean post failed");
    }

    /** Pipelined write (no response read): the caller reads responses in order later. */
    void send(byte[] head, byte[] body) throws IOException {
      if (sock == null || sock.isClosed() || !sock.isConnected()) reconnect();
      out.write(head);
      out.write(body);
      out.flush();
    }

    /** One POST, no resend: for :stream, where a resend could open a second session. */
    LeanResp postOnce(byte[] head, byte[] body) throws IOException {
      if (sock == null || sock.isClosed() || !sock.isConnected()) reconnect();
      out.write(head);
      out.write(body);
      out.flush();
      return readResponse(true);
    }

    /** Send a GET head and read status + headers only; the body is then read via {@link #bodyStream}. */
    int startGet(byte[] head) throws IOException {
      if (sock == null || sock.isClosed() || !sock.isConnected()) reconnect();
      out.write(head);
      out.flush();
      LeanResp r = readResponse(false);
      return r.status;
    }

    /** Body of the response opened by {@link #startGet}: de-chunked, length-bounded, or until EOF. */
    InputStream bodyStream() {
      if (bodyChunked) return new ChunkedIn(this);
      if (bodyLength >= 0) return new BoundedIn(in, bodyLength);
      return in;
    }

    /** Parse status line and headers; when {@code readBody}, also the (small) body. */
    LeanResp readResponse(boolean readBody) throws IOException {
      if (!readLine()) throw new EOFException("empty status");
      int sc = parseStatus();
      long contentLen = -1;
      boolean chunked = false;
      String retryAfter = null, location = null;
      while (true) {
        if (!readLine()) throw new EOFException("headers");
        if (lineLen == 0) break;
        int c = indexOf((byte) ':');
        if (c < 0) continue;
        int vs = c + 1, ve = lineLen;
        while (vs < ve && (line[vs] == ' ' || line[vs] == '\t')) vs++;
        while (ve > vs && (line[ve - 1] == ' ' || line[ve - 1] == '\t')) ve--;
        if (nameIs(c, H_CONTENT_LENGTH)) contentLen = parseLong(vs, ve);
        else if (nameIs(c, H_TRANSFER_ENCODING)) chunked = containsIgnoreCase(vs, ve, V_CHUNKED);
        else if (nameIs(c, H_RETRY_AFTER)) retryAfter = new String(line, vs, ve - vs, StandardCharsets.US_ASCII);
        else if (nameIs(c, H_LOCATION)) location = new String(line, vs, ve - vs, StandardCharsets.US_ASCII);
      }
      bodyChunked = chunked;
      bodyLength = contentLen;
      if (!readBody) return new LeanResp(sc, null, retryAfter, location, 0);
      byte[] body;
      if (chunked) body = readChunkedBody();
      else if (contentLen > 0) {
        body = in.readNBytes((int) contentLen);
        if (body.length != contentLen) throw new EOFException("short body");
      } else {
        body = EMPTY; // 204, or Content-Length: 0
      }
      return new LeanResp(sc, body, retryAfter, location, 0);
    }
    static final byte[] EMPTY = new byte[0];

    /** Next CRLF- (or LF-) terminated line into {@link #line}; false on EOF before any byte. */
    boolean readLine() throws IOException {
      lineLen = 0;
      int b;
      boolean any = false;
      while ((b = in.read()) >= 0) {
        any = true;
        if (b == '\n') break;
        if (b == '\r') continue;
        if (lineLen == line.length) line = Arrays.copyOf(line, line.length * 2);
        line[lineLen++] = (byte) b;
      }
      return any;
    }
    int parseStatus() throws IOException {
      // "HTTP/1.1 200 OK": three digits after the first space
      int sp = indexOf((byte) ' ');
      if (sp < 0 || sp + 4 > lineLen) throw new IOException("bad status line");
      int v = 0;
      for (int i = sp + 1; i < sp + 4; i++) {
        int d = line[i] - '0';
        if (d < 0 || d > 9) throw new IOException("bad status code");
        v = v * 10 + d;
      }
      return v;
    }
    int indexOf(byte x) {
      for (int i = 0; i < lineLen; i++) if (line[i] == x) return i;
      return -1;
    }
    boolean nameIs(int nameEnd, byte[] lowerName) {
      int e = nameEnd;
      while (e > 0 && (line[e - 1] == ' ' || line[e - 1] == '\t')) e--;
      if (e != lowerName.length) return false;
      for (int i = 0; i < e; i++) {
        int ch = line[i];
        if (ch >= 'A' && ch <= 'Z') ch += 32;
        if (ch != lowerName[i]) return false;
      }
      return true;
    }
    boolean containsIgnoreCase(int s, int e, byte[] lowerNeedle) {
      outer:
      for (int i = s; i + lowerNeedle.length <= e; i++) {
        for (int j = 0; j < lowerNeedle.length; j++) {
          int ch = line[i + j];
          if (ch >= 'A' && ch <= 'Z') ch += 32;
          if (ch != lowerNeedle[j]) continue outer;
        }
        return true;
      }
      return false;
    }
    long parseLong(int s, int e) throws IOException {
      if (s >= e) throw new IOException("empty number");
      long v = 0;
      for (int i = s; i < e; i++) {
        int d = line[i] - '0';
        if (d < 0 || d > 9) throw new IOException("bad number");
        v = v * 10 + d;
      }
      return v;
    }
    /** Chunk-size line (hex, optional ;ext); -1 on EOF. */
    int readChunkSize() throws IOException {
      if (!readLine()) return -1;
      int e = indexOf((byte) ';');
      if (e < 0) e = lineLen;
      while (e > 0 && (line[e - 1] == ' ' || line[e - 1] == '\t')) e--;
      if (e == 0) throw new IOException("empty chunk size");
      int v = 0;
      for (int i = 0; i < e; i++) {
        int ch = line[i], d;
        if (ch >= '0' && ch <= '9') d = ch - '0';
        else if (ch >= 'a' && ch <= 'f') d = ch - 'a' + 10;
        else if (ch >= 'A' && ch <= 'F') d = ch - 'A' + 10;
        else throw new IOException("bad chunk size");
        if (v > (Integer.MAX_VALUE >> 4)) throw new IOException("chunk too large");
        v = (v << 4) | d;
      }
      return v;
    }
    void skipTrailers() throws IOException {
      while (readLine() && lineLen > 0) { /* trailer field */ }
    }
    void expectCrlf() throws IOException {
      if (in.read() != '\r' || in.read() != '\n') throw new IOException("bad chunk crlf");
    }
    byte[] readChunkedBody() throws IOException {
      ByteArrayOutputStream all = new ByteArrayOutputStream();
      while (true) {
        int n = readChunkSize();
        if (n < 0) throw new EOFException("chunk size");
        if (n == 0) { skipTrailers(); break; }
        byte[] chunk = in.readNBytes(n);
        if (chunk.length != n) throw new EOFException("short chunk");
        all.write(chunk);
        expectCrlf();
      }
      return all.toByteArray();
    }
  }

  /** De-chunking view over a {@link LeanHttp} connection (no copies beyond the caller's buffer). */
  static final class ChunkedIn extends InputStream {
    final LeanHttp h;
    int left; // bytes left in the current chunk
    boolean eof;
    ChunkedIn(LeanHttp h) { this.h = h; }
    boolean ensure() throws IOException {
      if (eof) return false;
      if (left > 0) return true;
      int n = h.readChunkSize();
      if (n <= 0) {
        if (n == 0) h.skipTrailers();
        eof = true;
        return false;
      }
      left = n;
      return true;
    }
    @Override public int read() throws IOException {
      if (!ensure()) return -1;
      int b = h.in.read();
      if (b < 0) { eof = true; return -1; }
      if (--left == 0) h.expectCrlf();
      return b;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) return 0;
      if (!ensure()) return -1;
      int n = h.in.read(b, off, Math.min(len, left));
      if (n < 0) { eof = true; return -1; }
      left -= n;
      if (left == 0) h.expectCrlf();
      return n;
    }
  }

  /** Content-Length-bounded view. */
  static final class BoundedIn extends InputStream {
    final InputStream in;
    long left;
    BoundedIn(InputStream in, long len) { this.in = in; this.left = len; }
    @Override public int read() throws IOException {
      if (left <= 0) return -1;
      int b = in.read();
      if (b >= 0) left--;
      return b;
    }
    @Override public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) return -1;
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      return n;
    }
  }

  static final class LeanResp {
    final int status;
    final byte[] body;
    final String retryAfter;
    final String location;
    /** IOExceptions recovered inside LeanHttp.post by reconnecting (counted as transport retries). */
    final int recoveredAttempts;
    LeanResp(int s, byte[] b, String ra) { this(s, b, ra, null, 0); }
    LeanResp(int s, byte[] b, String ra, String loc, int recovered) {
      status = s; body = b; retryAfter = ra; location = loc; recoveredAttempts = recovered;
    }
  }


  static Args parse(String[] a) {
    Args args = new Args();
    for (int i = 0; i < a.length; i++) {
      switch (a[i]) {
        case "--base" -> args.base = a[++i];
        case "--tokens-file" -> args.tokensFile = a[++i];
        case "--out" -> args.out = a[++i];
        case "--accept" -> args.accept = a[++i];
        case "--warmup" -> args.warmup = Double.parseDouble(a[++i]);
        case "--secs" -> args.secs = Double.parseDouble(a[++i]);
        case "--streams" -> args.streams = Integer.parseInt(a[++i]);
        case "--groups" -> args.groups = Integer.parseInt(a[++i]);
        case "--chunk-bytes" -> args.chunkBytes = Integer.parseInt(a[++i]);
        case "--window" -> args.window = Integer.parseInt(a[++i]);
        case "--drain-timeout" -> args.drainTimeout = Double.parseDouble(a[++i]);
        case "--server-pid" -> args.serverPid = Integer.parseInt(a[++i]);
        case "--max-connections-per-ip" -> args.maxConnectionsPerIp = Integer.parseInt(a[++i]);
        case "--gen-sat-pct" -> args.genSatPct = Double.parseDouble(a[++i]);
        case "--gen-cores" -> args.genCores = Integer.parseInt(a[++i]);
        case "--lean-http" -> args.leanHttp = true;
        case "--no-lean-http" -> args.leanHttp = false;
        case "--rate-mib-s" -> args.rateMibS = Double.parseDouble(a[++i]);
        case "--pipeline-depth" -> args.pipelineDepth = Integer.parseInt(a[++i]);
        case "--pacer-loop" -> args.pacerLoop = true;
        case "--no-pacer-loop" -> args.pacerLoop = false;
        case "--pacer-spin-us" -> args.pacerSpinUs = Long.parseLong(a[++i]);
        case "--pace-ms" -> throw new IllegalArgumentException(
            "--pace-ms was #465's per-stream sleep; use --rate-mib-s (aggregate MiB/s) instead");
        case "--lean-consumer" -> args.leanConsumer = true;
        case "--no-lean-consumer" -> args.leanConsumer = false;
        case "--harness-commit" -> args.harnessCommit = a[++i];
        case "--claim-delay-ms" -> args.claimDelayMs = Long.parseLong(a[++i]);
        case "--hang-every" -> args.hangEvery = Integer.parseInt(a[++i]);
        case "--group-offset" -> args.groupOffset = Integer.parseInt(a[++i]);
        case "--admit-timeout" -> args.admitTimeoutS = Double.parseDouble(a[++i]);
        default -> throw new IllegalArgumentException(a[i]);
      }
    }
    if (args.base == null || args.tokensFile == null || args.out == null) throw new IllegalArgumentException("need --base --tokens-file --out");
    if ("chunked".equals(args.accept)) args.accept = "binary";
    if (args.rateMibS < 0) throw new IllegalArgumentException("--rate-mib-s must be >= 0");
    // Paced windows gate on the scheduled-send stamp at data[20..40) (inceptor Q1): it must fit.
    if (args.rateMibS > 0 && args.chunkBytes < 40) throw new IllegalArgumentException("--rate-mib-s needs --chunk-bytes >= 40");
    if (args.harnessCommit.isBlank()) {
      String hc = System.getenv("HARNESS_COMMIT");
      args.harnessCommit = hc == null ? "" : hc.trim();
    }
    if (args.groups <= 0) args.groups = args.streams; // 1:1 legacy
    if (args.groups > args.streams) args.groups = args.streams;
    String heapEnv = System.getenv("HEAP");
    if (heapEnv == null || heapEnv.isBlank()) heapEnv = System.getenv("HEAP_FLAGS");
    args.heapFlags = heapEnv == null ? "" : heapEnv.trim();
    if (args.serverPid <= 0) {
      String env = System.getenv("SERVER_PID");
      if (env != null && !env.isBlank()) args.serverPid = Integer.parseInt(env.trim());
    }
    return args;
  }

  static final class Result {
    String mode; int streams, agents, window, groups, chunkSamples, errorCount, teardownErrorCount, creditWaitN, writerCount;
    int[] streamToGroup; // measure-wave mapping stream index → group id
    String heapFlags;
    long bytes, creditStalls, requests, producerTransportRetries, consumerConnectRetries, refusedPerIp, openSessionsAfterDrain, groupOpenRefusals;
    long http503Responses, http503ClaimResponses, http503StreamResponses, http503BudgetExhausted;
    long http503ClaimGiveups, http503StreamGiveups;
    List<String> firstAdmit = List.of();
    List<String> firstAdmitWarmup = List.of();
    long gcMsWindow, gcCountWindow, paceWakeN, paceBehindN, paceCapwaitN, timerSlackSet;
    int pipelineDepth;
    boolean pacerLoop;
    String paceWakeHist = "[]", paceBehindHist = "[]", paceCapwaitHist = "[]", ackRttHist = "[]";
    Double paceWakeP99, paceBehindP99, paceCapwaitP99, ackRttP50, ackRttP99;
    Double warmupDrainOpenBefore, warmupDrainOpenAfter, warmupDrainChargedBefore, warmupDrainChargedAfter;
    long warmupDrainWaitMs;
    boolean warmupDrainTimedOut;
    int maxConnectionsPerIp;
    double elapsedS, wallS, load1Pre, load1Post, mibS, stallPct, creditWaitTotal, clientCpuSec, clientCpuPctOfWall, serverCpuSec, serverCpuPctOfWall;
    Double p50, p90, p99, creditWaitP50, creditWaitP99;
    boolean selfLoaded, stallFlag, generatorSaturated, transportRetryFlag, drainTimedOut;
    int genCores;
    double genSatLimitPct;
    String latencyBasis = "send";
    Double p50Send, p90Send, p99Send, p50Sched, p90Sched, p99Sched;
    String latHistSend = "[]", latHistSched = "[]", schedPerS = "[]";
    double rateMibS, schedLatePct, schedLagMaxUs, offeredActualMibS;
    Double lagP50, lagP99, lagFirstP99, lagRestP99;
    String lagHist = "[]", lagFirstHist = "[]", lagRestHist = "[]";
    long schedSends, schedExpected, schedSent, schedReceived, schedUnreceived, schedPhantomStreams,
        schedStampFallback, schedInflightAtEnd, schedStreamsShort, schedLateRegistered;
    boolean cutoffHit;
    double catchupS, catchupCpuPct;
    String harnessCommit = "", validityMarker = "";
    boolean leanConsumer;
    Map<String, Double> counters, hardZeros;
    List<String> errors, teardownErrors;
    String summary() {
      return String.format(Locale.ROOT,
          "{\"mib_s\":%.4f,\"chunk_samples\":%d,\"p50_us\":%s,\"p99_us\":%s,\"error_count\":%d,\"teardown_error_count\":%d,\"client_cpu_pct\":%.1f}",
          mibS, chunkSamples, p50, p99, errorCount, teardownErrorCount, clientCpuPctOfWall);
    }
    String toJson() {
      StringBuilder sb = new StringBuilder();
      sb.append("{\n");
      field(sb, "mode", quote(mode), true);
      field(sb, "streams", streams, true);
      field(sb, "agents", agents, true);
      field(sb, "window", window, true);
      field(sb, "elapsed_s", elapsedS, true);
      field(sb, "wall_s", wallS, true);
      field(sb, "load1_pre", load1Pre, true);
      field(sb, "load1_post", load1Post, true);
      field(sb, "self_loaded", selfLoaded, true);
      field(sb, "chunk_samples", chunkSamples, true);
      field(sb, "bytes", bytes, true);
      field(sb, "mib_s", mibS, true);
      field(sb, "p50_us", p50, true);
      field(sb, "p90_us", p90, true);
      field(sb, "p99_us", p99, true);
      field(sb, "credit_stalls", creditStalls, true);
      field(sb, "credit_stall_pct_of_chunks", stallPct, true);
      field(sb, "stall_flag", stallFlag, true);
      field(sb, "error_count", errorCount, true);
      field(sb, "teardown_error_count", teardownErrorCount, true);
      field(sb, "client_cpu_sec", clientCpuSec, true);
      field(sb, "client_cpu_pct_of_wall", clientCpuPctOfWall, true);
      field(sb, "server_cpu_sec", Double.isNaN(serverCpuSec) ? null : serverCpuSec, true);
      field(sb, "server_cpu_pct_of_wall", Double.isNaN(serverCpuPctOfWall) ? null : serverCpuPctOfWall, true);
      field(sb, "generator_saturated", generatorSaturated, true);
      field(sb, "gen_cores", genCores, true);
      field(sb, "gen_sat_limit_pct", genSatLimitPct, true);
      field(sb, "offered_mib_s", rateMibS, true);
      field(sb, "offered_actual_mib_s", offeredActualMibS, true);
      field(sb, "sched_sends", schedSends, true);
      field(sb, "sched_expected", schedExpected, true);
      field(sb, "sched_sent", schedSent, true);
      field(sb, "sched_received", schedReceived, true);
      field(sb, "sched_unreceived_at_cutoff", schedUnreceived, true);
      field(sb, "sched_phantom_streams", schedPhantomStreams, true);
      field(sb, "sched_inflight_at_end", schedInflightAtEnd, true);
      field(sb, "sched_stamp_fallback", schedStampFallback, true);
      field(sb, "cutoff_hit", cutoffHit, true);
      field(sb, "sched_streams_short", schedStreamsShort, true);
      field(sb, "sched_late_registered", schedLateRegistered, true);
      field(sb, "catchup_s", catchupS, true);
      field(sb, "catchup_cpu_pct_of_wall", catchupCpuPct, true);
      field(sb, "harness_commit", quote(harnessCommit), true);
      field(sb, "validity_marker", quote(validityMarker), true);
      field(sb, "sched_lag_p50_us", lagP50, true);
      field(sb, "sched_lag_p99_us", lagP99, true);
      field(sb, "sched_late_pct", schedLatePct, true);
      field(sb, "sched_lag_max_us", schedLagMaxUs, true);
      field(sb, "sched_lag_first_s_p99_us", lagFirstP99, true);
      field(sb, "sched_lag_rest_p99_us", lagRestP99, true);
      field(sb, "lean_consumer", leanConsumer, true);
      field(sb, "latency_basis", quote(latencyBasis), true);
      field(sb, "p99_send_us", p99Send, true);
      field(sb, "p99_sched_us", p99Sched, true);
      field(sb, "requests", requests, true);
      field(sb, "producer_transport_retries", producerTransportRetries, true);
      field(sb, "consumer_connect_retries", consumerConnectRetries, true);
      field(sb, "group_open_refusals", groupOpenRefusals, true);
      field(sb, "http_503_responses", http503Responses, true);
      field(sb, "http_503_claim_responses", http503ClaimResponses, true);
      field(sb, "http_503_stream_responses", http503StreamResponses, true);
      field(sb, "http_503_budget_exhausted", http503BudgetExhausted, true);
      field(sb, "http_503_claim_giveups", http503ClaimGiveups, true);
      field(sb, "http_503_stream_giveups", http503StreamGiveups, true);
      sb.append("  \"first_admit\":").append(listJson(firstAdmit)).append(",\n");
      sb.append("  \"first_admit_warmup\":").append(listJson(firstAdmitWarmup)).append(",\n");
      field(sb, "warmup_drain_open_sessions_before", warmupDrainOpenBefore, true);
      field(sb, "warmup_drain_open_sessions_after", warmupDrainOpenAfter, true);
      field(sb, "warmup_drain_charged_bytes_before", warmupDrainChargedBefore, true);
      field(sb, "warmup_drain_charged_bytes_after", warmupDrainChargedAfter, true);
      field(sb, "warmup_drain_wait_ms", warmupDrainWaitMs, true);
      field(sb, "warmup_drain_timed_out", warmupDrainTimedOut, true);
      field(sb, "pipeline_depth", pipelineDepth, true);
      sb.append("  \"pacer_loop\":").append(pacerLoop).append(",\n");
      field(sb, "gc_ms_window", gcMsWindow, true);
      field(sb, "gc_count_window", gcCountWindow, true);
      field(sb, "timer_slack_threads_set", timerSlackSet, true);
      field(sb, "pace_wake_n", paceWakeN, true);
      field(sb, "pace_behind_n", paceBehindN, true);
      field(sb, "pace_capwait_n", paceCapwaitN, true);
      field(sb, "pace_wake_p99_us", paceWakeP99, true);
      field(sb, "pace_behind_p99_us", paceBehindP99, true);
      field(sb, "pace_capwait_p99_us", paceCapwaitP99, true);
      field(sb, "ack_rtt_p50_us", ackRttP50, true);
      field(sb, "ack_rtt_p99_us", ackRttP99, true);
      sb.append("  \"pace_wake_hist\":").append(paceWakeHist).append(",\n");
      sb.append("  \"pace_behind_hist\":").append(paceBehindHist).append(",\n");
      sb.append("  \"pace_capwait_hist\":").append(paceCapwaitHist).append(",\n");
      sb.append("  \"ack_rtt_hist\":").append(ackRttHist).append(",\n");

      field(sb, "transport_retry_flag", transportRetryFlag, true);
      field(sb, "max_connections_per_ip", maxConnectionsPerIp, true);
      field(sb, "refused_per_ip", refusedPerIp, true);
      field(sb, "drain_timed_out", drainTimedOut, true);
      field(sb, "open_sessions_after_drain", openSessionsAfterDrain, true);
      field(sb, "groups", groups, true);
      field(sb, "streams_per_group_approx", streams > 0 && groups > 0 ? (double) streams / groups : 0, true);
      field(sb, "writer_count", writerCount, true);
      field(sb, "heap_flags", quote(heapFlags == null ? "" : heapFlags), true);
      sb.append("  \"stream_to_group\":");
      if (streamToGroup == null) sb.append("[]");
      else {
        sb.append("[");
        for (int i = 0; i < streamToGroup.length; i++) {
          if (i > 0) sb.append(",");
          sb.append(streamToGroup[i]);
        }
        sb.append("]");
      }
      sb.append(",\n");
      sb.append("  \"credit_wait\":{")
        .append("\"n\":").append(creditWaitN)
        .append(",\"total_s\":").append(creditWaitTotal)
        .append(",\"p50_s\":").append(creditWaitP50)
        .append(",\"p99_s\":").append(creditWaitP99)
        .append("},\n");
      sb.append("  \"chunk_latency\":{")
        .append("\"n\":").append(chunkSamples)
        .append(",\"basis\":").append(quote(latencyBasis))
        .append(",\"p50_us\":").append(p50)
        .append(",\"p90_us\":").append(p90)
        .append(",\"p99_us\":").append(p99)
        .append(",\"hist_sub_bits\":").append(LatBag.SUB_BITS)
        .append("},\n");
      sb.append("  \"chunk_latency_send\":{")
        .append("\"p50_us\":").append(p50Send)
        .append(",\"p90_us\":").append(p90Send)
        .append(",\"p99_us\":").append(p99Send)
        .append(",\"hist\":").append(latHistSend)
        .append("},\n");
      sb.append("  \"sched_per_s_hist\":").append(schedPerS).append(",\n");
      sb.append("  \"chunk_latency_sched\":{")
        .append("\"p50_us\":").append(p50Sched)
        .append(",\"p90_us\":").append(p90Sched)
        .append(",\"p99_us\":").append(p99Sched)
        .append(",\"hist\":").append(latHistSched)
        .append("},\n");
      sb.append("  \"sched_lag\":{")
        .append("\"p50_us\":").append(lagP50)
        .append(",\"p99_us\":").append(lagP99)
        .append(",\"first_s_p99_us\":").append(lagFirstP99)
        .append(",\"rest_p99_us\":").append(lagRestP99)
        .append(",\"hist\":").append(lagHist)
        .append(",\"first_s_hist\":").append(lagFirstHist)
        .append(",\"rest_hist\":").append(lagRestHist)
        .append("},\n");
      sb.append("  \"hard_zeros\":").append(mapJson(hardZeros)).append(",\n");
      sb.append("  \"counters\":").append(mapJson(counters)).append(",\n");
      sb.append("  \"errors\":").append(listJson(errors)).append(",\n");
      sb.append("  \"teardown_errors\":").append(listJson(teardownErrors)).append("\n");
      sb.append("}\n");
      return sb.toString();
    }
    void field(StringBuilder sb, String k, Object v, boolean comma) {
      sb.append("  \"").append(k).append("\":").append(v instanceof String ? v : String.valueOf(v));
      sb.append(comma ? ",\n" : "\n");
    }
    static String quote(String s) {
      StringBuilder sb = new StringBuilder(s.length() + 8);
      sb.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '"') sb.append("\\\"");
        else if (c == '\\') sb.append("\\\\");
        else if (c == '\n') sb.append("\\n");
        else if (c == '\r') sb.append("\\r");
        else if (c == '\t') sb.append("\\t");
        else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
        else sb.append(c);
      }
      return sb.append('"').toString();
    }
    static String mapJson(Map<String, Double> m) {
      if (m == null) return "{}";
      StringBuilder sb = new StringBuilder("{");
      boolean first = true;
      for (var e : m.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        Double v = e.getValue();
        sb.append(quote(e.getKey())).append(':').append(v == null || v.isNaN() ? "null" : v);
      }
      return sb.append('}').toString();
    }
    static String listJson(List<String> xs) {
      if (xs == null) return "[]";
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < xs.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(quote(xs.get(i).replace("\\", "\\\\").replace("\"", "\\\"")));
      }
      return sb.append(']').toString();
    }
  }
}
