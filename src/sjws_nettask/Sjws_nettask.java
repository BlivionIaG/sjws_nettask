/*
 * Copyright (C) 2019 BlivionIaG <BlivionIaG at chenco.tk>
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

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class Sjws_nettask {

    private Sjws_nettask() {
    }

    /** Process exit codes. */
    public static final int EXIT_OK = 0;
    public static final int EXIT_CANNOT_LISTEN = 1;
    public static final int EXIT_BAD_ROOT = 2;
    public static final int EXIT_USAGE = 64;

    /**
     * Runs the server until it is stopped.
     *
     * @return process exit code: 0 normal, 1 cannot listen, 2 bad document root
     */
    public static int serve(ServerConfig config) {
        return serve(config, H3Transports.defaultFactory(), System.out, System.err, s -> {
        });
    }

    /**
     * @param started called once the server listens (tests use it to stop the server)
     */
    static int serve(ServerConfig config, Transport.Factory h3Factory, PrintStream out, PrintStream err,
            Consumer<SjwsServer> started) {
        return serve(config, h3Factory, out, err, started, Daemon.DEFAULT_SHUTDOWN_TIMEOUT, null);
    }

    /**
     * @param pidFile written once the server is listening, and removed on shutdown; null to skip
     * @param shutdownTimeout how long in-flight responses may finish after SIGTERM/SIGINT
     */
    static int serve(ServerConfig config, Transport.Factory h3Factory, PrintStream out, PrintStream err,
            Consumer<SjwsServer> started, Duration shutdownTimeout, Path pidFile) {
        var root = config.root();
        if (!Files.isDirectory(root)) {
            err.println("Document root " + root.toAbsolutePath() + " does not exist or is not a directory.");
            return EXIT_BAD_ROOT;
        }
        if (pidFile != null) {
            var existing = PidFile.read(pidFile);
            if (PidFile.blocksStart(existing)) {
                err.println("Already running with pid " + existing.pid() + " (" + pidFile + ").");
                return EXIT_CANNOT_LISTEN;
            }
        }
        SjwsServer server;
        try {
            server = SjwsServer.start(config, h3Factory);
        } catch (BindException e) {
            err.println("Cannot listen on port " + config.port() + ": " + e.getMessage()
                    + " (already in use or not permitted).");
            return EXIT_CANNOT_LISTEN;
        } catch (IOException | UncheckedIOException e) {
            err.println("Failed to start the server on port " + config.port() + ": " + e);
            return EXIT_CANNOT_LISTEN;
        }
        try (var modules = OptionalModules.start(err)) {
            return awaitServing(server, config, out, err, started, shutdownTimeout, pidFile, null, -1L, true);
        }
    }

    /** Same lifecycle as {@link #serve}, starting from a loaded config file so reload can re-read it. */
    private static int serveLoaded(sjws_nettask.config.ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            PrintStream out, PrintStream err, Consumer<SjwsServer> started, Duration shutdownTimeout, Path pidFile,
            ServiceHooks hooks, long drainBudgetMs, boolean bindContent) {
        var config = loaded.document().server();
        if (!Files.isDirectory(config.root())) {
            err.println("Document root " + config.root().toAbsolutePath() + " does not exist or is not a directory.");
            return EXIT_BAD_ROOT;
        }
        if (pidFile != null) {
            var existing = PidFile.read(pidFile);
            if (PidFile.blocksStart(existing)) {
                err.println("Already running with pid " + existing.pid() + " (" + pidFile + ").");
                return EXIT_CANNOT_LISTEN;
            }
        }
        SjwsServer server;
        try {
            server = SjwsServer.start(loaded, h3Factory, bindContent);
        } catch (BindException e) {
            err.println("Cannot listen on port " + config.port() + ": " + e.getMessage()
                    + " (already in use or not permitted).");
            return EXIT_CANNOT_LISTEN;
        } catch (IOException | UncheckedIOException e) {
            err.println("Failed to start the server on port " + config.port() + ": " + e);
            return EXIT_CANNOT_LISTEN;
        }
        try (var modules = OptionalModules.start(err)) {
            return awaitServing(server, config, out, err, started, shutdownTimeout, pidFile, hooks, drainBudgetMs,
                    bindContent);
        }
    }

    private static int awaitServing(SjwsServer server, ServerConfig config, PrintStream out, PrintStream err,
            Consumer<SjwsServer> started, Duration shutdownTimeout, Path pidFile, ServiceHooks hooks,
            long drainBudgetMs, boolean bindContent) {
        server.shutdownTimeout(shutdownTimeout);
        var pid = new AtomicReference<PidFile>();
        var task = new Shutdown(server, pid, out, err);
        var hook = new Thread(task, "shutdown");
        task.bind(hook);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            if (pidFile != null) {
                PidFile.Claim claim;
                try {
                    claim = PidFile.claim(pidFile);
                } catch (IOException e) {
                    err.println("Cannot write pid file " + pidFile + ": " + e.getMessage());
                    return EXIT_CANNOT_LISTEN;
                }
                if (!claim.acquired()) {
                    var owner = claim.snapshot();
                    err.println("Already running with pid " + (owner.pid() > 0 ? owner.pid() : "?") + " (" + pidFile
                            + ").");
                    return EXIT_CANNOT_LISTEN;
                }
                pid.set(claim.file());
            }
            if (hooks != null) {
                if (pidFile == null) {
                    err.println("ERROR: --bootstrap requires --pid-file");
                    return EXIT_USAGE;
                }
                task.hooks(hooks);
                try {
                    hooks.onListening(server, pidFile, shutdownTimeout);
                } catch (IOException | RuntimeException e) {
                    err.println("ERROR: " + safeMessage(e.getMessage()));
                    return EXIT_CANNOT_LISTEN;
                }
                var line = hooks.startupLine();
                if (line != null && !line.isBlank()) {
                    out.println(line);
                }
            } else {
                publishHaDrainBudget(server, pidFile, drainBudgetMs);
            }
            if (!bindContent) {
                out.println("content port not bound");
            }
            out.println("Server started: " + server.describe());
            sjws_nettask.core.SafeOpen.startupWarning(sjws_nettask.core.SafeOpen.SECURE)
                    .ifPresent(w -> err.println("WARN: " + w));
            sjws_nettask.core.SafeOpen.unlistableWarning(config.allowUnlistableDirs())
                    .ifPresent(w -> err.println("WARN: " + w));
            started.accept(server);
            task.markServing();
            server.awaitTermination();
            task.reportTerminated();
            return EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_OK;
        } finally {
            task.run();
            removeHook(hook);
        }
    }

    /**
     * SIGTERM/SIGINT task: stop accepting, drain, then drop the pid file.
     * <p>
     * On a signal the JVM runs this hook and then halts, which aborts the main thread wherever
     * it is. The "terminated" line is written on the main thread after {@code awaitTermination}
     * returns; this hook waits for that write before returning so halt cannot drop it. If the
     * main thread does not report in time, the hook writes the line itself.
     */
    static final class Shutdown implements Runnable {
        private final SjwsServer server;
        private final AtomicReference<PidFile> pid;
        private final PrintStream out;
        private final PrintStream err;
        private final AtomicBoolean serving = new AtomicBoolean();
        private final AtomicBoolean logged = new AtomicBoolean();
        private final CountDownLatch reported = new CountDownLatch(1);
        private volatile Thread hookThread;
        private volatile ServiceHooks hooks;
        /** How long a signal hook waits for the main thread to log termination before logging it. */
        private Duration reportWait = Duration.ofSeconds(5);

        Shutdown(SjwsServer server, AtomicReference<PidFile> pid, PrintStream out, PrintStream err) {
            this.server = server;
            this.pid = pid;
            this.out = out;
            this.err = err;
        }

        void bind(Thread hook) {
            this.hookThread = hook;
        }

        void hooks(ServiceHooks hooks) {
            this.hooks = hooks;
        }

        /** Tests shrink the signal-hook wait so the fallback log path does not block. */
        void reportWait(Duration timeout) {
            this.reportWait = timeout;
        }

        void markServing() {
            this.serving.set(true);
        }

        /** Logs termination once. Unblocks a signal hook waiting in {@link #run()}. */
        void reportTerminated() {
            if (this.serving.get() && this.logged.compareAndSet(false, true)) {
                this.out.println("Server Terminated properly !");
                this.out.flush();
            }
            this.reported.countDown();
        }

        @Override
        public void run() {
            try {
                try {
                    this.server.drain();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    this.server.close();
                }
                if (Thread.currentThread() == this.hookThread && this.serving.get()) {
                    boolean mainLogged = false;
                    try {
                        mainLogged = this.reported.await(this.reportWait.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (!mainLogged) {
                        reportTerminated();
                    }
                }
            } finally {
                var hooks = this.hooks;
                try {
                    if (hooks != null) {
                        hooks.onShutdown();
                    }
                } catch (RuntimeException e) {
                    this.err.println("WARN: status cleanup failed");
                } finally {
                    releasePid(this.pid.get(), this.err);
                }
            }
        }
    }

    /**
     * Authoritative drain budget in milliseconds. Negative means {@link Daemon#DEFAULT_DRAIN_BUDGET_MS}.
     * Values above ten minutes are capped. This is not derived from HA drain settings;
     * {@code --shutdown-timeout} is a separate in-process limit.
     */
    public static long drainBudgetMillis(long configuredMillis) {
        long budget = configuredMillis < 0 ? Daemon.DEFAULT_DRAIN_BUDGET_MS : configuredMillis;
        if (budget > sjws_nettask.service.ServiceStatus.MAX_BUDGET_MS) {
            return sjws_nettask.service.ServiceStatus.MAX_BUDGET_MS;
        }
        return Math.max(0L, budget);
    }

    /**
     * Writes a drain budget for an HA process that is still role standalone. The {@code --status}
     * line stays {@code running pid=…}. Not called for control or data (their status file already
     * carries the budget) and not called when HA is off, so that path does not load
     * {@code sjws_nettask.service}.
     */
    static void publishHaDrainBudget(SjwsServer server, Path pidFile, long configuredBudgetMs) {
        if (server.ha() == null || pidFile == null) {
            return;
        }
        try {
            long pid = ProcessHandle.current().pid();
            long started = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli)
                    .orElse(-1L);
            Class.forName("sjws_nettask.service.ServiceStatus")
                    .getMethod("writeStandaloneDrain", Path.class, long.class, long.class, long.class)
                    .invoke(null, pidFile, pid, started, drainBudgetMillis(configuredBudgetMs));
        } catch (ReflectiveOperationException e) {
            System.err.println("WARN: could not record the drain budget");
        }
    }

    static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "bootstrap failed";
        }
        var one = message.replace('\n', ' ').replace('\r', ' ');
        return one.length() > 240 ? one.substring(0, 240) : one;
    }

    private static void releasePid(PidFile file, PrintStream err) {
        if (file == null) {
            return;
        }
        try {
            file.release();
        } catch (IOException e) {
            err.println("WARN: could not remove pid file " + file.path() + ": " + e.getMessage());
        }
    }

    private static void removeHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) { // the JVM is already shutting down: the hook is running
        }
    }

    /** Kept for existing callers and tests. */
    public static boolean checkValidPort(String port) {
        if (port == null || !port.matches("\\d{1,5}")) {
            return false;
        }
        var value = Integer.parseInt(port);
        return value > 0 && value < 65536;
    }

    /** {@link ServerConfig#usage()} plus the daemon controls. */
    public static String usage() {
        return ServerConfig.usage() + Daemon.usage();
    }

    /**
     * Everything {@link #main} does except exiting the process.
     *
     * @return the exit code
     */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err,
            Transport.Factory h3Factory, Consumer<SjwsServer> started) {
        for (var arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                out.print(usage());
                return EXIT_OK;
            }
        }
        var opt = Daemon.parse(args, env);
        boolean child = "1".equals(env.get(Daemon.CHILD_ENV));
        if (opt.action() == Daemon.Action.STOP || opt.action() == Daemon.Action.STATUS) {
            return control(opt, out, err);
        }
        var loaded = sjws_nettask.config.ConfigLoader.load(opt.remaining(), env);
        opt.warnings().forEach(w -> err.println("WARN: " + w));
        loaded.warnings().forEach(w -> err.println("WARN: " + w));
        if (!opt.ok() || !loaded.ok()) {
            opt.errors().forEach(e -> err.println("ERROR: " + e));
            loaded.errors().forEach(e -> err.println("ERROR: " + e));
            err.println("See --help.");
            return EXIT_USAGE;
        }
        var choice = RoleChoice.resolve(opt, loaded.document(), err);
        if (!choice.ok()) {
            err.println("See --help.");
            return EXIT_USAGE;
        }
        if (!child && (opt.action() == Daemon.Action.DAEMON || opt.action() == Daemon.Action.RESTART)) {
            if (opt.action() == Daemon.Action.RESTART) {
                int stopped = Daemon.stop(opt.pidFile(), opt.shutdownTimeout(), opt.drainBudgetMs(), out, err);
                if (stopped != 0) {
                    return stopped;
                }
            }
            return Daemon.daemonize(opt, env, out, err);
        }
        ServiceHooks hooks = null;
        if (opt.bootstrap() != null) {
            if (!opt.trackPid()) {
                err.println("ERROR: --bootstrap requires --pid-file");
                return EXIT_USAGE;
            }
            try {
                hooks = loadServiceHooks(opt.bootstrap(), choice.expectedRole(), opt.drainBudgetMs());
            } catch (IOException e) {
                err.println("ERROR: " + safeMessage(e.getMessage()));
                return EXIT_USAGE;
            }
        }
        loaded = applyProcessMode(loaded, choice, hooks);
        boolean bindContent = resolveBind(opt, loaded, err);
        if (child) {
            ReloadHook.installSighup();
        }
        PrintStream logOut = out;
        PrintStream logErr = err;
        boolean ownLog = false;
        if (opt.logToFile()) {
            try {
                var log = Daemon.openLog(opt.logFile());
                logOut = log;
                logErr = log;
                ownLog = true;
                if (child) {
                    Daemon.redirectStandardStreams(log);
                }
            } catch (IOException e) {
                err.println("Cannot write log file " + opt.logFile() + ": " + e.getMessage());
                return EXIT_CANNOT_LISTEN;
            }
        }
        try {
            return serveLoaded(loaded, h3Factory, logOut, logErr, started, opt.shutdownTimeout(),
                    opt.trackPid() ? opt.pidFile() : null, hooks, opt.drainBudgetMs(), bindContent);
        } finally {
            if (ownLog) {
                logOut.close();
            }
        }
    }

    /**
     * Loads the control/data bootstrap. Only called when {@code --bootstrap} is set, so a standalone
     * process does not resolve {@code sjws_nettask.service}.
     *
     * @param expectedRole {@code control} or {@code data} from {@code --role} or {@code node.mode},
     *        or null to take the role from the bootstrap file
     */
    private static ServiceHooks loadServiceHooks(Path bootstrap, String expectedRole, long drainBudgetMs)
            throws IOException {
        return sjws_nettask.service.ServiceLaunch.prepare(bootstrap, expectedRole, drainBudgetMs);
    }

    /**
     * Applies the resolved role onto the document so leadership and the content port follow it.
     * {@code --role} and {@code WEB_SERVER_ROLE} already won in {@link RoleChoice}; a bootstrap file
     * supplies the mode only when neither of those, and no config file, named one.
     */
    static sjws_nettask.config.ConfigLoader.Loaded applyProcessMode(sjws_nettask.config.ConfigLoader.Loaded loaded,
            RoleChoice choice, ServiceHooks hooks) {
        String mode = choice.processMode();
        if (mode == null && hooks != null) {
            mode = hooks.nodeMode();
        }
        if (mode == null) {
            return loaded;
        }
        var node = loaded.document().node();
        if (mode.equals(node.mode().wire())) {
            return loaded;
        }
        return loaded.withDocument(loaded.document().withNode(
                new sjws_nettask.config.NodeSettings(sjws_nettask.config.NodeSettings.Mode.parse(mode), node.labels())));
    }

    /**
     * Content port: an explicit flag or variable, then {@code server.bind_content}, then off for
     * {@code control} and on otherwise. A difference from the role default is logged.
     */
    static boolean resolveBind(Daemon.Options opt, sjws_nettask.config.ConfigLoader.Loaded loaded, PrintStream err) {
        var mode = loaded.document().node().mode();
        boolean byRole = mode != sjws_nettask.config.NodeSettings.Mode.CONTROL;
        Boolean chosen = opt.bindContent();
        String source = opt.bindFromEnv() ? "WEB_SERVER_BIND_CONTENT" : "--bind-content";
        if (chosen == null) {
            chosen = loaded.yamlBindContent();
            source = "server.bind_content";
        }
        if (chosen == null) {
            return byRole;
        }
        if (chosen.booleanValue() != byRole) {
            err.println("WARN: " + source + "=" + chosen + " overrides the " + mode.wire()
                    + " content-port default");
        }
        return chosen;
    }

    /**
     * Role for this process. The schema field is {@code node.mode} (there is no {@code node.role}).
     * Precedence is {@code --role}, then {@code WEB_SERVER_ROLE}, then {@code node.mode}. A difference
     * is logged here, once the process is starting. No flag, no variable, and no config file leaves
     * the choice to the bootstrap file, or standalone when that file is absent. Does not load
     * {@code sjws_nettask.service}.
     */
    static RoleChoice roleChoice(Daemon.Options opt, sjws_nettask.config.ConfigDocument document, PrintStream err) {
        return RoleChoice.resolve(opt, document, err);
    }

    static final class RoleChoice {
        private final boolean ok;
        /** {@code control}, {@code data}, {@code standalone}, or null to read the bootstrap file. */
        private final String processMode;

        private RoleChoice(boolean ok, String processMode) {
            this.ok = ok;
            this.processMode = processMode;
        }

        boolean ok() {
            return this.ok;
        }

        /** {@code control} or {@code data} for the bootstrap check, or null for standalone / unread. */
        String expectedRole() {
            if ("control".equals(this.processMode) || "data".equals(this.processMode)) {
                return this.processMode;
            }
            return null;
        }

        String processMode() {
            return this.processMode;
        }

        static RoleChoice resolve(Daemon.Options opt, sjws_nettask.config.ConfigDocument document, PrintStream err) {
            var mode = document.node().mode().wire();
            boolean fromFile = document.configFile() != null;
            if (opt.cliRole() != null && opt.envRole() != null && !opt.cliRole().equals(opt.envRole())) {
                err.println("WARN: --role=" + opt.cliRole() + " overrides WEB_SERVER_ROLE=" + opt.envRole());
            }
            var flagged = opt.role();
            String role;
            if (flagged != null) {
                role = flagged;
                if (fromFile && !flagged.equals(mode)) {
                    String source = opt.cliRole() != null ? "--role" : "WEB_SERVER_ROLE";
                    err.println("WARN: " + source + "=" + flagged + " overrides node.mode=" + mode);
                }
            } else if (fromFile) {
                role = mode;
            } else {
                role = null;
            }
            if ("control".equals(role) || "data".equals(role)) {
                if (opt.bootstrap() == null) {
                    err.println("ERROR: node.mode=" + role + " requires --bootstrap=PATH");
                    return new RoleChoice(false, null);
                }
                return new RoleChoice(true, role);
            }
            if ("standalone".equals(role) && opt.bootstrap() != null) {
                err.println("ERROR: node.mode=standalone cannot be combined with --bootstrap");
                return new RoleChoice(false, null);
            }
            if ("standalone".equals(role)) {
                return new RoleChoice(true, "standalone");
            }
            return new RoleChoice(true, null);
        }
    }

    /** {@code --stop} / {@code --status}: no server configuration is required. */
    private static int control(Daemon.Options opt, PrintStream out, PrintStream err) {
        opt.warnings().forEach(w -> err.println("WARN: " + w));
        if (opt.action() == Daemon.Action.STATUS) {
            return Daemon.status(opt.pidFile(), out, err);
        }
        return Daemon.stop(opt.pidFile(), opt.shutdownTimeout(), opt.drainBudgetMs(), out, err);
    }

    /**
     * See {@link #usage()}.
     *
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        int code = run(args, System.getenv(), System.out, System.err, H3Transports.defaultFactory(), s -> {
        });
        if (code != 0) {
            System.exit(code);
        }
    }
}
