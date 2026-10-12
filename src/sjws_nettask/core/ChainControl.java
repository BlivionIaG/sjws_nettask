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
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Atomic holder for one compiled filter snapshot. {@link #handle} reads a single volatile
 * reference and then runs that snapshot; it does not allocate, take a lock, or read the document
 * again. {@link #apply} validates and compiles on the calling thread and publishes the new
 * snapshot only after that succeeds. A thrown exception leaves the previous snapshot in place.
 * A request that has already loaded a snapshot finishes on it, including a request inside
 * {@code proceed} when the publish happens.
 * <p>
 * The version must be greater than or equal to the version currently published. A lower version
 * is rejected and the live chain is unchanged. The first published version may be 0; before any
 * successful apply the holder is at version {@code -1} and, until {@link sjws_nettask.SjwsServer}
 * is told to serve it, is not on the request path at all.
 * <p>
 * Node labels are an input to compilation, not a per-request check. {@link #nodeLabels} affects
 * the next successful apply.
 */
public final class ChainControl implements Handler {

    private final ReentrantLock publish = new ReentrantLock();
    private final RequestFilters registry;
    private final Handler terminal;
    private final Runnable onLive;
    private final Supplier<RequestFilter> outerSource;
    private volatile Set<String> nodeLabels;
    private volatile RequestFilter pinned;
    private volatile FilterDocument document;
    private volatile Snapshot current;
    private volatile boolean live;

    private ChainControl(RequestFilters registry, Set<String> nodeLabels, Handler terminal,
            Supplier<RequestFilter> outerSource, Runnable onLive) {
        this.registry = registry;
        this.nodeLabels = nodeLabels;
        this.terminal = terminal;
        this.outerSource = outerSource;
        this.onLive = onLive;
        this.current = new Snapshot(-1, terminal);
    }

    /**
     * @param registry factories used to compile each document; not mutated, and not read on the
     *        request path. Do not bind or replace a factory concurrently with {@link #apply}.
     * @param nodeLabels labels of this data-plane node; copied
     * @param terminal handler under the chain. An empty document calls it directly, and a
     *        {@link StaticFileHandler} is still a direct {@code handle} call
     * @param outer filter inserted at order 0 on every compiled snapshot, or a supplier that returns
     *        null when there is nothing to insert. Called from {@link #apply}, never from {@link #handle}
     * @param onLive invoked once, after the first snapshot is published, on the applying thread
     */
    public static ChainControl open(RequestFilters registry, Set<String> nodeLabels, Handler terminal,
            Supplier<RequestFilter> outer, Runnable onLive) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(terminal, "terminal");
        var labels = Set.copyOf(nodeLabels == null ? Set.<String>of() : nodeLabels);
        return new ChainControl(registry, labels, terminal, outer, onLive);
    }

    /** @return the published config version, or {@code -1} before the first successful apply */
    public long version() {
        return this.current.version;
    }

    /** @return the document last published, or null before the first successful apply */
    public FilterDocument document() {
        return this.document;
    }

    /** @return true after a document has been published onto the server */
    public boolean isLive() {
        return this.live;
    }

    /** The handler the next request will enter. Stable for the lifetime of that snapshot. */
    public Handler published() {
        return this.current.handler;
    }

    /**
     * Replaces the labels used the next time a document is compiled. Does not publish a snapshot
     * by itself; apply the same document again (an equal version is allowed) to rebuild.
     */
    public void nodeLabels(Set<String> nodeLabels) {
        this.nodeLabels = Set.copyOf(nodeLabels == null ? Set.<String>of() : nodeLabels);
    }

    /**
     * Inserts {@code filter} at order 0 on the next compile. If a document is already live, it is
     * recompiled and published at the same version. A second call is ignored.
     */
    public void pinOuter(RequestFilter filter) {
        Objects.requireNonNull(filter, "filter");
        this.publish.lock();
        try {
            if (this.pinned != null) {
                return;
            }
            this.pinned = filter;
            var doc = this.document;
            if (doc == null) {
                return;
            }
            this.current = compile(doc);
        } finally {
            this.publish.unlock();
        }
    }

    /**
     * Parses {@code json} and {@link #apply(FilterDocument)}. A parse failure publishes nothing.
     */
    public FilterDocument apply(String json) {
        return apply(FilterDocument.fromJson(json));
    }

    /** Parses UTF-8 {@code json}. Malformed bytes publish nothing. */
    public FilterDocument apply(byte[] json) {
        return apply(FilterDocument.fromJson(json));
    }

    /**
     * Validates {@code document}, compiles it, and publishes the snapshot with one volatile write.
     * In-flight requests keep the snapshot they already loaded.
     *
     * @throws IllegalArgumentException the document is older than the live version, or it did not
     *         compile. The live snapshot is unchanged
     */
    public FilterDocument apply(FilterDocument document) {
        Objects.requireNonNull(document, "document");
        this.publish.lock();
        try {
            if (document.version() < this.current.version) {
                throw new IllegalArgumentException(
                        "stale config version " + document.version() + " < " + this.current.version);
            }
            var snapshot = compile(document);
            this.document = document;
            this.current = snapshot;
            if (!this.live) {
                this.live = true;
                if (this.onLive != null) {
                    // Must not call apply or pinOuter. The publish lock is held.
                    this.onLive.run();
                }
            }
            return document;
        } finally {
            this.publish.unlock();
        }
    }

    /**
     * One volatile read, then the snapshot's handler. No allocation. A {@link StaticFileHandler}
     * terminal is invoked directly when the snapshot has no filters.
     */
    @Override
    public Response handle(Request request) {
        return this.current.dispatch(request);
    }

    private Snapshot compile(FilterDocument document) {
        var chain = this.registry.compile(document, this.nodeLabels, this.terminal, outerFilter());
        return new Snapshot(document.version(), chain == null ? this.terminal : chain);
    }

    /** Pinned filter wins; otherwise the supplier. Not called from {@link #handle}. */
    private RequestFilter outerFilter() {
        var pinned = this.pinned;
        if (pinned != null) {
            return pinned;
        }
        return this.outerSource == null ? null : this.outerSource.get();
    }

    private static final class Snapshot {

        private final long version;
        private final Handler handler;

        Snapshot(long version, Handler handler) {
            this.version = version;
            this.handler = handler;
        }

        Response dispatch(Request request) {
            var handler = this.handler;
            if (handler instanceof StaticFileHandler files) {
                return files.handle(request);
            }
            return handler.handle(request);
        }
    }
}
