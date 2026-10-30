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

import sjws_nettask.clusterharness.NodeFixtures;
import sjws_nettask.config.SnapshotVerifier;
import sjws_nettask.ha.HaEvents;
import sjws_nettask.ha.HaSettings;
import sjws_nettask.service.Bootstrap;
import sjws_nettask.service.ControlPlaneBinding;
import sjws_nettask.service.NodeEnrollment;
import sjws_nettask.service.ServiceLaunch;
import sjws_nettask.service.ServiceStatus;

/**
 * Daemon wiring for control and data roles: status text, join-token handling, HA drain on
 * SIGTERM's hook, and the standalone path staying off {@code sjws_nettask.service}.
 */
@Timeout(60)
class ServiceDaemonTest {

    private static final String SECRET = NodeFixtures.JOIN_SECRET;
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
        assertEquals("data", env.role());
        assertEquals("data", env.cliRole());
        assertEquals("control", env.envRole());
        assertEquals(Path.of("/from/env"), env.bootstrap());
        assertTrue(env.childArgs().stream().anyMatch(a -> a.equals("--role=data")));
        assertTrue(env.childArgs().stream().noneMatch(a -> a.contains("--role=control")));

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
    void nodeModeFollowsTheFileUnlessTheFlagDiffers() throws Exception {
        var html = Files.createDirectory(tmp.resolve("html"));
        var yaml = tmp.resolve("node.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\nserver:\n  root: " + html.toAbsolutePath() + "\n");
        var loaded = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());

        var missingBootstrap = Daemon.parse(new String[] {"--config=" + yaml}, Map.of());
        var refused = Sjws_nettask.roleChoice(missingBootstrap, loaded.document(), err);
        assertFalse(refused.ok());
        assertTrue(err().contains("node.mode=data requires --bootstrap"), err());

        errBytes.reset();
        var dataBoot = bootstrap(tmp, "data");
        var fromFile = Daemon.parse(
                new String[] {"--config=" + yaml, "--bootstrap=" + dataBoot, "--pid-file=" + tmp.resolve("a.pid")},
                Map.of());
        var taken = Sjws_nettask.roleChoice(fromFile, loaded.document(), err);
        assertTrue(taken.ok(), err());
        assertEquals("data", taken.expectedRole());
        assertFalse(err().contains("overrides"), err());

        errBytes.reset();
        Files.createDirectories(tmp.resolve("cp"));
        var controlBoot = bootstrap(tmp.resolve("cp"), "control");
        var flagged = Daemon.parse(new String[] {"--role=control", "--config=" + yaml, "--bootstrap=" + controlBoot},
                Map.of());
        var overridden = Sjws_nettask.roleChoice(flagged, loaded.document(), err);
        assertTrue(overridden.ok(), err());
        assertEquals("control", overridden.expectedRole());
        assertTrue(err().contains("--role=control overrides node.mode=data"), err());

        errBytes.reset();
        var matched = Daemon.parse(new String[] {"--role=data", "--config=" + yaml, "--bootstrap=" + dataBoot},
                Map.of());
        var same = Sjws_nettask.roleChoice(matched, loaded.document(), err);
        assertEquals("data", same.expectedRole());
        assertFalse(err().contains("overrides"), err());

        errBytes.reset();
        var env = Daemon.parse(new String[] {"--role=data", "--config=" + yaml, "--bootstrap=" + controlBoot},
                Map.of("WEB_SERVER_ROLE", "control"));
        assertEquals("data", env.role());
        var fromEnv = Sjws_nettask.roleChoice(env, loaded.document(), err);
        assertEquals("data", fromEnv.expectedRole());
        assertTrue(err().contains("--role=data overrides WEB_SERVER_ROLE=control"), err());
        assertFalse(err().contains("overrides node.mode"), err());

        errBytes.reset();
        var onlyEnv = Daemon.parse(new String[] {"--config=" + yaml, "--bootstrap=" + controlBoot},
                Map.of("WEB_SERVER_ROLE", "control"));
        assertEquals("control", onlyEnv.role());
        assertNull(onlyEnv.cliRole());
        var envOverFile = Sjws_nettask.roleChoice(onlyEnv, loaded.document(), err);
        assertEquals("control", envOverFile.expectedRole());
        assertTrue(err().contains("WEB_SERVER_ROLE=control overrides node.mode=data"), err());

