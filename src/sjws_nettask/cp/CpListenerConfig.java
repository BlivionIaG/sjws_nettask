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
 * One CP mTLS listener (admin, watch or Raft transport). Paths only; inline PEM is never accepted.
 *
 * @param name label used in audit actions, e.g. {@code cp-admin}
 * @param certPem server certificate chain (PEM)
 * @param keyPem server private key (PEM, unencrypted); owner-only 0600/0400 unless {@code insecureSkipPermissionChecks}
 * @param caPem pinned CA bundle client certificates must chain to
 * @param revocations CRL or denylist file as for {@link AdminTls}; null for none
 * @param insecureSkipPermissionChecks dev only: skip the owner/mode check on {@code keyPem}
 */
public record CpListenerConfig(String name, Path certPem, Path keyPem, Path caPem, Path revocations,
        boolean insecureSkipPermissionChecks) {

    public CpListenerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("cp listener requires a name");
        }
        if (certPem == null || keyPem == null || caPem == null) {
            throw new IllegalArgumentException(name + " requires cert, key and ca");
        }
    }
}
