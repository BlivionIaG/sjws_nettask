import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
import sjws_nettask.messaging.ledger.*;

/** Report-only census: open G*12 NONE writers, record handles/threads/occupancy/charged. */
public class PoolSweepMain {
  public static void main(String[] args) throws Exception {
    int G = Integer.parseInt(args[0]);
    int per = 12;
    Path root = Files.createTempDirectory("pool-sweep-" + G);
    long t0 = System.nanoTime();
    List<LedgerWriter> ws = new ArrayList<>();
    List<SegmentedLog> logs = new ArrayList<>();
    int refusals = 0;
    for (int g = 0; g < G; g++) {
      for (int p = 0; p < per; p++) {
        try {
          var log = SegmentedLog.open(root.resolve("g" + g + "-p" + p));
          logs.add(log);
          var w = new LedgerWriter(log, Durability.NONE, 0, 1024, 1 << 16);
          ws.add(w);
          w.append(List.of(("x"+g+":"+p).getBytes())).get();
        } catch (LedgerBudgetExhaustedException e) {
          refusals++;
        }
      }
    }
    var pool = SharedLedgerPool.writersOrNull();
    int writerThreads = 0;
    double maxOcc = 0, meanOcc = 0;
    long depthSum = 0;
    if (pool != null && !pool.disabled()) {
      for (int i = 0; i < pool.size(); i++) {
        var t = pool.writerThread(i);
        if (t != null && t.isAlive()) writerThreads++;
        depthSum += pool.queueDepth(i);
      }
      double[] occ = pool.occupancyMaxMean();
      maxOcc = occ[0]; meanOcc = occ[1];
    }
    long live = Thread.getAllStackTraces().keySet().stream()
        .filter(th -> th.getName().startsWith("ledger-writer")).filter(Thread::isAlive).count();
    var rt = Runtime.getRuntime();
    System.gc();
    long heap = rt.totalMemory() - rt.freeMemory();
    var budget = LedgerWriterBudget.shared();
    System.out.printf(Locale.ROOT,
        "G=%d handles=%d writerThreads_alive=%d ledgerWriterNamed=%d heapMB=%.1f charged=%d refusals=%d occMax=%.0f occMean=%.2f depthSum=%d load1=%s%n",
        G, ws.size(), writerThreads, live, heap / (1024.0 * 1024), budget.charged(), refusals + (int)budget.refusals(),
        maxOcc, meanOcc, depthSum, Files.readString(Path.of("/proc/loadavg")).split(" ")[0]);
    for (var w : ws) try { w.close(); } catch (Exception ignored) {}
    for (var log : logs) try { log.close(); } catch (Exception ignored) {}
  }
}
