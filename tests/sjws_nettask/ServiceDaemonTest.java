package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import sjws_nettask.ha.HaEvents;
import sjws_nettask.ha.HaSettings;
import sjws_nettask.service.Bootstrap;
import sjws_nettask.service.ControlPlaneBinding;
import sjws_nettask.service.NodeEnrollment;
import sjws_nettask.service.ServiceStatus;

/**
 * Daemon wiring for control and data roles: status text, join-token handling, HA drain on
 * SIGTERM's hook, and the standalone path staying off {@code sjws_nettask.service}.
 */
@Timeout(60)
class ServiceDaemonTest {

    private static final String SECRET = "0123456789abcdef";
    private static final Transport.Factory NO_H3 = (c, h) -> {
        throw new AssertionError("HTTP/3 must not be created");
    };

    @TempDir
    Path tmp;

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @AfterEach
    void clearEnrollment() {
        var current = NodeEnrollment.current();
        if (current != null) {
            current.close();
        }
    }

    String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void roleAndBootstrapParseAndNeverPutTheSecretOnTheChildCommand() {
        var opt = Daemon.parse(new String[] {"--role=data", "--bootstrap=rel/boot", "--daemon"}, Map.of());
        assertTrue(opt.ok(), opt.errors().toString());
        assertEquals("data", opt.role());
        assertEquals(Path.of("rel/boot"), opt.bootstrap());
        var child = String.join(" ", opt.childArgs());
        assertTrue(child.contains("--role=data"), child);
        assertTrue(child.contains("--bootstrap="), child);
        assertTrue(Path.of(child.substring(child.indexOf("--bootstrap=") + "--bootstrap=".length())).isAbsolute());
        assertFalse(child.contains(SECRET), child);

        var env = Daemon.parse(new String[] {"--role=data", "--bootstrap=/from/args"},
                Map.of("WEB_SERVER_ROLE", "control", "WEB_SERVER_BOOTSTRAP", "/from/env"));
        assertEquals("control", env.role());
        assertEquals(Path.of("/from/env"), env.bootstrap());

        var missing = Daemon.parse(new String[] {"--role=data"}, Map.of());
        assertFalse(missing.ok());
        assertTrue(missing.errors().get(0).contains("requires --bootstrap"), missing.errors().toString());

        var both = Daemon.parse(new String[] {"--role=standalone", "--bootstrap=/b"}, Map.of());
        assertFalse(both.ok());
        assertTrue(both.errors().get(0).contains("cannot be combined"), both.errors().toString());

        var bad = Daemon.parse(new String[] {"--role=" + SECRET, "--bootstrap", "--role"}, Map.of());
        assertFalse(bad.ok(), bad.errors().toString());
        assertTrue(bad.errors().stream().noneMatch(e -> e.contains(SECRET)), bad.errors().toString());

        var fromFile = Daemon.parse(new String[] {"--bootstrap=/etc/sjws_nettask/data-1/bootstrap"}, Map.of());
        assertTrue(fromFile.ok(), fromFile.errors().toString());
        assertNull(fromFile.role());
        assertFalse(String.join(" ", fromFile.childArgs()).contains("--role="));

        var blank = Daemon.parse(new String[] {"--role=  ", "--bootstrap="}, Map.of());
        assertFalse(blank.ok());
        var nul = Daemon.parse(new String[] {"--bootstrap=/tmp/boot\0"}, Map.of());
        assertFalse(nul.ok());
        assertTrue(nul.errors().stream().noneMatch(e -> e.indexOf('\0') >= 0));
        var huge = Daemon.parse(new String[] {"--bootstrap=" + "a".repeat(4097)}, Map.of());
        assertFalse(huge.ok());
    }

    @Test
    void stopWaitKeepsTheStandaloneBudgetAndCapsAHostileOne() {
        assertEquals(15_000, Daemon.stopWaitMillis(Duration.ofSeconds(10), -1));
        assertEquals(15_000, Daemon.stopWaitMillis(Duration.ofSeconds(10), 0));
        assertEquals(55_000, Daemon.stopWaitMillis(Duration.ofSeconds(10), 50_000));
        assertEquals(605_000, Daemon.stopWaitMillis(Duration.ofSeconds(10), 9_000_000));
    }

