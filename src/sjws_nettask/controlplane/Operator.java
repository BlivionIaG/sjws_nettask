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
package sjws_nettask.controlplane;

import java.util.Objects;

/**
 * An operator identity. {@code certSubject} is an RFC 2253 subject DN (canonicalized) matched
 * against a verified mTLS client certificate; {@code tokenHash} is the SHA-256 hex of the token
 * secret. Either may be null, not both. A disabled operator is denied everything.
 */
public record Operator(String id, Role role, String certSubject, String tokenHash, boolean enabled) {

    public Operator {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("operator id must match [a-z0-9][a-z0-9_-]{0,63}: " + id);
        }
        Objects.requireNonNull(role, "role");
        if (certSubject == null && tokenHash == null) {
            throw new IllegalArgumentException("operator " + id + " needs a certificate subject or a token");
        }
        if (tokenHash != null && !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("token hash must be 64 lowercase hex digits");
        }
        if (certSubject != null) {
            certSubject = Rbac.canonicalSubject(certSubject);
        }
    }

    /** Never prints the token hash. */
    @Override
    public String toString() {
        return "Operator[" + this.id + ", " + this.role + (this.certSubject != null ? ", cert=" + this.certSubject : "")
                + (this.tokenHash != null ? ", token" : "") + (this.enabled ? "" : ", disabled") + "]";
    }
}
