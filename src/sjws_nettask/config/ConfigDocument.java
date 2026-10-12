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
 * Source model behind a {@link ConfigSnapshot}. Serializable as canonical YAML
 * ({@link ConfigCodec}). The request path does not read {@link #node()}.
 *
 * @param middleware file order; empty means no chain
 * @param node role and labels; {@link NodeSettings#STANDALONE} when the file omits {@code node}
 * @param schemaVersion {@link #SCHEMA}; a different value is rejected
 * @param bundleVersion control-plane version ({@code version} in the file). {@code 0} means unset,
 *        so a local edit that does not carry a version still applies
 * @param configFile file this document was read from, or null when there is no file
 * @param contentHash lowercase SHA-256 of that file's bytes, or null when there is no file
 * @param canonicalHash lowercase SHA-256 of {@link ConfigCodec#text(ConfigDocument)}. It does not
 *        cover the TLS key password or this hash itself
 */
public record ConfigDocument(ServerConfig server, AcmeSettings acme, Routing routing, List<MiddlewareSpec> middleware,
        AccessSettings access, NodeSettings node, int schemaVersion, long bundleVersion, Path configFile,
        String contentHash, String canonicalHash) {

    /** The only schema this process accepts. */
    public static final int SCHEMA = 1;

    public ConfigDocument {
        middleware = List.copyOf(middleware);
        if (node == null) {
            node = NodeSettings.STANDALONE;
        }
        if (schemaVersion < 0) {
            throw new IllegalArgumentException("schemaVersion");
        }
        if (bundleVersion < 0) {
            throw new IllegalArgumentException("bundleVersion");
        }
        if (canonicalHash == null) {
            canonicalHash = "";
        }
    }

    public static ConfigDocument defaults(ServerConfig server) {
        return of(server, AcmeSettings.disabled(), Routing.disabled(), List.of(), AccessSettings.DEFAULT,
                NodeSettings.STANDALONE, SCHEMA, 0, null, null);
    }

    /**
     * Builds a document and sets {@link #canonicalHash()} from the canonical YAML. The hash is not
     * part of the hashed text.
     */
    public static ConfigDocument of(ServerConfig server, AcmeSettings acme, Routing routing,
            List<MiddlewareSpec> middleware, AccessSettings access, NodeSettings node, int schemaVersion,
            long bundleVersion, Path configFile, String contentHash) {
        var draft = new ConfigDocument(server, acme, routing, middleware, access, node, schemaVersion, bundleVersion,
                configFile, contentHash, "");
        return new ConfigDocument(server, acme, routing, middleware, access, node, schemaVersion, bundleVersion,
                configFile, contentHash, ConfigCodec.sha256(ConfigCodec.text(draft)));
    }

    public ConfigDocument withServer(ServerConfig server) {
        return of(server, this.acme, this.routing, this.middleware, this.access, this.node, this.schemaVersion,
                this.bundleVersion, this.configFile, this.contentHash);
    }

    public ConfigDocument withNode(NodeSettings node) {
        return of(this.server, this.acme, this.routing, this.middleware, this.access, node, this.schemaVersion,
                this.bundleVersion, this.configFile, this.contentHash);
    }
}
