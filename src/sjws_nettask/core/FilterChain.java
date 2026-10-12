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
package sjws_nettask.core;

import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable filter chain compiled by {@link RequestFilters#build}. The nodes are allocated once
 * at startup and shared by every request, so {@link #handle} allocates nothing on the success
 * path. Not used at all when no filters are registered and the terminal is the static file
 * handler: transports then call {@link StaticFileHandler#handle} directly ({@link #dispatch}
 * returns that method reference). A different terminal (a router) is called through {@link Handler}
 * only when one is actually installed.
 * <p>
 * Thread-safe. Filters run inline on the calling thread.
 */
public final class FilterChain implements Handler {

    private final RequestFilter.Chain head;

    FilterChain(RequestFilter.Chain head) {
        this.head = head;
    }

    @Override
    public Response handle(Request request) {
        return this.head.proceed(request);
    }

    /**
     * Startup choice for a transport that dispatches through a {@link Function}.
     * Equivalent to {@link #dispatch(StaticFileHandler, Handler, FilterChain) dispatch(handler, null, chain)}.
     */
    public static Function<Request, Response> dispatch(StaticFileHandler handler, FilterChain chain) {
        return dispatch(handler, null, chain);
    }

    /**
     * Startup choice for a transport that dispatches through a {@link Function}. Chosen once.
     * A non-null chain returns {@code chain::handle} (the chain already closes over its terminal).
     * Otherwise a null terminal, or the static file handler itself, returns {@code files::handle}
     * so the chain class is not on that call path. Any other terminal returns {@code terminal::handle}.
     * <p>
     * A future HTTP/2 adapter should do the same with its concrete handler type when both
     * {@code chain} and {@code terminal} are absent, so the unbound path stays a direct call.
     */
    public static Function<Request, Response> dispatch(StaticFileHandler files, Handler terminal, FilterChain chain) {
        Objects.requireNonNull(files, "handler");
        if (chain != null) {
            return chain::handle;
        }
        if (terminal == null || terminal == files) {
            return files::handle;
        }
        return terminal::handle;
    }

    /** One registered filter. Catches its failures so the connection stays up. */
    static final class FilterNode implements RequestFilter.Chain {

        private final String name;
        private final RequestFilter filter;
        private final RequestFilter.Chain next;

        FilterNode(String name, RequestFilter filter, RequestFilter.Chain next) {
            this.name = name;
            this.filter = filter;
            this.next = next;
        }

        @Override
        public Response proceed(Request request) {
            Response response;
            try {
                response = this.filter.filter(request, this.next);
            } catch (RuntimeException e) {
                System.err.println("Filter " + this.name + " failed: " + e);
                return failure(request);
            }
            if (response == null) {
                System.err.println("Filter " + this.name + " returned no response");
                return failure(request);
            }
            return response;
        }
    }

    /** Skips {@link #delegate} when the request path does not match; the rest of the chain still runs. */
    static final class Routed implements RequestFilter {

        private final RoutePattern pattern;
        private final RequestFilter delegate;

        Routed(RoutePattern pattern, RequestFilter delegate) {
            this.pattern = pattern;
            this.delegate = delegate;
        }

        @Override
        public Response filter(Request request, Chain next) {
            if (!this.pattern.matches(request.target())) {
                return next.proceed(request);
            }
            return this.delegate.filter(request, next);
        }
    }

    /**
     * End of the chain. A handler failure becomes a 500 that earlier filters can still observe,
     * instead of escaping into the transport and dropping the connection.
     */
    static final class TerminalNode implements RequestFilter.Chain {

        private final Handler terminal;

        TerminalNode(Handler terminal) {
            this.terminal = terminal;
        }

        @Override
        public Response proceed(Request request) {
            try {
                return this.terminal.handle(request);
            } catch (RuntimeException e) {
                System.err.println("Request handler failed: " + e);
                return failure(request);
            }
        }
    }

    static Response failure(Request request) {
        return StaticFileHandler.error(500, request != null && request.headOnly());
    }
}
