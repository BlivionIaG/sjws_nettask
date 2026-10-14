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
