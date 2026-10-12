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
package sjws_nettask.routing;

import java.util.Set;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Answers every request with a redirect to {@code location}; with {@code keepPath} the request
 * target is appended (so a Router route with rewrite "/" maps "/old/x?q" to location + "x?q").
 * A run of leading slashes or backslashes in the target becomes one separator, so
 * {@code /old//evil.example} cannot turn a relative location into the protocol-relative
 * {@code //evil.example} (or {@code /\evil.example}, which browsers read the same way).
 */
public record RedirectHandler(int status, String location, boolean keepPath) implements Handler {

    private static final Set<Integer> STATUSES = Set.of(301, 302, 303, 307, 308);

    public RedirectHandler {
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("not a redirect status: " + status);
        }
        if (location == null || location.isEmpty() || location.indexOf('\r') >= 0 || location.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("bad location");
        }
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        String loc = this.location;
        if (this.keepPath) {
            loc = appendPath(loc, target);
        }
        var r = StaticFileHandler.error(this.status, request.headOnly());
        r.headers().set("location", loc);
        return r;
    }

    static String appendPath(String location, String target) {
        int i = 0;
        while (i < target.length() && (target.charAt(i) == '/' || target.charAt(i) == '\\')) {
            i++;
        }
        if (i == 0) {
            return location + target; // "?q", "*": nothing to join
        }
        return (location.endsWith("/") ? location : location + "/") + target.substring(i);
    }
}
