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

    /** Same as {@link #serve(ServerConfig, Transport.Factory, PrintStream, PrintStream, Consumer)} with a config file. */
    private static int serveLoaded(sjws_nettask.config.ConfigLoader.Loaded loaded, Transport.Factory h3Factory,
            PrintStream out, PrintStream err, Consumer<SjwsServer> started) {
        var config = loaded.document().server();
        SjwsServer server;
        try {
            server = SjwsServer.start(loaded, h3Factory);
        } catch (BindException e) {
            err.println("Cannot listen on port " + config.port() + ": " + e.getMessage()
                    + " (already in use or not permitted).");
            return EXIT_CANNOT_LISTEN;
        } catch (IOException | UncheckedIOException e) {
            err.println("Failed to start the server on port " + config.port() + ": " + e);
            return EXIT_CANNOT_LISTEN;
        }
        var hook = new Thread(server::close, "shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        out.println("Server started: " + server.describe());
        sjws_nettask.core.SafeOpen.startupWarning(sjws_nettask.core.SafeOpen.SECURE)
                .ifPresent(w -> err.println("WARN: " + w));
        sjws_nettask.core.SafeOpen.unlistableWarning(config.allowUnlistableDirs())
                .ifPresent(w -> err.println("WARN: " + w));
        try {
            started.accept(server);
            server.awaitTermination();
            out.println("Server Terminated properly !");
            return EXIT_OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EXIT_OK;
        } finally {
            server.close();
            removeHook(hook);
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
        var loaded = sjws_nettask.config.ConfigLoader.load(args, env);
        if (loaded.helpRequested()) {
            out.print(ServerConfig.usage());
            return EXIT_OK;
        }
        loaded.warnings().forEach(w -> err.println("WARN: " + w));
        if (!loaded.ok()) {
            loaded.errors().forEach(e -> err.println("ERROR: " + e));
            err.println("See --help.");
            return EXIT_USAGE;
        }
        var root = loaded.document().server().root();
        if (!Files.isDirectory(root)) {
            err.println("Document root " + root.toAbsolutePath() + " does not exist or is not a directory.");
            return EXIT_BAD_ROOT;
        }
        return serveLoaded(loaded, h3Factory, out, err, started);
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
