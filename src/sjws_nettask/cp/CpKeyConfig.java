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

import java.nio.file.Path;

/**
 * Where {@link KeyRing#load} finds the control-plane key material. Paths only: key bytes never
 * come from config, env or properties.
 *
 * @param keyDir {@code online.key}, {@code trust.bundle} and the second {@code trust.state} copy; mode 0700
 * @param rootPub pinned root SPKI (DER or PEM), outside {@code keyDir} and {@code stateDir}
 * @param stateDir node state; holds the primary {@code trust.state}
 * @param rootPubSha256 optional install-time pin: hex SHA-256 of the root SPKI (colons ignored); null for none
 * @param insecureSkipPermissionChecks dev only, config file only: skip owner/mode checks (symlink and
 *        regular-file checks stay)
 * @param minBundleSerial lowest acceptable {@code bundleSerial}; also the explicit first-start seed when
 *        no {@code trust.state} exists. 0 means none
 */
public record CpKeyConfig(Path keyDir, Path rootPub, Path stateDir, String rootPubSha256,
        boolean insecureSkipPermissionChecks, long minBundleSerial) {

    public CpKeyConfig {
        if (keyDir == null || rootPub == null || stateDir == null) {
            throw new IllegalArgumentException("cp keys require keyDir, rootPub and stateDir");
        }
        if (minBundleSerial < 0) {
            throw new IllegalArgumentException("cp.minBundleSerial must be >= 0");
        }
        if (rootPubSha256 != null && rootPubSha256.isBlank()) {
            rootPubSha256 = null;
        }
    }
}
