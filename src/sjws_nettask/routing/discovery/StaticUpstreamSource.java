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
package sjws_nettask.routing.discovery;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The default {@link UpstreamSource}: holds the set from the config file (or whatever the control
 * plane last sent through {@link #update}). Thread-safe; updates are serialized, reads are one
 * volatile load.
 */
public final class StaticUpstreamSource implements UpstreamSource {

    private final String service;
    private final List<Consumer<UpstreamSet>> listeners = new CopyOnWriteArrayList<>();
    private volatile UpstreamSet current;
    private long seen = Long.MIN_VALUE;
    private boolean fed; // a control-plane update was applied: config reloads no longer override it

    public StaticUpstreamSource(String service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /** A source that starts with {@code initial} (validated like an update). */
    public StaticUpstreamSource(UpstreamSet initial) {
        this(initial.service());
        apply(initial, false);
    }

    public String service() {
        return this.service;
    }

    @Override
    public UpstreamSet current() {
        return this.current;
    }

    @Override
    public AutoCloseable subscribe(Consumer<UpstreamSet> listener) {
        Objects.requireNonNull(listener, "listener");
        this.listeners.add(listener);
        return () -> this.listeners.remove(listener);
    }

    @Override
    public boolean update(UpstreamSet set) {
        return apply(set, true);
    }

    /**
     * Config reload: replaces the set unless the control plane has fed this source, ignoring the
     * version order (config sets are not versioned against the control plane's).
     *
     * @return true when applied
     */
    public boolean reloadFromConfig(UpstreamSet set) {
        Objects.requireNonNull(set, "set");
        UpstreamSet applied;
        synchronized (this) {
            if (this.fed || !set.service().equals(this.service)) {
                return false;
            }
            var live = set.withoutDead();
            if (live.size() == 0 || live.equals(this.current)) {
                return false;
            }
            this.current = live;
            applied = live;
        }
        publish(applied);
        return true;
    }

    /** True once a control-plane update has been applied. */
    public synchronized boolean controlPlaneFed() {
        return this.fed;
    }

    private boolean apply(UpstreamSet set, boolean fromControlPlane) {
        Objects.requireNonNull(set, "set");
        if (!set.service().equals(this.service)) {
            throw new IllegalArgumentException("set for '" + set.service() + "' offered to '" + this.service + "'");
        }
        UpstreamSet applied;
        synchronized (this) {
            if (set.version() <= this.seen) {
                return false;
            }
            this.seen = set.version();
            var live = set.withoutDead();
            if (live.size() == 0) {
                return false; // keep the last non-empty set (or none, which answers 503)
            }
            this.current = live;
            this.fed |= fromControlPlane;
            applied = live;
        }
        publish(applied);
        return true;
    }

    private void publish(UpstreamSet set) {
        for (var l : this.listeners) {
            try {
                l.accept(set);
            } catch (RuntimeException e) {
                System.err.println("WARN: upstream set listener for " + this.service + " failed: " + e);
            }
        }
    }
}