    @Test
    void messagesDoNotGrowWithoutBound() {
        assertEquals("bootstrap failed", Sjws_nettask.safeMessage(null));
        assertEquals("bootstrap failed", Sjws_nettask.safeMessage(" \n "));
        assertEquals("a b", Sjws_nettask.safeMessage("a\nb"));
        assertEquals(240, Sjws_nettask.safeMessage("x".repeat(400)).length());
        assertEquals(-1, Sjws_nettask.drainBudgetMillis(null, Duration.ofSeconds(1)));
    }

    @Test
    void statusLineStaysPlainForStandaloneAndGainsFieldsForData() throws Exception {
        long me = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        var pid = tmp.resolve("self.pid");
        Files.writeString(pid, me + "\n" + started + "\n");
        assertEquals(0, Daemon.status(pid, out, err));
        assertEquals("running pid=" + me + System.lineSeparator(), out());

        outBytes.reset();
        ServiceStatus.writeStandaloneDrain(pid, me, started, 40_000);
        assertEquals(0, Daemon.status(pid, out, err));
        assertEquals("running pid=" + me + System.lineSeparator(), out(), out());
        assertEquals(40_000, ServiceStatus.drainBudget(pid, me, started));

        outBytes.reset();
        ServiceStatus.write(pid, me, started, "data", "broker", "follower", 4, 12_000);
        assertEquals(0, Daemon.status(pid, out, err));
        assertTrue(out().contains("role=data"), out());
        assertTrue(out().contains("data-role=broker"), out());
        assertTrue(out().contains("connection=follower"), out());
        assertTrue(out().contains("config-version=4"), out());
        assertFalse(out().contains(SECRET));
    }

