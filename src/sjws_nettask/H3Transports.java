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

import java.io.IOException;

/**
 * Where {@link SjwsServer} gets its HTTP/3 transport from.
 */
public final class H3Transports {

    private H3Transports() {
    }

    /**
     * TODO(merge h3-transport): return the real transport, e.g.
     * {@code (config, handler) -> new Http3ServerTransport(new Http3Server(config, handler))},
     * once the {@code sjws_nettask.h3} package lands. Until then HTTP/3 always "fails to bind",
     * so {@code --h3} logs a warning and the server stays HTTP/1.1-only without Alt-Svc.
     */
    public static Transport.Factory defaultFactory() {
        return Unavailable::new;
    }

    /** Placeholder transport: refuses to start. */
    static final class Unavailable implements Transport {

        Unavailable(ServerConfig config, sjws_nettask.core.StaticFileHandler handler) {
        }

        @Override
        public String name() {
            return "HTTP/3";
        }

        @Override
        public void start() throws IOException {
            throw new IOException("HTTP/3 support is not included in this build");
        }

        @Override
        public int port() {
            throw new IllegalStateException("not started");
        }

        @Override
        public void close() {
        }
    }
}
