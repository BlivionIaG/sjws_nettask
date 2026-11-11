
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
    // #415: instance histograms so the consumer records, per chunk, both the send-stamp latency
    // (LAT) and the scheduled-send-time latency (LAT_CO). inceptor Q1 (2026-11-11): paced gate
    // windows report p99 from the scheduled send time, so a stalled producer cannot hide backlog.
    final ThreadLocal<long[]> TL = ThreadLocal.withInitial(() -> new long[BUCKETS]);
    final ConcurrentLinkedQueue<long[]> ALL = new ConcurrentLinkedQueue<>();
    final ThreadLocal<Boolean> REG = ThreadLocal.withInitial(() -> Boolean.FALSE);
    final LongAdder TOTAL = new LongAdder();

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
      long[] a = TL.get();
      if (!REG.get()) { ALL.add(a); REG.set(Boolean.TRUE); }
      long u = us < 1 ? 1 : (long) us;
      a[index(u)]++;
      TOTAL.increment();
    }
    double pctFromBuckets(double p) {
      long[] sum = new long[BUCKETS];
      long n = 0;
      for (long[] a : ALL) {
        for (int i = 0; i < BUCKETS; i++) { sum[i] += a[i]; n += a[i]; }
      }
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
      for (long[] a : ALL) for (long c : a) n += c;
      return n;
    }
    /** Sparse [[bucket, count], ...] JSON so several generator processes can be merged ((B) split). */
    String sparseJson() {
      long[] sum = new long[BUCKETS];
      for (long[] a : ALL) for (int i = 0; i < BUCKETS; i++) sum[i] += a[i];
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
      for (long[] a : ALL) Arrays.fill(a, 0);
      ALL.clear();
      TOTAL.reset();
    }
  }
  /** Send-stamp chunk latency (actual send → consumer). Reported beside the gate p99 when paced. */
  static final LatBag LAT = new LatBag();
  /**
   * Scheduled-send-time chunk latency (slot → consumer). The gate p99 ({@code p99_us}) when paced
   * (inceptor Q1). Equal to LAT in closed loop, where the slot is the send time.
   */
  static final LatBag LAT_CO = new LatBag();
  /** #415 pacing: sends during measure, sends that started > 1 ms after their slot, worst lag. */
  static final LongAdder SCHED_SENDS = new LongAdder(), SCHED_LATE = new LongAdder();
  static final LongAccumulator SCHED_LAG_MAX_NS = new LongAccumulator(Long::max, 0L);
  static final long LATE_NS = 1_000_000L;

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
     * #415: aggregate offered rate in MiB/s. 0 = closed loop (legacy: every stream POSTs as fast as
     * its round trip allows). > 0 = each stream sends on a fixed schedule of
     * streams × chunkBytes / rate seconds per chunk (wrk2-style: late slots are caught up, never
     * dropped, so LAT_CO keeps the backlog).
     */
    double rateMibS = 0;
    /** #415: raw-socket :stream + replies GET for binary drains (HttpClient off the hot path). */
    boolean leanConsumer = true;
    boolean leanConsumerActive() { return leanHttp && leanConsumer && "binary".equals(accept); }
  }

  public static void main(String[] a) throws Exception {
    Args args = parse(a);
    List<String> tokens = loadTokens(args.tokensFile);
    Result r = run(args, tokens);
    Files.writeString(Path.of(args.out), r.toJson());
    System.out.println(r.summary());
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
    AtomicBoolean producersStop = new AtomicBoolean();
    AtomicBoolean consumersStop = new AtomicBoolean();
    AtomicBoolean inTeardown = new AtomicBoolean();
    AtomicBoolean measureActive = new AtomicBoolean();
    AtomicLong requests = new AtomicLong();
    AtomicLong credit429 = new AtomicLong();
    AtomicLong producerTransportRetries = new AtomicLong();
    AtomicLong consumerConnectRetries = new AtomicLong();
    AtomicLong groupOpenRefusals = new AtomicLong(); // claim/stream HTTP 503 (ledger budget) — not transport

    // Warmup (same group count; fewer streams)
    wave(http, args, tokens, Math.min(32, args.streams), args.warmup, "w",
        producersStop, consumersStop, inTeardown, measureActive, bytesRx, creditWaits,
        measureErr, teardownErr, requests, credit429, producerTransportRetries, consumerConnectRetries, groupOpenRefusals);
    bytesRx.reset(); LAT.clearAll(); LAT_CO.clearAll(); creditWaits.clear();
    SCHED_SENDS.reset(); SCHED_LATE.reset(); SCHED_LAG_MAX_NS.reset(); measureErr.clear(); teardownErr.clear();
    requests.set(0); credit429.set(0);
    producerTransportRetries.set(0); consumerConnectRetries.set(0);

    // Measure + sample client (/proc/self) and server (/proc/<serverPid>) CPU over the window
    AtomicReference<double[]> cpuSample = new AtomicReference<>(new double[]{0,0,0,0}); // clientSec, clientPct, serverSec, serverPct
    Thread cpuMon = Thread.startVirtualThread(() -> {
      try {
        // Skip first 2s (JIT/ramp), then average 1s slices through the measure window
        Thread.sleep(2000);
        double cSecSum = 0, sSecSum = 0, wallSum = 0;
        int n = 0;
        long deadline = System.nanoTime() + (long)(Math.max(3, args.secs - 3) * 1e9);
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

    long t0 = System.nanoTime();
    int[] streamToGroup = wave(http, args, tokens, args.streams, args.secs, "g",
        producersStop, consumersStop, inTeardown, measureActive, bytesRx, creditWaits,
        measureErr, teardownErr, requests, credit429, producerTransportRetries, consumerConnectRetries, groupOpenRefusals);
    double wall = (System.nanoTime() - t0) / 1e9;
    cpuMon.join(2000);
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
    r.refusedPerIp = counters.getOrDefault("sjws_connections_refused_per_ip_total", 0.0).longValue();
    r.elapsedS = args.secs; r.wallS = wall; r.load1Pre = load1Pre; r.load1Post = load1Post;
    double[] cpu = cpuSample.get();
    r.clientCpuSec = cpu[0]; r.clientCpuPctOfWall = cpu[1];
    r.serverCpuSec = cpu[2]; r.serverCpuPctOfWall = cpu[3];
    // inceptor 2026-10-30 18:46: self_loaded iff gen CPU > 80% of its cores (also load1_post>=4)
    double satLim = args.genSatPct > 0 ? args.genSatPct : 80.0 * args.genCores;
    boolean genSat = !Double.isNaN(r.clientCpuPctOfWall) && r.clientCpuPctOfWall > satLim;
    r.selfLoaded = load1Post >= 4.0 || genSat;
    r.generatorSaturated = genSat;
    r.genCores = args.genCores;
    r.genSatLimitPct = satLim;
    r.chunkSamples = (int) Math.min(Integer.MAX_VALUE, LAT.count());
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
    r.latHistSend = LAT.sparseJson(); r.latHistSched = LAT_CO.sparseJson();
    LAT.clearAll(); LAT_CO.clearAll();
    r.rateMibS = args.rateMibS;
    r.leanConsumer = args.leanConsumerActive();
    long ss = SCHED_SENDS.sum();
    r.schedSends = ss;
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
      AtomicBoolean producersStop, AtomicBoolean consumersStop, AtomicBoolean inTeardown,
      AtomicBoolean measureActive,
      LongAdder bytesRx, ConcurrentLinkedQueue<Double> creditWaits,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429,
      AtomicLong producerTransportRetries, AtomicLong consumerConnectRetries,
      AtomicLong groupOpenRefusals) throws Exception {
    producersStop.set(false); consumersStop.set(false); inTeardown.set(false);
    measureActive.set(false);
    int gCount = Math.max(1, Math.min(args.groups > 0 ? args.groups : n, n));
    int[] mapping = new int[n];
    List<Thread> threads = new ArrayList<>(n * 2);
    for (int i = 0; i < n; i++) {
      int gid = i % gCount;
      mapping[i] = gid;
      String pool = label + gid; // Architecture 2026-10-30 15:13: G groups, streams round-robin
      String tok = tokens.get(i % tokens.size());
      int idx = i;
      threads.add(Thread.startVirtualThread(() -> {
        try { consumer(http, args, tok, pool, consumersStop, inTeardown, measureActive, bytesRx, measureErr, teardownErr, consumerConnectRetries, groupOpenRefusals); }
        catch (Exception e) { note(inTeardown, measureErr, teardownErr, "consumer " + pool, e); }
      }));
      if (i % 8 == 0) Thread.sleep(20); // slower admit ramp at 1k (was 10ms/32)
      threads.add(Thread.startVirtualThread(() -> {
        try { producer(http, args, tok, pool, producersStop, inTeardown, measureActive, creditWaits, measureErr, teardownErr, requests, credit429, producerTransportRetries, groupOpenRefusals); }
        catch (Exception e) { note(inTeardown, measureErr, teardownErr, "producer " + pool, e); }
      }));
    }
    // Account bytes/latency only during the nominal measure interval (not admit ramp / teardown).
    measureActive.set(true);
    try {
      Thread.sleep((long)(duration * 1000));
    } finally {
      measureActive.set(false);
    }
    inTeardown.set(true);
    producersStop.set(true);
    Thread.sleep(2000);
    consumersStop.set(true);
    Thread.sleep(2000);
    for (Thread t : threads) t.join(30_000);
    return mapping;
  }

  static void producer(HttpClient http, Args args, String token, String pool,
      AtomicBoolean stop, AtomicBoolean inTeardown, AtomicBoolean measureActive,
      ConcurrentLinkedQueue<Double> creditWaits,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429, AtomicLong producerTransportRetries,
      AtomicLong groupOpenRefusals) throws Exception {
    HttpResponse<byte[]> claim = post(http, args.base + "/swarm/v1/tasks/" + enc(pool) + ":claim?visibilityMs=60000&waitMs=30000&max=1",
        token, new byte[0]);
    int claimSc = claim.statusCode();
    // #335 ledger budget: HTTP 503 on group open — group_open_refusals, never transport_retry.
    if (claimSc == 503) {
      groupOpenRefusals.incrementAndGet();
      note(inTeardown, measureErr, teardownErr, "producer-claim-refused " + pool, new IOException("claim 503"));
      return;
    }
    if (claimSc != 200) throw new IOException("claim " + claimSc);
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
    // #415 pacing: per-stream slot = streams × chunkBytes / rate; random phase so 1k streams do not
    // fire in lockstep. Slots are never skipped: a late stream catches up (one sync POST at a time).
    final long intervalNs = args.rateMibS > 0
        ? Math.max(1L, (long) (args.streams * (double) args.chunkBytes * 1e9 / (args.rateMibS * 1048576.0)))
        : 0L;
    long nextNs = intervalNs > 0 ? System.nanoTime() + rnd.nextLong(intervalNs) : 0L;
    final boolean coStamp = args.chunkBytes >= 40; // second 20-digit stamp fits in the payload
    try {
      if (args.leanHttp) lean = new LeanHttp(URI.create(args.base), 60_000);
      while (!stop.get()) {
        if (mode == 1 && creditChunks < 1) {
          double wait = creditBackoffS(zeroTries++, 1.0, rnd);
          creditWaits.add(wait);
          Thread.sleep((long) Math.max(1, wait * 1000));
          if (stop.get()) break;
        }
        long slotNs;
        if (intervalNs > 0) {
          long now = System.nanoTime();
          while (now < nextNs && !stop.get()) {
            LockSupport.parkNanos(nextNs - now);
            now = System.nanoTime();
          }
          if (stop.get()) break;
          slotNs = nextNs;
          nextNs += intervalNs;
          if (measureActive.get()) {
            long lag = now - slotNs;
            SCHED_SENDS.increment();
            if (lag > LATE_NS) SCHED_LATE.increment();
            SCHED_LAG_MAX_NS.accumulate(lag);
          }
        } else {
          slotNs = -1;
        }
        long emitNs = System.nanoTime();
        if (slotNs < 0) slotNs = emitNs; // closed loop: schedule stamp == send stamp
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
              break;
            }
            if (sc != 200) throw new IOException("chunk " + sc);
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
      AtomicBoolean stop, AtomicBoolean inTeardown, AtomicBoolean measureActive, LongAdder bytesRx,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong consumerConnectRetries, AtomicLong groupOpenRefusals) throws Exception {
    byte[] task = frame(TYPE_TASK, 0, "gate".getBytes(StandardCharsets.UTF_8));
    Exception last = null;
    for (int attempt = 0; attempt < 3 && !stop.get(); attempt++) {
      if (args.leanConsumerActive()) {
        // #415: :stream POST and the replies GET on one raw keep-alive socket; frames are decoded
        // from a reused buffer (no HttpClient selector hop, no per-frame byte[] or String).
        LeanHttp lh = null;
        try {
          lh = new LeanHttp(URI.create(args.base), 200_000);
          String spath = "/swarm/v1/tasks/" + enc(pool) + ":stream?deadlineMs=180000";
          LeanResp sr = lh.postOnce(lh.head("POST", spath, token, CT, CT, task.length), task);
          if (sr.status == 503) {
            groupOpenRefusals.incrementAndGet();
            note(inTeardown, measureErr, teardownErr, "consumer-stream-refused " + pool, new IOException("stream 503"));
            return;
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
          int gsc = lh.startGet(lh.head("GET", loc, token, null, CT, -1));
          if (gsc != 200) throw new IOException("replies " + gsc);
          readBinaryLean(lh.bodyStream(), stop, measureActive, bytesRx);
          return;
        } catch (Exception e) {
          last = e;
          if (!inTeardown.get()) consumerConnectRetries.incrementAndGet();
          if (attempt < 2 && !stop.get()) {
            try { Thread.sleep(20L << attempt); } catch (InterruptedException ie) {
              Thread.currentThread().interrupt(); throw ie;
            }
          }
          continue;
        } finally {
          if (lh != null) lh.close();
        }
      }
      try {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(args.base + "/swarm/v1/tasks/" + enc(pool) + ":stream?deadlineMs=180000"))
            .timeout(Duration.ofSeconds(200))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", CT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(task));
        if ("binary".equals(args.accept) || "chunked".equals(args.accept))
          b.header("Accept", CT);
        else
          b.header("Accept", "text/event-stream");
        HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        int streamSc = resp.statusCode();
        if (streamSc == 503) {
          groupOpenRefusals.incrementAndGet();
          note(inTeardown, measureErr, teardownErr, "consumer-stream-refused " + pool, new IOException("stream 503"));
          return;
        }
        if (streamSc != 201 && streamSc != 200)
          throw new IOException("stream " + streamSc);
        String loc = resp.headers().firstValue("location").orElse(null);
        if (loc == null) {
          Frame f = decodeFrame(ByteBuffer.wrap(resp.body()));
          loc = "/swarm/v1/replies/" + hex(f.payload);
        }
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
          if ("sse".equals(args.accept)) readSse(in, stop, measureActive, bytesRx);
          else readBinary(in, stop, measureActive, bytesRx);
        }
        return;
      } catch (Exception e) {
        last = e;
        // Count every failed connect/setup attempt (not only the final throw into error_count).
        if (!inTeardown.get()) consumerConnectRetries.incrementAndGet();
        if (attempt < 2 && !stop.get()) {
          try { Thread.sleep(20L << attempt); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); throw ie;
          }
          continue;
        }
      }
    }
    if (last != null) throw last;
  }


  /** Strip the optional single space after SSE "data:" (StreamHttp emits "data: "). */
  static String sseDataField(String line) {
    String v = line.substring(5);
    if (!v.isEmpty() && v.charAt(0) == ' ') v = v.substring(1);
    return v;
  }

  static void readBinary(InputStream in, AtomicBoolean stop, AtomicBoolean measureActive, LongAdder bytesRx) throws IOException {
    byte[] hdr = new byte[FRAME_HDR];
    while (!stop.get()) {
      if (!readFully(in, hdr)) break;
      int ver = hdr[0] & 0xff, type = hdr[1] & 0xff, flags = hdr[2] & 0xff;
      int len = ((hdr[3] & 0xff) << 16) | ((hdr[4] & 0xff) << 8) | (hdr[5] & 0xff);
      byte[] payload = new byte[len];
      if (!readFully(in, payload)) break;
      if (type != TYPE_TOKEN) continue;
      if (payload.length < CHUNK_HDR + 20) {
        if ((flags & LAST) != 0) break;
        continue;
      }
      long recv = System.nanoTime();
      // chunk v2: [2][hi8][lo8][attempt4][seq4][flags1][bytes]
      if (payload.length >= CHUNK_HDR + 20) {
        try {
          long emit = Long.parseLong(new String(payload, CHUNK_HDR, 20, StandardCharsets.US_ASCII));
          if (measureActive.get()) {
            LAT.add((recv - emit) / 1e3);
            long slot = payload.length >= CHUNK_HDR + 40 ? asciiLong(payload, CHUNK_HDR + 20) : -1;
            LAT_CO.add((recv - (slot > 0 ? slot : emit)) / 1e3);
            bytesRx.add(payload.length - CHUNK_HDR);
          }
        } catch (NumberFormatException ignored) {}
      }
      if ((payload[CHUNK_HDR - 1] & LAST) != 0 || (flags & LAST) != 0) break;
    }
  }

  /**
   * #415 binary drain over {@link LeanHttp#bodyStream()}: one reused payload buffer, stamps parsed
   * from bytes. Same accounting as {@link #readBinary} (bytes = chunk data, latency in µs).
   */
  static void readBinaryLean(InputStream in, AtomicBoolean stop, AtomicBoolean measureActive, LongAdder bytesRx) throws IOException {
    byte[] hdr = new byte[FRAME_HDR];
    byte[] buf = new byte[4096];
    while (!stop.get()) {
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
      long recv = System.nanoTime();
      long emit = asciiLong(buf, CHUNK_HDR);
      if (emit > 0 && measureActive.get()) {
        LAT.add((recv - emit) / 1e3);
        long slot = len >= CHUNK_HDR + 40 ? asciiLong(buf, CHUNK_HDR + 20) : -1;
        LAT_CO.add((recv - (slot > 0 ? slot : emit)) / 1e3);
        bytesRx.add(len - CHUNK_HDR);
      }
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

  static void readSse(InputStream in, AtomicBoolean stop, AtomicBoolean measureActive, LongAdder bytesRx) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line; StringBuilder data = new StringBuilder(); String event = "";
    while (!stop.get() && (line = br.readLine()) != null) {
      if (line.startsWith("event:")) event = line.substring(6).trim();
      else if (line.startsWith("data:")) data.append(sseDataField(line)).append('\n');
      else if (line.isEmpty()) {
        if ("end".equals(event)) break;
        if ("chunk".equals(event) || data.length() > 0) {
          String d = data.toString();
          if (d.length() >= 20) {
            try {
              long emit = Long.parseLong(d.substring(0, 20).trim());
              long recv = System.nanoTime();
              if (measureActive.get()) {
                LAT.add((recv - emit) / 1e3);
                long slot = -1;
                if (d.length() >= 40) {
                  try { slot = Long.parseLong(d.substring(20, 40).trim()); } catch (NumberFormatException ignored) {}
                }
                LAT_CO.add((recv - (slot > 0 ? slot : emit)) / 1e3);
                bytesRx.add(d.length());
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
   * B1 (Architecture 2026-10-30): drain until open_sessions==0, timeout = max(args, GRACE_MS+15s).
   * Hard zeros are read only from the post-drain scrape. Timeout ⇒ drainTimedOut (window invalid).
   */
  static DrainOutcome waitDrained(HttpClient http, String metricsUrl, double timeoutS) throws Exception {
    long timeoutMs = Math.max((long)(timeoutS * 1000), GRACE_MS + DRAIN_SLACK_MS);
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
        case "--lean-consumer" -> args.leanConsumer = true;
        case "--no-lean-consumer" -> args.leanConsumer = false;
        default -> throw new IllegalArgumentException(a[i]);
      }
    }
    if (args.base == null || args.tokensFile == null || args.out == null) throw new IllegalArgumentException("need --base --tokens-file --out");
    if ("chunked".equals(args.accept)) args.accept = "binary";
    if (args.rateMibS < 0) throw new IllegalArgumentException("--rate-mib-s must be >= 0");
    // Paced windows gate on the scheduled-send stamp at data[20..40) (inceptor Q1): it must fit.
    if (args.rateMibS > 0 && args.chunkBytes < 40) throw new IllegalArgumentException("--rate-mib-s needs --chunk-bytes >= 40");
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
    int maxConnectionsPerIp;
    double elapsedS, wallS, load1Pre, load1Post, mibS, stallPct, creditWaitTotal, clientCpuSec, clientCpuPctOfWall, serverCpuSec, serverCpuPctOfWall;
    Double p50, p90, p99, creditWaitP50, creditWaitP99;
    boolean selfLoaded, stallFlag, generatorSaturated, transportRetryFlag, drainTimedOut;
    int genCores;
    double genSatLimitPct;
    String latencyBasis = "send";
    Double p50Send, p90Send, p99Send, p50Sched, p90Sched, p99Sched;
    String latHistSend = "[]", latHistSched = "[]";
    double rateMibS, schedLatePct, schedLagMaxUs;
    long schedSends;
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
      field(sb, "sched_sends", schedSends, true);
      field(sb, "sched_late_pct", schedLatePct, true);
      field(sb, "sched_lag_max_us", schedLagMaxUs, true);
      field(sb, "lean_consumer", leanConsumer, true);
      field(sb, "latency_basis", quote(latencyBasis), true);
      field(sb, "p99_send_us", p99Send, true);
      field(sb, "p99_sched_us", p99Sched, true);
      field(sb, "requests", requests, true);
      field(sb, "producer_transport_retries", producerTransportRetries, true);
      field(sb, "consumer_connect_retries", consumerConnectRetries, true);
      field(sb, "group_open_refusals", groupOpenRefusals, true);
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
      sb.append("  \"chunk_latency_sched\":{")
        .append("\"p50_us\":").append(p50Sched)
        .append(",\"p90_us\":").append(p90Sched)
        .append(",\"p99_us\":").append(p99Sched)
        .append(",\"hist\":").append(latHistSched)
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
