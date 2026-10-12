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
package sjws_nettask.config;

import java.util.concurrent.atomic.AtomicReference;
import sjws_nettask.core.Handler;

/**
 * The request path's only view of configuration: one volatile read of an immutable snapshot.
 * Reload publishes a new snapshot with {@link #set}; it never mutates the previous one.
 */
public final class LiveConfig {

    private final AtomicReference<ConfigSnapshot> current;

    public LiveConfig(ConfigSnapshot initial) {
        this.current = new AtomicReference<>(initial);
    }

    public ConfigSnapshot get() {
        return this.current.get();
    }

    /** One volatile read, then the prebuilt handler. */
    public Handler handler() {
        return this.current.get().handler();
    }

    public void set(ConfigSnapshot snapshot) {
        this.current.set(snapshot);
    }
}
