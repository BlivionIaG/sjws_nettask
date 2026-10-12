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

import sjws_nettask.core.StaticFileHandler;

/**
 * An additional listener started by {@link SjwsServer} next to HTTP/1.1 (today: HTTP/3).
 * Implementations serve requests through the shared {@link StaticFileHandler}.
 */
public interface Transport extends AutoCloseable {

    /** Short name for logs, e.g. "HTTP/3". */
    String name();

    /**
     * Binds and starts serving. Must either succeed completely or throw and leave nothing
     * running (the server then continues without this transport).
     *
     * @return this
     */
    Transport start() throws Exception;

    /** @return the bound port; only valid after a successful {@link #start()} */
    int port();

    /**
     * Refuses new connections from now on; existing ones keep being served until {@link #close()}.
     * Called at the moment the HTTP/1.1 listener stops, so no protocol accepts clients longer than
     * the other. Idempotent.
     */
    default void stopAccepting() {
    }

    /** Stops serving and releases the port. Idempotent; safe after a failed start. */
    @Override
    void close();

    /** Creates (but does not start) a transport. */
    @FunctionalInterface
    interface Factory {
        Transport create(ServerConfig config, StaticFileHandler handler);
    }
}
