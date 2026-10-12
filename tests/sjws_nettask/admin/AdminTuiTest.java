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
package sjws_nettask.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sjws_nettask.observe.ClusterBoard;
import sjws_nettask.observe.ClusterStatus;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

class AdminTuiTest {

    @TempDir
    Path root;

    private ServerConfig config(int port) {
        return ServerConfig.builder(this.root).port(port).tlsKeyPassword("secret").build();
    }

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-12T07:00:00Z"), ZoneOffset.UTC);

    // --- LogBuffer ---

    @Test
    void logBufferIsBoundedAndSplitsLines() {
        var log = new LogBuffer(3, FIXED);
        log.add("a");
        log.add("b\nc");
        log.add("d");
        assertEquals(List.of("07:00:00 b", "07:00:00 c", "07:00:00 d"), log.tail(10));
        assertEquals(List.of("07:00:00 d"), log.tail(1));
        assertEquals(List.of(), log.tail(0));
        assertEquals(List.of(), log.tail(-1));
        assertThrows(IllegalArgumentException.class, () -> new LogBuffer(0));
        new LogBuffer(1).add(null);
    }

    @Test
    void tailFileReadsOnlyTheEnd() throws IOException {
        var f = this.root.resolve("server.log");
        Files.writeString(f, "one\ntwo\nthree\nfour\n");
        assertEquals(List.of("three", "four"), LogBuffer.tailFile(f, 2, 1024));
        assertEquals(List.of("one", "two", "three", "four"), LogBuffer.tailFile(f, 10, 1024));
        // 10 bytes from the end = "hree\nfour\n": the partial "hree" is dropped
        assertEquals(List.of("four"), LogBuffer.tailFile(f, 10, 10));
        Files.writeString(f, "");
        assertEquals(List.of(), LogBuffer.tailFile(f, 5, 1024));
        Files.writeString(f, "no newline");
        assertEquals(List.of("no newline"), LogBuffer.tailFile(f, 5, 1024));
        assertThrows(IOException.class, () -> LogBuffer.tailFile(this.root.resolve("missing"), 1, 1));
    }

    // --- EmbeddedServerControl with a real server ---

    @Test
    void startStopReloadRealServer() throws Exception {
        var log = new LogBuffer(50);
        try (var control = new EmbeddedServerControl(() -> config(0), log)) {
            assertEquals(ServerStatus.State.STOPPED, control.status().state());
            assertEquals(Map.of(), control.status().config());
            control.start();
            var st = control.status();
            assertEquals(ServerStatus.State.RUNNING, st.state());
            assertTrue(st.tcpPort() > 0);
            assertEquals(-1, st.udpPort());
            assertEquals(0, st.connections());
            assertTrue(st.summary().contains("tcp/" + st.tcpPort()));
            assertEquals("****", st.config().get("tlsKeyPassword"));
            assertEquals(this.root.toString(), st.config().get("root"));
            try (var probe = new java.net.Socket("localhost", st.tcpPort())) {
                assertTrue(probe.isConnected());
            }
            control.start(); // no-op
            assertTrue(log.tail(1).get(0).endsWith("start: already running"));
            control.reload();
            assertEquals(ServerStatus.State.RUNNING, control.status().state());
            int port = control.status().tcpPort();
            control.stop();
            control.stop(); // idempotent
            assertEquals(ServerStatus.State.STOPPED, control.status().state());
            assertThrows(IOException.class, () -> new java.net.Socket("localhost", port).close());
            assertTrue(control.controllable());
        }
    }

    @Test
    void startFailureIsReportedAsFailed() throws Exception {
        try (var busy = new ServerSocket(0)) {
            var log = new LogBuffer(50);
            var control = new EmbeddedServerControl(() -> config(busy.getLocalPort()), log);
            var e = assertThrows(ServerControl.ControlException.class, control::start);
            assertTrue(e.getMessage().contains("start failed on port " + busy.getLocalPort()), e.getMessage());
            var st = control.status();
            assertEquals(ServerStatus.State.FAILED, st.state());
            assertEquals(e.getMessage(), st.summary());
            control.close();
        }
    }

    @Test
    void badConfigOnReloadKeepsTheRunningServer() throws Exception {
        var calls = new ArrayList<String>();
        var log = new LogBuffer(50);
        java.util.function.Supplier<ServerConfig> source = () -> {
            calls.add("load");
            if (calls.size() > 1) {
                throw new IllegalArgumentException("bad port");
            }
            return config(0);
        };
        try (var control = new EmbeddedServerControl(source, log)) {
            control.start();
            int port = control.status().tcpPort();
            var e = assertThrows(ServerControl.ControlException.class, control::reload);
            assertEquals("configuration error: bad port", e.getMessage());
            assertEquals(port, control.status().tcpPort());
        }
    }

