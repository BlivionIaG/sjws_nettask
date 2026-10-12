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

import sjws_nettask.core.FilterChain;
import sjws_nettask.core.Handler;
import sjws_nettask.core.StaticFileHandler;

/**
 * An additional listener started by {@link SjwsServer} next to HTTP/1.1 (today: HTTP/3).
 * Implementations serve requests through the shared {@link StaticFileHandler}, or through the
 * {@link Handler} the server passes when filters or another terminal (a router) are installed.
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

    /**
     * Creates (but does not start) a transport.
     * <p>
     * {@link #create(ServerConfig, StaticFileHandler)} is the single abstract method so existing
     * factories keep compiling. {@link SjwsServer} calls the four-argument form. The defaults drop
     * the chain and the terminal (test doubles that do not serve requests). A transport that
     * serves requests must override the four-argument form and dispatch with
     * {@link FilterChain#dispatch(StaticFileHandler, Handler, FilterChain)} so a null chain and a
     * null terminal stay a direct {@link StaticFileHandler#handle} call.
     */
    @FunctionalInterface
    interface Factory {
        Transport create(ServerConfig config, StaticFileHandler handler);

        /**
         * @param chain compiled filters, or null when none are registered
         */
        default Transport create(ServerConfig config, StaticFileHandler handler, FilterChain chain) {
            return create(config, handler);
        }

        /**
         * {@link SjwsServer} calls this. The default forwards to the three-argument form so a factory
         * that already overrides that form still receives the chain. It does not call back into this
         * method.
         *
         * @param chain compiled filters, or null when none are registered. When non-null the chain
         *        already wraps {@code terminal}
         * @param terminal handler HTTP/1.1 will call, or null to call {@code handler} directly.
         *        Dropped by this default; a serving transport overrides this method to use it
         */
        default Transport create(ServerConfig config, StaticFileHandler handler, FilterChain chain, Handler terminal) {
            return create(config, handler, chain);
        }
    }
}
