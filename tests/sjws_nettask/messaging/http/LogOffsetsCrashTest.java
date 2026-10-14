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
package sjws_nettask.messaging.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.messaging.ledger.Durability;

/** kill -9 while committing offsets: every acked commit (GROUP, fsynced) is the stored offset after restart. */
class LogOffsetsCrashTest {

    @TempDir Path dir;

    /** Child: produces 1000 records, then 4 threads commit increasing offsets for groups g0..g3 until killed. */
    public static void main(String[] args) throws Exception {
        var topics = new LogTopics(Path.of(args[0]), Durability.ASYNC);
        var h = new MessagingHttp(topics);
        for (int i = 0; i < 1000; i++) MessagingHttpTest.post(h, "t", MessagingHttpTest.recs("r" + i), null);
        for (int t = 0; t < 4; t++) {
            int g = t;
            Thread.ofPlatform().start(() -> {
                for (long o = 0; ; o = (o + 1) % 1001) {
                    if (LogOffsetsTest.put(h, "t", "g" + g, o).status() != 200) continue;
                    synchronized (System.out) {
                        System.out.println("g" + g + " " + o);
                        System.out.flush();
                    }
                }
            });
        }
        Thread.sleep(Long.MAX_VALUE);
    }

    @Test
    void ackedCommitsSurviveSigkill() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LogOffsetsCrashTest.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        var last = new HashMap<String, Long>();
        int n = 0;
        try (var in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                var parts = line.split(" ");
                if (parts.length != 2 || !parts[0].matches("g\\d")) continue;
                last.put(parts[0], Long.parseLong(parts[1]));
                if (++n > 400) break;
            }
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        assertTrue(n > 400, "child commits: " + n);
        try (var topics = new LogTopics(dir, Durability.ASYNC)) {
            var h = new MessagingHttp(topics);
            for (var e : last.entrySet()) {
                long got = topics.offsets().committed("t", e.getKey());
                // the child may have committed more after the last line we read; never less
                assertTrue(got >= e.getValue() || got == 0 && e.getValue() > 900, e.getKey() + ": " + got + " < " + e.getValue());
            }
            assertEquals(200, LogOffsetsTest.put(h, "t", "g0", 5).status()); // commits continue after recovery
        }
    }
}
