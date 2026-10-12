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
package sjws_nettask.acme;

import java.nio.charset.StandardCharsets;
import sjws_nettask.core.Body;
import sjws_nettask.core.Handler;
import sjws_nettask.core.Request;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Serves {@code /.well-known/acme-challenge/} in front of the static handler. Installed only when
 * ACME is enabled on the main listener, so the request path does not branch on it otherwise.
 * An unknown token under that prefix is 404 and does not fall through to a file of the same name.
 */
public final class AcmeChallengeHandler implements Handler {

    public static final String PREFIX = "/.well-known/acme-challenge/";

    private final ChallengeBoard board;
    private final Handler next;

    public AcmeChallengeHandler(ChallengeBoard board, Handler next) {
        this.board = board;
        this.next = next;
    }

    @Override
    public Response handle(Request request) {
        var target = request.target();
        int query = target.indexOf('?');
        var path = query < 0 ? target : target.substring(0, query);
        if (!path.startsWith(PREFIX)) {
            return this.next.handle(request);
        }
        var token = path.substring(PREFIX.length());
        if (token.isEmpty() || token.indexOf('/') >= 0) {
            return StaticFileHandler.error(404, request.headOnly());
        }
        var authorization = this.board.get(token);
        if (authorization == null) {
            return StaticFileHandler.error(404, request.headOnly());
        }
        return new Response(200,
                ResponseHeaders.of().add("content-type", "application/octet-stream")
                        .add("x-content-type-options", StaticFileHandler.NOSNIFF),
                Body.Bytes.of(authorization.getBytes(StandardCharsets.US_ASCII)), request.headOnly());
    }
}
