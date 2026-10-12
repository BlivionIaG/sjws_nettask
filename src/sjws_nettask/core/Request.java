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
package sjws_nettask.core;

import java.util.Objects;

/**
 * Protocol-neutral request.
 *
 * @param method request method, case-sensitive ("GET", "HEAD", ...)
 * @param target origin-form request-target: raw (still percent-encoded) path plus optional "?query"
 * @param headers conditionals and negotiation headers (never null; use {@link RequestHeaders#NONE})
 */
public record Request(String method, String target, RequestHeaders headers) {

    public Request {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(headers, "headers");
    }

    public Request(String method, String target) {
        this(method, target, RequestHeaders.NONE);
    }

    public boolean headOnly() {
        return "HEAD".equals(this.method);
    }
}
