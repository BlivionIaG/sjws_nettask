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

import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;

/**
 * Request path when HA is attached. Probes return a precomputed response. Every other
 * request takes one volatile snapshot read and uses that handler for the rest of the call,
 * so a publish mid-request cannot change what this call serves.
 */
public final class HaHandler implements Handler {

    private final SnapshotHolder<Handler> snapshots;
    private final HealthBoard health;

    public HaHandler(SnapshotHolder<Handler> snapshots, HealthBoard health) {
        this.snapshots = snapshots;
        this.health = health;
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        if (isProbe(target, "/healthz")) {
            return this.health.health(request.headOnly());
        }
        if (isProbe(target, "/readyz")) {
            return this.health.ready(request.headOnly());
        }
        var snap = this.snapshots.current();
        if (snap == null) {
            return this.health.ready(request.headOnly());
        }
        return snap.value().handle(request);
    }

    /** Path equals {@code probe}, or that path plus a query. No allocation. */
    static boolean isProbe(String target, String probe) {
        int n = probe.length();
        if (target.length() < n || !target.startsWith(probe)) {
            return false;
        }
        if (target.length() == n) {
            return true;
        }
        char next = target.charAt(n);
        return next == '?' || next == '#';
    }
}
