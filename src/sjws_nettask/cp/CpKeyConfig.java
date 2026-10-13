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
import java.util.Objects;

/**
 * Where {@link KeyRing#load(CpKeyConfig)} finds the control-plane key material (control-plane-hosting.md §2).
 * Agreed with Security: hosting owns this record and its parsing ({@link CpConfig}); Security owns
 * {@code KeyRing.load}.
 *
 * @param keyDir directory holding {@code trust.bundle} and {@code online.key}
 * @param rootPub pinned root public key, outside {@code stateDir} and {@code keyDir}
 * @param stateDir CP state directory; {@code trust.state} (anti-rollback floor) lives here
 * @param rootPubSha256 optional lowercase hex SHA-256 pin of the root SPKI, or null
 * @param insecureSkipPermissionChecks dev-only; true only when the config file says so explicitly
 * @param minBundleSerial lowest {@code bundleSerial} accepted even with no {@code trust.state} (default 0)
 */
public record CpKeyConfig(Path keyDir, Path rootPub, Path stateDir, String rootPubSha256,
        boolean insecureSkipPermissionChecks, long minBundleSerial) {

    public CpKeyConfig {
        Objects.requireNonNull(keyDir, "keyDir");
        Objects.requireNonNull(rootPub, "rootPub");
        Objects.requireNonNull(stateDir, "stateDir");
        if (rootPubSha256 != null && !rootPubSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("cp.rootPubSha256 must be 64 lowercase hex characters");
        }
        if (minBundleSerial < 0) {
            throw new IllegalArgumentException("cp.minBundleSerial must not be negative");
        }
    }
}