    @Test
    void failedReloadRestoresThePreviousConfig() throws Exception {
        var log = new LogBuffer(50);
        var n = new int[] {0};
        EmbeddedServerControl.Starter starter = c -> {
            if (n[0]++ == 1) {
                throw new BindException("in use");
            }
            return SjwsServer.start(c);
        };
        try (var control = new EmbeddedServerControl(() -> config(0), starter, log, Clock.systemUTC())) {
            control.start();
            assertThrows(ServerControl.ControlException.class, control::reload);
            assertEquals(ServerStatus.State.RUNNING, control.status().state());
            assertTrue(String.join("\n", log.tail(10)).contains("reload failed, restoring previous configuration"));
            control.stop();
            // reload while stopped does not try to restore anything
            n[0] = 1;
            assertThrows(ServerControl.ControlException.class, control::reload);
            assertEquals(ServerStatus.State.FAILED, control.status().state());
        }
    }

    @Test
    void uptimeUsesTheClock() throws Exception {
        var now = new Instant[] {Instant.parse("2026-10-12T07:00:00Z")};
        var clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId z) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        try (var control = new EmbeddedServerControl(() -> config(0), SjwsServer::start, new LogBuffer(5), clock)) {
            control.start();
            now[0] = now[0].plusSeconds(3725);
            assertEquals(Duration.ofSeconds(3725), control.status().uptime());
        }
    }

    // --- AdminTui ---

    /** Scriptable fake: what a daemon-mode control would look like to the TUI. */
    static final class FakeControl implements ServerControl {
        ServerStatus status = ServerStatus.stopped("stopped", Map.of("root", "/srv", "port", "8080"));
        final List<String> calls = new ArrayList<>();
        boolean controllable = true;
        ControlException failure;
        ClusterStatus cluster;

        @Override public ServerStatus status() { return this.status; }
        @Override public ClusterStatus clusterStatus() { return this.cluster; }
        @Override public void start() throws ControlException { call("start"); }
        @Override public void stop() throws ControlException { call("stop"); }
        @Override public void reload() throws ControlException { call("reload"); }
        @Override public boolean controllable() { return this.controllable; }
        @Override public void close() { this.calls.add("close"); }

        private void call(String name) throws ControlException {
            this.calls.add(name);
            if (this.failure != null) {
                throw this.failure;
            }
        }
    }

    @Test
    void commandsDriveTheControl() {
        var control = new FakeControl();
        var tui = new AdminTui(control, new LogBuffer(5), null, false);
        assertTrue(tui.handle("s"));
        assertTrue(tui.handle(" X "));
        assertTrue(tui.handle("r"));
        assertEquals(List.of("start", "stop", "reload"), control.calls);
        assertEquals("reload: ok", tui.message());
        assertFalse(tui.handle("q"));
        tui.handle("zz");
        assertEquals("unknown command 'zz' (h for help)", tui.message());
        tui.handle("h");
        assertTrue(tui.message().startsWith("commands:"));
        tui.handle("?");
        assertTrue(tui.message().startsWith("commands:"));
        tui.handle("");
        assertEquals("refreshed", tui.message());
    }

    @Test
    void viewsSwitch() {
        var tui = new AdminTui(new FakeControl(), new LogBuffer(5), null, false);
        assertEquals(AdminTui.View.STATUS, tui.view());
        tui.handle("c");
        assertEquals(AdminTui.View.CONFIG, tui.view());
        assertTrue(tui.render().contains("  root                   /srv"));
        tui.handle("3");
        assertEquals(AdminTui.View.LOGS, tui.view());
        tui.handle("2");
        assertEquals(AdminTui.View.CONFIG, tui.view());
        tui.handle("l");
        tui.handle("1");
        assertEquals(AdminTui.View.STATUS, tui.view());
        tui.handle("c");
        tui.handle("v");
        assertEquals(AdminTui.View.STATUS, tui.view());
    }

    @Test
    void errorsAndReadOnlyControlsAreShown() {
        var control = new FakeControl();
        var tui = new AdminTui(control, new LogBuffer(5), null, true);
        control.failure = new ServerControl.ControlException("port busy");
        tui.handle("s");
        assertEquals(AdminTui.RED + "error: port busy" + AdminTui.RESET, tui.message());
        control.controllable = false;
        control.calls.clear();
        tui.handle("x");
        assertEquals("stop is not available for this server", tui.message());
        assertEquals(List.of(), control.calls);
    }

    @Test
    void renderStatusPlain() {
        var control = new FakeControl();
        var log = new LogBuffer(5);
        log.add("started");
        control.status = new ServerStatus(ServerStatus.State.RUNNING, 8080, 8443, 3, Duration.ofSeconds(90061),
                "HTTP/1.1 on tcp/8080", Map.of("root", "/srv"));
        var screen = new AdminTui(control, log, null, false).render();
        assertFalse(screen.contains("\u001b"));
        assertTrue(screen.contains("State: RUNNING  up 1d 01:01:01"), screen);
        assertTrue(screen.contains("HTTP/1.1 tcp port : 8080"));
        assertTrue(screen.contains("HTTP/3 udp port   : 8443"));
        assertTrue(screen.contains("Connections       : 3"));
        assertTrue(screen.contains("Document root     : /srv"));
        assertTrue(screen.contains("started"));
        assertTrue(screen.contains("V STATUS"));
        assertTrue(screen.endsWith("> "));
        assertFalse(screen.contains("Cluster desired"));
    }

    @Test
    void renderClusterSection() {
        var control = new FakeControl();
        control.cluster = new ClusterStatus(7, 1500.0, 2_000_000L, 200, 4, 1, new ClusterStatus.Node[] {
                new ClusterStatus.Node("a", true, true, false, 7, false, 100, 1, 1000.0, 0),
                new ClusterStatus.Node("b", false, false, true, 6, true, 5, 3, 0, Long.MAX_VALUE) },
                new ClusterStatus.Series[] { new ClusterStatus.Series("/api", 10, 2, 1_000_000L) },
                new ClusterStatus.Series[] { new ClusterStatus.Series("key-id", 10, 2, 1_000_000L) });
        var screen = new AdminTui(control, new LogBuffer(5), null, false).render();
        assertTrue(screen.contains("Cluster desired 7: 1500.0 req/s  p99 0.002 s  errors 4  drifted 1"), screen);
        assertTrue(screen.contains("node a applied 7  1000.0 req/s  errors 1  p99 -"), screen);
        assertTrue(screen.contains("node b applied 6 DRIFT down draining not-ready  0.0 req/s  errors 3  p99 +Inf"),
                screen);
        assertTrue(screen.contains("route /api  requests 10  errors 2  p99 0.001 s"), screen);
        assertTrue(screen.contains("api-key key-id  requests 10  errors 2  p99 0.001 s"), screen);
    }

    @Test
    void embeddedControlExposesAnAttachedBoard() {
        try (var control = new EmbeddedServerControl(() -> config(0), SjwsServer::start, new LogBuffer(5),
                Clock.systemUTC())) {
            assertNull(control.clusterStatus());
            var board = new ClusterBoard();
            board.desiredVersion(3);
            control.attachCluster(board);
            assertEquals(3, control.clusterStatus().desiredVersion());
            control.attachCluster(null);
            assertNull(control.clusterStatus());
        }
    }

    @Test
    void renderStatesInColor() {
        var control = new FakeControl();
        var tui = new AdminTui(control, new LogBuffer(5), null, true);
        var stopped = tui.render();
        assertTrue(stopped.startsWith(AdminTui.CLEAR));
        assertTrue(stopped.contains(AdminTui.YELLOW + "STOPPED"));
        assertTrue(stopped.contains("HTTP/1.1 tcp port : -"));
        assertTrue(stopped.contains("Connections       : -"));
        control.status = new ServerStatus(ServerStatus.State.FAILED, -1, -1, -1, Duration.ZERO, "boom", Map.of());
        var failed = tui.render();
        assertTrue(failed.contains(AdminTui.RED + "FAILED"));
        assertFalse(failed.contains("Document root"));
        control.status = new ServerStatus(ServerStatus.State.RUNNING, 1, -1, 0, Duration.ofSeconds(5), "x", null);
        assertTrue(tui.render().contains(AdminTui.GREEN + "RUNNING" + AdminTui.RESET + "  up 00:00:05"));
    }

    @Test
    void logsViewTailsTheFile() throws IOException {
        var f = this.root.resolve("out.log");
        Files.writeString(f, "GET /index.html 200\n");
        var log = new LogBuffer(5);
        log.add("stopped");
        var tui = new AdminTui(new FakeControl(), log, f, true);
        tui.handle("l");
        var screen = tui.render();
        assertTrue(screen.contains("GET /index.html 200"));
        assertTrue(screen.contains("stopped"));
        Files.delete(f);
        assertTrue(tui.render().contains(AdminTui.YELLOW + "cannot read:"));
        assertFalse(new AdminTui(new FakeControl(), log, null, false).render().contains("Tail of"));
    }

    @Test
    void runLoopUntilQuitOrEof() throws IOException {
        var control = new FakeControl();
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        new AdminTui(control, new LogBuffer(5), null, false).run(new BufferedReader(new StringReader("s\nq\nx\n")), out);
        assertEquals(List.of("start"), control.calls);
        assertEquals(2, bytes.toString(StandardCharsets.UTF_8).split("> ", -1).length - 1);
        new AdminTui(control, new LogBuffer(5), null, false).run(new BufferedReader(new StringReader("r")), out);
        assertEquals(List.of("start", "reload"), control.calls);
    }

    @Test
    void formatUptime() {
        assertEquals("00:00:00", AdminTui.formatUptime(Duration.ZERO));
        assertEquals("23:59:59", AdminTui.formatUptime(Duration.ofSeconds(86_399)));
        assertEquals("1d 00:00:00", AdminTui.formatUptime(Duration.ofDays(1)));
    }

    // --- AdminMain ---

    private static String runMain(String input, Map<String, String> env, FakeControl fake, String... args)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        int code = AdminMain.run(args, () -> env, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8), (cfg, log) -> fake);
        return code + "|" + bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void mainAutostartsAndClosesTheControl() throws IOException {
        var fake = new FakeControl();
        var out = runMain("q\n", Map.of(), fake, "0", this.root.toString(), "--no-color");
        assertTrue(out.startsWith("0|sjws admin"), out);
        assertEquals(List.of("start", "close"), fake.calls);
        var quiet = new FakeControl();
        runMain("", Map.of(), quiet, "0", this.root.toString(), "--no-autostart", "--log-file=x.log");
        assertEquals(List.of("close"), quiet.calls);
        var failing = new FakeControl();
        failing.failure = new ServerControl.ControlException("nope");
        assertTrue(runMain("q\n", Map.of(), failing, "0", this.root.toString()).startsWith("0|" + AdminTui.CLEAR));
    }

    @Test
    void mainHelpAndUsageErrors() throws IOException {
        var help = runMain("", Map.of(), new FakeControl(), "--help");
        assertTrue(help.startsWith("0|"));
        assertTrue(help.contains("--log-file=PATH"));
        var bad = runMain("", Map.of(), new FakeControl(), "--cert=a.pem", this.root.toString());
        assertTrue(bad.startsWith("64|ERROR: "), bad);
    }

    @Test
    void mainWithEmbeddedControlRunsARealServer() throws IOException {
        var bytes = new ByteArrayOutputStream();
        int code = AdminMain.run(new String[] {"0", this.root.toString(), "--no-color", "--bogus-flag"}, Map::of,
                new ByteArrayInputStream("\nq\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                EmbeddedServerControl::new);
        var screen = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, screen);
        assertTrue(screen.contains("State: RUNNING"), screen);
        assertTrue(screen.contains("WARN: "), "parse warnings reach the event log: " + screen);
    }

    @Test
    void configSourceReparsesAndRejectsErrors() {
        var env = new java.util.HashMap<String, String>();
        var source = AdminMain.configSource(new String[] {this.root.toString()}, () -> env);
        env.put("WEB_SERVER_PORT", "9001");
        assertEquals(9001, source.get().port());
        env.put("WEB_SERVER_PORT", "9002");
        assertEquals(9002, source.get().port());
        var bad = AdminMain.configSource(new String[] {"--cert=a.pem"}, Map::of);
        assertThrows(IllegalArgumentException.class, bad::get);
    }

    @Test
    void describeFollowsTheRecordAndMasksSecrets() {
        var view = EmbeddedServerControl.describe(config(1234));
        assertEquals(ServerConfig.class.getRecordComponents().length, view.size());
        assertEquals("1234", view.get("port"));
        assertEquals("****", view.get("tlsKeyPassword"));
        assertEquals("null", EmbeddedServerControl.describe(ServerConfig.defaults(this.root)).get("tlsKeyPassword"));
        assertEquals("port", view.keySet().iterator().next());
    }
}
