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
package sjws_nettask.admin;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable status snapshot.
 *
 * @param state       lifecycle state
 * @param tcpPort     bound HTTP/1.1 port, or -1
 * @param udpPort     bound HTTP/3 port, or -1
 * @param connections active HTTP/1.1 connections, or -1 if unknown
 * @param uptime      time since start, {@link Duration#ZERO} when stopped
 * @param summary     one-line description (no secrets)
 * @param config      ordered key/value view of the effective configuration (secrets masked)
 */
public record ServerStatus(State state, int tcpPort, int udpPort, int connections, Duration uptime, String summary,
        Map<String, String> config) {

    public enum State { STOPPED, RUNNING, FAILED }

    public ServerStatus {
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    public static ServerStatus stopped(String summary, Map<String, String> config) {
        return new ServerStatus(State.STOPPED, -1, -1, -1, Duration.ZERO, summary, config);
    }
}