    @Test
    void aLargeDrainBudgetDoesNotDelayAProcessThatExitsOnSigterm() throws Exception {
        var sleep = new ProcessBuilder("sleep", "30").start();
        var pid = tmp.resolve("sleep.pid");
        try {
            long started = sleep.info().startInstant().orElseThrow().toEpochMilli();
            Files.writeString(pid, sleep.pid() + "\n" + started + "\n");
            ServiceStatus.write(pid, sleep.pid(), started, "control", "", "leader", 1, 600_000);
            long began = System.nanoTime();
            assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(pid, Duration.ofSeconds(2), out, err));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertTrue(elapsedMs < 5_000, "stop waited " + elapsedMs + "ms");
            assertTrue(out().contains("stopped pid=" + sleep.pid()), out());
            assertFalse(Files.exists(ServiceStatus.beside(pid)));
        } finally {
            sleep.destroyForcibly();
        }
    }

    @Test
    void dataNodeServesAndStatusOmitsTheSecret() throws Exception {
        var fix = bootstrap(tmp, "data");
        var html = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(html.resolve("hello.txt"), "hello");
        var pid = tmp.resolve("server.pid");
        var seen = new AtomicReference<String>();
        int port = freePort();
        int code = Sjws_nettask.run(new String[] {"--role=data", "--bootstrap=" + fix, "--pid-file=" + pid,
                "--shutdown-timeout=2", Integer.toString(port), html.toString()}, Map.of(), out, err, NO_H3, server -> {
                    try {
                        var buf = new ByteArrayOutputStream();
                        assertEquals(0, Daemon.status(pid, new PrintStream(buf, true, StandardCharsets.UTF_8), err));
                        seen.set(buf.toString(StandardCharsets.UTF_8));
                        assertEquals(200, HttpTestSupport.get(port, "/hello.txt", 2000).statusCode());
                        NodeEnrollment.current().attach(new ControlPlaneBinding() {
                            @Override
                            public String connectionState() {
                                return "connected";
                            }

                            @Override
                            public long configVersion() {
                                return 11;
                            }
                        });
                        buf.reset();
                        Daemon.status(pid, new PrintStream(buf, true, StandardCharsets.UTF_8), err);
                        seen.set(buf.toString(StandardCharsets.UTF_8));
                        server.close();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + out());
        assertTrue(seen.get().contains("role=data"), seen.get());
        assertTrue(seen.get().contains("connection=connected"), seen.get());
        assertTrue(seen.get().contains("config-version=11"), seen.get());
        assertTrue(out().contains("control-plane role=data/gateway"), out());
        assertFalse(out().contains(SECRET), out());
        assertFalse(err().contains(SECRET), err());
        assertFalse(seen.get().contains(SECRET), seen.get());
        assertNull(NodeEnrollment.current());
        assertFalse(Files.exists(ServiceStatus.beside(pid)));
    }

    @Test
    void badBootstrapAndMissingPidFileFailBeforeListen() throws Exception {
        var html = Files.createDirectory(tmp.resolve("html"));
        int code = Sjws_nettask.run(new String[] {"--role=control", Integer.toString(freePort()), html.toString()},
                Map.of(), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_USAGE, code);
        assertTrue(err().contains("requires --bootstrap"), err());

        outBytes.reset();
        errBytes.reset();
        var boot = bootstrap(tmp, "data");
        code = Sjws_nettask.run(new String[] {"--bootstrap=" + boot, Integer.toString(freePort()), html.toString()},
                Map.of(), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_USAGE, code, err());
        assertTrue(err().contains("requires --pid-file"), err());
        assertFalse(err().contains(SECRET), err());
        assertFalse(Files.exists(tmp.resolve("state")), "state dir is not created when the pid file is missing");
    }

    @Test
    void unreadableBootstrapIsAUsageError() throws Exception {
        var html = Files.createDirectory(tmp.resolve("html"));
        var boot = tmp.resolve("missing-boot");
        int code = Sjws_nettask.run(new String[] {"--bootstrap=" + boot, "--pid-file=" + tmp.resolve("p.pid"),
                Integer.toString(freePort()), html.toString()}, Map.of(), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_USAGE, code, err());
        assertTrue(err().contains("ERROR:"), err());
        assertFalse(err().contains(SECRET));
    }

    @Test
    void shutdownHookUsesHaDrainAndStillClosesWhenTheDelayIsInterrupted() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(root.resolve("a.txt"), "a");
        var events = new AtomicInteger();
        var settings = HaSettings.builder().drainDelay(Duration.ZERO).drainTimeout(Duration.ofSeconds(2))
                .events(new HaEvents() {
                    @Override
                    public void drainStarted() {
                        events.incrementAndGet();
                    }

                    @Override
                    public void drainFinished() {
                        events.addAndGet(10);
                    }
                }).build();
        try (var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings)) {
            assertTrue(Sjws_nettask.drainBudgetMillis(server, Duration.ofSeconds(10)) > 15_000);
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.markServing();
            task.run();
            assertEquals(11, events.get());
            assertFalse(server.ha().health().alive());
        }

        var interrupted = new AtomicInteger();
        var failing = HaSettings.builder().drainDelay(Duration.ofSeconds(5)).drainTimeout(Duration.ofSeconds(2))
                .sleeper(delay -> {
                    interrupted.incrementAndGet();
                    throw new InterruptedException("stop");
                })
                .events(HaEvents.NONE)
                .build();
        var root2 = Files.createDirectory(tmp.resolve("html2"));
        try (var server = SjwsServer.start(ServerConfig.defaults(root2).withPort(0), failing)) {
            int port = server.tcpPort();
            var task = new Sjws_nettask.Shutdown(server, new AtomicReference<>(), out, err);
            task.hooks(new ServiceHooks() {
                @Override
                public void onListening(SjwsServer server, Path pidFile, Duration shutdownTimeout) {
                }

                @Override
                public void onShutdown() {
                    throw new IllegalStateException("cleanup");
                }

                @Override
                public String startupLine() {
                    return null;
                }
            });
            task.run();
            assertEquals(1, interrupted.get());
            assertThrows(java.io.IOException.class, () -> HttpTestSupport.get(port, "/a.txt", 500));
            assertTrue(err().contains("status cleanup failed"), err());
        }
    }

    @Test
    void haDrainBudgetIsRecordedWithoutChangingStatusText() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var settings = HaSettings.builder().drainDelay(Duration.ofMillis(5)).drainTimeout(Duration.ofMillis(20))
                .build();
        try (var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings)) {
            var pid = tmp.resolve("ha.pid");
            Sjws_nettask.publishHaDrainBudget(server, null, Duration.ofSeconds(1));
            Sjws_nettask.publishHaDrainBudget(server, pid, Duration.ofSeconds(10));
            long me = ProcessHandle.current().pid();
            long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
            assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
            assertTrue(ServiceStatus.drainBudget(pid, me, started) > 0);
            var blocker = Files.writeString(tmp.resolve("not-a-dir"), "x");
            Sjws_nettask.publishHaDrainBudget(server, blocker.resolve("x.pid"), Duration.ofSeconds(1));
        }
        try (var plain = SjwsServer.start(ServerConfig.defaults(root).withPort(0))) {
            assertEquals(-1, Sjws_nettask.drainBudgetMillis(plain, Duration.ofSeconds(1)));
        }
    }

    @Test
    void hugeDrainBudgetIsCapped() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var settings = HaSettings.builder()
                .drainDelay(Duration.ofMillis(Long.MAX_VALUE / 2))
                .drainTimeout(Duration.ofMillis(Long.MAX_VALUE / 2))
                .build();
        try (var server = SjwsServer.start(ServerConfig.defaults(root).withPort(0), settings)) {
            assertEquals(600_000L, Sjws_nettask.drainBudgetMillis(server, Duration.ofSeconds(10)));
        }
    }

    @Test
    void standaloneProcessDoesNotLoadTheServicePackage() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(root.resolve("index.html"), "hi");
        var pid = tmp.resolve("classload.pid");
        var log = tmp.resolve("classload.log");
        var verbose = tmp.resolve("verbose.txt");
        int port = freePort();
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var pb = new ProcessBuilder(java, "-verbose:class", "-cp", System.getProperty("java.class.path"),
                "sjws_nettask.Sjws_nettask", "--shutdown-timeout=2", "--pid-file=" + pid, "--log-file=" + log,
                Integer.toString(port), root.toString());
        pb.redirectError(verbose.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = pb.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                if (Files.exists(log) && Files.readString(log).contains("Server started:")) {
                    break;
                }
                if (!process.isAlive()) {
                    fail("server exited " + process.exitValue() + " verbose=" + tail(verbose));
                }
                Thread.sleep(50);
            }
            assertTrue(Files.exists(log) && Files.readString(log).contains("Server started:"), tail(verbose));
            assertFalse(Files.exists(ServiceStatus.beside(pid)), "standalone must not write a status file");
            try (var lines = Files.lines(verbose, StandardCharsets.ISO_8859_1)) {
                var hit = lines.filter(l -> l.contains("sjws_nettask.service") || l.contains("sjws_nettask.cp."))
                        .findFirst();
                assertTrue(hit.isEmpty(), hit.orElse(""));
            }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void roleUnitsDoNotPutSecretsOnTheCommandLineOrDependOnEachOther() throws Exception {
        var control = Files.readString(Path.of("contrib/sjws_nettask-control@.service"));
        var data = Files.readString(Path.of("contrib/sjws_nettask-data@.service"));
        var dropIn = Files.readString(Path.of("contrib/sjws_nettask-data-same-host.conf"));
        var standalone = Files.readString(Path.of("contrib/sjws_nettask.service"));
        for (var unit : java.util.List.of(control, data)) {
            assertTrue(unit.contains("Type=forking"), unit);
            assertTrue(unit.contains("--bootstrap="), unit);
            assertTrue(unit.contains("-XX:-HeapDumpOnOutOfMemoryError"), unit);
            assertTrue(unit.contains("LimitCORE=0"), unit);
            assertTrue(unit.contains("TimeoutStopSec=90"), unit);
            assertTrue(unit.contains("UMask=0077"), unit);
            assertFalse(hasDirective(unit, "ExecReload"), unit);
            assertFalse(hasDirective(unit, "Requires="), unit);
            assertFalse(hasDirective(unit, "secret="), unit);
            assertFalse(unit.contains(SECRET), unit);
        }
        assertTrue(control.contains("--role=control"), control);
        assertTrue(data.contains("--role=data"), data);
        assertTrue(dropIn.contains("After=sjws_nettask-control@1.service"), dropIn);
        assertFalse(dropIn.contains("Requires="), dropIn);
        assertFalse(standalone.contains("--role="), standalone);
        assertFalse(standalone.contains("HeapDumpOnOutOfMemoryError"), standalone);
        var doc = Files.readString(Path.of("docs/operations/control-plane-service.md"));
        assertTrue(doc.contains("Lifecycle.drain()"), doc);
        assertTrue(doc.contains("leaderReady"), doc);
        assertTrue(doc.contains("SIGHUP"), doc);
    }

    @Test
    void daemonChildReportsTheRoleAndDropsItOnStop() throws Exception {
        var fix = bootstrap(tmp, "data");
        var html = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(html.resolve("hello.txt"), "hello");
        var pid = tmp.resolve("daemon.pid");
        var log = tmp.resolve("daemon.log");
        int code = Sjws_nettask.run(new String[] {"--daemon", "--role=data", "--bootstrap=" + fix,
                "--shutdown-timeout=2", "--pid-file=" + pid, "--log-file=" + log, Integer.toString(freePort()),
                html.toString()}, Map.of(), out, err, NO_H3, s -> {
                });
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + out());
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            String status = "";
            while (System.nanoTime() < deadline) {
                outBytes.reset();
                Daemon.status(pid, out, err);
                status = out();
                if (status.contains("role=data") && status.contains("connection=pending")) {
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(status.contains("role=data"), status + Files.readString(log));
            assertFalse(status.contains(SECRET), status);
            assertFalse(Files.readString(log).contains(SECRET));
            long child = Long.parseLong(status.replaceAll("(?s).*pid=([0-9]+).*", "$1"));
            var cmdline = Files.readString(Path.of("/proc", Long.toString(child), "cmdline"),
                    StandardCharsets.ISO_8859_1);
            assertFalse(cmdline.contains(SECRET), "secret on the command line");
            assertTrue(cmdline.contains("--bootstrap="), cmdline);
            outBytes.reset();
            assertEquals(Sjws_nettask.EXIT_OK, Sjws_nettask.run(
                    new String[] {"--stop", "--pid-file=" + pid, "--shutdown-timeout=2"}, Map.of(), out, err, NO_H3,
                    s -> {
                    }));
            assertTrue(out().contains("stopped pid="), out());
        } finally {
            Daemon.stop(pid, Duration.ofSeconds(2), out, err);
        }
    }

    /** A systemd assignment, ignoring comments that name a directive we refuse to set. */
    private static boolean hasDirective(String unit, String key) {
        for (var line : unit.split("\n")) {
            var trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            if (trimmed.startsWith(key)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(Path verbose) throws Exception {
        if (!Files.exists(verbose)) {
            return "missing";
        }
        var lines = Files.readAllLines(verbose);
        int from = Math.max(0, lines.size() - 30);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    private static Path bootstrap(Path root, String role) throws Exception {
        var pin = root.resolve("root.pub");
        var token = root.resolve("join.token");
        var state = root.resolve("state");
        var boot = root.resolve("bootstrap");
        Files.writeString(pin, "test-root-public-key\n");
        Files.writeString(token, "token-id=node-1\nsecret=" + SECRET + "\n");
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        String body = role.equals("control")
                ? "role=control\nnode-id=cp-1\npin=" + pin + "\njoin-token=" + token + "\nstate-dir=" + state
                        + "\nlisten=127.0.0.1:9443\n"
                : "role=data\ndata-role=gateway\nnode-id=gw-1\ncontrol-plane=127.0.0.1:9443\npin=" + pin
                        + "\njoin-token=" + token + "\nstate-dir=" + state + "\n";
        Files.writeString(boot, body);
        Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));
        return boot;
    }

    private static int freePort() throws Exception {
        try (var channel = ServerSocketChannel.open()) {
            channel.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }
    }
}
