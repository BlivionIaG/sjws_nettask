
/*
 * S2c PR 8 Java stream loadgen. JDK 25+, no extra deps.
 *   java StreamLoad.java --base URL --tokens-file PATH --out result.json [options]
 * Pin with taskset -c 0-1. Same semantics as drive.py for agg.py.
 */
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

public class StreamLoad {
  static final String CT = "application/vnd.sjws.swarm.v1";
  static final int FRAME_HDR = 6, CHUNK_HDR = 26;
  static final int LAST = 0x01, FIRST = 0x08;
  static final int TYPE_TASK = 1, TYPE_LEASE = 3, TYPE_TOKEN = 6, TYPE_CREDIT = 9;

  static final class Args {
    String base, tokensFile, out, accept = "binary";
    double warmup = 5, secs = 20, drainTimeout = 75;
    int streams = 1000, chunkBytes = 1024, window = 8;
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
    ConcurrentLinkedQueue<Double> latUs = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Double> creditWaits = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> measureErr = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> teardownErr = new ConcurrentLinkedQueue<>();
    AtomicBoolean producersStop = new AtomicBoolean();
    AtomicBoolean consumersStop = new AtomicBoolean();
    AtomicBoolean inTeardown = new AtomicBoolean();
    AtomicLong requests = new AtomicLong();
    AtomicLong credit429 = new AtomicLong();

    // Warmup
    wave(http, args, tokens, Math.min(32, args.streams), args.warmup, "w",
        producersStop, consumersStop, inTeardown, bytesRx, latUs, creditWaits,
        measureErr, teardownErr, requests, credit429);
    bytesRx.reset(); latUs.clear(); creditWaits.clear(); measureErr.clear(); teardownErr.clear();
    requests.set(0); credit429.set(0);

    // Measure + sample client CPU
    AtomicReference<double[]> cpuSample = new AtomicReference<>(new double[]{0,0});
    Thread cpuMon = Thread.startVirtualThread(() -> {
      try {
        long[] c0 = readProcStat();
        long t0 = System.nanoTime();
        Thread.sleep(Math.max(1000, (long)(args.secs * 500)));
        long[] c1 = readProcStat();
        long dt = System.nanoTime() - t0;
        // crude: total busy of this process from /proc/self/stat utime+stime
        double sec = (c1[0] - c0[0]) * 1.0 / scClk();
        cpuSample.set(new double[]{sec, sec / (dt / 1e9) * 100.0});
      } catch (Exception ignored) {}
    });

    long t0 = System.nanoTime();
    wave(http, args, tokens, args.streams, args.secs, "g",
        producersStop, consumersStop, inTeardown, bytesRx, latUs, creditWaits,
        measureErr, teardownErr, requests, credit429);
    double wall = (System.nanoTime() - t0) / 1e9;
    cpuMon.join(2000);
    double load1Post = load1();

