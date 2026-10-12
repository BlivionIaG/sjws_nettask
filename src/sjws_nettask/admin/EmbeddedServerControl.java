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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import sjws_nettask.ServerConfig;
import sjws_nettask.SjwsServer;

/**
 * Runs an {@link SjwsServer} in this JVM. {@code reload} re-evaluates the config supplier (e.g.
 * re-parses arguments and environment) and restarts; if the new config fails to start, the
 * previous one is started again so the server is not left down.
 */
public final class EmbeddedServerControl implements ServerControl {

    /** Starts a server; tests substitute it. */
    @FunctionalInterface
    public interface Starter {
        SjwsServer start(ServerConfig config) throws IOException;
    }

    private final Supplier<ServerConfig> configSource;
    private final Starter starter;
    private final LogBuffer log;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile SjwsServer server;
    private volatile ServerConfig config;
    private volatile Instant startedAt;
    private volatile String lastError;

    public EmbeddedServerControl(Supplier<ServerConfig> configSource, LogBuffer log) {
        this(configSource, SjwsServer::start, log, Clock.systemUTC());
    }

    EmbeddedServerControl(Supplier<ServerConfig> configSource, Starter starter, LogBuffer log, Clock clock) {
        this.configSource = configSource;
        this.starter = starter;
        this.log = log;
        this.clock = clock;
    }

    @Override
    public ServerStatus status() {
        var s = this.server;
        var c = this.config;
        var view = c == null ? Map.<String, String>of() : describe(c);
        if (s == null) {
            var err = this.lastError;
            return err == null ? ServerStatus.stopped("stopped", view)
                    : new ServerStatus(ServerStatus.State.FAILED, -1, -1, -1, Duration.ZERO, err, view);
        }
        var manager = s.clientManager();
        String summary = s.describe();
        try {
            // First status read arms counters when the server was started with observability off.
            summary = summary + ", requests " + s.metricsSnapshot().requests();
        } catch (IOException e) {
            this.log.add("metrics snapshot failed: " + e.getMessage());
        }
        return new ServerStatus(ServerStatus.State.RUNNING, s.tcpPort(), s.udpPort(),
                manager == null ? -1 : manager.activeConnections(),
                Duration.between(this.startedAt, this.clock.instant()), summary, view);
    }

    @Override
    public void start() throws ControlException {
        this.lock.lock();
        try {
            if (this.server != null) {
                this.log.add("start: already running");
                return;
            }
            startWith(load());
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void stop() {
        this.lock.lock();
        try {
            var s = this.server;
            if (s == null) {
                return;
            }
            this.server = null;
            s.close();
            this.lastError = null;
            this.log.add("stopped");
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void reload() throws ControlException {
        this.lock.lock();
        try {
            var next = load(); // a bad config leaves the running server alone
            var previous = this.server == null ? null : this.config;
            stop();
            try {
                startWith(next);
                this.log.add("reloaded");
            } catch (ControlException e) {
                if (previous != null) {
                    this.log.add("reload failed, restoring previous configuration");
                    startWith(previous);
                }
                throw e;
            }
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private ServerConfig load() throws ControlException {
        try {
            return this.configSource.get();
        } catch (RuntimeException e) {
            this.log.add("configuration error: " + e.getMessage());
            throw new ControlException("configuration error: " + e.getMessage(), e);
        }
    }

    private void startWith(ServerConfig c) throws ControlException {
        this.config = c;
        try {
            var started = this.starter.start(c);
            this.startedAt = this.clock.instant(); // before publishing: status() reads server first
            this.lastError = null;
            this.server = started;
            this.log.add("started: " + started.describe());
        } catch (IOException | UncheckedIOException e) {
            this.lastError = "start failed on port " + c.port() + ": " + e.getMessage();
            this.log.add(this.lastError);
            throw new ControlException(this.lastError, e);
        }
    }

    /**
     * Every {@link ServerConfig} record component, in declaration order, so fields added by other
     * work show up without touching the TUI. The TLS key password is masked.
     */
    static Map<String, String> describe(ServerConfig c) {
        var out = new LinkedHashMap<String, String>();
        for (var component : ServerConfig.class.getRecordComponents()) {
            Object value;
            try {
                value = component.getAccessor().invoke(c);
            } catch (IllegalAccessException | InvocationTargetException e) {
                value = "?";
            }
            if (component.getName().toLowerCase(java.util.Locale.ROOT).contains("password") && value != null) {
                value = "****";
            }
            out.put(component.getName(), String.valueOf(value));
        }
        return out;
    }
}
