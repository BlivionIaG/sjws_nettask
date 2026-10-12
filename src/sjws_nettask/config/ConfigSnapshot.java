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
package sjws_nettask.config;

import sjws_nettask.acme.AcmeChallengeHandler;
import sjws_nettask.acme.ChallengeBoard;
import sjws_nettask.core.FileCache;
import sjws_nettask.core.Handler;
import sjws_nettask.core.StaticFileHandler;

/**
 * Immutable configuration for the request path: one prebuilt handler, swapped as a whole.
 * With {@code routing.enabled} the handler is a {@code Router} built from {@link #document()}; the
 * middleware list and access settings are stored there and not consulted here. When ACME is on the main listener the challenge handler runs first, so
 * {@code /.well-known/acme-challenge/} is answered before the file handler's dotfile check.
 *
 * @param files the static-file handler (also what {@code SjwsServer.handler()} returns)
 * @param handler what a request runs: {@code files} or a router over it, possibly behind the ACME challenge wrapper
 * @param generation increases by one on every successful reload
 * @param resources what {@link #close()} releases (the router's upstream pools), or null
 */
public record ConfigSnapshot(ConfigDocument document, StaticFileHandler files, Handler handler, int generation,
        AutoCloseable resources) {

    public ConfigSnapshot(ConfigDocument document, StaticFileHandler files, Handler handler, int generation) {
        this(document, files, handler, generation, null);
    }

    /**
     * Releases upstream connection pools after this snapshot was replaced (reload) or the server
     * stopped. Requests still running on it finish; their connections are then closed. Idempotent.
     */
    public void close() {
        if (this.resources != null) {
            try {
                this.resources.close();
            } catch (Exception e) {
                System.err.println("WARN: closing routing resources: " + e);
            }
        }
    }

    public static ConfigSnapshot compile(ConfigDocument document, ChallengeBoard board, int generation) {
        var server = document.server();
        var files = new StaticFileHandler(server.root(), server.allowUnlistableDirs(),
                new FileCache(server.cacheBytes()), server.access());
        Handler handler = files;
        AutoCloseable resources = null;
        if (document.routing().enabled()) {
            var router = RoutingCompiler.compile(document.routing(), files);
            handler = router;
            resources = router;
        }
        var acme = document.acme();
        if (acme.enabled() && acme.onMainListener(server.port())) {
            handler = new AcmeChallengeHandler(board, handler); // challenges are answered before any route
        }
        return new ConfigSnapshot(document, files, handler, generation, resources);
    }
}
