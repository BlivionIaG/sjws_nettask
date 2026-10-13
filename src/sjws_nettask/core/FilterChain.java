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

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable filter chain compiled by {@link RequestFilters#build}. The nodes are allocated once
 * at startup and shared by every request. {@link #handle} does not allocate on the success path
 * after the calling thread's first entry (that entry creates the thread-local once-check, which
 * is then reused). Not used at all when no filters are registered and the terminal is the static
 * file handler: transports then call {@link StaticFileHandler#handle} directly ({@link #dispatch}
 * returns that method reference). A different terminal (a router) is called through {@link Handler}
 * only when one is actually installed.
 * <p>
 * Thread-safe: the nodes are immutable, and the once-check below is thread-local, so concurrent
 * requests do not coordinate. Filters run inline on the calling thread.
 * <p>
 * {@link RequestFilter.Chain#proceed} is once per entry of this chain on that thread. A second
 * call throws {@link IllegalStateException} (the calling filter then becomes a 500) and does not
 * run the rest of the chain again. The check lives only on this path. With no filters the
 * transports never call {@link #handle}, so nothing here is allocated.
 */
public final class FilterChain implements Handler {

    /**
     * One per thread that has entered a chain, reused for the life of that thread. HTTP/1.1
     * handles a connection on one thread, so later requests on it do not allocate. The map is
     * keyed by the shared node, not by the request, and a depth keeps a nested chain (per-route
     * filters wrapping a handler that the outer chain calls) from clearing the outer marks.
     */
    private static final ThreadLocal<Guard> GUARDS = ThreadLocal.withInitial(Guard::new);

    private final RequestFilter.Chain head;

    FilterChain(RequestFilter.Chain head) {
        this.head = head;
    }

    @Override
    public Response handle(Request request) {
        var guard = GUARDS.get();
        int depth = ++guard.depth;
        int streams = guard.streams.size();
        try {
            return this.head.proceed(request);
        } finally {
            release(guard, this.head, depth);
            guard.depth = depth - 1;
            // the returned stream now belongs to the caller (the transport, or an outer chain node)
            while (guard.streams.size() > streams) {
                guard.streams.remove(guard.streams.size() - 1);
            }
        }
    }

    /** Rejects a second {@code proceed} of this node on the thread already inside the chain. */
    private static void enter(RequestFilter.Chain node) {
        var guard = GUARDS.get();
        if (guard.open.putIfAbsent(node, Integer.valueOf(guard.depth)) != null) {
            throw new IllegalStateException("proceed called twice");
        }
    }

    /** Drops marks this entry added. Marks at another depth belong to an outer chain. */
    private static void release(Guard guard, RequestFilter.Chain node, int depth) {
        var mark = Integer.valueOf(depth);
        while (node != null) {
            if (mark.equals(guard.open.get(node))) {
                guard.open.remove(node);
            }
            node = node instanceof FilterNode filter ? filter.next : null;
        }
    }

    /**
     * Called with the response a node returns, or null if it failed. Closes every streamed body
     * produced below the node since {@code mark} that this response does not carry: a filter
     * that replaced or dropped a proxied response must not leak its upstream connection or byte
     * budget. The carried stream, if there is one, is recorded for the nodes above.
     */
    private static Response settle(Guard guard, int mark, Response response) {
        var keep = response == null ? null : response.body();
        for (int i = guard.streams.size() - 1; i >= mark; i--) {
            var dropped = guard.streams.remove(i);
            if (dropped != keep) {
                Body.discard(dropped);
            }
        }
        if (keep instanceof Body.Stream stream) {
            guard.streams.add(stream);
        }
        return response;
    }

    private static final class Guard {
        /** Streamed bodies produced inside the chain on this thread and not yet handed out. Reused. */
        private final java.util.ArrayList<Body.Stream> streams = new java.util.ArrayList<>(4);
        private final IdentityHashMap<RequestFilter.Chain, Integer> open = new IdentityHashMap<>(8);
        private int depth;
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
            enter(this);
            var guard = GUARDS.get();
            int mark = guard.streams.size();
            Response response;
            try {
                response = this.filter.filter(request, this.next);
            } catch (RuntimeException e) {
                System.err.println("Filter " + this.name + " failed: " + e);
                settle(guard, mark, null);
                return failure(request);
            }
            if (response == null) {
                System.err.println("Filter " + this.name + " returned no response");
                settle(guard, mark, null);
                return failure(request);
            }
            return settle(guard, mark, response);
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
            enter(this);
            var guard = GUARDS.get();
            int mark = guard.streams.size();
            Response response;
            try {
                response = this.terminal.handle(request);
            } catch (RuntimeException e) {
                System.err.println("Request handler failed: " + e);
                return failure(request);
            }
            if (response == null) {
                System.err.println("Request handler returned no response");
                return failure(request);
            }
            return settle(guard, mark, response);
        }
    }

    static Response failure(Request request) {
        return StaticFileHandler.error(500, request != null && request.headOnly());
    }
}
