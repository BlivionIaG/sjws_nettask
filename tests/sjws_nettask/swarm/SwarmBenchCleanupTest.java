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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** bench/SwarmBench.java removes its ledger directory and refuses to start on a nearly full disk. */
class SwarmBenchCleanupTest {

    @TempDir
    Path dir;

    private Process start(List<String> extra, String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(extra);
        cmd.add("-cp");
        cmd.add(Path.of("target", "classes").toAbsolutePath().toString());
        cmd.add(Path.of("bench", "SwarmBench.java").toAbsolutePath().toString());
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(this.dir.resolve("out.txt").toFile())
                .start();
    }

    private long benchDirs() throws Exception {
        try (var s = Files.list(this.dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("swarmbench")).count();
        }
    }

    @Test
    void theTempDirectoryIsDeletedAfterARun() throws Exception {
        var p = start(List.of(), this.dir.toString(), "dispatch", "1", "0.5");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "bench finished");
        var out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("dispatch workers=1"), out);
        assertEquals(0, benchDirs(), "no swarmbench* directory left: " + out);
    }

    @Test
    void multiWorkerRequestReplyVariantRuns() throws Exception {
        var p = start(List.of(), this.dir.toString(), "reqreply-mw", "3x500", "0.5");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "bench finished");
        var out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(0, p.exitValue(), out);
        // n= counts completions (ok), and it equals the 250 measured calls only because none failed
        var m = java.util.regex.Pattern.compile("reqreply workers=3 rate=500 .* n=(\\d+) ok=(\\d+) failed=0 missing=0").matcher(out);
        assertTrue(m.find(), out);
        assertEquals(250, Long.parseLong(m.group(1)));
        assertEquals(m.group(1), m.group(2));
        assertEquals(0, benchDirs(), "no swarmbench* directory left: " + out);
    }

    @Test
    void failedCallsAreCountedNotTreatedAsZero() throws Exception {
        // every 5th task is acked without a reply; its call times out after 300 ms and must count as failed
        var p = start(List.of("-Dsjws.bench.dropEvery=5", "-Dsjws.bench.callDeadlineMs=300"), this.dir.toString(),
                "reqreply-mw", "2x200", "1");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "bench finished");
        var out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(3, p.exitValue(), "a run with failures is invalid: " + out);
        var m = java.util.regex.Pattern.compile("n=(\\d+) ok=(\\d+) failed=(\\d+) missing=(\\d+)").matcher(out);
        assertTrue(m.find(), out);
        long n = Long.parseLong(m.group(1)), failed = Long.parseLong(m.group(3)), missing = Long.parseLong(m.group(4));
        assertTrue(failed > 0, out);
        assertEquals(200, n + failed + missing, "every measured call is ok, failed or missing: " + out);
        assertTrue(n < 200, "n is completions, not the array size: " + out);
        assertTrue(out.contains("INVALID:"), out);
        // p99 is over completed calls only, so it can't be 0 from failed slots
        var p99 = java.util.regex.Pattern.compile("rtt_p50_us=([\\d.]+)").matcher(out);
        assertTrue(p99.find() && Double.parseDouble(p99.group(1)) > 0, out);
    }

    @Test
    void workersUseDistinctConsumersAndArgsAreValidated() throws Exception {
        var src = Files.readString(Path.of("bench", "SwarmBench.java"));
        assertTrue(src.contains("String consumer = \"w\" + w;"), "one consumer name per worker");
        assertTrue(src.contains("t.claim(\"echo\", consumer,"), "workers claim under their own consumer");
        var p = start(List.of(), this.dir.toString(), "reqreply-mw", "3", "0.5");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assertTrue(p.exitValue() != 0);
        assertTrue(Files.readString(this.dir.resolve("out.txt")).contains("<workers>x<rate>"));
        var q = start(List.of(), this.dir.toString(), "reqreply-mw", "2x0", "0.5");
        assertTrue(q.waitFor(120, TimeUnit.SECONDS));
        assertTrue(q.exitValue() != 0);
        assertTrue(Files.readString(this.dir.resolve("out.txt")).contains("rate >= 1"));
    }

    @Test
    void singleWorkerRequestReplyReportsOneWorker() throws Exception {
        var p = start(List.of(), this.dir.toString(), "reqreply", "500", "0.5");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "bench finished");
        var out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("reqreply workers=1 rate=500"), out);
    }

    @Test
    void heartbeatPerSecondCountsAreOptIn() throws Exception {
        var p = start(List.of(), this.dir.toString(), "heartbeat", "off", "1");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "bench finished");
        var out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.contains("heartbeat mode=off dispatch_tasks/s="), out);
        assertTrue(!out.contains("persec="), "off by default: " + out);

        var q = start(List.of("-Dsjws.bench.persec=true"), this.dir.toString(), "heartbeat", "off", "1");
        assertTrue(q.waitFor(120, TimeUnit.SECONDS), "bench finished");
        out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(0, q.exitValue(), out);
        // one bucket per second from the start of the 2 s warmup, plus one spare: 1 s run -> 4 buckets
        var m = java.util.regex.Pattern.compile("(?m)^persec=\\[(\\d+),(\\d+),(\\d+),(\\d+)\\]$").matcher(out);
        assertTrue(m.find(), out);
        // only the warm-up bucket is certain to be non-empty: a slow runner can stall past the 1 s measured window
        assertTrue(Long.parseLong(m.group(1)) > 0, out);
        assertTrue(out.contains("heartbeat mode=off dispatch_tasks/s="), out);

        var r = start(List.of("-Dsjws.bench.hbWarmup=5"), this.dir.toString(), "heartbeat", "off", "1");
        assertTrue(r.waitFor(120, TimeUnit.SECONDS));
        assertTrue(r.exitValue() != 0);
        assertTrue(Files.readString(this.dir.resolve("out.txt")).contains("sjws.bench.hbWarmup is not supported"));
        assertEquals(0, benchDirs());
    }

    @Test
    void theTempDirectoryIsDeletedWhenTheRunIsKilled() throws Exception {
        var p = start(List.of(), this.dir.toString(), "dispatch", "1", "600");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (benchDirs() == 0 && System.nanoTime() < until) Thread.sleep(50);
        assertEquals(1, benchDirs(), "the run started");
        Thread.sleep(500);
        p.destroy(); // SIGTERM: the shutdown hook deletes the ledger
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, benchDirs(), Files.readString(this.dir.resolve("out.txt")));
    }

    @Test
    void aNearlyFullDiskAbortsBeforeWriting() throws Exception {
        var p = start(List.of("-Dsjws.bench.minFreeBytes=" + Long.MAX_VALUE), this.dir.toString(), "dispatch", "1", "1");
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        var out = Files.readString(this.dir.resolve("out.txt"));
        assertEquals(2, p.exitValue(), out);
        assertTrue(out.contains("ABORT:"), out);
        assertEquals(0, benchDirs());
    }
}
