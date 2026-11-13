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
 * Protocol-neutral response.
 *
 * @param status HTTP status code
 * @param headers representation/semantic fields (no transport fields, see {@link ResponseHeaders})
 * @param body content; for HEAD it is still present so writers can advertise its length
 * @param headOnly the request was HEAD: send the head (with the body's length) but no body
 * @param localAdmission produced by local proxy admission (e.g. peer-budget share) rather than an
 *        upstream; balancers must not count these against endpoint health
 * @param generated an error page, listing or redirect built by this server (not an upstream or
 *        file body). Survives a later filter rewriting headers, so problem+json conversion does
 *        not depend on a header marker.
 */
public record Response(int status, ResponseHeaders headers, Body body, boolean headOnly,
        boolean localAdmission, boolean generated) {

    public Response {
        if (status < 100 || status > 999) {
            throw new IllegalArgumentException("bad status " + status);
        }
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    public Response(int status, ResponseHeaders headers, Body body) {
        this(status, headers, body, false, false, false);
    }

    public Response(int status, ResponseHeaders headers, Body body, boolean headOnly) {
        this(status, headers, body, headOnly, false, false);
    }

    public Response(int status, ResponseHeaders headers, Body body, boolean headOnly, boolean localAdmission) {
        this(status, headers, body, headOnly, localAdmission, false);
    }

    /** Copy with {@link #localAdmission()} set; same instance if already marked. */
    public Response asLocalAdmission() {
        return this.localAdmission ? this
                : new Response(this.status, this.headers, this.body, this.headOnly, true, this.generated);
    }

    /** Copy with {@link #generated()} set; same instance if already marked. */
    public Response asGenerated() {
        return this.generated ? this
                : new Response(this.status, this.headers, this.body, this.headOnly, this.localAdmission, true);
    }

    public long contentLength() {
        return this.body.length();
    }

    /** @return false for 1xx, 204 and 304, which never carry content (nor Content-Length) */
    public boolean mayHaveBody() {
        return this.status >= 200 && this.status != 204 && this.status != 304;
    }

    /** @return true if a body goes on the wire: not HEAD and a status that may carry content */
    public boolean sendsBody() {
        return !this.headOnly && mayHaveBody();
    }
}