    Map<String, Double> counters = waitDrained(http, args.base + "/metrics", args.drainTimeout);
    Result r = new Result();
    r.mode = args.accept; r.streams = args.streams; r.agents = tokens.size(); r.window = args.window;
    r.elapsedS = args.secs; r.wallS = wall; r.load1Pre = load1Pre; r.load1Post = load1Post;
    r.selfLoaded = load1Post >= 4.0;
    List<Double> lat = new ArrayList<>(latUs);
    Collections.sort(lat);
    r.chunkSamples = lat.size();
    r.bytes = bytesRx.sum();
    r.mibS = (r.bytes / (1024.0 * 1024.0)) / Math.max(1e-6, args.secs);
    r.p50 = pct(lat, 0.50); r.p90 = pct(lat, 0.90); r.p99 = pct(lat, 0.99);
    List<Double> cw = new ArrayList<>(creditWaits); Collections.sort(cw);
    r.creditWaitN = cw.size(); r.creditWaitTotal = cw.stream().mapToDouble(d->d).sum();
    r.creditWaitP50 = pct(cw, 0.50); r.creditWaitP99 = pct(cw, 0.99);
    r.creditStalls = credit429.get();
    r.stallPct = r.chunkSamples == 0 ? 0 : 100.0 * r.creditStalls / r.chunkSamples;
    r.stallFlag = r.stallPct > 1.0;
    r.counters = counters;
    r.errorCount = measureErr.size(); r.teardownErrorCount = teardownErr.size();
    r.errors = measureErr.stream().limit(30).toList();
    r.teardownErrors = teardownErr.stream().limit(30).toList();
    r.clientCpuSec = cpuSample.get()[0]; r.clientCpuPctOfWall = cpuSample.get()[1];
    r.requests = requests.get();
    r.hardZeros = Map.of(
        "sjws_swarm_stream_node_refusals_total", counters.getOrDefault("sjws_swarm_stream_node_refusals_total", Double.NaN),
        "sjws_swarm_stream_session_admit_refusals_total", counters.getOrDefault("sjws_swarm_stream_session_admit_refusals_total", Double.NaN),
        "sjws_swarm_stream_budget_underflows_total", counters.getOrDefault("sjws_swarm_stream_budget_underflows_total", Double.NaN),
        "sjws_swarm_stream_reserve_underflows_total", counters.getOrDefault("sjws_swarm_stream_reserve_underflows_total", Double.NaN),
        "sjws_swarm_stream_charged_bytes", counters.getOrDefault("sjws_swarm_stream_charged_bytes", Double.NaN),
        "sjws_swarm_stream_open_sessions", counters.getOrDefault("sjws_swarm_stream_open_sessions", Double.NaN));
    return r;
  }

  static void wave(HttpClient http, Args args, List<String> tokens, int n, double duration, String label,
      AtomicBoolean producersStop, AtomicBoolean consumersStop, AtomicBoolean inTeardown,
      LongAdder bytesRx, ConcurrentLinkedQueue<Double> latUs, ConcurrentLinkedQueue<Double> creditWaits,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429) throws Exception {
    producersStop.set(false); consumersStop.set(false); inTeardown.set(false);
    List<Thread> threads = new ArrayList<>(n * 2);
    for (int i = 0; i < n; i++) {
      String pool = label + i;
      String tok = tokens.get(i % tokens.size());
      int idx = i;
      threads.add(Thread.startVirtualThread(() -> {
        try { consumer(http, args, tok, pool, consumersStop, inTeardown, bytesRx, latUs, measureErr, teardownErr); }
        catch (Exception e) { note(inTeardown, measureErr, teardownErr, "consumer " + pool, e); }
      }));
      if (i % 32 == 0) Thread.sleep(10);
      threads.add(Thread.startVirtualThread(() -> {
        try { producer(http, args, tok, pool, producersStop, inTeardown, creditWaits, measureErr, teardownErr, requests, credit429); }
        catch (Exception e) { note(inTeardown, measureErr, teardownErr, "producer " + pool, e); }
      }));
    }
    Thread.sleep((long)(duration * 1000));
    inTeardown.set(true);
    producersStop.set(true);
    Thread.sleep(2000);
    consumersStop.set(true);
    Thread.sleep(2000);
    for (Thread t : threads) t.join(30_000);
  }

