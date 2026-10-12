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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registration entry point for request filters. Fill it before
 * {@link sjws_nettask.SjwsServer#start(sjws_nettask.ServerConfig, RequestFilters)}, then the
 * server compiles it once. An empty registry compiles to null. The server then calls the
 * terminal handler directly, which is a concrete {@link StaticFileHandler#handle} when that
 * terminal is the static file handler.
 * <p>
 * Not thread-safe to mutate. The {@link FilterChain} from {@link #build} is immutable and is
 * safe to share across requests. Registering more filters after {@code build} does not change
 * a chain already built.
 * <p>
 * The configuration workstream owns the file format. It should bind each implementation with
 * {@link #factory} and pass the parsed {@code middleware} list to {@link #addAll}. See
 * {@link MiddlewareSpec} for the fields that list must produce (name, order, route, options).
 */
public final class RequestFilters {

    private final Map<String, FilterFactory> factories = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private int sequence;

    private RequestFilters() {
    }

    public static RequestFilters create() {
        return new RequestFilters();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }

    /**
     * Binds a configuration {@code name} to a factory. Names are trimmed. The same name cannot
     * be bound twice.
     */
    public RequestFilters factory(String name, FilterFactory factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        var id = name.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("filter name is required");
        }
        if (this.factories.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("filter already registered: " + id);
        }
        return this;
    }

    /** Appends a global filter at order 0. Earlier equal-order filters stay further out. */
    public RequestFilters add(RequestFilter filter) {
        return add(0, null, filter);
    }

    /** @param order lower runs first on the way in and last on the way out */
    public RequestFilters add(int order, RequestFilter filter) {
        return add(order, null, filter);
    }

    /**
     * @param route {@link RoutePattern} syntax; null, blank or {@code *} matches every request
     */
    public RequestFilters add(int order, String route, RequestFilter filter) {
        Objects.requireNonNull(filter, "filter");
        var pattern = RoutePattern.parse(route);
        int seq = this.sequence++;
        this.entries.add(new Entry(order, seq, "filter#" + seq, pattern.isAll() ? null : pattern, filter, null));
        return this;
    }

    /**
     * One configuration entry. The factory for {@link MiddlewareSpec#name()} is resolved at
     * {@link #build}, so factories may be bound before or after the specs are added.
     */
    public RequestFilters add(MiddlewareSpec spec) {
        Objects.requireNonNull(spec, "spec");
        var pattern = RoutePattern.parse(spec.route());
        int seq = this.sequence++;
        this.entries.add(new Entry(spec.order(), seq, spec.name(), pattern.isAll() ? null : pattern, null, spec));
        return this;
    }

    /**
     * Copies {@code other}'s factories and entries after whatever is already registered here.
     * Order values are kept. New sequence numbers follow this registry, so a filter added here
     * first stays further out when the order is equal.
     */
    public RequestFilters include(RequestFilters other) {
        Objects.requireNonNull(other, "filters");
        for (var factory : other.factories.entrySet()) {
            this.factories.putIfAbsent(factory.getKey(), factory.getValue());
        }
        for (var entry : other.entries) {
            int seq = this.sequence++;
            this.entries.add(new Entry(entry.order, seq, entry.name, entry.route, entry.filter, entry.spec));
        }
        return this;
    }

    /** The whole {@code middleware} list. Null or empty adds nothing. */
    public RequestFilters addAll(List<MiddlewareSpec> specs) {
        if (specs != null) {
            for (var spec : specs) {
                add(spec);
            }
        }
        return this;
    }

    /**
     * @return a chain that ends at {@code terminal}, or null when nothing was registered
     *         (callers must then invoke {@code terminal} directly)
     * @throws IllegalArgumentException unknown filter name, or a factory returned null
     */
    public FilterChain build(Handler terminal) {
        Objects.requireNonNull(terminal, "terminal");
        if (this.entries.isEmpty()) {
            return null;
        }
        var ordered = new ArrayList<>(this.entries);
        ordered.sort(Comparator.comparingInt(Entry::order).thenComparingInt(Entry::sequence));
        RequestFilter.Chain next = new FilterChain.TerminalNode(terminal);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            var entry = ordered.get(i);
            var filter = entry.filter != null ? entry.filter : create(entry.spec);
            if (entry.route != null) {
                filter = new FilterChain.Routed(entry.route, filter);
            }
            next = new FilterChain.FilterNode(entry.name, filter, next);
        }
        return new FilterChain(next);
    }

    /**
     * Handler to install for one route, or for the whole server. An empty registry returns
     * {@code terminal} itself, so a route with no filters pays nothing. Otherwise the returned
     * chain ends at {@code terminal}.
     */
    public Handler wrapping(Handler terminal) {
        var chain = build(terminal);
        return chain == null ? terminal : chain;
    }

    private RequestFilter create(MiddlewareSpec spec) {
        var factory = this.factories.get(spec.name());
        if (factory == null) {
            throw new IllegalArgumentException("no filter factory named \"" + spec.name() + "\"");
        }
        var created = factory.create(spec);
        if (created == null) {
            throw new IllegalArgumentException("filter factory \"" + spec.name() + "\" returned null");
        }
        return created;
    }

    private record Entry(int order, int sequence, String name, RoutePattern route, RequestFilter filter,
            MiddlewareSpec spec) {
    }
}
