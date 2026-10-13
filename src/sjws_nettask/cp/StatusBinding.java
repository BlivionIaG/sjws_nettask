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
package sjws_nettask.cp;

import sjws_nettask.service.ConnectionState;
import sjws_nettask.service.ControlPlaneBinding;

/**
 * What {@code --status} shows after {@link sjws_nettask.service.NodeEnrollment#attach}. The request
 * path does not read it. {@code leader} is reported only once {@code leaderReady()} is true.
 */
public final class StatusBinding implements ControlPlaneBinding {

    private final String connection;
    private final long version;

    public StatusBinding(String connection, long version) {
        this.connection = ConnectionState.canonical(connection);
        this.version = Math.max(0L, version);
    }

    /** A control voter. Not ready to sign until {@code leaderReady}. */
    public static StatusBinding control(boolean elected, boolean leaderReady, long version) {
        if (leaderReady) {
            return new StatusBinding("leader", version);
        }
        if (elected) {
            return new StatusBinding("connecting", version);
        }
        return new StatusBinding("follower", version);
    }

    /** A data node. Down with no snapshot stays {@code pending}; down with last-good is {@code disconnected}. */
    public static StatusBinding data(boolean connected, boolean hasLastGood, long version) {
        if (connected) {
            return new StatusBinding("connected", version);
        }
        if (hasLastGood) {
            return new StatusBinding("disconnected", version);
        }
        return new StatusBinding("pending", 0L);
    }

    @Override
    public String connectionState() {
        return connection;
    }

    @Override
    public long configVersion() {
        return version;
    }
}
