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

import java.nio.file.Path;
import java.util.List;
import sjws_nettask.ServerConfig;
import sjws_nettask.core.MiddlewareSpec;

/**
 * One validated configuration: server settings plus the sections other workstreams fill in.
 *
 * @param middleware file order; empty means no chain
 * @param configFile file this document was read from, or null when there is no file
 * @param contentHash lowercase SHA-256 of that file's bytes, or null when there is no file
 */
public record ConfigDocument(ServerConfig server, AcmeSettings acme, Routing routing, List<MiddlewareSpec> middleware,
        AccessSettings access, Path configFile, String contentHash) {

    public ConfigDocument {
        middleware = List.copyOf(middleware);
    }

    public static ConfigDocument defaults(ServerConfig server) {
        return new ConfigDocument(server, AcmeSettings.disabled(), Routing.disabled(), List.of(),
                AccessSettings.DEFAULT, null, null);
    }

    public ConfigDocument withServer(ServerConfig server) {
        return new ConfigDocument(server, this.acme, this.routing, this.middleware, this.access, this.configFile,
                this.contentHash);
    }
}