  static void producer(HttpClient http, Args args, String token, String pool,
      AtomicBoolean stop, AtomicBoolean inTeardown, ConcurrentLinkedQueue<Double> creditWaits,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr,
      AtomicLong requests, AtomicLong credit429) throws Exception {
    // claim
    HttpResponse<byte[]> claim = post(http, args.base + "/swarm/v1/tasks/" + enc(pool) + ":claim?visibilityMs=60000&waitMs=30000&max=1",
        token, new byte[0]);
    if (claim.statusCode() != 200) throw new IOException("claim " + claim.statusCode());
    Lease lease = parseLease(claim);
    String path = "/swarm/v1/leases/" + enc(lease.id) + ":chunk";
    long seq = 0;
    int pad = args.chunkBytes - 20;
    if (pad < 0) throw new IllegalArgumentException("chunk-bytes");
    byte[] padBytes = new byte[pad];
    // credit window: track in-flight
    Semaphore inflight = new Semaphore(args.window);
    List<Thread> senders = new ArrayList<>();
    AtomicBoolean done = new AtomicBoolean();
    ConcurrentLinkedQueue<byte[]> q = new ConcurrentLinkedQueue<>();
    for (int s = 0; s < args.window; s++) {
      senders.add(Thread.startVirtualThread(() -> {
        while (!done.get() || !q.isEmpty()) {
          byte[] body = q.poll();
          if (body == null) { try { Thread.sleep(1); } catch (InterruptedException ie) { return; } continue; }
          long tBlocked = System.nanoTime();
          try {
            inflight.acquire();
            double waited = (System.nanoTime() - tBlocked) / 1e9;
            if (waited > 0.00005) creditWaits.add(waited);
            // wire-time stamp: refresh emit ns in payload just before POST
            if (body.length > FRAME_HDR + CHUNK_HDR + 20) {
              long emit = System.nanoTime();
              String ts = String.format("%020d", emit);
              byte[] tsB = ts.getBytes(StandardCharsets.US_ASCII);
              System.arraycopy(tsB, 0, body, FRAME_HDR + CHUNK_HDR, 20);
            }
            HttpResponse<byte[]> r = post(http, args.base + path, token, body);
            requests.incrementAndGet();
            if (r.statusCode() == 429) credit429.incrementAndGet();
            else if (r.statusCode() != 200 && r.statusCode() != 204)
              throw new IOException("chunk " + r.statusCode());
          } catch (Exception e) {
            note(inTeardown, measureErr, teardownErr, "producer-send " + pool, e);
          } finally {
            inflight.release();
          }
        }
      }));
    }
    while (!stop.get()) {
      long emit = System.nanoTime();
      byte[] payload = new byte[args.chunkBytes];
      String ts = String.format("%020d", emit);
      System.arraycopy(ts.getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 20);
      System.arraycopy(padBytes, 0, payload, 20, pad);
      int flags = (seq == 0 ? FIRST : 0);
      byte[] frame = tokenFrame(lease.streamHi, lease.streamLo, lease.attempt, seq++, flags, payload);
      q.add(frame);
    }
    // LAST
    byte[] last = tokenFrame(lease.streamHi, lease.streamLo, lease.attempt, seq, LAST, new byte[0]);
    q.add(last);
    // wait queue drain
    for (int i = 0; i < 5000 && !q.isEmpty(); i++) Thread.sleep(2);
    done.set(true);
    for (Thread t : senders) t.join(10_000);
  }

