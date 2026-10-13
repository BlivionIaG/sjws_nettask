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
package sjws_nettask.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import sjws_nettask.ServiceHooks;
import sjws_nettask.SjwsServer;
import sjws_nettask.Sjws_nettask;

/**
 * Loads a bootstrap file into {@link ServiceHooks}. Invoked only when {@code --bootstrap} is set,
 * so a standalone process does not load this class. No watch thread is started: the binding stays
 * pending until the control-plane workstream calls {@link NodeEnrollment#attach}.
 */
public final class ServiceLaunch {

    private ServiceLaunch() {
    }

    public static ServiceHooks prepare(Path bootstrapFile, String expectedRole) throws IOException {
        return prepare(bootstrapFile, expectedRole, -1L);
    }

    public static ServiceHooks prepare(Path bootstrapFile, String expectedRole, long drainBudgetMs) throws IOException {
        return prepare(bootstrapFile, expectedRole, drainBudgetMs, null, null);
    }

    /**
     * @param yamlCp raw {@code cp:} block of the config file (CONTROL role only), or null
     * @param configBase directory of the config file, or null
     */
    public static ServiceHooks prepare(Path bootstrapFile, String expectedRole, long drainBudgetMs,
            Map<String, Object> yamlCp, Path configBase) throws IOException {
        return new Hooks(Bootstrap.load(bootstrapFile, expectedRole), drainBudgetMs, yamlCp, configBase);
    }

    /**
     * Hosting starts only when the operator configured it, so a control bootstrap without {@code cp} settings
     * keeps today's behaviour. Once configured, any error is fatal (fail closed).
     */
    static boolean controlConfigured(Map<String, Object> yamlCp, java.util.function.Function<String, String> props,
            Map<String, String> env) {
        return yamlCp != null || props.apply("sjws.cp.nodeId") != null || env.get("SJWS_CP_NODE_ID") != null;
    }

    /** The CONTROL-role entry point, named only as a string (control-plane-hosting.md §1, §5). */
    static final String CONTROL_CLASS = "sjws_nettask.cp.CpLaunch";

    /**
     * Starts the CONTROL role by reflection: config, KeyRing.load, CpStore, WatchServer, AdminServer, ready.
     * Any failure is an IOException, so the process exits non-zero (fail closed).
     */
    static CpReloadable launchControl(Map<String, Object> yamlCp, Path configBase) throws IOException {
        try {
            var launch = Class.forName(CONTROL_CLASS).getMethod("launch", Map.class, Path.class,
                    java.io.PrintStream.class);
            return (CpReloadable) launch.invoke(null, yamlCp, configBase, System.err);
        } catch (java.lang.reflect.InvocationTargetException e) {
            var cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("control role failed to start: " + cause, cause);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IOException("control role unavailable: " + e, e);
        }
    }

    private static final class Hooks implements ServiceHooks {
        private final Bootstrap bootstrap;
        private final long drainBudgetMs;
        private final Map<String, Object> yamlCp;
        private final Path configBase;
        private NodeEnrollment enrollment;
        /** The CONTROL-role host; null on a data node. Its reload is the single CP SIGHUP hook. */
        private volatile CpReloadable control;
        /** A {@code sjws_nettask.cp.DataWatch} on a data node; typed loosely so this class names no cp type. */
        private AutoCloseable watch;

        private Hooks(Bootstrap bootstrap, long drainBudgetMs, Map<String, Object> yamlCp, Path configBase) {
            this.bootstrap = bootstrap;
            this.drainBudgetMs = drainBudgetMs;
            this.yamlCp = yamlCp;
            this.configBase = configBase;
        }

        @Override
        public void onListening(SjwsServer server, Path pidFile, Duration shutdownTimeout) throws IOException {
            boolean ha = server != null && server.ha() != null;
            if (ha) {
                // Refuse a drain that cannot finish inside the daemon budget before the listener is published.
                sjws_nettask.cp.NodeLifecycle.drainBudgetMillis(true, server.ha().settings().drainDelay().toMillis(),
                        server.ha().settings().drainTimeout().toMillis());
            }
            long budget = Sjws_nettask.drainBudgetMillis(this.drainBudgetMs);
            this.enrollment = NodeEnrollment.open(this.bootstrap, pidFile, budget);
            if (server != null) {
                server.setLocalReload(this::reloadLocal);
            }
            if (server != null && this.bootstrap.role() == NodeRole.DATA) {
                this.watch = openWatch(server);
            }
            if (this.bootstrap.role() == NodeRole.CONTROL) {
                if (controlConfigured(this.yamlCp, System::getProperty, System.getenv())) {
                    this.control = launchControl(this.yamlCp, this.configBase);
                } else {
                    System.err.println("WARN: control role without cp settings (no cp: block, sjws.cp.nodeId or"
                            + " SJWS_CP_NODE_ID): control-plane services are not hosted");
                }
            }
        }

        /**
         * Dials the control plane (design §8). An address the watch cannot use, or no join token,
         * leaves the node {@code pending}: it keeps serving what it has and does not exit.
         */
        private AutoCloseable openWatch(SjwsServer server) {
            var join = this.bootstrap.join();
            var contact = this.bootstrap.controlPlane();
            var files = this.bootstrap.cpTls();
            if ((join == null && files == null) || contact == null || contact.isBlank()) {
                return null;
            }
            try {
                var tls = files == null ? null
                        : new sjws_nettask.cp.CpTls(files.cert(), files.key(), files.ca(), null, files.peer(), 0);
                return sjws_nettask.cp.DataWatch.open(contact, this.bootstrap.nodeId(),
                        join == null ? null : join.tokenId(), join == null ? null : join.copySecret(),
                        this.bootstrap.pinFile(), this.bootstrap.stateDir(), server, this.enrollment,
                        System::currentTimeMillis, tls);
            } catch (IOException e) {
                System.err.println("WARN: control-plane watch not started: " + e.getMessage());
                return null;
            }
        }

        @Override
        public String nodeMode() {
            return this.bootstrap.role().wire();
        }

        private void reloadLocal() {
            try {
                var fresh = Bootstrap.load(this.bootstrap.source(), this.bootstrap.role().wire());
                this.enrollment.adoptLocal(fresh);
            } catch (IOException e) {
                throw new IllegalStateException("bootstrap reload failed");
            }
            var host = this.control;
            if (host != null) {
                var failed = host.reload();
                if (failed != null) {
                    throw new IllegalStateException("control-plane reload failed, old material kept: " + failed);
                }
            }
        }

        @Override
        public void onShutdown() {
            var host = this.control;
            if (host != null) {
                this.control = null;
                host.close();
            }
            var running = this.watch;
            if (running != null) {
                try {
                    running.close();
                } catch (Exception e) {
                    // Shutdown continues; the watch thread is a daemon.
                }
            }
            var open = this.enrollment;
            if (open != null) {
                open.close();
            }
        }

        @Override
        public String startupLine() {
            return this.bootstrap.startupLine();
        }
    }
}
