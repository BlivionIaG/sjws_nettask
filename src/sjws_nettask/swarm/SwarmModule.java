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
package sjws_nettask.swarm;

import sjws_nettask.OptionalModule;
import sjws_nettask.core.HandlerContributor;
import sjws_nettask.core.HandlerMounts;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Entry point of the optional agent-swarm layer (docs/design/agent-swarm.md). Loaded only by name from
 * {@code sjws_nettask.OptionalModules} when the swarm is enabled; nothing outside this package imports it.
 * <p>
 * Phase S0 delivers the guard rails and the {@code /swarm/v1/*} mount. This class starts no thread,
 * opens no partition and binds no port. Requests under that prefix get 501 until the task, lease and
 * reply services arrive in S1.
 */
public final class SwarmModule implements OptionalModule, HandlerContributor {

    private volatile boolean closed;

    public SwarmModule() {
        // S0: no services yet.
    }

    @Override
    public void contribute(HandlerMounts mounts) {
        mounts.mount("/swarm/v1", SwarmModule::unavailable);
    }

    /** Placeholder for the S1 task API. */
    private static Response unavailable(Request request) {
        return StaticFileHandler.error(501, request.headOnly());
    }

    public boolean closed() {
        return this.closed;
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