        errBytes.reset();
        var bothData = Daemon.parse(new String[] {"--role=data", "--config=" + yaml, "--bootstrap=" + dataBoot},
                Map.of("WEB_SERVER_ROLE", "data"));
        var sameSource = Sjws_nettask.roleChoice(bothData, loaded.document(), err);
        assertEquals("data", sameSource.expectedRole());
        assertFalse(err().contains("overrides WEB_SERVER_ROLE"), err());

        errBytes.reset();
        var standaloneFile = tmp.resolve("alone.yaml");
        Files.writeString(standaloneFile, "schema: 1\nnode:\n  mode: standalone\nserver:\n  root: "
                + html.toAbsolutePath() + "\n");
        var alone = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + standaloneFile}, Map.of());
        var combined = Daemon.parse(new String[] {"--config=" + standaloneFile, "--bootstrap=" + dataBoot}, Map.of());
        var rejected = Sjws_nettask.roleChoice(combined, alone.document(), err);
        assertFalse(rejected.ok());
        assertTrue(err().contains("node.mode=standalone cannot be combined"), err());

        errBytes.reset();
        var noFile = Daemon.parse(new String[] {"--bootstrap=" + dataBoot}, Map.of());
        var deferred = Sjws_nettask.roleChoice(noFile,
                sjws_nettask.config.ConfigLoader.load(noFile.remaining(), Map.of()).document(), err);
        assertTrue(deferred.ok(), err());
        assertNull(deferred.expectedRole());

        errBytes.reset();
        var plain = Daemon.parse(new String[] {html.toString()}, Map.of());
        var untouched = Sjws_nettask.roleChoice(plain,
                sjws_nettask.config.ConfigLoader.load(plain.remaining(), Map.of()).document(), err);
        assertTrue(untouched.ok());
        assertNull(untouched.expectedRole());
        assertEquals("", err());

        errBytes.reset();
        var down = Daemon.parse(new String[] {"--role=standalone", "--config=" + yaml}, Map.of());
        var forced = Sjws_nettask.roleChoice(down, loaded.document(), err);
        assertTrue(forced.ok(), err());
        assertNull(forced.expectedRole());
        assertEquals("standalone", forced.processMode());
        assertTrue(err().contains("overrides node.mode=data"), err());
    }

    @Test
    void aConfigFileSuppliesDataAndTheFlagCanOverrideIt() throws Exception {
        var html = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(html.resolve("hello.txt"), "hello");
        var yaml = tmp.resolve("data.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: data\nserver:\n  root: " + html.toAbsolutePath() + "\n");
        var boot = bootstrap(tmp, "data");
        var pid = tmp.resolve("from-file.pid");
        int code = Sjws_nettask.run(new String[] {"--config=" + yaml, "--bootstrap=" + boot, "--pid-file=" + pid,
                "--state-dir=" + tmp.resolve("last-good"), "--shutdown-timeout=2", Integer.toString(freePort()),
                html.toString()}, Map.of(), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + out());
        assertTrue(out().contains("role=data/gateway"), out());
        assertFalse(err().contains("overrides"), err());

        outBytes.reset();
        errBytes.reset();
        Files.createDirectories(tmp.resolve("cp"));
        var controlBoot = bootstrap(tmp.resolve("cp"), "control");
        var pid2 = tmp.resolve("override.pid");
        code = Sjws_nettask.run(new String[] {"--role=control", "--config=" + yaml, "--bootstrap=" + controlBoot,
                "--pid-file=" + pid2, "--shutdown-timeout=2", Integer.toString(freePort()), html.toString()}, Map.of(),
                out, err, NO_H3, SjwsServer::close);
        // The flag still wins over node.mode, and a CONTROL role without cp settings now fails closed (exit 64,
        // before any listener) instead of running a node that hosts nothing.
        assertEquals(Sjws_nettask.EXIT_USAGE, code, err() + out());
        assertTrue(err().contains("--role=control overrides node.mode=data"), err());
        assertTrue(err().contains("control role without cp settings"), err());
        assertFalse(out().contains("role=control "), out());
        assertFalse(out().contains(SECRET), out());
        assertFalse(err().contains(SECRET), err());
        assertFalse(Files.exists(pid2), "no pid file: refused before listen");
    }

    @Test
    void stopWaitUsesTheDrainBudgetPlusFiveSeconds() {
        assertEquals(35_000, Daemon.stopWaitMillis(Duration.ofSeconds(10), -1));
        assertEquals(5_000, Daemon.stopWaitMillis(Duration.ofSeconds(10), 0));
        assertEquals(55_000, Daemon.stopWaitMillis(Duration.ofSeconds(600), 50_000));
        assertEquals(605_000, Daemon.stopWaitMillis(Duration.ofSeconds(1), 9_000_000));
        assertEquals(30_000L, Sjws_nettask.drainBudgetMillis(-1));
        assertEquals(0L, Sjws_nettask.drainBudgetMillis(0));
        assertEquals(600_000L, Sjws_nettask.drainBudgetMillis(9_000_000));
    }

    @Test
    void messagesDoNotGrowWithoutBound() {
        assertEquals("bootstrap failed", Sjws_nettask.safeMessage(null));
        assertEquals("bootstrap failed", Sjws_nettask.safeMessage(" \n "));
        assertEquals("a b", Sjws_nettask.safeMessage("a\nb"));
        assertEquals(240, Sjws_nettask.safeMessage("x".repeat(400)).length());
        assertEquals(30_000L, Sjws_nettask.drainBudgetMillis(-5));
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
        var sleep = new ProcessBuilder("/bin/sleep", "30").start();
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
                "--state-dir=" + tmp.resolve("last-good"), "--shutdown-timeout=2", Integer.toString(port),
                html.toString()}, Map.of(), out, err, NO_H3, server -> {
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
            Sjws_nettask.publishHaDrainBudget(server, null, -1L);
            Sjws_nettask.publishHaDrainBudget(server, pid, 40_000L);
            long me = ProcessHandle.current().pid();
            long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
            assertEquals("", ServiceStatus.runningSuffix(pid, me, started));
            assertEquals(40_000L, ServiceStatus.drainBudget(pid, me, started));
            var blocker = Files.writeString(tmp.resolve("not-a-dir"), "x");
            Sjws_nettask.publishHaDrainBudget(server, blocker.resolve("x.pid"), 1_000L);
        }
        try (var plain = SjwsServer.start(ServerConfig.defaults(root).withPort(0))) {
            var pid = tmp.resolve("plain.pid");
            Sjws_nettask.publishHaDrainBudget(plain, pid, 50_000L);
            assertFalse(Files.exists(ServiceStatus.beside(pid)));
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
            assertTrue(unit.contains("TimeoutStopSec=45"), unit);
            assertTrue(unit.contains("UMask=0077"), unit);
            assertTrue(hasDirective(unit, "ExecReload=/bin/kill -HUP $MAINPID"), unit);
            assertFalse(hasDirective(unit, "Requires="), unit);
            assertFalse(hasDirective(unit, "secret="), unit);
            assertFalse(unit.contains(SECRET), unit);
        }
        assertTrue(control.contains("--role=control"), control);
        assertTrue(data.contains("--role=data"), data);
        assertTrue(control.contains("--config=/etc/sjws_nettask/control-%i/sjws_nettask-control.yaml"), control);
        assertTrue(data.contains("--config=/etc/sjws_nettask/data-%i/sjws_nettask-data.yaml"), data);
        assertTrue(standalone.contains("--config=/etc/sjws_nettask/sjws_nettask-standalone.yaml"), standalone);
        assertTrue(dropIn.contains("After=sjws_nettask-control@1.service"), dropIn);
        assertFalse(dropIn.contains("Requires="), dropIn);
        assertFalse(standalone.contains("--role="), standalone);
        assertFalse(standalone.contains("HeapDumpOnOutOfMemoryError"), standalone);
        assertTrue(standalone.contains("TimeoutStopSec=45"), standalone);
        assertTrue(hasDirective(standalone, "ExecReload=/bin/kill -HUP $MAINPID"), standalone);
        assertFalse(control.lines().anyMatch(line -> !line.strip().startsWith("#") && line.contains("--bind-content")),
                control);
        var doc = Files.readString(Path.of("docs/operations/control-plane-service.md"));
        assertTrue(doc.contains("Lifecycle.drain()"), doc);
        assertTrue(doc.contains("leaderReady"), doc);
        assertTrue(doc.contains("SIGHUP"), doc);
        assertTrue(doc.contains("/readyz"), doc);
        assertTrue(doc.contains("idempotent"), doc);
    }

    @Test
    void drainBudgetAndContentPortFollowTheFlags() throws Exception {
        var budget = Daemon.parse(new String[] {"--drain-budget=12", "--bind-content=false"},
                Map.of("WEB_SERVER_DRAIN_BUDGET", "3", "WEB_SERVER_BIND_CONTENT", "true"));
        assertEquals(3_000L, budget.drainBudgetMs());
        assertEquals(Boolean.TRUE, budget.bindContent());
        assertTrue(budget.bindFromEnv());
        assertTrue(budget.childArgs().contains("--drain-budget=3"));
        assertTrue(budget.childArgs().contains("--bind-content=true"));
        var bare = Daemon.parse(new String[] {"--bind-content", "--drain-budget=4"}, Map.of());
        assertEquals(Boolean.TRUE, bare.bindContent());
        assertEquals(4_000L, bare.drainBudgetMs());
        assertFalse(bare.bindFromEnv());
        var invalid = Daemon.parse(new String[] {"--drain-budget=601", "--drain-budget=no"},
                Map.of("WEB_SERVER_DRAIN_BUDGET", "x"));
        assertEquals(-1L, invalid.drainBudgetMs());
        assertTrue(invalid.warnings().size() >= 2, invalid.warnings().toString());
        var kept = Daemon.parse(new String[] {"--drain-budget=4"}, Map.of("WEB_SERVER_DRAIN_BUDGET", "nope"));
        assertEquals(4_000L, kept.drainBudgetMs());

        var html = Files.createDirectory(tmp.resolve("bind-html"));
        Files.writeString(html.resolve("index.html"), "hi");
        var controlBoot = bootstrap(tmp, "control");
        var yaml = tmp.resolve("bind.yaml");
        Files.writeString(yaml, "schema: 1\nnode:\n  mode: control\nserver:\n  bind_content: false\n  root: "
                + html.toAbsolutePath() + "\n");
        var loaded = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertEquals(Boolean.FALSE, loaded.yamlBindContent());
        var implicit = Daemon.parse(new String[] {"--config=" + yaml}, Map.of());
        errBytes.reset();
        assertFalse(Sjws_nettask.resolveBind(implicit, loaded, err));
        assertEquals("", err());
        var opted = Daemon.parse(new String[] {"--bind-content=true"}, Map.of());
        errBytes.reset();
        assertTrue(Sjws_nettask.resolveBind(opted, loaded, err));
        assertTrue(err().contains("--bind-content=true overrides the control"), err());
        var fromEnv = Daemon.parse(new String[] {}, Map.of("WEB_SERVER_BIND_CONTENT", "true"));
        errBytes.reset();
        assertTrue(Sjws_nettask.resolveBind(fromEnv, loaded, err));
        assertTrue(err().contains("WEB_SERVER_BIND_CONTENT=true"), err());
        var dataYaml = tmp.resolve("bind-data.yaml");
        Files.writeString(dataYaml, "schema: 1\nnode:\n  mode: data\nserver:\n  bind_content: false\n  root: "
                + html.toAbsolutePath() + "\n");
        var dataLoaded = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + dataYaml}, Map.of());
        errBytes.reset();
        assertFalse(Sjws_nettask.resolveBind(Daemon.parse(new String[] {}, Map.of()), dataLoaded, err));
        assertTrue(err().contains("server.bind_content=false overrides the data"), err());
        var matched = Daemon.parse(new String[] {"--bind-content=true"}, Map.of());
        errBytes.reset();
        assertTrue(Sjws_nettask.resolveBind(matched, dataLoaded, err));
        assertEquals("", err());

        ServiceHooks nameless = new ServiceHooks() {
            @Override
            public void onListening(SjwsServer server, Path pidFile, java.time.Duration shutdownTimeout) {
            }

            @Override
            public void onShutdown() {
            }

            @Override
            public String startupLine() {
                return null;
            }
        };
        assertNull(nameless.nodeMode());
        var plain = sjws_nettask.config.ConfigLoader.load(new String[] {html.toString()}, Map.of());
        var noRole = Sjws_nettask.roleChoice(Daemon.parse(new String[] {html.toString()}, Map.of()), plain.document(),
                err);
        assertSame(plain, Sjws_nettask.applyProcessMode(plain, noRole, null));
        errBytes.reset();
        assertTrue(Sjws_nettask.resolveBind(Daemon.parse(new String[] {}, Map.of()), plain, err));
        assertEquals("", err());
        var asData = Sjws_nettask.applyProcessMode(plain, noRole, new ServiceHooks() {
            @Override
            public void onListening(SjwsServer server, Path pidFile, java.time.Duration shutdownTimeout) {
            }

            @Override
            public void onShutdown() {
            }

            @Override
            public String startupLine() {
                return null;
            }

            @Override
            public String nodeMode() {
                return "data";
            }
        });
        assertEquals(sjws_nettask.config.NodeSettings.Mode.DATA, asData.document().node().mode());
        Files.createDirectories(tmp.resolve("already"));
        var already = Sjws_nettask.roleChoice(
                Daemon.parse(new String[] {"--role=data", "--bootstrap=" + bootstrap(tmp.resolve("already"), "data")},
                        Map.of()),
                asData.document(), err);
        assertSame(asData, Sjws_nettask.applyProcessMode(asData, already, null));

        // CONTROL without cp settings fails closed, with or without --bind-content.
        for (var flags : java.util.List.of(new String[0], new String[] {"--bind-content"})) {
            outBytes.reset();
            errBytes.reset();
            var args = new java.util.ArrayList<String>(java.util.List.of("--role=control"));
            args.addAll(java.util.List.of(flags));
            args.addAll(java.util.List.of("--bootstrap=" + controlBoot, "--pid-file=" + tmp.resolve("refused.pid"),
                    "--shutdown-timeout=1", Integer.toString(freePort()), html.toString()));
            int refused = Sjws_nettask.run(args.toArray(String[]::new), Map.of(), out, err, NO_H3, SjwsServer::close);
            assertEquals(Sjws_nettask.EXIT_USAGE, refused, err() + out());
            assertTrue(err().contains("control role without cp settings"), err());
            assertTrue(err().contains("refusing to start"), err());
        }

        // The unbound content port, now reached through server.bind_content=false on a standalone node.
        outBytes.reset();
        errBytes.reset();
        var noBind = tmp.resolve("standalone-nobind.yaml");
        Files.writeString(noBind, "schema: 1\nserver:\n  port: 0\n  bind_content: false\n  root: " + html.toAbsolutePath() + "\n");
        var pid = tmp.resolve("nobind.pid");
        int code = Sjws_nettask.run(new String[] {"--config=" + noBind, "--pid-file=" + pid,
                "--shutdown-timeout=1"}, Map.of(), out, err, NO_H3, server -> {
                    assertEquals(-1, server.tcpPort());
                    assertEquals("content port not bound", server.describe());
                    server.shutdownTimeout(Duration.ofSeconds(1));
                    server.close();
                });
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + out());
        assertTrue(out().contains("content port not bound"), out());

        outBytes.reset();
        errBytes.reset();
        var pid2 = tmp.resolve("bind.pid");
        code = Sjws_nettask.run(new String[] {"--config=" + noBind, "--bind-content", "--pid-file=" + pid2,
                "--shutdown-timeout=1"}, Map.of(), out, err, NO_H3, server -> {
                    assertTrue(server.tcpPort() > 0);
                    server.close();
                });
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + out());

        outBytes.reset();
        errBytes.reset();
        code = Sjws_nettask.run(new String[] {"--shutdown-timeout=1", "--pid-file=" + tmp.resolve("child.pid"),
                "--log-file=" + tmp.resolve("child.log"), Integer.toString(freePort()), html.toString()},
                Map.of(Daemon.CHILD_ENV, "1"), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + out());
    }

    @Test
    void dataReloadKeepsPushedConfigAndRefreshesTheBootstrap() throws Exception {
        var one = Files.createDirectory(tmp.resolve("one"));
        var two = Files.createDirectory(tmp.resolve("two"));
        var three = Files.createDirectory(tmp.resolve("three"));
        Files.writeString(one.resolve("hello.txt"), "one");
        Files.writeString(two.resolve("hello.txt"), "two");
        Files.writeString(three.resolve("hello.txt"), "three");
        var yaml = tmp.resolve("live.yaml");
        var cfgState = tmp.resolve("cfg-state");
        Files.writeString(yaml, "schema: 1\nversion: 1\nnode:\n  mode: data\n  state_dir: " + cfgState.toAbsolutePath()
                + "\nserver:\n  port: 0\n  root: " + one.toAbsolutePath() + "\n");
        var boot = bootstrap(tmp, "data");
        var pid = tmp.resolve("live.pid");
        var loaded = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        assertTrue(loaded.ok(), loaded.errors().toString());
        var hooks = ServiceLaunch.prepare(boot, "data");
        // The daemon does not inject a verifier; the control-plane workstream does. noop stands in for that.
        try (var server = SjwsServer.start(loaded, SnapshotVerifier.noop())) {
            hooks.onListening(server, pid, Duration.ofSeconds(1));
                    try {
                        Files.writeString(yaml, "schema: 1\nversion: 2\nnode:\n  mode: data\n  state_dir: "
                                + cfgState.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + two.toAbsolutePath()
                                + "\n");
                        var local = server.reload();
                        assertTrue(local.applied(), local.errors().toString());
                        assertEquals(two.toAbsolutePath(), server.config().root());
                        var base = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of())
                                .document();
                        var pushed = sjws_nettask.config.ConfigDocument.of(
                                base.server().toBuilder().root(three).build(), base.acme(), base.routing(),
                                base.middleware(), base.access(), base.node(), 1, 3, null)
                                .withStateDir(base.stateDir());
                        var applied = server.applyRemote(pushed);
                        assertTrue(applied.applied(), applied.errors().toString());
                        assertEquals(three.toAbsolutePath(), server.config().root());
                        Files.writeString(yaml, "schema: 1\nversion: 4\nnode:\n  mode: data\n  state_dir: "
                                + cfgState.toAbsolutePath() + "\nserver:\n  port: 0\n  root: " + one.toAbsolutePath()
                                + "\n");
                        Files.writeString(boot, Files.readString(boot) + "listen=127.0.0.1:9\n");
                        Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));
                        var kept = server.reload();
                        assertFalse(kept.applied());
                        assertTrue(kept.warnings().toString().contains("reloaded local bootstrap"), kept.warnings().toString());
                        assertEquals(three.toAbsolutePath(), server.config().root());
                        assertEquals("127.0.0.1:9", NodeEnrollment.current().bootstrap().listen());
                        var text = Files.readString(boot).replace("node-id=gw-1", "node-id=other");
                        Files.writeString(boot, text);
                        Files.setPosixFilePermissions(boot, PosixFilePermissions.fromString("rw-------"));
                        var rejected = server.reload();
                        assertTrue(rejected.warnings().toString().contains("failed"), rejected.warnings().toString());
                        assertEquals(three.toAbsolutePath(), server.config().root());
                        Files.delete(boot);
                        var missing = server.reload();
                        assertTrue(missing.warnings().toString().contains("failed"), missing.warnings().toString());
                        var enrollment = NodeEnrollment.current();
                        enrollment.close();
                        var again = bootstrap(tmp, "data");
                        var fresh = Bootstrap.load(again, "data");
                        assertThrows(IllegalStateException.class, () -> enrollment.adoptLocal(fresh));
                        assertThrows(IllegalArgumentException.class, () -> enrollment.adoptLocal(null));
                    } finally {
                        hooks.onShutdown();
                    }
        }

        var direct = sjws_nettask.config.ConfigLoader.load(new String[] {"--config=" + yaml}, Map.of());
        try (var server = SjwsServer.start(direct, SnapshotVerifier.noop())) {
            var doc = direct.document();
            var pushed = sjws_nettask.config.ConfigDocument.of(doc.server().toBuilder().root(three).build(), doc.acme(),
                    doc.routing(), doc.middleware(), doc.access(), doc.node(), 1, 5, null)
                    .withStateDir(doc.stateDir());
            assertTrue(server.applyRemote(pushed).applied());
            var kept = server.reload();
            assertTrue(kept.warnings().toString().contains("no local bootstrap"), kept.warnings().toString());
            assertEquals(three.toAbsolutePath(), server.config().root());
            server.setLocalReload(() -> {
                throw new IllegalStateException("x");
            });
            assertTrue(server.reload().warnings().toString().contains("failed"));
            var seen = new AtomicInteger();
            server.setLocalReload(seen::incrementAndGet);
            assertTrue(server.reload().warnings().toString().contains("reloaded local bootstrap"));
            assertEquals(1, seen.get());
            assertEquals(three.toAbsolutePath(), server.config().root());
        }
    }

    @Test
    void daemonChildReportsTheRoleAndDropsItOnStop() throws Exception {
        var fix = bootstrap(tmp, "data");
        var html = Files.createDirectory(tmp.resolve("www"));
        Files.writeString(html.resolve("hello.txt"), "hello");
        var pid = tmp.resolve("daemon.pid");
        var log = tmp.resolve("daemon.log");
        int code = Sjws_nettask.run(new String[] {"--daemon", "--role=data", "--bootstrap=" + fix,
                "--shutdown-timeout=2", "--pid-file=" + pid, "--log-file=" + log,
                "--state-dir=" + tmp.resolve("last-good"), Integer.toString(freePort()), html.toString()}, Map.of(),
                out, err, NO_H3, s -> {
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
            if (Files.isExecutable(Path.of("/usr/bin/setsid"))) {
                var ps = new ProcessBuilder("/bin/ps", "-o", "sid=", "-p", Long.toString(child)).start();
                String sid = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                assertEquals(0, ps.waitFor());
                assertEquals(Long.toString(child), sid, "detached session survives terminal hangup");
            }
            boolean hangupIgnored = sighupIgnored();
            var kill = new ProcessBuilder("/bin/kill", "-HUP", Long.toString(child)).start();
            assertEquals(0, kill.waitFor());
            Thread.sleep(300);
            assertTrue(ProcessHandle.of(child).orElseThrow().isAlive(), "SIGHUP must not kill the child");
            if (!hangupIgnored) {
                var logged = Files.readString(log);
                assertTrue(logged.contains("no config file") || logged.contains("reload"), logged);
            }
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

    /** SIGHUP is signal 1, the least significant bit of SigIgn. */
    private static boolean sighupIgnored() throws Exception {
        var status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return false;
        }
        for (var line : Files.readAllLines(status)) {
            if (line.startsWith("SigIgn:")) {
                var hex = line.substring("SigIgn:".length()).trim();
                return new java.math.BigInteger(hex, 16).testBit(0);
            }
        }
        return false;
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

    /** Pending bootstrap ({@code connection=pending}). State dir is {@code root/state}. */
    private static Path bootstrap(Path root, String role) throws Exception {
        return NodeFixtures.pending(root, role).bootstrap();
    }

    private static int freePort() throws Exception {
        try (var channel = ServerSocketChannel.open()) {
            channel.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }
    }
}
