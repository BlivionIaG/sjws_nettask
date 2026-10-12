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
 * An operator identity: {@code certSubject} is the URI SAN ({@code urn:...}) or RFC 2253 subject
 * DN (canonicalized) of its verified mTLS client certificate. A disabled operator is denied
 * everything.
 */
public record Operator(String id, Role role, String certSubject, boolean enabled) {

    public Operator {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("operator id must match [a-z0-9][a-z0-9_-]{0,63}: " + id);
        }
        Objects.requireNonNull(role, "role");
        if (certSubject == null) {
            throw new IllegalArgumentException("operator " + id + " needs a certificate subject or SAN");
        }
        certSubject = Rbac.canonicalSubject(certSubject);
    }

    @Override
    public String toString() {
        return "Operator[" + this.id + ", " + this.role + ", cert=" + this.certSubject
                + (this.enabled ? "" : ", disabled") + "]";
    }
}
