package sjws_nettask.messaging.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash test: a child JVM appends with the ASYNC default (odd values) and per-append GROUP (even values) and prints each acked offset; we SIGKILL it
 * mid-stream, reopen, and require every acked record to be present and intact. SIGKILL is a process
 * crash (page cache survives), so ASYNC acks must survive too; only power/kernel loss may drop ASYNC tails.
 */
class LedgerCrashTest {

    @TempDir Path dir;

    /** Child entry point: writes until killed. */
    public static void main(String[] args) throws Exception {
        try (var log = SegmentedLog.open(Path.of(args[0]), 64 * 1024); var w = new LedgerWriter(log, Durability.ASYNC)) {
            for (long i = 0; ; i++) {
                long v = i;
                w.append(List.of(ByteBuffer.allocate(8).putLong(v).array(), new byte[(int) (v % 300)]), v % 2 == 0 ? Durability.GROUP : null)
                        .thenAccept(off -> { synchronized (System.out) { System.out.println(off + " " + v); System.out.flush(); } });
            }
        }
    }

    @RepeatedTest(3)
    void noAckedRecordLostAfterSigkill() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LedgerCrashTest.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        long maxAckedOffset = -1;
        var acked = new java.util.HashMap<Long, Long>();
        try (var in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
            String line;
            while ((line = in.readLine()) != null) {
                var parts = line.split(" ");
                if (parts.length != 2 || !parts[0].matches("\\d+")) continue;
                long off = Long.parseLong(parts[0]);
                acked.put(off, Long.parseLong(parts[1]));
                maxAckedOffset = Math.max(maxAckedOffset, off);
                if (System.nanoTime() > until && acked.size() > 50) break;
            }
            p.destroyForcibly(); // SIGKILL
            p.waitFor(10, TimeUnit.SECONDS);
        }
        assertTrue(acked.size() > 50, "child produced acks");
        try (var log = SegmentedLog.open(dir, 64 * 1024)) {
            assertTrue(log.nextOffset() > maxAckedOffset, "next " + log.nextOffset() + " max acked " + maxAckedOffset);
            for (var e : acked.entrySet()) {
                var rb = log.read(e.getKey());
                assertEquals(e.getKey(), rb.baseOffset());
                assertEquals(e.getValue(), ByteBuffer.wrap(rb.records().get(0)).getLong());
                assertEquals(e.getValue() % 300, rb.records().get(1).length);
            }
        }
    }
}
