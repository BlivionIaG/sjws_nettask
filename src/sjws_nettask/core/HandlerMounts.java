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
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Startup registry of prefix handlers. {@link HandlerContributor#contribute} calls {@link #mount}
 * only while the server is starting. Overlapping or duplicate patterns fail that startup.
 * {@link #install} then returns either {@code fallback} itself, when nothing was mounted, or one
 * immutable dispatcher in front of it. The dispatcher is not built, and is not on the request
 * path, when the registry stays empty.
 * <p>
 * A mounted handler may itself be {@link RequestFilters#wrapping a filter chain}. Filters
 * registered on the server wrap the dispatcher, so they see mounted requests and the fallback.
 */
public final class HandlerMounts {

    private final List<RoutePattern> patterns = new ArrayList<>();
    private final List<Handler> handlers = new ArrayList<>();
    private boolean closed;

    private HandlerMounts() {
    }

    /**
     * Mounts {@code prefix} as a prefix pattern: the path equals it or sits under it.
     * {@code /swarm/v1}, {@code /swarm/v1/} and {@code /swarm/v1/*} are the same mount.
     * An exact path uses {@link #mount(RoutePattern, Handler)}.
     */
    public void mount(String prefix, Handler handler) {
        Objects.requireNonNull(handler, "handler");
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix is required");
        }
        var text = prefix.trim();
        if (text.indexOf('?') >= 0 || text.indexOf('#') >= 0) {
            throw new IllegalArgumentException("prefix must not contain ? or #");
        }
        if (!text.endsWith("/*")) {
            if (text.length() > 1 && text.endsWith("/")) {
                text = text.substring(0, text.length() - 1);
            }
            if (!text.startsWith("/")) {
                throw new IllegalArgumentException("prefix must start with /");
            }
            text = text + "/*";
        }
        mount(RoutePattern.parse(text), handler);
    }

    /**
     * Mounts one already-parsed pattern. {@code *} overlaps every other mount. Two patterns that
     * can match the same well-formed path are rejected.
     */
    public void mount(RoutePattern pattern, Handler handler) {
        if (this.closed) {
            throw new IllegalStateException("mounts are closed");
        }
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(handler, "handler");
        for (var existing : this.patterns) {
            if (existing.overlaps(pattern)) {
                throw new IllegalArgumentException("mounts overlap: " + existing + " and " + pattern);
            }
        }
        this.patterns.add(pattern);
        this.handlers.add(handler);
    }

    /**
     * Service providers of {@link HandlerContributor}, then {@code explicit}. Both are collected
     * here so a caller that passes an empty explicit list still sees providers. A null explicit
     * list means none. A null element is rejected.
     */
    public static List<HandlerContributor> discover(Iterable<HandlerContributor> explicit) {
        return discover(HandlerContributor.class.getClassLoader(), explicit);
    }

    /** As {@link #discover(Iterable)}, reading the service file through {@code loader}. */
    public static List<HandlerContributor> discover(ClassLoader loader, Iterable<HandlerContributor> explicit) {
        var found = new ArrayList<HandlerContributor>();
        ServiceLoader.load(HandlerContributor.class, loader).forEach(found::add);
        if (explicit != null) {
            for (var contributor : explicit) {
                if (contributor == null) {
                    throw new IllegalArgumentException("contributor");
                }
                found.add(contributor);
            }
        }
        return found;
    }

    /**
     * Asks each contributor to mount, then compiles. An empty contribution returns {@code fallback}
     * unchanged. Contributors run in list order; the first matching mount wins only because
     * overlapping mounts have already been rejected.
     */
    public static Handler install(Iterable<HandlerContributor> contributors, Handler fallback) {
        Objects.requireNonNull(contributors, "contributors");
        Objects.requireNonNull(fallback, "fallback");
        var mounts = new HandlerMounts();
        for (var contributor : contributors) {
            if (contributor == null) {
                throw new IllegalArgumentException("contributor");
            }
            contributor.contribute(mounts);
        }
        mounts.closed = true;
        if (mounts.patterns.isEmpty()) {
            return fallback;
        }
        return new Dispatch(mounts.patterns.toArray(RoutePattern[]::new),
                mounts.handlers.toArray(Handler[]::new), fallback);
    }

    /** Immutable prefix table. Plain paths match without allocating. */
    private static final class Dispatch implements Handler {

        private final RoutePattern[] patterns;
        private final Handler[] handlers;
        private final Handler fallback;

        Dispatch(RoutePattern[] patterns, Handler[] handlers, Handler fallback) {
            this.patterns = patterns;
            this.handlers = handlers;
            this.fallback = fallback;
        }

        @Override
        public Response handle(Request request) {
            var target = request.target();
            for (int i = 0; i < this.patterns.length; i++) {
                if (this.patterns[i].matches(target)) {
                    return this.handlers[i].handle(request);
                }
            }
            return this.fallback.handle(request);
        }
    }
}
