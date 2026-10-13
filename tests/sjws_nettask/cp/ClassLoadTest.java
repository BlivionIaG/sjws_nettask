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
package sjws_nettask.cp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Standalone must not load the control plane or the Raft package. */
class ClassLoadTest {

    @TempDir
    Path dir;

    @Test
    void standaloneDoesNotLoadControlPlaneOrCluster() throws Exception {
        var root = Files.createDirectory(dir.resolve("html"));
        var log = dir.resolve("classes.log");
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.ha.BenchServer", "--mode=single", "--port=0", "--root=" + root).redirectError(log.toFile())
                .redirectOutput(log.toFile()).start();
        try {
            var deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            var text = "";
            while (System.nanoTime() < deadline) {
                if (Files.exists(log)) {
                    text = Files.readString(log);
                    if (text.contains("bench port=")) {
                        break;
                    }
                }
                Thread.sleep(50);
            }
            assertTrue(text.contains("bench port="), text);
            assertFalse(text.contains("sjws_nettask.cp."), text);
            for (var cp : new String[] {"KeyRing", "KeyRingLoader", "TrustBundle", "TrustState", "CpKeyConfig",
                    "CpListenerConfig", "CpServerTls", "CpTls"}) {
                assertFalse(text.contains("sjws_nettask.cp." + cp + " "), cp);
            }
            assertFalse(text.contains("sjws_nettask.cluster."), text);
            assertFalse(text.contains("sjws_nettask.service."), text);
        } finally {
            process.destroy();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    /** control-plane-hosting.md §5, §8.7: a witness host loads no key ring, store or listener. */
    @Test
    void witnessHostLoadsNoKeyRingStoreOrListeners() throws Exception {
        var javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var log = dir.resolve("witness-classes.log");
        var process = new ProcessBuilder(javaBin, "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.cp.CpWitnessLoadMain").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String text;
        try {
            boolean done = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            text = Files.readString(log);
            assertTrue(done, "witness subprocess hung: " + text.lines().filter(l -> !l.contains("class,load"))
                    .reduce("", (a, b) -> a + "\n" + b));
        } finally {
            process.destroyForcibly();
        }
        assertTrue(text.contains("witness up"), text);
        assertTrue(text.contains("sjws_nettask.cp.CpWitness "), "sanity: the witness itself is loaded");
        for (var name : java.util.List.of("KeyRing", "CpStore", "WatchServer", "AdminServer", "CpHost ")) {
            assertFalse(text.contains("sjws_nettask.cp." + name), name + " loaded by a witness host");
        }
    }
}
