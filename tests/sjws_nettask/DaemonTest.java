package sjws_nettask;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Daemon controls: argument parsing, pid-file lifecycle (including a stale or recycled pid),
 * detached start/stop/status/restart, and graceful shutdown (stop accepting, drain, then force).
 */
@Timeout(60)
class DaemonTest {

    @TempDir
    Path tmp;

    final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    static final Transport.Factory NO_H3 = (c, h) -> {
        throw new AssertionError("HTTP/3 must not be created");
    };

    String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    void reset() {
        outBytes.reset();
        errBytes.reset();
    }

    // --- parsing ----------------------------------------------------------------------

    @Test
    void parsesActionsPathsAndTimeouts() {
        var opt = Daemon.parse(new String[] {"8080", "--daemon", "/www", "--pid-file=/run/sjws.pid",
                "--log-file=/var/log/sjws.log", "--shutdown-timeout=4"}, Map.of());
        assertTrue(opt.ok(), opt.errors().toString());
        assertEquals(Daemon.Action.DAEMON, opt.action());
        assertEquals(Path.of("/run/sjws.pid"), opt.pidFile());
        assertEquals(Path.of("/var/log/sjws.log"), opt.logFile());
        assertEquals(Duration.ofSeconds(4), opt.shutdownTimeout());
        assertTrue(opt.trackPid());
        assertTrue(opt.logToFile());
        assertArrayEquals(new String[] {"8080", "/www"}, opt.remaining());
        assertTrue(opt.warnings().isEmpty(), opt.warnings().toString());
    }

    @Test
    void environmentOverridesPidLogAndTimeout() {
        var opt = Daemon.parse(new String[] {"--shutdown-timeout=4", "--pid-file=/from/args", "--log-file=/from/args.log"},
                Map.of("WEB_SERVER_SHUTDOWN_TIMEOUT", "8", "WEB_SERVER_PID_FILE", "/from/env",
                        "WEB_SERVER_LOG_FILE", "/from/env.log"));
        assertEquals(Duration.ofSeconds(8), opt.shutdownTimeout());
        assertEquals(Path.of("/from/env"), opt.pidFile());
        assertEquals(Path.of("/from/env.log"), opt.logFile());
        assertEquals(Daemon.Action.SERVE, opt.action(), "paths alone do not detach");
        assertTrue(opt.trackPid());
        assertTrue(opt.logToFile());
    }

    @Test
    void invalidValuesWarnAndFallBack() {
        var opt = Daemon.parse(new String[] {"--shutdown-timeout=nope", "--shutdown-timeout=999999", "--pid-file",
                "--pid-file=", "--log-file=", "--daemon=maybe", "--stop=maybe"}, Map.of());
        assertTrue(opt.ok(), opt.errors().toString());
        assertEquals(Daemon.Action.SERVE, opt.action());
        assertEquals(Daemon.DEFAULT_SHUTDOWN_TIMEOUT, opt.shutdownTimeout());
        assertEquals(Path.of(Daemon.DEFAULT_PID_FILE), opt.pidFile());
        assertFalse(opt.trackPid());
        assertFalse(opt.logToFile());
        assertTrue(opt.warnings().size() >= 5, opt.warnings().toString());
        assertTrue(opt.warnings().get(0).contains("Invalid --shutdown-timeout"), opt.warnings().toString());
        assertTrue(opt.warnings().stream().anyMatch(w -> w.contains("Missing value for --pid-file")),
                opt.warnings().toString());
        assertTrue(opt.warnings().stream().anyMatch(w -> w.contains("--daemon")), opt.warnings().toString());
    }

    @Test
    void controlActionsAreMutuallyExclusive() {
        var opt = Daemon.parse(new String[] {"--stop", "--restart"}, Map.of());
        assertFalse(opt.ok());
        assertTrue(opt.errors().get(0).contains("mutually exclusive"), opt.errors().toString());
        assertEquals(Daemon.Action.SERVE, opt.action());
    }

    @Test
    void falseFlagsDoNotSelectAnAction() {
        var opt = Daemon.parse(new String[] {"--daemon=false", "--stop=0", "--status=off", "--restart=no"}, Map.of());
        assertEquals(Daemon.Action.SERVE, opt.action());
        assertTrue(opt.warnings().isEmpty(), opt.warnings().toString());
    }

    @Test
    void childEnvironmentTracksPidAndLogWithoutDetachingAgain() {
        var opt = Daemon.parse(new String[] {"--daemon", "8080"}, Map.of(Daemon.CHILD_ENV, "1"));
        assertEquals(Daemon.Action.DAEMON, opt.action());
        assertTrue(opt.trackPid());
        assertTrue(opt.logToFile());
        var childOnly = Daemon.parse(new String[] {}, Map.of(Daemon.CHILD_ENV, "1"));
        assertEquals(Daemon.Action.SERVE, childOnly.action());
        assertTrue(childOnly.trackPid());
        assertTrue(childOnly.logToFile());
        var restart = Daemon.parse(new String[] {"--restart"}, Map.of());
        assertTrue(restart.trackPid());
        assertTrue(restart.logToFile());
        assertTrue(opt.childArgs().contains("--pid-file=" + Daemon.DEFAULT_PID_FILE));
        assertTrue(opt.childArgs().contains("--shutdown-timeout=10"));
    }

    @Test
    void pidFileMayContainAnEqualsSign() {
        var opt = Daemon.parse(new String[] {"--pid-file=/tmp/a=b"}, Map.of());
        assertEquals(Path.of("/tmp/a=b"), opt.pidFile());
    }

    @Test
    void helpWinsOverStop() throws Exception {
        var sleep = new ProcessBuilder("sleep", "30").start();
        var pid = tmp.resolve("help.pid");
        try {
            Files.writeString(pid, PidFile.text(sleep.pid(), startMillis(sleep)));
            int code = Sjws_nettask.run(new String[] {"--help", "--stop", "--pid-file=" + pid}, Map.of(), out, err,
                    NO_H3, SjwsServer::close);
            assertEquals(Sjws_nettask.EXIT_OK, code);
            assertEquals(Sjws_nettask.usage(), out());
            assertTrue(sleep.isAlive(), "help must not signal the pid-file process");
        } finally {
            sleep.destroyForcibly();
        }
    }

