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
import sjws_nettask.ServerConfig;

/**
 * One validated configuration: server settings plus the sections other workstreams fill in.
 *
 * @param configFile file this document was read from, or null when there is no file
 */
public record ConfigDocument(ServerConfig server, AcmeSettings acme, Routing routing, MiddlewareChain middleware,
        Path configFile) {

    public static ConfigDocument defaults(ServerConfig server) {
        return new ConfigDocument(server, AcmeSettings.disabled(), Routing.disabled(), MiddlewareChain.EMPTY, null);
    }

    public ConfigDocument withServer(ServerConfig server) {
        return new ConfigDocument(server, this.acme, this.routing, this.middleware, this.configFile);
    }
}
