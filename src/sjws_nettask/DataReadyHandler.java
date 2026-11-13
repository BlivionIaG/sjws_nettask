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
package sjws_nettask;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/**
 * {@code GET/HEAD /readyz} for a data node that has no HA handler. Answers 200 or 503 from a
 * supplier; every other target goes to the inner handler. HA already serves {@code /readyz}
 * from {@code HealthBoard}.
 */
final class DataReadyHandler implements Handler {

    private static final byte[] OK = "ok\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNAVAILABLE = "unavailable\n".getBytes(StandardCharsets.UTF_8);
    private static final Response READY = text(200, OK, false);
    private static final Response READY_HEAD = text(200, OK, true);
    private static final Response NOT_READY = text(503, UNAVAILABLE, false);
    private static final Response NOT_READY_HEAD = text(503, UNAVAILABLE, true);

    private final Supplier<Handler> inner;
    private final BooleanSupplier ready;

    DataReadyHandler(Supplier<Handler> inner, BooleanSupplier ready) {
        this.inner = inner;
        this.ready = ready;
    }

    @Override
    public Response handle(Request request) {
        if (isReadyz(request.target())) {
            if (this.ready.getAsBoolean()) {
                return request.headOnly() ? READY_HEAD : READY;
            }
            return request.headOnly() ? NOT_READY_HEAD : NOT_READY;
        }
        return this.inner.get().handle(request);
    }

    /** Path equals {@code /readyz}, or that path plus a query. */
    static boolean isReadyz(String target) {
        if (target == null || target.length() < 7 || !target.startsWith("/readyz")) {
            return false;
        }
        if (target.length() == 7) {
            return true;
        }
        char next = target.charAt(7);
        return next == '?' || next == '#';
    }

    private static Response text(int status, byte[] body, boolean head) {
        var headers = ResponseHeaders.of().add("content-type", "text/plain; charset=utf-8");
        return new Response(status, headers, Body.Bytes.of(body), head);
    }
}
