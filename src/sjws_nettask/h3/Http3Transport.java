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
package sjws_nettask.h3;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import sjws_nettask.ServerConfig;
import sjws_nettask.Transport;
import sjws_nettask.core.StaticFileHandler;

/**
 * {@link Http3Server} as a {@link Transport} for {@code SjwsServer}. TLS comes from the config
 * (tlsCert/tlsKey/tlsKeyPassword, else self-signed). If the native QUIC library is missing,
 * {@link #start()} fails with a clear message and the server keeps serving HTTP/1.1 only.
 */
public final class Http3Transport implements Transport {

    private final ServerConfig config;
    private final StaticFileHandler handler;
    private final BooleanSupplier available;
    private final Supplier<Throwable> unavailabilityCause;
    private Http3Server server;

    public Http3Transport(ServerConfig config, StaticFileHandler handler) {
        this(config, handler, Http3Server::isAvailable, Http3Server::unavailabilityCause);
    }

    /** @param available availability hook (tests simulate missing natives with it) */
    public Http3Transport(ServerConfig config, StaticFileHandler handler, BooleanSupplier available,
            Supplier<Throwable> unavailabilityCause) {
        this.config = Objects.requireNonNull(config, "config");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.available = Objects.requireNonNull(available, "available");
        this.unavailabilityCause = Objects.requireNonNull(unavailabilityCause, "unavailabilityCause");
    }

    @Override
    public String name() {
        return "HTTP/3";
    }

    @Override
    public void start() throws IOException {
        if (this.server != null) {
            throw new IllegalStateException("already started");
        }
        if (!this.available.getAsBoolean()) {
            var cause = this.unavailabilityCause.get();
            throw new IOException("native QUIC library not available on " + System.getProperty("os.name") + "/"
                    + System.getProperty("os.arch") + " (" + (cause == null ? "unknown cause" : cause.toString())
                    + "); if /tmp is mounted noexec, set -Dio.netty.native.workdir to an exec-mounted directory",
                    cause);
        }
        this.server = new Http3Server(this.config, this.handler).start();
    }

    @Override
    public int port() {
        if (this.server == null) {
            throw new IllegalStateException("not started");
        }
        return this.server.boundPort();
    }

    /** @return the running server (after {@link #start()}), or null */
    public Http3Server server() {
        return this.server;
    }

    @Override
    public void close() {
        if (this.server != null) {
            this.server.close();
        }
    }
}
