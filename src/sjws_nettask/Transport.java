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
import sjws_nettask.observe.ObserverSlot;
import sjws_nettask.observe.RequestObserver;

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

    /** How long {@link #close()} may wait for responses in progress. Default: ignored. */
    default void shutdownTimeout(java.time.Duration timeout) {
    }

    /** Stops serving and releases the port. Idempotent; safe after a failed start. */
    @Override
    void close();

    /** @return connections being served (0 when this transport does not track them) */
    default int connections() {
        return 0;
    }

    /**
     * Installs the shared observer slot before {@link #start()}, or ignores it. HTTP/3 keeps the
     * slot so a later admin snapshot is visible. A null slot leaves the transport's own default.
     */
    default Transport useSlot(ObserverSlot slot) {
        return this;
    }

    /**
     * Atomically replaces the request handler after {@link #start()}. Implementors own the swap
     * (one volatile write / CAS), body-attach policy, and any request-size limits for the new
     * handler; the default no-ops. HTTP/2 and HTTP/3 install the chain. No effect when the observe
     * filter was already compiled into the startup chain.
     */
    default void armHandler(Handler handler) {
    }

    /**
     * Creates (but does not start) a transport.
     * <p>
     * {@link #create(ServerConfig, StaticFileHandler)} is the single abstract method so existing
     * factories keep compiling. {@link SjwsServer} calls the five-argument form (filters plus the
     * shared {@link ObserverSlot}). Defaults drop what they do not understand. A transport that
     * serves requests overrides the four-argument filter form and
     * {@link FilterChain#dispatch(StaticFileHandler, Handler, FilterChain)}, and
     * {@link #useSlot(ObserverSlot)} when it shares the observer.
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

        /** Ignores the observer unless overridden. */
        default Transport create(ServerConfig config, StaticFileHandler handler, RequestObserver observer) {
            return create(config, handler);
        }

        /**
         * @param chain compiled filters, or null when none are registered. When non-null the chain
         *        already wraps {@code terminal}
         * @param terminal handler HTTP/1.1 will call, or null to call {@code handler} directly
         */
        default Transport create(ServerConfig config, StaticFileHandler handler, FilterChain chain, Handler terminal) {
            return create(config, handler, chain);
        }

        /**
         * Freezes the observer currently in the slot. HTTP/3's factory keeps the slot itself so a
         * later replacement is visible.
         */
        default Transport create(ServerConfig config, StaticFileHandler handler, ObserverSlot slot) {
            return create(config, handler, slot.get());
        }

        /**
         * What {@link SjwsServer} calls. Forwards the chain to the four-argument form, then
         * {@link Transport#useSlot(ObserverSlot)}. Does not call back into this method.
         */
        default Transport create(ServerConfig config, StaticFileHandler handler, FilterChain chain, Handler terminal,
                ObserverSlot slot) {
            return create(config, handler, chain, terminal).useSlot(slot);
        }
    }
}