  static void consumer(HttpClient http, Args args, String token, String pool,
      AtomicBoolean stop, AtomicBoolean inTeardown, LongAdder bytesRx, ConcurrentLinkedQueue<Double> latUs,
      ConcurrentLinkedQueue<String> measureErr, ConcurrentLinkedQueue<String> teardownErr) throws Exception {
    byte[] task = frame(TYPE_TASK, 0, "gate".getBytes(StandardCharsets.UTF_8));
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
    if (resp.statusCode() != 201 && resp.statusCode() != 200)
      throw new IOException("stream " + resp.statusCode());
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
      if ("sse".equals(args.accept)) readSse(in, stop, bytesRx, latUs);
      else readBinary(in, stop, bytesRx, latUs);
    }
  }

  static void readBinary(InputStream in, AtomicBoolean stop, LongAdder bytesRx, ConcurrentLinkedQueue<Double> latUs) throws IOException {
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
      byte[] data = Arrays.copyOfRange(payload, CHUNK_HDR, payload.length);
      if (data.length >= 20) {
        try {
          long emit = Long.parseLong(new String(data, 0, 20, StandardCharsets.US_ASCII));
          latUs.add((recv - emit) / 1e3);
          bytesRx.add(data.length);
        } catch (NumberFormatException ignored) {}
      }
      if ((payload[CHUNK_HDR - 1] & LAST) != 0 || (flags & LAST) != 0) break;
    }
  }

  static void readSse(InputStream in, AtomicBoolean stop, LongAdder bytesRx, ConcurrentLinkedQueue<Double> latUs) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    String line; StringBuilder data = new StringBuilder(); String event = "";
    while (!stop.get() && (line = br.readLine()) != null) {
      if (line.startsWith("event:")) event = line.substring(6).trim();
      else if (line.startsWith("data:")) data.append(line.substring(5)).append('\n');
      else if (line.isEmpty()) {
        if ("end".equals(event)) break;
        if ("chunk".equals(event) || data.length() > 0) {
          String d = data.toString();
          if (d.length() >= 20) {
            try {
              long emit = Long.parseLong(d.substring(0, 20).trim());
              long recv = System.nanoTime();
              latUs.add((recv - emit) / 1e3);
              bytesRx.add(d.length());
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

  static Map<String, Double> waitDrained(HttpClient http, String metricsUrl, double timeoutS) throws Exception {
    long deadline = System.nanoTime() + (long)(timeoutS * 1e9);
    Map<String, Double> last = Map.of();
    while (System.nanoTime() < deadline) {
      last = fetchMetrics(http, metricsUrl);
      Double open = last.get("sjws_swarm_stream_open_sessions");
      if (open == null || open == 0.0) return last;
      Thread.sleep(500);
    }
    return last;
  }
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
    int o = 0;
    while (o < buf.length) {
      int n = in.read(buf, o, buf.length - o);
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
    return Double.parseDouble(s.split("\\s+")[0]);
  }
  static long[] readProcStat() throws IOException {
    // /proc/self/stat fields 14,15 utime stime (clock ticks)
    String[] f = Files.readString(Path.of("/proc/self/stat")).split("\\s+");
    return new long[]{Long.parseLong(f[13]) + Long.parseLong(f[14])};
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
      String[] p = line.split("\\s+");
      if (p.length >= 2) out.add(p[1]);
    }
    if (out.isEmpty()) throw new IllegalArgumentException("no tokens");
    return out;
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
        case "--chunk-bytes" -> args.chunkBytes = Integer.parseInt(a[++i]);
        case "--window" -> args.window = Integer.parseInt(a[++i]);
        case "--drain-timeout" -> args.drainTimeout = Double.parseDouble(a[++i]);
        default -> throw new IllegalArgumentException(a[i]);
      }
    }
    if (args.base == null || args.tokensFile == null || args.out == null) throw new IllegalArgumentException("need --base --tokens-file --out");
    if ("chunked".equals(args.accept)) args.accept = "binary";
    return args;
  }

  static final class Result {
    String mode; int streams, agents, window, chunkSamples, errorCount, teardownErrorCount, creditWaitN;
    long bytes, creditStalls, requests;
    double elapsedS, wallS, load1Pre, load1Post, mibS, stallPct, creditWaitTotal, clientCpuSec, clientCpuPctOfWall;
    Double p50, p90, p99, creditWaitP50, creditWaitP99;
    boolean selfLoaded, stallFlag;
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
      field(sb, "requests", requests, true);
      sb.append("  \"credit_wait\":{")
        .append("\"n\":").append(creditWaitN)
        .append(",\"total_s\":").append(creditWaitTotal)
        .append(",\"p50_s\":").append(creditWaitP50)
        .append(",\"p99_s\":").append(creditWaitP99)
        .append("},\n");
      sb.append("  \"chunk_latency\":{")
        .append("\"n\":").append(chunkSamples)
        .append(",\"p50_us\":").append(p50)
        .append(",\"p90_us\":").append(p90)
        .append(",\"p99_us\":").append(p99)
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
