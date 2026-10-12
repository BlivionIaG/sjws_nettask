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
                this.server.close();
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
                releasePid(this.pid.get(), this.err);
            }
        }
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
        var parsed = ServerConfig.parse(opt.remaining(), env);
        opt.warnings().forEach(w -> err.println("WARN: " + w));
        parsed.warnings().forEach(w -> err.println("WARN: " + w));
        if (!opt.ok() || !parsed.ok()) {
            opt.errors().forEach(e -> err.println("ERROR: " + e));
            parsed.errors().forEach(e -> err.println("ERROR: " + e));
            err.println("See --help.");
            return EXIT_USAGE;
        }
        if (!child && (opt.action() == Daemon.Action.DAEMON || opt.action() == Daemon.Action.RESTART)) {
            if (opt.action() == Daemon.Action.RESTART) {
                int stopped = Daemon.stop(opt.pidFile(), opt.shutdownTimeout(), out, err);
                if (stopped != 0) {
                    return stopped;
                }
            }
            return Daemon.daemonize(opt, env, out, err);
        }
        if (child) {
            Daemon.ignoreSignal("HUP");
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
            return serve(parsed.config(), h3Factory, logOut, logErr, started, opt.shutdownTimeout(),
                    opt.trackPid() ? opt.pidFile() : null);
        } finally {
            if (ownLog) {
                logOut.close();
            }
        }
    }

    /** {@code --stop} / {@code --status}: no server configuration is required. */
    private static int control(Daemon.Options opt, PrintStream out, PrintStream err) {
        opt.warnings().forEach(w -> err.println("WARN: " + w));
        if (opt.action() == Daemon.Action.STATUS) {
            return Daemon.status(opt.pidFile(), out, err);
        }
        return Daemon.stop(opt.pidFile(), opt.shutdownTimeout(), out, err);
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
