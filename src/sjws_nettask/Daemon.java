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
package sjws_nettask;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

/**
 * Background-service controls: {@code --daemon}, {@code --stop}, {@code --status}, {@code --restart},
 * the pid file and the log file. Java has no {@code fork}, so {@code --daemon} starts a second JVM
 * in a new session ({@code setsid} when the binary exists) and exits once that child is up.
 * The child handles SIGHUP as reload ({@link ReloadHook}), writes the pid file and logs to a file.
 * {@code setsid} still keeps a terminal hangup from being delivered. {@code --stop} sends SIGTERM
 * ({@link ProcessHandle#destroy()}) and waits for {@link SjwsServer#drain()}, which is
 * {@link sjws_nettask.ha.Lifecycle#drain()} when high availability is attached and
 * {@link SjwsServer#close()} otherwise.
 */
final class Daemon {

    /** Set on the detached child so it serves in the foreground instead of detaching again. */
    static final String CHILD_ENV = "SJWS_DAEMON_CHILD";

    static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);
    static final String DEFAULT_PID_FILE = "sjws_nettask.pid";
    static final String DEFAULT_LOG_FILE = "sjws_nettask.log";
    static final Duration READY_TIMEOUT = Duration.ofSeconds(20);
    /** Extra wait after the drain budget before {@code --stop} sends SIGKILL. */
    static final Duration STOP_MARGIN = Duration.ofSeconds(5);
    /**
     * Authoritative {@code --stop} budget when the status file has no {@code drain-ms}.
     * {@code --shutdown-timeout} still bounds in-flight responses inside the process.
     */
    static final long DEFAULT_DRAIN_BUDGET_MS = 30_000L;

    /** {@code --status} exit: running. */
    static final int STATUS_RUNNING = 0;
    /** {@code --status} exit: pid file names a dead process. */
    static final int STATUS_STALE = 1;
    /** {@code --status} exit: no pid file. */
    static final int STATUS_STOPPED = 3;

    private static final Path SETSID = Path.of("/usr/bin/setsid");

    enum Action {
        SERVE, DAEMON, STOP, STATUS, RESTART
    }

    /**
     * Daemon flags pulled out of the command line before {@link ServerConfig#parse}.
     *
     * @param trackPid write the pid file (daemon, restart, or an explicit pid-file path)
     * @param logToFile send this process's messages to {@code logFile}
     * @param role effective role: {@code --role}, otherwise {@code WEB_SERVER_ROLE}, otherwise null
     * @param cliRole role from {@code --role}, or null when the flag was absent
     * @param envRole role from {@code WEB_SERVER_ROLE}, or null when the variable was absent
     * @param drainBudgetMs {@code --drain-budget} in milliseconds, or {@code -1} for the default
     * @param bindContent explicit content-port choice, or null to use the file and then the role
     * @param bindFromEnv true when {@code WEB_SERVER_BIND_CONTENT} supplied {@code bindContent}
     */
    record Options(String[] remaining, Action action, Path pidFile, boolean trackPid, Path logFile, boolean logToFile,
            Duration shutdownTimeout, List<String> warnings, List<String> errors, Path bootstrap, String role,
            String cliRole, String envRole, long drainBudgetMs, Boolean bindContent, boolean bindFromEnv) {

        boolean ok() {
            return this.errors.isEmpty();
        }

        /** Arguments for the detached child: server args plus the resolved pid, log and timeout. */
        List<String> childArgs() {
            var args = new ArrayList<String>();
            java.util.Collections.addAll(args, this.remaining);
            args.add("--pid-file=" + this.pidFile);
            args.add("--log-file=" + this.logFile);
            args.add("--shutdown-timeout=" + this.shutdownTimeout.toSeconds());
            if (this.role != null) {
                args.add("--role=" + this.role);
            }
            if (this.bootstrap != null) {
                args.add("--bootstrap=" + this.bootstrap.toAbsolutePath());
            }
            if (this.drainBudgetMs >= 0) {
                args.add("--drain-budget=" + (this.drainBudgetMs / 1000));
            }
            if (this.bindContent != null) {
                args.add("--bind-content=" + this.bindContent);
            }
            return args;
        }
    }

    private Daemon() {
    }

    /** Appended to {@link ServerConfig#usage()}. */
    static String usage() {
        return """

                  --daemon                  run in the background (detach)
                  --pid-file=PATH           pid file (sjws_nettask.pid)               WEB_SERVER_PID_FILE
                  --log-file=PATH           log file when daemonized, or when set     WEB_SERVER_LOG_FILE
                                            (sjws_nettask.log)
                  --shutdown-timeout=S      finish in-flight responses for S seconds  WEB_SERVER_SHUTDOWN_TIMEOUT
                                            after SIGTERM/SIGINT (10; 0 = close now)
                  --drain-budget=S          seconds --stop waits for drain (30)       WEB_SERVER_DRAIN_BUDGET
                                            before the 5s SIGKILL margin; 0 waits
                                            only that margin. Recorded as drain-ms
                  --stop                    stop the server named by the pid file
                  --status                  report whether that server is running
                  --restart                 stop it, then start it in the background
                  --role=ROLE               standalone (default), control, or data    WEB_SERVER_ROLE
                  --bootstrap=PATH          control/data bootstrap file               WEB_SERVER_BOOTSTRAP
                                            (pin and join-token path; the secret is
                                            not a flag or a variable)
                  --bind-content=BOOL       bind the content port                     WEB_SERVER_BIND_CONTENT
                                            (off for control, on for data and
                                            standalone, unless this is set)

                --stop and --status only need --pid-file. A daemon writes the pid file once the
                process is up, redirects output to the log file, and leaves the terminal. systemd
                active follows that pid file only; it is not readiness. Readiness is /readyz when
                high availability serves it, and --status. The daemon child handles SIGHUP by
                reloading (ReloadHook). setsid, when present, still starts a new session so a
                terminal hangup is not delivered. A foreground process is not given that handler.
                SIGTERM and SIGINT stop accepting new connections and let responses already in
                progress finish until --shutdown-timeout elapses. HTTP/3's own drain stays 2 seconds
                (GOAWAY). When high availability is attached, that signal runs the HA drain
                (not-ready, then the deregistration delay, then the same close). --stop waits for
                drain-ms (the status file, else --drain-budget, else 30s) plus 5 seconds, then
                SIGKILL. That budget is not added on top of --shutdown-timeout. A standalone
                --status line stays "running pid=N". Control and data add role, connection, and
                config-version. Connection state does not drain the process. --role=control or data
                requires --bootstrap and --pid-file. The join token is a mode 0600 file named by
                the bootstrap file. node.mode in the config file is the same role. Precedence is
                --role, then WEB_SERVER_ROLE, then node.mode. A difference is logged at startup.
                """;
    }

    /**
     * Pulls daemon flags out of {@code args}. Environment overrides the pid file, log file and
     * shutdown timeout (same precedence as {@link ServerConfig}). Control actions are flags only,
     * so a variable cannot turn a {@code --stop} into a start.
     */
    static Options parse(String[] args, Map<String, String> env) {
        var warnings = new ArrayList<String>();
        var errors = new ArrayList<String>();
        var remaining = new ArrayList<String>();
        boolean daemon = false;
        boolean stop = false;
        boolean status = false;
        boolean restart = false;
        String pid = null;
        String log = null;
        String cliRole = null;
        String bootstrap = null;
        long timeout = DEFAULT_SHUTDOWN_TIMEOUT.toSeconds();
        long drainBudgetMs = -1L;
        Boolean bindContent = null;
        boolean bindFromEnv = false;
        boolean pidSet = false;
        boolean logSet = false;

        for (var arg : args) {
            if (!arg.startsWith("--")) {
                remaining.add(arg);
                continue;
            }
            int eq = arg.indexOf('=');
            var name = eq < 0 ? arg : arg.substring(0, eq);
            var value = eq < 0 ? null : arg.substring(eq + 1);
            switch (name) {
                case "--daemon" -> daemon = value == null || bool(value, daemon, name, warnings);
                case "--stop" -> stop = value == null || bool(value, stop, name, warnings);
                case "--status" -> status = value == null || bool(value, status, name, warnings);
                case "--restart" -> restart = value == null || bool(value, restart, name, warnings);
                case "--pid-file" -> {
                    pid = text(value, pid, name, warnings);
                    pidSet = pid != null;
                }
                case "--log-file" -> {
                    log = text(value, log, name, warnings);
                    logSet = log != null;
                }
                case "--shutdown-timeout" -> timeout = number(value, timeout, 0, 86_400, name, warnings);
                case "--drain-budget" -> drainBudgetMs = budgetMillis(value, drainBudgetMs, name, warnings);
                case "--role" -> cliRole = roleValue(value, cliRole, name, errors);
                case "--bootstrap" -> bootstrap = pathValue(value, bootstrap, name, errors);
                case "--bind-content" -> bindContent = value == null
                        ? Boolean.TRUE
                        : bool(value, bindContent == null || bindContent, name, warnings);
                default -> remaining.add(arg);
            }
        }

        String v;
        if ((v = env.get("WEB_SERVER_PID_FILE")) != null) {
            pid = text(v, pid, "WEB_SERVER_PID_FILE", warnings);
            pidSet = pid != null;
        }
        if ((v = env.get("WEB_SERVER_LOG_FILE")) != null) {
            log = text(v, log, "WEB_SERVER_LOG_FILE", warnings);
            logSet = log != null;
        }
        if ((v = env.get("WEB_SERVER_SHUTDOWN_TIMEOUT")) != null) {
            timeout = number(v, timeout, 0, 86_400, "WEB_SERVER_SHUTDOWN_TIMEOUT", warnings);
        }
        if ((v = env.get("WEB_SERVER_DRAIN_BUDGET")) != null) {
            drainBudgetMs = budgetMillis(v, drainBudgetMs, "WEB_SERVER_DRAIN_BUDGET", warnings);
        }
        String envRole = null;
        if ((v = env.get("WEB_SERVER_ROLE")) != null) {
            envRole = roleValue(v, null, "WEB_SERVER_ROLE", errors);
        }
        if ((v = env.get("WEB_SERVER_BOOTSTRAP")) != null) {
            bootstrap = pathValue(v, bootstrap, "WEB_SERVER_BOOTSTRAP", errors);
        }
        if ((v = env.get("WEB_SERVER_BIND_CONTENT")) != null) {
            bindContent = bool(v, bindContent == null || bindContent, "WEB_SERVER_BIND_CONTENT", warnings);
            bindFromEnv = true;
        }
        String role = cliRole != null ? cliRole : envRole;
        if ("standalone".equals(role) && bootstrap != null) {
            errors.add("--role=standalone cannot be combined with --bootstrap");
        }
        if (("control".equals(role) || "data".equals(role)) && bootstrap == null) {
            errors.add("--role=" + role + " requires --bootstrap=PATH");
        }

        var chosen = new ArrayList<String>();
        if (daemon) {
            chosen.add("--daemon");
        }
        if (stop) {
            chosen.add("--stop");
        }
        if (status) {
            chosen.add("--status");
        }
        if (restart) {
            chosen.add("--restart");
        }
        Action action = Action.SERVE;
        if (chosen.size() > 1) {
            errors.add(String.join(", ", chosen) + " are mutually exclusive");
        } else if (stop) {
            action = Action.STOP;
        } else if (status) {
            action = Action.STATUS;
        } else if (restart) {
            action = Action.RESTART;
        } else if (daemon) {
            action = Action.DAEMON;
        }

        boolean child = "1".equals(env.get(CHILD_ENV));
        boolean track = pidSet || action == Action.DAEMON || action == Action.RESTART || child;
        boolean toFile = logSet || action == Action.DAEMON || action == Action.RESTART || child;
        var opt = new Options(remaining.toArray(String[]::new), action,
                Path.of(pid == null ? DEFAULT_PID_FILE : pid), track,
                Path.of(log == null ? DEFAULT_LOG_FILE : log), toFile,
                Duration.ofSeconds(timeout), List.copyOf(warnings), List.copyOf(errors),
                bootstrap == null ? null : Path.of(bootstrap), role, cliRole, envRole, drainBudgetMs,
                bindContent, bindFromEnv);
        return opt;
    }

    /** Opens {@code file} for append, creating parent directories. The caller owns the stream. */
    static PrintStream openLog(Path file) throws IOException {
        var parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return new PrintStream(new FileOutputStream(file.toFile(), true), true, StandardCharsets.UTF_8);
    }

    /** Points {@link System#out} and {@link System#err} at {@code log}. The child JVM does this. */
    static void redirectStandardStreams(PrintStream log) {
        System.setOut(log);
        System.setErr(log);
    }

    /**
     * Ignores a signal via {@code sun.misc.Signal} (jdk.unsupported). Returns false when the JVM
     * or the OS has no such signal. The daemon child does not ignore HUP: it installs
     * {@link ReloadHook}. {@code setsid} is what keeps a terminal hangup from being delivered.
     * Tests use this to show an already-ignored SIGHUP stays ignored.
     */
    static boolean ignoreSignal(String name) {
        try {
            var signalClass = Class.forName("sun.misc.Signal");
            var handlerClass = Class.forName("sun.misc.SignalHandler");
            var signal = signalClass.getConstructor(String.class).newInstance(name);
            var ignore = handlerClass.getField("SIG_IGN").get(null);
            signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, ignore);
            return true;
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException | IllegalAccessException
                | InstantiationException | InvocationTargetException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Rebuilds the command that launched this JVM, with {@code appArgs} in place of the previous
     * program arguments, and detaches it with {@code setsid} when that binary exists. JVM flags
     * before {@code -jar} / the main class (for example {@code -Xmx}) are kept. When this process
     * was not itself {@code sjws_nettask} (the test runner), falls back to {@code java -cp}.
     */
    static List<String> childCommand(List<String> appArgs) {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return childCommand(appArgs, ProcessHandle.current().info().arguments(), java,
                System.getProperty("java.class.path"));
    }

    /**
     * @param current arguments of this process, without the executable; empty when the OS hides them
     */
    static List<String> childCommand(List<String> appArgs, Optional<String[]> current, String javaBin,
            String classPath) {
        var cmd = new ArrayList<String>();
        // Surefire itself is launched with -jar surefirebooter.jar. Only reuse the current
        // command line when it is actually this server, so a test run does not re-exec itself.
        if (current.isPresent() && isOurLaunch(current.get()) && splice(cmd, javaBin, current.get(), appArgs)) {
            return detach(cmd, SETSID);
        }
        cmd.clear();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add("sjws_nettask.Sjws_nettask");
        cmd.addAll(appArgs);
        return detach(cmd, SETSID);
    }

    /** @return true when {@code argv} is {@code java -jar sjws_nettask.jar} or the main class */
    static boolean isOurLaunch(String[] argv) {
        for (int i = 0; i < argv.length; i++) {
            if ("sjws_nettask.Sjws_nettask".equals(argv[i])) {
                return true;
            }
            if ("-jar".equals(argv[i]) && i + 1 < argv.length) {
                var path = argv[i + 1].replace('\\', '/');
                if (path.contains("sjws_nettask") && !path.contains("surefire")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @return true when {@code argv} contains {@code -jar} or the main class and {@code out} was filled
     */
    static boolean splice(List<String> out, String javaBin, String[] argv, List<String> appArgs) {
        int main = -1;
        for (int i = 0; i < argv.length; i++) {
            if ("-jar".equals(argv[i]) && i + 1 < argv.length) {
                main = i + 1;
                break;
            }
            if ("sjws_nettask.Sjws_nettask".equals(argv[i])) {
                main = i;
                break;
            }
        }
        if (main < 0) {
            return false;
        }
        out.add(javaBin);
        for (int i = 0; i <= main; i++) {
            out.add(argv[i]);
        }
        out.addAll(appArgs);
        return true;
    }

    /** Prepends {@code setsid} when that binary is executable, so the child becomes a new session. */
    static List<String> detach(List<String> command, Path setsid) {
        if (!Files.isExecutable(setsid)) {
            return command;
        }
        var out = new ArrayList<String>();
        out.add(setsid.toString());
        out.addAll(command);
        return out;
    }

    /** Copies server configuration variables from {@code configured} and marks the child. */
    static void applyServerEnv(Map<String, String> childEnv, Map<String, String> configured) {
        childEnv.keySet().removeIf(k -> k.startsWith("WEB_SERVER_"));
        configured.forEach((k, v) -> {
            if (k.startsWith("WEB_SERVER_") && v != null) {
                childEnv.put(k, v);
            }
        });
        childEnv.put(CHILD_ENV, "1");
    }

    static int daemonize(Options opt, Map<String, String> env, PrintStream out, PrintStream err) {
        return daemonize(opt, env, out, err, childCommand(opt.childArgs()), READY_TIMEOUT);
    }

    /**
     * Starts {@code command}, waits until the pid file names that process (the child writes it
     * after the process is up), and returns. The parent does not keep the child: stdout and stderr
     * are the log file, stdin is discarded.
     */
    static int daemonize(Options opt, Map<String, String> env, PrintStream out, PrintStream err,
            List<String> command, Duration readyTimeout) {
        var running = PidFile.read(opt.pidFile());
        if (PidFile.blocksStart(running)) {
            err.println("Already running with pid " + running.pid() + " (" + opt.pidFile() + ").");
            return Sjws_nettask.EXIT_CANNOT_LISTEN;
        }
        try {
            openLog(opt.logFile()).close();
        } catch (IOException e) {
            err.println("Cannot write log file " + opt.logFile() + ": " + e.getMessage());
            return Sjws_nettask.EXIT_CANNOT_LISTEN;
        }
        var pb = new ProcessBuilder(command);
        applyServerEnv(pb.environment(), env);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(opt.logFile().toFile()));
        // /dev/null (Unix) or NUL (Windows). One of them exists on the platforms we run.
        pb.redirectInput(ProcessBuilder.Redirect.from(discardInput(List.of("/dev/null", "NUL")).toFile()));
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            err.println("Cannot start the background server: " + e.getMessage());
            err.println("Command: " + String.join(" ", command));
            return Sjws_nettask.EXIT_CANNOT_LISTEN;
        }
        long deadline = System.nanoTime() + readyTimeout.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                var snap = PidFile.read(opt.pidFile());
                if (PidFile.identifies(snap, process.toHandle())) {
                    out.println("Started sjws_nettask (pid " + snap.pid() + "), logging to "
                            + opt.logFile().toAbsolutePath());
                    return Sjws_nettask.EXIT_OK;
                }
                if (!process.isAlive()) {
                    int code = process.exitValue();
                    err.println("Background server exited with status " + code + ".");
                    err.println("Command: " + String.join(" ", command));
                    tail(opt.logFile(), err);
                    return code == 0 ? Sjws_nettask.EXIT_CANNOT_LISTEN : code;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(process);
            err.println("Interrupted while starting the background server.");
            return Sjws_nettask.EXIT_CANNOT_LISTEN;
        }
        abandon(process);
        err.println("Background server did not become ready.");
        err.println("Command: " + String.join(" ", command));
        tail(opt.logFile(), err);
        return Sjws_nettask.EXIT_CANNOT_LISTEN;
    }

    static int stop(Path pidFile, Duration timeout, PrintStream out, PrintStream err) {
        return stop(pidFile, timeout, -1L, out, err, ProcessHandle::of);
    }

    /** {@code configuredBudgetMs} is used when the status file has no {@code drain-ms}. Negative means the default. */
    static int stop(Path pidFile, Duration timeout, long configuredBudgetMs, PrintStream out, PrintStream err) {
        return stop(pidFile, timeout, configuredBudgetMs, out, err, ProcessHandle::of);
    }

    /** {@code processes} resolves a pid. Tests supply a handle whose lifetime they control. */
    static int stop(Path pidFile, Duration timeout, PrintStream out, PrintStream err,
            LongFunction<Optional<ProcessHandle>> processes) {
        return stop(pidFile, timeout, -1L, out, err, processes);
    }

    static int stop(Path pidFile, Duration timeout, long configuredBudgetMs, PrintStream out, PrintStream err,
            LongFunction<Optional<ProcessHandle>> processes) {
        var snap = PidFile.read(pidFile);
        if (!snap.present()) {
            out.println("stopped");
            return Sjws_nettask.EXIT_OK;
        }
        if (!snap.running()) {
            deleteIfUnchanged(snap, pidFile, err);
            out.println("stopped stale pid=" + displayPid(snap));
            return Sjws_nettask.EXIT_OK;
        }
        var handle = processes.apply(snap.pid()).orElse(null);
        if (handle == null || !PidFile.isLive(snap.pid(), snap.startedMillis())) {
            deleteIfUnchanged(snap, pidFile, err);
            out.println("stopped stale pid=" + displayPid(snap));
            return Sjws_nettask.EXIT_OK;
        }
        // Re-check immediately before the signal. A pid recycled in this window can still be hit;
        // the start-time check is the guard, and Linux cannot close that window without pidfd.
        var started = handle.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        if (snap.startedMillis() >= 0 && started >= 0 && started != snap.startedMillis()) {
            deleteIfUnchanged(snap, pidFile, err);
            out.println("stopped stale pid=" + snap.pid());
            return Sjws_nettask.EXIT_OK;
        }
        handle.destroy();
        long recorded = sjws_nettask.service.ServiceStatus.drainBudget(pidFile, snap.pid(), snap.startedMillis());
        long waitMs = stopWaitMillis(timeout, recorded >= 0 ? recorded : configuredBudgetMs);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        try {
            while (handle.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Interrupted while stopping pid " + snap.pid() + ".");
            return Sjws_nettask.EXIT_CANNOT_LISTEN;
        }
        if (handle.isAlive()) {
            handle.destroyForcibly();
            long killBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            try {
                while (handle.isAlive() && System.nanoTime() < killBy) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (handle.isAlive()) {
            err.println("ERROR: pid " + snap.pid() + " did not exit.");
            return Sjws_nettask.EXIT_CANNOT_LISTEN;
        }
        deleteIfUnchanged(snap, pidFile, err);
        out.println("stopped pid=" + snap.pid());
        return Sjws_nettask.EXIT_OK;
    }

    /** SIGTERM, then SIGKILL if the process is still alive after a short wait. */
    private static void abandon(Process process) {
        boolean interrupted = Thread.interrupted();
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static int status(Path pidFile, PrintStream out, PrintStream err) {
        var snap = PidFile.read(pidFile);
        if (!snap.present()) {
            out.println("stopped");
            return STATUS_STOPPED;
        }
        if (!snap.running()) {
            out.println("stopped stale pid=" + displayPid(snap));
            return STATUS_STALE;
        }
        var suffix = sjws_nettask.service.ServiceStatus.runningSuffix(pidFile, snap.pid(), snap.startedMillis());
        if (suffix.isEmpty()) {
            out.println("running pid=" + snap.pid());
        } else {
            out.println("running pid=" + snap.pid() + " " + suffix);
        }
        return STATUS_RUNNING;
    }

    static void tail(Path log, PrintStream err) {
        List<String> lines;
        try {
            lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("Could not read the log file " + log + ": " + e.getMessage());
            return;
        }
        int from = Math.max(0, lines.size() - 20);
        if (from == lines.size()) {
            return;
        }
        err.println("Last log lines from " + log + ":");
        for (int i = from; i < lines.size(); i++) {
            err.println(lines.get(i));
        }
    }

    private static String displayPid(PidFile.Snapshot snap) {
        return snap.pid() > 0 ? Long.toString(snap.pid()) : "?";
    }

    /**
     * How long {@code --stop} waits after SIGTERM before SIGKILL. The drain budget is authoritative:
     * a negative budget means {@link #DEFAULT_DRAIN_BUDGET_MS}. {@code timeout} is not added;
     * {@code --shutdown-timeout} bounds the in-process close only.
     */
    static long stopWaitMillis(Duration timeout, long drainBudgetMillis) {
        long budget = drainBudgetMillis < 0 ? DEFAULT_DRAIN_BUDGET_MS : drainBudgetMillis;
        long capped = Math.min(Math.max(0L, budget), sjws_nettask.service.ServiceStatus.MAX_BUDGET_MS);
        return capped + STOP_MARGIN.toMillis();
    }

    private static void deleteIfUnchanged(PidFile.Snapshot snap, Path path, PrintStream err) {
        sjws_nettask.service.ServiceStatus.deleteIfMatch(path, snap.pid(), snap.startedMillis());
        try {
            PidFile.removeIfSame(snap, path);
        } catch (IOException e) {
            err.println("WARN: could not remove pid file " + path + ": " + e.getMessage());
        }
    }

    private static String roleValue(String value, String fallback, String what, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add("Missing value for " + what + " (use " + what + "=standalone|control|data)");
            return fallback;
        }
        var role = value.trim().toLowerCase(Locale.ROOT);
        if (!role.equals("standalone") && !role.equals("control") && !role.equals("data")) {
            errors.add("Invalid " + what + ", expected standalone, control, or data");
            return fallback;
        }
        return role;
    }

    private static String pathValue(String value, String fallback, String what, List<String> errors) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.length() > 4096) {
            errors.add("Missing or invalid value for " + what);
            return fallback;
        }
        return value;
    }

    /** First existing path of {@code names}, or null. {@code NUL} is the Windows null device. */
    static Path discardInput(List<String> names) {
        for (var name : names) {
            var path = Path.of(name);
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private static boolean bool(String value, boolean fallback, String what, List<String> warnings) {
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on":
                return true;
            case "false", "0", "no", "off":
                return false;
            default:
                warnings.add("Invalid " + what + " (value '" + value + "'), expected true/false; using " + fallback);
                return fallback;
        }
    }

    private static String text(String value, String fallback, String what, List<String> warnings) {
        if (value == null || value.isBlank()) {
            warnings.add("Missing value for " + what + " (use " + what + "=VALUE)");
            return fallback;
        }
        return value;
    }

    private static long number(String value, long fallback, long min, long maxValue, String what,
            List<String> warnings) {
        if (value != null && value.matches("[0-9]{1,9}")) {
            long n = Long.parseLong(value);
            // Digits only, and min is 0, so there is no lower bound to apply.
            if (n <= maxValue) {
                return n;
            }
        }
        warnings.add("Invalid " + what + " (value '" + value + "'), expected " + min + ".." + maxValue + "; using "
                + fallback);
        return fallback;
    }

    /** Seconds on the command line, stored as milliseconds. Invalid input keeps {@code current}. */
    private static long budgetMillis(String value, long current, String what, List<String> warnings) {
        String using = current < 0 ? "the default" : Long.toString(current / 1000);
        if (value != null && value.matches("[0-9]{1,9}")) {
            long seconds = Long.parseLong(value);
            if (seconds <= 600) {
                return seconds * 1000L;
            }
        }
        warnings.add("Invalid " + what + " (value '" + value + "'), expected 0..600; using " + using);
        return current;
    }
}
