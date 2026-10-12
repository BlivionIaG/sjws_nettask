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
package sjws_nettask.ha;

import java.util.function.Supplier;

import sjws_nettask.core.Handler;

/** HA state attached to one server. Absent entirely when the server is started without it. */
public final class HaRuntime {

    private final HaSettings settings;
    private final SnapshotHolder<Handler> snapshots;
    private final HealthBoard health;
    private final Lifecycle lifecycle;
    private final HaHandler handler;

    private HaRuntime(HaSettings settings, SnapshotHolder<Handler> snapshots, HealthBoard health, Lifecycle lifecycle,
            HaHandler handler) {
        this.settings = settings;
        this.snapshots = snapshots;
        this.health = health;
        this.lifecycle = lifecycle;
        this.handler = handler;
    }

    public static HaRuntime create(HaSettings settings, Handler files) {
        return create(settings, () -> files);
    }

    /**
     * {@code configHandler} is the config snapshot (one volatile read). A later
     * {@link SnapshotHolder#publish} replaces it for cluster generations. The holder starts empty
     * so reload does not maintain a second copy of the same handler.
     */
    public static HaRuntime create(HaSettings settings, Supplier<Handler> configHandler) {
        var health = new HealthBoard(settings.requireQuorum());
        health.onChange(() -> settings.metrics().gauge("ha_ready", health.readyNow() ? 1 : 0));
        var holder = new SnapshotHolder<Handler>();
        health.setSnapshotLoaded(true);
        var lifecycle = new Lifecycle(health, settings);
        return new HaRuntime(settings, holder, health, lifecycle, new HaHandler(holder, configHandler, health));
    }

    public HaSettings settings() {
        return this.settings;
    }

    public SnapshotHolder<Handler> snapshots() {
        return this.snapshots;
    }

    public HealthBoard health() {
        return this.health;
    }

    public Lifecycle lifecycle() {
        return this.lifecycle;
    }

    public Handler handler() {
        return this.handler;
    }
}
