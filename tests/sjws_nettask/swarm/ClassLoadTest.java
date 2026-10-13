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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zero cost when off (docs/design/agent-swarm.md §3.3, item 2), in the shape of
 * {@code sjws_nettask.cp.ClassLoadTest}: a real process under {@code -Xlog:class+load} must not load any
 * {@code sjws_nettask.swarm} class unless the swarm is enabled. The enabled case is the positive control
 * that proves the log would show it.
 */
class ClassLoadTest {

    private static final String SWARM = "sjws_nettask.swarm.";

    @TempDir
    Path dir;

    @Test
    void benchServerSingleLoadsNoSwarmClass() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html"));
        var text = run(List.of(), "bench port=", "sjws_nettask.ha.BenchServer", "--mode=single", "--port=0",
                "--root=" + root);
        assertFalse(text.contains(SWARM), text);
    }

    @Test
    void standaloneCommandLineLoadsNoSwarmClass() throws Exception {
        var root = Files.createDirectory(this.dir.resolve("html"));
        var text = run(List.of(), "Server started:", "sjws_nettask.Sjws_nettask", "0", root.toString());
        assertFalse(text.contains(SWARM), text);
    }

    @Test
    void configFilePathLoadsNoSwarmClass() throws Exception {
        // The config-file path is the one control and data roles start through (Sjws_nettask.serve(Loaded)).
        var text = run(List.of(), "Server started:", "sjws_nettask.Sjws_nettask", "--config=" + yaml());
        assertFalse(text.contains(SWARM), text);
    }

    @Test
    void explicitlyDisabledLoadsNoSwarmClass() throws Exception {
        var text = run(List.of("-Dsjws.swarm.enabled=false"), "Server started:", "sjws_nettask.Sjws_nettask",
                "--config=" + yaml());
        assertFalse(text.contains(SWARM), text);
    }

    @Test
    void enabledLoadsTheSwarmModule() throws Exception {
        var text = run(List.of("-Dsjws.swarm.enabled=true"), "Server started:", "sjws_nettask.Sjws_nettask",
                "--config=" + yaml());
        assertTrue(text.contains("sjws_nettask.swarm.SwarmModule"), text);
    }

    private Path yaml() throws Exception {
        var root = Files.createDirectories(this.dir.resolve("site"));
        var yaml = this.dir.resolve("sjws.yaml");
        Files.writeString(yaml, "server:\n  port: 0\n  root: " + root.toAbsolutePath() + "\n");
        return yaml;
    }

    private String run(List<String> jvmFlags, String ready, String... main) throws Exception {
        var log = this.dir.resolve("classes-" + System.nanoTime() + ".log");
        var cmd = new ArrayList<String>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xlog:class+load=info");
        cmd.addAll(jvmFlags);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.addAll(List.of(main));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().remove("SJWS_SWARM_ENABLED");
        pb.environment().remove("WEB_SERVER_DIR");
        pb.environment().remove("WEB_SERVER_PORT");
        var process = pb.start();
        try {
            var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            var text = "";
            while (System.nanoTime() < deadline) {
                text = Files.exists(log) ? Files.readString(log) : "";
                if (text.contains(ready) || !process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            // The module starts right after "Server started:"; let its class load land in the log.
            Thread.sleep(300);
            text = Files.readString(log);
            assertTrue(text.contains(ready), text);
            return text;
        } finally {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }
}