    @Test
    void stopAndStatusDoNotNeedADocumentRoot() {
        assertEquals(Daemon.STATUS_STOPPED, Sjws_nettask.run(new String[] {"--status", "--pid-file=" + tmp.resolve("nope")},
                Map.of(), out, err, NO_H3, SjwsServer::close));
        assertEquals("stopped" + System.lineSeparator(), out());
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Sjws_nettask.run(new String[] {"--stop", "--pid-file=" + tmp.resolve("nope")},
                Map.of(), out, err, NO_H3, SjwsServer::close));
        assertEquals("stopped" + System.lineSeparator(), out());
    }

    @Test
    void conflictingActionsAreAUsageError() {
        assertEquals(Sjws_nettask.EXIT_USAGE, Sjws_nettask.run(new String[] {"--daemon", "--stop"}, Map.of(), out, err,
                NO_H3, SjwsServer::close));
        assertTrue(err().contains("ERROR: --daemon, --stop are mutually exclusive"), err());
        assertTrue(err().contains("See --help."), err());
    }

    @Test
    void foregroundLogAndPidFile() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(root.resolve("hello.txt"), "hello");
        var pid = tmp.resolve("server.pid");
        var log = tmp.resolve("server.log");
        var seen = new CountDownLatch(1);
        int code = Sjws_nettask.run(new String[] {"--pid-file=" + pid, "--log-file=" + log, "--shutdown-timeout=2",
                String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3, server -> {
                    assertEquals(0, Daemon.status(pid, out, err));
                    assertTrue(out().contains("running pid=" + ProcessHandle.current().pid()), out());
                    seen.countDown();
                    server.close();
                });
        assertEquals(Sjws_nettask.EXIT_OK, code, err());
        assertEquals(0, seen.getCount());
        assertFalse(Files.exists(pid), "pid file removed on shutdown");
        var logged = Files.readString(log);
        assertTrue(logged.contains("Server started: HTTP/1.1 on tcp/"), logged);
        assertTrue(logged.contains("Server Terminated properly !"), logged);
        assertEquals("", err(), "startup lines go to the log file");
    }

    @Test
    void childRedirectsStandardStreamsAndIgnoresHangup() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var pid = tmp.resolve("child.pid");
        var log = tmp.resolve("child.log");
        var beforeOut = System.out;
        var beforeErr = System.err;
        int code = Sjws_nettask.run(
                new String[] {"--pid-file=" + pid, "--log-file=" + log, String.valueOf(freePort()), root.toString()},
                Map.of(Daemon.CHILD_ENV, "1"), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_OK, code, err());
        assertSame(beforeOut, System.out, "in-process child must not replace System.out");
        assertSame(beforeErr, System.err, "in-process child must not replace System.err");
        assertFalse(Files.exists(pid));
        assertTrue(Files.readString(log).contains("Server started:"), Files.readString(log));
        assertTrue(Daemon.ignoreSignal("HUP"));
        assertFalse(Daemon.ignoreSignal("NOT_A_REAL_SIGNAL_XYZ"));
    }

    @Test
    void daemonPathsLeaveStandardStreamsAlone() throws Exception {
        var beforeOut = System.out;
        var beforeErr = System.err;
        var root = Files.createDirectory(tmp.resolve("html"));
        var pid = tmp.resolve("hook.pid");
        var log = tmp.resolve("hook.log");
        var redirected = new AtomicReference<PrintStream>();
        int code = Sjws_nettask.run(
                new String[] {"--pid-file=" + pid, "--log-file=" + log, String.valueOf(freePort()), root.toString()},
                Map.of(Daemon.CHILD_ENV, "1"), out, err, NO_H3, SjwsServer::close, redirected::set);
        assertEquals(Sjws_nettask.EXIT_OK, code, err());
        assertNotNull(redirected.get(), "the detached-child hook receives the log stream");
        assertSame(beforeOut, System.out);
        assertSame(beforeErr, System.err);
        assertTrue(Files.readString(log).contains("Server started:"), Files.readString(log));

        var notChild = new AtomicReference<PrintStream>();
        assertEquals(Daemon.STATUS_STOPPED, Sjws_nettask.run(
                new String[] {"--status", "--pid-file=" + tmp.resolve("missing.pid")}, Map.of(), out, err, NO_H3,
                SjwsServer::close, notChild::set));
        assertNull(notChild.get(), "a non-child run does not redirect");
        assertSame(beforeOut, System.out);
        assertSame(beforeErr, System.err);

        var savedOut = System.out;
        var savedErr = System.err;
        var sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        try {
            Daemon.redirectStandardStreams(sink);
            assertSame(sink, System.out);
            assertSame(sink, System.err);
        } finally {
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
        assertSame(beforeOut, System.out);
        assertSame(beforeErr, System.err);
    }

    // --- command line -----------------------------------------------------------------

    @Test
    void spliceKeepsJvmFlagsAndReplacesProgramArguments() {
        var out = new ArrayList<String>();
        assertTrue(Daemon.splice(out, "/java", new String[] {"-Xmx1g", "-jar", "/opt/app.jar", "--daemon", "1"},
                List.of("8080", "--pid-file=p")));
        assertEquals(List.of("/java", "-Xmx1g", "-jar", "/opt/app.jar", "8080", "--pid-file=p"), out);
        out.clear();
        assertTrue(Daemon.splice(out, "/java",
                new String[] {"-cp", "a.jar", "sjws_nettask.Sjws_nettask", "--daemon"}, List.of("--log-file=l")));
        assertEquals(List.of("/java", "-cp", "a.jar", "sjws_nettask.Sjws_nettask", "--log-file=l"), out);
        assertFalse(Daemon.splice(new ArrayList<>(), "/java", new String[] {"-jar"}, List.of("x")));
        assertFalse(Daemon.splice(new ArrayList<>(), "/java", new String[] {"-version"}, List.of()));
        assertTrue(Daemon.isOurLaunch(new String[] {"-jar", "/opt/sjws_nettask.jar"}));
        assertTrue(Daemon.isOurLaunch(new String[] {"-cp", "a.jar", "sjws_nettask.Sjws_nettask"}));
        assertFalse(Daemon.isOurLaunch(new String[] {"-jar", "/surefire/surefirebooter.jar"}));
        assertFalse(Daemon.isOurLaunch(new String[] {"-jar", "sjws_nettask-surefire.jar"}));
        assertFalse(Daemon.isOurLaunch(new String[] {"-jar"}));
        assertFalse(Daemon.isOurLaunch(new String[] {"-version"}));
    }

    @Test
    void detachUsesSetsidOnlyWhenTheBinaryExists() {
        var cmd = List.of("/java", "-jar", "a.jar");
        if (Files.isExecutable(Path.of("/usr/bin/setsid"))) {
            assertEquals("/usr/bin/setsid", Daemon.detach(cmd, Path.of("/usr/bin/setsid")).get(0));
        }
        assertEquals(cmd, Daemon.detach(cmd, tmp.resolve("no-setsid")));
    }

    @Test
    void childCommandCarriesTheApplicationArguments() {
        var cmd = Daemon.childCommand(List.of("--pid-file=from-test"));
        assertTrue(cmd.contains("--pid-file=from-test"), cmd.toString());
        assertTrue(cmd.stream().anyMatch(a -> a.endsWith("java")), cmd.toString());

        var ours = Daemon.childCommand(List.of("8080"),
                Optional.of(new String[] {"-Xmx1g", "-jar", "/opt/sjws_nettask.jar", "--daemon"}),
                "/java", "unused-cp");
        assertEquals(List.of("/java", "-Xmx1g", "-jar", "/opt/sjws_nettask.jar", "8080"),
                ours.subList(ours.indexOf("/java"), ours.size()));
        var hidden = Daemon.childCommand(List.of("1"), Optional.empty(), "/java", "cp");
        assertTrue(hidden.contains("cp") && hidden.contains("sjws_nettask.Sjws_nettask"), hidden.toString());
        assertEquals(Path.of("/dev/null"), Daemon.discardInput(List.of("NUL", "/dev/null")));
        assertNull(Daemon.discardInput(List.of("no-such-null-device")));

        var env = new HashMap<String, String>();
        var configured = new HashMap<String, String>();
        configured.put("WEB_SERVER_PORT", null);
        configured.put("WEB_SERVER_ROOT", "/www");
        Daemon.applyServerEnv(env, configured);
        assertEquals("/www", env.get("WEB_SERVER_ROOT"));
        assertFalse(env.containsKey("WEB_SERVER_PORT"));
        var secret = "daemon-cluster-key-should-not-pass";
        env.put("WEB_SERVER_HA_CLUSTER_KEY", secret);
        env.put("WEB_SERVER_PORT", "9");
        Daemon.applyServerEnv(env, Map.of("WEB_SERVER_HA_CLUSTER_KEY", secret, "WEB_SERVER_PORT", "9",
                "WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS", "daemon-previous-key-no-pass"));
        assertEquals("9", env.get("WEB_SERVER_PORT"));
        assertFalse(env.containsKey("WEB_SERVER_HA_CLUSTER_KEY"));
        assertFalse(env.containsKey("WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS"));
        assertFalse(env.values().toString().contains(secret));

        var yes = Daemon.parse(new String[] {"--daemon=yes"}, Map.of());
        assertEquals(Daemon.Action.DAEMON, yes.action());
        assertEquals(Daemon.Action.STOP, Daemon.parse(new String[] {"--stop=1"}, Map.of()).action());
        assertEquals(Daemon.Action.STATUS, Daemon.parse(new String[] {"--status=on"}, Map.of()).action());
        assertEquals(Daemon.Action.RESTART, Daemon.parse(new String[] {"--restart=true"}, Map.of()).action());
        var blank = Daemon.parse(new String[] {"--shutdown-timeout"}, Map.of("WEB_SERVER_PID_FILE", " ",
                "WEB_SERVER_LOG_FILE", ""));
        assertFalse(blank.trackPid());
        assertFalse(blank.logToFile());
        assertEquals(Daemon.DEFAULT_SHUTDOWN_TIMEOUT, blank.shutdownTimeout());
        assertThrows(IOException.class, () -> Daemon.openLog(Path.of("/")));
    }

    @Test
    void serverEnvIsReplacedAndTheChildIsMarked() {
        var env = new HashMap<String, String>();
        env.put("WEB_SERVER_PORT", "1");
        env.put("HOME", "/home");
        Daemon.applyServerEnv(env, Map.of("WEB_SERVER_PORT", "2", "NOT_A_SERVER_VAR", "x"));
        assertEquals("2", env.get("WEB_SERVER_PORT"));
        assertEquals("/home", env.get("HOME"));
        assertEquals("1", env.get(Daemon.CHILD_ENV));
        assertFalse(env.containsKey("NOT_A_SERVER_VAR"));
    }

    @Test
    void redirectStandardStreamsIsRestorable() {
        var savedOut = System.out;
        var savedErr = System.err;
        var log = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        try {
            Daemon.redirectStandardStreams(log);
            assertSame(log, System.out);
            assertSame(log, System.err);
        } finally {
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    // --- pid file ---------------------------------------------------------------------

    @Test
    void claimWritesTheRunningProcessAndReleaseRemovesTheFile() throws Exception {
        var pid = tmp.resolve("nested").resolve("sjws.pid");
        var claim = PidFile.claim(pid);
        try {
            assertTrue(claim.acquired());
            var snap = PidFile.read(pid);
            assertTrue(snap.running());
            assertEquals(ProcessHandle.current().pid(), snap.pid());
            long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
            assertEquals(started, snap.startedMillis());
            assertEquals(0, Daemon.status(pid, out, err));
            assertEquals("running pid=" + snap.pid() + System.lineSeparator(), out());
        } finally {
            claim.file().release();
        }
        claim.file().release(); // idempotent
        assertFalse(Files.exists(pid));
        assertEquals(Daemon.STATUS_STOPPED, Daemon.status(pid, out, err));
    }

    @Test
    void aSecondClaimInThisJvmIsRefusedUntilRelease() throws Exception {
        var pid = tmp.resolve("once.pid");
        var first = PidFile.claim(pid);
        assertTrue(first.acquired());
        try {
            var second = PidFile.claim(pid);
            assertFalse(second.acquired());
            assertTrue(second.snapshot().running());
            assertNull(second.file());
        } finally {
            first.file().release();
        }
        var again = PidFile.claim(pid);
        assertTrue(again.acquired());
        again.file().release();
    }

    @Test
    void concurrentClaimsLetExactlyOneWin() throws Exception {
        var pid = tmp.resolve("race.pid");
        var start = new CountDownLatch(1);
        var claims = new PidFile.Claim[2];
        var threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            int n = i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    claims[n] = PidFile.claim(pid);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (var t : threads) {
            t.join(5_000);
        }
        int wins = (claims[0].acquired() ? 1 : 0) + (claims[1].acquired() ? 1 : 0);
        assertEquals(1, wins);
        if (claims[0].acquired()) {
            claims[0].file().release();
        }
        if (claims[1].acquired()) {
            claims[1].file().release();
        }
    }

    @Test
    void staleAndReusedPidsAreNotSignalled() throws Exception {
        long me = ProcessHandle.current().pid();
        long started = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
        assertTrue(PidFile.isLive(me, started));
        assertTrue(PidFile.isLive(me, -1), "alive pid with no recorded start time");
        assertFalse(PidFile.isLive(me, 1), "same pid, different start time");
        assertFalse(PidFile.isLive(-1, 0));
        assertFalse(PidFile.isLive(0, 0));
        assertFalse(PidFile.blocksStart(PidFile.parse(me + "\n")), "our pid, no start time, is not a running server");
        assertTrue(PidFile.blocksStart(PidFile.parse(PidFile.text(me, started))));
        assertTrue(PidFile.identifies(PidFile.parse(PidFile.text(me, started)), ProcessHandle.current()));
        assertFalse(PidFile.identifies(PidFile.parse(me + "\n"), ProcessHandle.current()));
        var wrongStart = new PidFile.Snapshot(true, me, started + 1, true);
        assertFalse(PidFile.identifies(wrongStart, ProcessHandle.current()));

        var snap = PidFile.parse(PidFile.text(me, started));
        assertTrue(snap.running());
        assertEquals(me, snap.pid());
        assertTrue(PidFile.parse("nope\n").stale());
        assertTrue(PidFile.parse("nope\n").present());
        assertFalse(PidFile.parse("").present());
        assertFalse(PidFile.parse(null).present());
        assertEquals(-1, PidFile.parse("12\nabc\n").startedMillis());
        assertTrue(PidFile.sameOwner(snap, new PidFile.Snapshot(true, me, started, false)));
        assertFalse(PidFile.sameOwner(snap, PidFile.Snapshot.ABSENT));
        assertFalse(PidFile.sameOwner(snap, new PidFile.Snapshot(true, me + 1, started, true)));
        assertFalse(PidFile.sameOwner(snap, new PidFile.Snapshot(true, me, started + 1, true)));
        assertFalse(PidFile.Snapshot.ABSENT.stale());
        assertFalse(new PidFile.Snapshot(false, me, started, true).stale());
        assertFalse(snap.stale());

        var reused = tmp.resolve("reused.pid");
        Files.writeString(reused, PidFile.text(me, 1));
        assertFalse(PidFile.read(reused).running());
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(reused, Duration.ofSeconds(1), out, err));
        assertFalse(Files.exists(reused), "stale file removed");
        assertTrue(out().contains("stopped stale pid=" + me), out());
        assertTrue(ProcessHandle.current().isAlive());

        var dead = new ProcessBuilder("sleep", "0").start();
        assertTrue(dead.waitFor(5, TimeUnit.SECONDS));
        var stale = tmp.resolve("stale.pid");
        Files.writeString(stale, PidFile.text(dead.pid(), startMillis(dead)));
        reset();
        assertEquals(Daemon.STATUS_STALE, Daemon.status(stale, out, err));
        assertTrue(out().startsWith("stopped stale pid="), out());
        var claim = PidFile.claim(stale);
        assertTrue(claim.acquired(), "a dead pid does not block a new server");
        claim.file().release();

        var recycled = tmp.resolve("recycled-onto-us.pid");
        Files.writeString(recycled, me + "\n");
        assertTrue(PidFile.read(recycled).running());
        var taken = PidFile.claim(recycled);
        assertTrue(taken.acquired(), "a pid recycled onto this process must not block startup");
        assertEquals(started, PidFile.read(recycled).startedMillis());
        taken.file().release();
        assertFalse(Files.exists(recycled));
    }

    @Test
    void aLiveForeignPidIsLeftAlone() throws Exception {
        var sleep = new ProcessBuilder("sleep", "60").start();
        var pid = tmp.resolve("sleep.pid");
        try {
            Files.writeString(pid, PidFile.text(sleep.pid(), startMillis(sleep)));
            var claim = PidFile.claim(pid);
            assertFalse(claim.acquired(), "must not steal a pid file that names a live process");
            assertEquals(0, Daemon.status(pid, out, err));
            assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(pid, Duration.ofSeconds(2), out, err));
            assertTrue(sleep.waitFor(5, TimeUnit.SECONDS), "SIGTERM should stop sleep");
            assertFalse(Files.exists(pid));
            assertTrue(out().contains("stopped pid=" + sleep.pid()), out());
        } finally {
            sleep.destroyForcibly();
        }
    }

    @Test
    void symlinkPidFileIsRefusedAndGarbageIsStale() throws Exception {
        var target = tmp.resolve("target.pid");
        Files.writeString(target, "1\n1\n");
        var link = tmp.resolve("link.pid");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable");
        }
        var ex = assertThrows(IOException.class, () -> PidFile.claim(link));
        assertTrue(ex.getMessage().contains("symlink"), ex.getMessage());

        var huge = tmp.resolve("huge.pid");
        Files.writeString(huge, "x".repeat(600));
        assertTrue(PidFile.read(huge).stale());
        assertEquals(Daemon.STATUS_STALE, Daemon.status(huge, out, err));
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(huge, Duration.ofSeconds(1), out, err));
        assertFalse(Files.exists(huge));

        var emptyDir = Files.createDirectory(tmp.resolve("pid-dir"));
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(emptyDir, Duration.ofSeconds(1), out, err));
        assertFalse(Files.exists(emptyDir));

        var fullDir = Files.createDirectory(tmp.resolve("full-pid-dir"));
        Files.writeString(fullDir.resolve("kept"), "x");
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(fullDir, Duration.ofSeconds(1), out, err));
        assertTrue(Files.exists(fullDir), "a non-empty directory is not removed");
        assertTrue(err().contains("could not remove pid file"), err());
    }

    /**
     * An empty directory's {@code st_size} is 4096 on ext4 and 40 on tmpfs. Staleness has to
     * follow the file type on both, and the 512-byte cap has to apply only to regular files.
     * {@code /dev/shm} is tmpfs; {@code /var/tmp} stays on disk when {@code /tmp} is a tmpfs.
     */
    @Test
    void pidFileKindIsClassifiedWithoutUsingDirectorySize() throws Exception {
        var stores = new ArrayList<Path>();
        var kinds = new ArrayList<String>();
        var described = new ArrayList<String>();
        try {
            addPidStore(stores, kinds, described, Path.of("/dev/shm"), true);
            addPidStore(stores, kinds, described, Path.of("/var/tmp"), false);
            boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
            if (linux) {
                assertTrue(kinds.contains("tmpfs"), "tmpfs fixture missing: " + described);
                assertTrue(kinds.contains("disk"), "disk fixture missing: " + described);
            } else if (stores.isEmpty()) {
                org.junit.jupiter.api.Assumptions.abort("no pid-file fixture directory");
            }
            var emptySizes = new HashMap<String, Long>();
            for (int i = 0; i < stores.size(); i++) {
                emptySizes.put(kinds.get(i), exercisePidStore(stores.get(i)));
            }
            if (emptySizes.containsKey("tmpfs") && emptySizes.containsKey("disk")) {
                assertTrue(emptySizes.get("tmpfs") <= 512, emptySizes.toString());
                assertTrue(emptySizes.get("disk") > 512, emptySizes.toString());
            }
            refuseDeviceNode();
        } finally {
            for (var store : stores) {
                deleteTree(store);
            }
        }
    }

    private static void addPidStore(List<Path> stores, List<String> kinds, List<String> described, Path root,
            boolean wantTmpfs) throws IOException {
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            described.add(root + " unusable");
            return;
        }
        String type = Files.getFileStore(root).type();
        described.add(root + "=" + type);
        if (wantTmpfs != "tmpfs".equals(type)) {
            return;
        }
        stores.add(Files.createTempDirectory(root, "sjws-pid-"));
        kinds.add(wantTmpfs ? "tmpfs" : "disk");
    }

    /** @return the empty directory's {@code st_size} on this store */
    private long exercisePidStore(Path store) throws Exception {
        var empty = Files.createDirectory(store.resolve("empty"));
        long emptySize = Files.size(empty);
        var emptySnap = PidFile.read(empty);
        assertTrue(emptySnap.present() && emptySnap.stale(), "size=" + emptySize + " snap=" + emptySnap);
        assertEquals(-1, emptySnap.pid());
        var refused = assertThrows(IOException.class, () -> PidFile.claim(empty));
        assertTrue(refused.getMessage().contains("not a regular file"), refused.getMessage());
        assertTrue(Files.isDirectory(empty), "claim must not replace a directory");
        reset();
        assertEquals(Daemon.STATUS_STALE, Daemon.status(empty, out, err));
        assertTrue(out().startsWith("stopped stale pid="), out());
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(empty, Duration.ofSeconds(1), out, err));
        assertFalse(Files.exists(empty), "empty directory removed (size " + emptySize + ")");

        var full = Files.createDirectory(store.resolve("full"));
        Files.writeString(full.resolve("kept"), "x");
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(full, Duration.ofSeconds(1), out, err));
        assertTrue(Files.isDirectory(full), "a non-empty directory is not removed");
        assertEquals("x", Files.readString(full.resolve("kept")));
        assertTrue(err().contains("could not remove pid file"), err());

        var target = Files.writeString(store.resolve("target.pid"), "1\n1\n");
        var link = store.resolve("link.pid");
        Files.createSymbolicLink(link, target);
        var linkEx = assertThrows(IOException.class, () -> PidFile.claim(link));
        assertTrue(linkEx.getMessage().contains("symlink"), linkEx.getMessage());
        assertFalse(linkEx.getMessage().contains("not a regular file"), linkEx.getMessage());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("1\n1\n", Files.readString(target));

        var nested = Files.createDirectory(store.resolve("nested"));
        var linkDir = store.resolve("link-dir.pid");
        Files.createSymbolicLink(linkDir, nested.getFileName());
        var linkAttrs = Files.readAttributes(linkDir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertTrue(linkAttrs.isSymbolicLink(), "NOFOLLOW sees the symlink, not the directory");
        assertFalse(linkAttrs.isRegularFile());
        var linkDirSnap = PidFile.read(linkDir);
        assertTrue(linkDirSnap.stale(), "symlink to a directory of size " + Files.size(nested));
        var linkDirEx = assertThrows(IOException.class, () -> PidFile.claim(linkDir));
        assertTrue(linkDirEx.getMessage().contains("symlink"), linkDirEx.getMessage());
        assertFalse(linkDirEx.getMessage().contains("not a regular file"), linkDirEx.getMessage());
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(linkDir, Duration.ofSeconds(1), out, err));
        assertFalse(Files.exists(linkDir), "symlink removed");
        assertTrue(Files.isDirectory(nested), "symlink target kept");

        var huge = store.resolve("huge.pid");
        Files.writeString(huge, "x".repeat(600));
        assertTrue(Files.size(huge) > 512);
        assertTrue(PidFile.read(huge).stale());
        var small = Files.writeString(store.resolve("small.pid"), "9\n8\n");
        var smallSnap = PidFile.read(small);
        assertEquals(9, smallSnap.pid());
        assertEquals(8, smallSnap.startedMillis());

        assertLongSymlinkReadsTheRegularTarget(store);

        var fifo = store.resolve("fifo.pid");
        var mkfifo = new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).redirectErrorStream(true).start();
        var mkfifoOut = new String(mkfifo.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, mkfifo.waitFor(), mkfifoOut);
        assertTrue(Files.size(fifo) <= 512, "fifo size " + Files.size(fifo));
        assertTrue(PidFile.read(fifo).stale(), "a fifo is stale without being read");
        var fifoEx = assertThrows(IOException.class, () -> PidFile.claim(fifo));
        assertTrue(fifoEx.getMessage().contains("not a regular file"), fifoEx.getMessage());
        assertTrue(Files.exists(fifo), "claim must not replace a fifo");
        reset();
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(fifo, Duration.ofSeconds(1), out, err));
        assertFalse(Files.exists(fifo), "fifo removed without opening it");

        var hidden = Files.writeString(store.resolve("hidden.pid"), "4\n5\n");
        assertTrue(hidden.toFile().setReadable(false));
        try {
            boolean blocked;
            try {
                Files.readString(hidden);
                blocked = false;
            } catch (IOException e) {
                blocked = true;
            }
            if (blocked) {
                assertEquals(PidFile.Snapshot.ABSENT, PidFile.read(hidden));
            }
        } finally {
            assertTrue(hidden.toFile().setReadable(true));
        }

        var broken = store.resolve("broken.pid");
        Files.createSymbolicLink(broken, store.resolve("missing-target"));
        assertEquals(PidFile.Snapshot.ABSENT, PidFile.read(broken));
        return emptySize;
    }

    private static void assertLongSymlinkReadsTheRegularTarget(Path store) throws IOException {
        var cursor = Files.createDirectory(store.resolve("long"));
        var rel = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            var name = "n" + i + "x".repeat(80);
            cursor = Files.createDirectory(cursor.resolve(name));
            if (rel.length() > 0) {
                rel.append('/');
            }
            rel.append(name);
        }
        Files.writeString(cursor.resolve("pid"), "12\n34\n");
        rel.append("/pid");
        assertTrue(rel.length() > 512, "relative target length " + rel.length());
        var link = store.resolve("long.pid");
        Files.createSymbolicLink(link, Path.of("long").resolve(rel.toString()));
        long linkSize = Files.readAttributes(link, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        assertTrue(linkSize > 512, "symlink st_size " + linkSize);
        var snap = PidFile.read(link);
        assertEquals(12, snap.pid(), "size cap must apply to the regular target, not the link");
        assertEquals(34, snap.startedMillis());
    }

    private void refuseDeviceNode() throws IOException {
        var dev = Path.of("/dev/null");
        if (!Files.exists(dev) || Files.isRegularFile(dev, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        long size = Files.size(dev);
        assertTrue(size <= 512, "device size " + size);
        var snap = PidFile.read(dev);
        assertTrue(snap.present() && snap.stale(), "device size " + size + " snap=" + snap);
        var ex = assertThrows(IOException.class, () -> PidFile.claim(dev));
        assertTrue(ex.getMessage().contains("not a regular file"), ex.getMessage());
        assertTrue(Files.exists(dev));
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void unwritablePidFileFailsBeforeListening() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var blocker = Files.writeString(tmp.resolve("not-a-directory"), "x");
        int code = Sjws_nettask.run(
                new String[] {"--pid-file=" + blocker.resolve("sjws.pid"), String.valueOf(freePort()), root.toString()},
                Map.of(), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code, err());
        assertTrue(err().contains("Cannot write pid file"), err());
    }

    // --- stop / status edge cases -----------------------------------------------------

    @Test
    void stopRemovesAMalformedFileWithoutSignalling() throws Exception {
        var pid = tmp.resolve("bad.pid");
        Files.writeString(pid, "not-a-pid\n");
        assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(pid, Duration.ofSeconds(1), out, err));
        assertTrue(out().contains("stopped stale pid=?"), out());
        assertFalse(Files.exists(pid));
    }

    @Test
    void tailPrintsTheLastLinesAndReportsAnUnreadableLog() throws Exception {
        var log = tmp.resolve("log");
        Files.writeString(log, "one\ntwo\n");
        Daemon.tail(log, err);
        assertTrue(err().contains("one") && err().contains("two"), err());
        reset();
        Daemon.tail(Files.createDirectory(tmp.resolve("logdir")), err);
        assertTrue(err().contains("Could not read the log file"), err());
        reset();
        Daemon.tail(tmp.resolve("missing-log"), err);
        assertTrue(err().contains("Could not read the log file"), err());
    }

    // --- detach -----------------------------------------------------------------------

    @Test
    void daemonStartsServesAndStopDrains() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(root.resolve("hello.txt"), "hello");
        var pid = tmp.resolve("daemon.pid");
        var log = tmp.resolve("daemon.log");
        int code = Sjws_nettask.run(new String[] {"--daemon", "--shutdown-timeout=2", "--pid-file=" + pid,
                "--log-file=" + log, String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3, s -> {
                });
        assertEquals(Sjws_nettask.EXIT_OK, code, err() + "\n" + out());
        try {
            assertTrue(out().startsWith("Started sjws_nettask (pid "), out());
            long child = waitUntilRunning(pid);
            int port = waitForPort(log, -1);
            waitFor(() -> helloServed(port), 5_000, "GET /hello.txt");
            assertEquals(0, Daemon.status(pid, out, err));
            reset();
            assertEquals(Sjws_nettask.EXIT_OK, Sjws_nettask.run(new String[] {"--stop", "--pid-file=" + pid,
                    "--shutdown-timeout=2"}, Map.of(), out, err, NO_H3, s -> {
                    }));
            assertTrue(out().contains("stopped pid=" + child), out());
            waitFor(() -> !alive(child), 5_000, "process exit");
            waitFor(() -> !Files.exists(pid), 5_000, "pid file removed");
            waitFor(() -> logContains(log, "Server Terminated properly !"), 5_000, "termination log");
            waitFor(() -> !portAccepts(port), 5_000, "port closed");
        } finally {
            Daemon.stop(pid, Duration.ofSeconds(2), out, err);
        }
    }

    @Test
    void daemonRefusesASecondStartAndRestartReplacesTheProcess() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(root.resolve("hello.txt"), "hello");
        var pid = tmp.resolve("daemon.pid");
        var log = tmp.resolve("daemon.log");
        assertEquals(0, startDaemon(pid, log, root));
        long first = waitUntilRunning(pid);
        int firstPort = waitForPort(log, -1);
        try {
            reset();
            int again = startDaemon(pid, log, root);
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, again, err());
            assertTrue(err().contains("Already running with pid " + first), err());
            assertTrue(ProcessHandle.of(first).orElseThrow().isAlive());

            reset();
            int restarted = Sjws_nettask.run(new String[] {"--restart", "--shutdown-timeout=2", "--pid-file=" + pid,
                    "--log-file=" + log, String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3, s -> {
                    });
            assertEquals(Sjws_nettask.EXIT_OK, restarted, err() + "\n" + out());
            long second = waitUntilRunning(pid);
            assertNotEquals(first, second);
            assertFalse(ProcessHandle.of(first).map(ProcessHandle::isAlive).orElse(false));
            int port = waitForPort(log, firstPort);
            assertEquals(200, HttpTestSupport.get(port, "/hello.txt", 4000).statusCode());
        } finally {
            Daemon.stop(pid, Duration.ofSeconds(2), out, err);
        }
    }

    @Test
    void daemonReportsAChildThatExits() throws Exception {
        var pid = tmp.resolve("fail.pid");
        var log = tmp.resolve("fail.log");
        int code = Sjws_nettask.run(new String[] {"--daemon", "--pid-file=" + pid, "--log-file=" + log,
                String.valueOf(freePort()), tmp.resolve("missing-root").toString()}, Map.of(), out, err, NO_H3, s -> {
                });
        assertEquals(Sjws_nettask.EXIT_BAD_ROOT, code, err());
        assertTrue(err().contains("exited with status 2"), err());
        assertTrue(Files.readString(log).contains("does not exist"), Files.readString(log));
        assertFalse(Files.exists(pid));
    }

    @Test
    void daemonizeFailsFastWhenAlreadyRunningOrTheCommandCannotStart() throws Exception {
        var pid = tmp.resolve("held.pid");
        var log = tmp.resolve("held.log");
        var held = PidFile.claim(pid);
        assertTrue(held.acquired());
        try {
            var opt = Daemon.parse(new String[] {"--daemon", "--pid-file=" + pid, "--log-file=" + log}, Map.of());
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, Daemon.daemonize(opt, Map.of(), out, err));
            assertTrue(err().contains("Already running"), err());
        } finally {
            held.file().release();
        }

        reset();
        var opt = Daemon.parse(new String[] {"--daemon", "--pid-file=" + tmp.resolve("n.pid"),
                "--log-file=" + tmp.resolve("n.log")}, Map.of());
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN,
                Daemon.daemonize(opt, Map.of(), out, err, List.of(tmp.resolve("no-such-binary").toString()),
                        Duration.ofSeconds(2)));
        assertTrue(err().contains("Cannot start the background server"), err());

        reset();
        var immediate = Daemon.parse(new String[] {"--daemon", "--pid-file=" + tmp.resolve("t.pid"),
                "--log-file=" + tmp.resolve("t.log")}, Map.of());
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, Daemon.daemonize(immediate, Map.of(), out, err,
                List.of("/usr/bin/true"), Duration.ofSeconds(2)));
        assertTrue(err().contains("exited with status 0"), err());

        reset();
        var slowPid = tmp.resolve("slow.pid");
        var slowChild = tmp.resolve("slow-child.pid");
        var slow = Daemon.parse(new String[] {"--daemon", "--pid-file=" + slowPid, "--log-file=" + tmp.resolve("slow.log")},
                Map.of());
        var sleeper = sleeperScript(tmp.resolve("sleeper.sh"), false);
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, Daemon.daemonize(slow, Map.of(), out, err,
                List.of(sleeper.toString(), slowChild.toString()), Duration.ofSeconds(1)));
        assertTrue(err().contains("did not become ready"), err());
        assertFalse(alive(readPid(slowChild)), "the unready child is killed");

        var blocker = Files.writeString(tmp.resolve("blocker"), "x");
        reset();
        var badLog = Daemon.parse(new String[] {"--daemon", "--pid-file=" + tmp.resolve("b.pid"),
                "--log-file=" + blocker.resolve("sjws.log")}, Map.of());
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, Daemon.daemonize(badLog, Map.of(), out, err));
        assertTrue(err().contains("Cannot write log file"), err());
    }

    @Test
    void stopLeavesALockedOrReplacedPidFileAndAnInterruptAbandonsTheChild() throws Exception {
        var pid = tmp.resolve("locked.pid");
        Files.writeString(pid, "nope\n");
        var snap = PidFile.read(pid);
        var python = Path.of("/usr/bin/python3");
        if (!Files.isExecutable(python)) {
            org.junit.jupiter.api.Assumptions.abort("python3 is required to hold a POSIX lock");
        }
        var holder = new ProcessBuilder(python.toString(), "-c",
                "import fcntl, time, sys\n"
                        + "f = open(sys.argv[1], 'a+')\n"
                        + "fcntl.lockf(f, fcntl.LOCK_EX)\n"
                        + "print('locked', flush=True)\n"
                        + "time.sleep(30)\n",
                pid.toString()).redirectErrorStream(true).start();
        try {
            var ready = holder.inputReader().readLine();
            assertEquals("locked", ready);
            PidFile.removeIfSame(snap, pid);
            assertEquals("nope\n", Files.readString(pid), "a lock held by another process keeps the file");
        } finally {
            holder.destroyForcibly();
            holder.waitFor(5, TimeUnit.SECONDS);
        }

        Files.writeString(pid, PidFile.text(ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()));
        PidFile.removeIfSame(snap, pid);
        assertTrue(Files.exists(pid), "content that changed after the snapshot is kept");
        Files.delete(pid);
        PidFile.removeIfSame(snap, pid);

        var childPid = tmp.resolve("child.pid");
        var script = sleeperScript(tmp.resolve("detach-sleeper.sh"), false);
        var opt = Daemon.parse(new String[] {"--daemon", "--pid-file=" + tmp.resolve("d.pid"),
                "--log-file=" + tmp.resolve("d.log")}, Map.of());
        var code = new AtomicInteger(-1);
        var worker = new Thread(() -> code.set(Daemon.daemonize(opt, Map.of(), out, err,
                List.of(script.toString(), childPid.toString()), Duration.ofSeconds(30))), "daemonize");
        worker.start();
        waitFor(() -> readablePid(childPid), 5_000, "child pid");
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "daemonize did not return after interrupt");
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code.get(), err());
        assertTrue(err().contains("Interrupted while starting"), err());
        assertFalse(alive(readPid(childPid)), "an interrupted start kills the child");

        var stubbornPid = tmp.resolve("stubborn.pid");
        var stubborn = sleeperScript(tmp.resolve("stubborn.sh"), true);
        var process = new ProcessBuilder(stubborn.toString(), stubbornPid.toString()).start();
        try {
            waitFor(() -> readablePid(stubbornPid), 5_000, "stubborn pid");
            long recorded = readPid(stubbornPid);
            assertEquals(process.pid(), recorded);
            Files.writeString(tmp.resolve("stubborn.pidfile"), PidFile.text(recorded, startMillis(process)));
            reset();
            var stopCode = new AtomicInteger(-1);
            var stopper = new Thread(() -> stopCode.set(Daemon.stop(tmp.resolve("stubborn.pidfile"), Duration.ZERO, out, err)),
                    "stop");
            stopper.start();
            Thread.sleep(100);
            stopper.interrupt();
            stopper.join(10_000);
            assertFalse(stopper.isAlive(), "--stop did not return after interrupt");
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, stopCode.get(), err());
            assertTrue(err().contains("Interrupted while stopping"), err());
            assertTrue(process.isAlive(), "interrupting --stop leaves the process to finish draining");
            assertTrue(Files.exists(tmp.resolve("stubborn.pidfile")));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    // --- graceful shutdown ------------------------------------------------------------

    @Test
    void reaperDropsIdleConnectionsAndKeepsResponsesInProgress() throws Exception {
        try (var reaper = new ConnectionReaper(Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));
                var pair = new Pair()) {
            var idle = reaper.register(pair.server);
            idle.idle();
            reaper.beginDrain();
            assertEquals(0, reaper.tracked());

            var writing = reaper.register(pair.client);
            writing.writing();
            reaper.beginDrain();
            assertTrue(reaper.any(ConnectionReaper.State.WRITING));
            assertEquals(1, reaper.tracked());

            var head = reaper.register(pair.server);
            head.head();
            reaper.beginDrain();
            assertFalse(reaper.any(ConnectionReaper.State.HEAD));
            assertTrue(reaper.any(ConnectionReaper.State.WRITING));

            writing.processing();
            reaper.sweep(System.nanoTime());
            assertEquals(1, reaper.tracked(), "PROCESSING is not cut during the drain");
            writing.writing();
            reaper.sweep(System.nanoTime());
            assertTrue(reaper.any(ConnectionReaper.State.WRITING), "a response in progress survives the drain sweep");
            writing.idle();
            reaper.sweep(0); // deadline is in the future; draining still ends an idle connection
            assertEquals(0, reaper.tracked());

            var stuck = reaper.register(pair.client);
            stuck.processing();
            reaper.forceAll();
            assertEquals(0, reaper.tracked());
        }
    }

    @Test
    void idleConnectionDoesNotConsumeTheShutdownTimeout() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3);
        server.shutdownTimeout(Duration.ofSeconds(10));
        try (var client = new Socket(InetAddress.getLoopbackAddress(), server.tcpPort())) {
            waitFor(() -> server.clientManager().reaper() != null
                    && server.clientManager().reaper().any(ConnectionReaper.State.IDLE), 2000, "idle");
            long start = System.nanoTime();
            server.close();
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertTrue(elapsed < 2_000, "idle close took " + elapsed + " ms");
            assertEquals(-1, client.getInputStream().read());
        }
        assertEquals(-1, server.tcpPort());
    }

    @Test
    void inFlightResponseCompletesAndNewConnectionsAreRefused() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var body = bodyThatStalls((byte) 'x');
        Files.write(root.resolve("big.bin"), body);
        var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3);
        server.shutdownTimeout(Duration.ofSeconds(5));
        int port = server.tcpPort();
        var client = new Socket();
        client.setReceiveBufferSize(4 * 1024);
        client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        client.setSoTimeout(8_000);
        try {
            HttpTestSupport.write(client.getOutputStream(),
                    "GET /big.bin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
            waitFor(() -> {
                var reaper = server.clientManager().reaper();
                return reaper != null && reaper.any(ConnectionReaper.State.WRITING);
            }, 3000, "writing");
            var closed = new AtomicInteger(-1);
            var closer = new Thread(() -> {
                server.close();
                closed.set(0);
            });
            closer.start();
            Thread.sleep(150);
            assertEquals(-1, closed.get(), "close must still be draining the response");
            assertThrows(IOException.class, () -> {
                try (var extra = new Socket()) {
                    extra.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
                }
            });
            var response = HttpTestSupport.readResponse(client.getInputStream());
            assertEquals(200, response.statusCode());
            assertArrayEquals(body, response.body());
            closer.join(5_000);
            assertFalse(closer.isAlive());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void shutdownTimeoutForceClosesAStalledResponse() throws Throwable {
        var root = Files.createDirectory(tmp.resolve("html"));
        var body = bodyThatStalls((byte) 'y');
        Files.write(root.resolve("big.bin"), body);
        var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3);
        server.shutdownTimeout(Duration.ofMillis(500));
        int port = server.tcpPort();
        try (var client = new Socket()) {
            client.setReceiveBufferSize(4 * 1024);
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            client.setSoTimeout(3_000);
            HttpTestSupport.write(client.getOutputStream(), "GET /big.bin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
            waitFor(() -> {
                var reaper = server.clientManager().reaper();
                return reaper != null && reaper.any(ConnectionReaper.State.WRITING);
            }, 3000, "writing");
            var err = SjwsServerTest.stderrOf(() -> {
                long start = System.nanoTime();
                server.close();
                long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
                assertTrue(elapsed >= 400, "drained for only " + elapsed + " ms");
                assertTrue(elapsed < 4_000, "close took " + elapsed + " ms");
            });
            assertTrue(err.contains("still open after"), err);
        }
        assertThrows(IOException.class, () -> HttpTestSupport.get(port, "/big.bin", 1000));
        assertEquals(-1, server.tcpPort());
    }

    @Test
    void shutdownTimeoutRejectsANegativeValue() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3);
        try {
            assertThrows(IllegalArgumentException.class, () -> server.shutdownTimeout(Duration.ofMillis(-1)));
            assertThrows(IllegalArgumentException.class,
                    () -> server.shutdownTimeout(Duration.ofMillis((long) Integer.MAX_VALUE + 1)));
            server.shutdownTimeout(Duration.ZERO);
        } finally {
            server.close();
        }
    }

    @Test
    void launcherAndSystemdExampleExposeTheControls() throws Exception {
        var script = Files.readString(Path.of("contrib/sjws"));
        assertTrue(script.startsWith("#!/bin/sh"), script);
        for (var word : List.of("start)", "stop)", "status)", "restart)", "--daemon", "--stop", "--status", "--restart",
                "SJWS_PID_FILE", "SJWS_LOG_FILE")) {
            assertTrue(script.contains(word), word);
        }
        assertTrue(Files.isExecutable(Path.of("contrib/sjws")), "contrib/sjws should be executable");
        var unit = Files.readString(Path.of("contrib/sjws_nettask.service"));
        assertTrue(unit.contains("Type=forking"), unit);
        assertTrue(unit.contains("PIDFile=/run/sjws_nettask.pid"), unit);
        assertTrue(unit.contains("--daemon"), unit);
        assertTrue(unit.contains("ExecStop="), unit);
        assertTrue(unit.contains("--shutdown-timeout=10"), unit);

        var dummy = Files.createFile(tmp.resolve("dummy.jar"));
        var pb = new ProcessBuilder("sh", "contrib/sjws");
        pb.environment().put("SJWS_JAR", dummy.toString());
        pb.redirectErrorStream(true);
        var process = pb.start();
        var text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(64, process.exitValue(), text);
        assertTrue(text.contains("Usage:"), text);
    }

    @Test
    void pidLockLogAndStopEdges() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        Files.writeString(root.resolve("hello.txt"), "hello");

        var held = PidFile.claim(tmp.resolve("held.pid"));
        assertTrue(held.acquired());
        try (var ignored = held.file()) {
            assertEquals(tmp.resolve("held.pid"), held.file().path());
            reset();
            int code = Sjws_nettask.run(new String[] {"--pid-file=" + held.file().path(), String.valueOf(freePort()),
                    root.toString()}, Map.of(), out, err, NO_H3, SjwsServer::close);
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code, err());
            assertTrue(err().contains("Already running with pid " + ProcessHandle.current().pid()), err());
        }

        var blocker = Files.writeString(tmp.resolve("not-a-log-dir"), "x");
        reset();
        int logCode = Sjws_nettask.run(new String[] {"--log-file=" + blocker.resolve("sjws.log"),
                String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3, SjwsServer::close);
        assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, logCode, err());
        assertTrue(err().contains("Cannot write log file"), err());

        var ro = Files.createDirectory(tmp.resolve("ro-pid"));
        var roPid = ro.resolve("sjws.pid");
        reset();
        try {
            int code = Sjws_nettask.run(new String[] {"--pid-file=" + roPid, "--shutdown-timeout=1",
                    String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3, server -> {
                        assertTrue(ro.toFile().setWritable(false));
                        server.close();
                    });
            assertEquals(Sjws_nettask.EXIT_OK, code, err());
            assertTrue(err().contains("could not remove pid file"), err());
        } finally {
            assertTrue(ro.toFile().setWritable(true));
        }

        var locked = tmp.resolve("py.pid");
        Files.writeString(locked, "nope\n");
        var python = Path.of("/usr/bin/python3");
        if (Files.isExecutable(python)) {
            var holder = new ProcessBuilder(python.toString(), "-c",
                    "import fcntl, time, sys\n"
                            + "f = open(sys.argv[1], 'a+')\n"
                            + "fcntl.lockf(f, fcntl.LOCK_EX)\n"
                            + "print('locked', flush=True)\n"
                            + "time.sleep(30)\n",
                    locked.toString()).redirectErrorStream(true).start();
            try {
                assertEquals("locked", holder.inputReader().readLine());
                var refused = PidFile.claim(locked);
                assertFalse(refused.acquired());
                assertNull(refused.file());
                reset();
                int code = Sjws_nettask.run(new String[] {"--pid-file=" + locked, String.valueOf(freePort()),
                        root.toString()}, Map.of(), out, err, NO_H3, SjwsServer::close);
                assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code, err());
                assertTrue(err().contains("Already running with pid ?"), err());
            } finally {
                holder.destroyForcibly();
                holder.waitFor(5, TimeUnit.SECONDS);
            }
        }

        var owned = tmp.resolve("owned.pid");
        var claim = PidFile.claim(owned);
        assertTrue(claim.acquired());
        PidFile.removeIfSame(PidFile.read(owned), owned);
        assertTrue(Files.exists(owned), "this JVM already holds the lock");
        claim.file().release();

        var denied = tmp.resolve("denied.pid");
        Files.writeString(denied, "nope\n");
        var deniedSnap = PidFile.read(denied);
        assertTrue(denied.toFile().setWritable(false));
        PidFile.removeIfSame(deniedSnap, denied);
        assertFalse(Files.exists(denied));

        var kept = tmp.resolve("kept.pid");
        Files.writeString(kept, "1\n1\n");
        assertTrue(kept.toFile().setWritable(false));
        PidFile.removeIfSame(PidFile.parse("nope\n"), kept);
        assertTrue(kept.toFile().setWritable(true));
        assertEquals("1\n1\n", Files.readString(kept));

        var sleep = new ProcessBuilder("sleep", "60").start();
        var sleepPid = tmp.resolve("sleep-edge.pid");
        try {
            long start = startMillis(sleep);
            Files.writeString(sleepPid, PidFile.text(sleep.pid(), start));
            sjws_nettask.service.ServiceStatus.writeStandaloneDrain(sleepPid, sleep.pid(), start, 0);
            reset();
            assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(sleepPid, Duration.ofSeconds(-6), out, err, pid -> {
                assertEquals(sleep.pid(), pid);
                return Optional.of(new FakeHandle(start, true));
            }));
            assertTrue(sleep.isAlive(), "the stand-in handle is signalled, not the real process");
            assertFalse(Files.exists(sleepPid));

            Files.writeString(sleepPid, PidFile.text(sleep.pid(), start));
            reset();
            assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(sleepPid, Duration.ZERO, out, err, pid -> Optional.empty()));
            assertTrue(out().contains("stopped stale"), out());
            assertTrue(sleep.isAlive());

            Files.writeString(sleepPid, PidFile.text(sleep.pid(), start));
            reset();
            assertEquals(Sjws_nettask.EXIT_OK,
                    Daemon.stop(sleepPid, Duration.ZERO, out, err, pid -> Optional.of(new FakeHandle(start + 5, true))));
            assertTrue(out().contains("stopped stale pid=" + sleep.pid()), out());
            assertTrue(sleep.isAlive());
            assertFalse(Files.exists(sleepPid));

            Files.writeString(sleepPid, PidFile.text(sleep.pid(), start));
            reset();
            var stuck = new AtomicInteger(-1);
            var worker = new Thread(() -> stuck.set(Daemon.stop(sleepPid, Duration.ofSeconds(-6), out, err,
                    pid -> Optional.of(new FakeHandle(start, false)))), "stuck-stop");
            worker.start();
            Thread.sleep(100);
            worker.interrupt();
            worker.join(5_000);
            assertFalse(worker.isAlive());
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, stuck.get(), err());
            assertTrue(err().contains("did not exit") || err().contains("Interrupted"), err());
        } finally {
            sleep.destroyForcibly();
            sleep.waitFor(5, TimeUnit.SECONDS);
        }

        if (Files.isExecutable(python)) {
            var childPid = tmp.resolve("ignores-term.pid");
            var script = tmp.resolve("ignore_term.py");
            Files.writeString(script, "import os, signal, sys, time\n"
                    + "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                    + "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
                    + "time.sleep(60)\n");
            var opt = Daemon.parse(new String[] {"--daemon", "--pid-file=" + tmp.resolve("ig.pid"),
                    "--log-file=" + tmp.resolve("ig.log")}, Map.of());
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, Daemon.daemonize(opt, Map.of(), out, err,
                    List.of(python.toString(), script.toString(), childPid.toString()), Duration.ofMillis(400)));
            long child = readPid(childPid);
            try {
                waitFor(() -> !alive(child), 3_000, "SIGKILL after an ignored SIGTERM");
            } finally {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
        }

        var savedOut = System.out;
        var help = new java.io.ByteArrayOutputStream();
        System.setOut(new PrintStream(help, true, StandardCharsets.UTF_8));
        try {
            Sjws_nettask.main(new String[] {"--help"});
        } finally {
            System.setOut(savedOut);
        }
        assertTrue(help.toString(StandardCharsets.UTF_8).contains("--daemon"));
    }

    @Test
    void recycledPidFileAndRestartInterrupt() throws Exception {
        assertThrows(IOException.class, () -> PidFile.claim(Path.of("/")));
        assertFalse(PidFile.identifies(PidFile.Snapshot.ABSENT, ProcessHandle.current()));

        var target = tmp.resolve("link-target.pid");
        Files.writeString(target, "nope\n");
        var link = tmp.resolve("link.pid");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable");
        }
        var linkSnap = PidFile.read(link);
        Files.writeString(target, "1\n1\n");
        PidFile.removeIfSame(linkSnap, link);
        assertTrue(Files.isSymbolicLink(link), "a changed symlink target is not removed");

        var sleep = new ProcessBuilder("sleep", "60").start();
        var pid = tmp.resolve("foreign.pid");
        try {
            long start = startMillis(sleep);
            assertFalse(PidFile.identifies(PidFile.parse(PidFile.text(sleep.pid(), start)), ProcessHandle.current()));
            var claim = PidFile.claim(pid);
            assertTrue(claim.acquired());
            try (var ch = java.nio.channels.FileChannel.open(pid, java.nio.file.StandardOpenOption.WRITE)) {
                ch.truncate(0);
                ch.write(java.nio.ByteBuffer.wrap(PidFile.text(sleep.pid(), start).getBytes(StandardCharsets.UTF_8)));
            }
            claim.file().release();
            assertEquals(sleep.pid(), PidFile.read(pid).pid(), "release must not delete a file now naming someone else");

            Files.writeString(pid, sleep.pid() + "\n");
            sjws_nettask.service.ServiceStatus.writeStandaloneDrain(pid, sleep.pid(), -1, 0);
            reset();
            assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(pid, Duration.ofSeconds(-6), out, err,
                    p -> Optional.of(new FakeHandle(-1, true))));
            assertTrue(sleep.isAlive());

            Files.writeString(pid, PidFile.text(sleep.pid(), start));
            sjws_nettask.service.ServiceStatus.writeStandaloneDrain(pid, sleep.pid(), start, 0);
            reset();
            assertEquals(Sjws_nettask.EXIT_OK, Daemon.stop(pid, Duration.ofSeconds(-6), out, err,
                    p -> Optional.of(new FakeHandle(-1, true))));
            assertTrue(sleep.isAlive());
        } finally {
            sleep.destroyForcibly();
            sleep.waitFor(5, TimeUnit.SECONDS);
        }

        var python = Path.of("/usr/bin/python3");
        if (!Files.isExecutable(python)) {
            return;
        }
        var root = Files.createDirectory(tmp.resolve("html"));
        var childPid = tmp.resolve("restart-child.pid");
        var script = tmp.resolve("ignore_restart.py");
        Files.writeString(script, "import os, signal, sys, time\n"
                + "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                + "open(sys.argv[1], 'w').write(str(os.getpid()))\n"
                + "time.sleep(60)\n");
        var process = new ProcessBuilder(python.toString(), script.toString(), childPid.toString()).start();
        try {
            waitFor(() -> readablePid(childPid), 5_000, "restart child");
            long recorded = readPid(childPid);
            var pidFile = tmp.resolve("restart.pid");
            Files.writeString(pidFile, recorded + "\n");
            var code = new AtomicInteger(-1);
            var worker = new Thread(() -> code.set(Sjws_nettask.run(new String[] {"--restart", "--shutdown-timeout=0",
                    "--pid-file=" + pidFile, String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3,
                    s -> {
                    })), "restart");
            worker.start();
            Thread.sleep(150);
            worker.interrupt();
            worker.join(8_000);
            assertFalse(worker.isAlive());
            assertEquals(Sjws_nettask.EXIT_CANNOT_LISTEN, code.get(), err());
            assertTrue(process.isAlive());
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeInterruptsTheDrainAndRejectsABadManagerTimeout() throws Exception {
        var root = Files.createDirectory(tmp.resolve("html"));
        var body = bodyThatStalls((byte) 'z');
        Files.write(root.resolve("big.bin"), body);
        var server = SjwsServer.start(ServerConfig.builder(root).port(0).build(), NO_H3);
        server.shutdownTimeout(Duration.ofSeconds(3));
        assertThrows(IllegalArgumentException.class, () -> server.clientManager().shutdownTimeout(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> server.clientManager().shutdownTimeout(Duration.ofMillis((long) Integer.MAX_VALUE + 1)));
        int port = server.tcpPort();
        try (var client = new Socket()) {
            client.setReceiveBufferSize(4 * 1024);
            client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            HttpTestSupport.write(client.getOutputStream(), "GET /big.bin HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n");
            waitFor(() -> {
                var reaper = server.clientManager().reaper();
                return reaper != null && reaper.any(ConnectionReaper.State.WRITING);
            }, 3000, "writing");
            var closer = new Thread(server::close, "closer");
            closer.start();
            Thread.sleep(100);
            Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> "accept-loop".equals(t.getName()))
                    .forEach(Thread::interrupt);
            var waiter = new Thread(server::close, "second-close");
            waiter.start();
            Thread.sleep(50);
            waiter.interrupt();
            closer.interrupt();
            closer.join(8_000);
            waiter.join(8_000);
            assertFalse(closer.isAlive());
            assertFalse(waiter.isAlive());
        } finally {
            server.close();
            Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> "accept-loop".equals(t.getName()))
                    .forEach(t -> {
                        try {
                            t.join(2_000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private int startDaemon(Path pid, Path log, Path root) {
        return Sjws_nettask.run(new String[] {"--daemon", "--shutdown-timeout=2", "--pid-file=" + pid, "--log-file=" + log,
                String.valueOf(freePort()), root.toString()}, Map.of(), out, err, NO_H3, s -> {
                });
    }

    private static long waitUntilRunning(Path pid) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            var snap = PidFile.read(pid);
            if (snap.running()) {
                return snap.pid();
            }
            Thread.sleep(20);
        }
        fail("pid file did not become ready: " + PidFile.read(pid));
        return -1;
    }

    /** @param ignore a port already logged by a previous process; -1 to accept the first */
    private static int waitForPort(Path log, int ignore) throws Exception {
        var pattern = Pattern.compile("Server started: HTTP/1\\.1 on tcp/(\\d+)");
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < end) {
            if (Files.exists(log)) {
                var m = pattern.matcher(Files.readString(log));
                int port = -1;
                while (m.find()) {
                    port = Integer.parseInt(m.group(1));
                }
                if (port > 0 && port != ignore) {
                    return port;
                }
            }
            Thread.sleep(20);
        }
        fail("no listen port in " + log + ":\n" + (Files.exists(log) ? Files.readString(log) : "<missing>"));
        return -1;
    }

    private static boolean pidAlive(Path pid) {
        var snap = PidFile.read(pid);
        return snap.running();
    }

    /** @param ignoreTerm when true, the shell ignores SIGTERM and {@code sleep} is a child */
    private static Path sleeperScript(Path script, boolean ignoreTerm) throws IOException {
        var body = ignoreTerm
                ? "#!/bin/sh\ntrap '' TERM\necho $$ > \"$1\"\nsleep 60\n"
                : "#!/bin/sh\necho $$ > \"$1\"\nexec sleep 60\n";
        Files.writeString(script, body);
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("cannot mark executable: " + script);
        }
        return script;
    }

    private static long readPid(Path file) throws IOException {
        return Long.parseLong(Files.readString(file).trim());
    }

    /** The writer creates the file before the pid bytes are visible. */
    private static boolean readablePid(Path file) {
        try {
            var text = Files.readString(file).trim();
            return !text.isEmpty() && Long.parseLong(text) > 0;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** True when a fresh connection receives the hello.txt body. A refused connect is not ready yet. */
    private static boolean helloServed(int port) {
        try {
            var response = HttpTestSupport.get(port, "/hello.txt", 1_000);
            return response.statusCode() == 200 && "hello".equals(response.bodyText());
        } catch (IOException e) {
            return false;
        }
    }

    /** True when a TCP connect to {@code port} is accepted. Connection refused is closed. */
    private static boolean portAccepts(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean logContains(Path log, String text) {
        try {
            return Files.exists(log) && Files.readString(log).contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    private static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long startMillis(Process process) {
        return process.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
    }

    /**
     * Bytes that cannot fit in the autotuned loopback windows ({@code tcp_wmem} + {@code tcp_rmem}).
     * A 2 MiB file is fully written, and the connection leaves {@link ConnectionReaper.State#WRITING},
     * before the next poll when the kernel absorbs it. {@code timer.writing()} still runs
     * ({@link ChannelOutput}); the state just does not last. A client that does not read then
     * stays in WRITING, which is what the drain tests have to observe.
     */
    private static byte[] bodyThatStalls(byte fill) throws IOException {
        long limit = 0;
        for (var name : List.of("/proc/sys/net/ipv4/tcp_wmem", "/proc/sys/net/ipv4/tcp_rmem")) {
            var path = Path.of(name);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            long highest = 0;
            for (var part : Files.readString(path).trim().split("\\s+")) {
                highest = Math.max(highest, Long.parseLong(part));
            }
            limit += highest;
        }
        long size = Math.max(16L * 1024 * 1024, limit + 4L * 1024 * 1024);
        if (size > 32L * 1024 * 1024) {
            size = 32L * 1024 * 1024;
        }
        var body = new byte[(int) size];
        java.util.Arrays.fill(body, fill);
        return body;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, long timeoutMs, String what) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for " + what);
    }

    /** Stand-in {@link ProcessHandle} so stop's signal paths do not need a recycled pid. */
    private static final class FakeHandle implements ProcessHandle {
        private final long start;
        private final boolean dieOnKill;
        private boolean alive = true;

        FakeHandle(long start, boolean dieOnKill) {
            this.start = start;
            this.dieOnKill = dieOnKill;
        }

        @Override
        public long pid() {
            return 1;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return new Info() {
                @Override
                public Optional<String> command() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> commandLine() {
                    return Optional.empty();
                }

                @Override
                public Optional<String[]> arguments() {
                    return Optional.empty();
                }

                @Override
                public Optional<Instant> startInstant() {
                    return Optional.of(Instant.ofEpochMilli(start));
                }

                @Override
                public Optional<Duration> totalCpuDuration() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> user() {
                    return Optional.empty();
                }
            };
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return new CompletableFuture<>();
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            if (this.dieOnKill) {
                this.alive = false;
            }
            return true;
        }

        @Override
        public boolean isAlive() {
            return this.alive;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid(), other.pid());
        }
    }

    /** A connected loopback channel pair. */
    private static final class Pair implements AutoCloseable {
        final java.nio.channels.SocketChannel client;
        final java.nio.channels.SocketChannel server;

        Pair() throws IOException {
            try (var listener = ServerSocketChannel.open().bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                this.client = java.nio.channels.SocketChannel.open(listener.getLocalAddress());
                this.server = listener.accept();
            }
        }

        @Override
        public void close() throws IOException {
            this.client.close();
            this.server.close();
        }
    }
}
