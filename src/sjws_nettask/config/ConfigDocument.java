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
import java.util.Arrays;
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
 * @param stateDir where a data node stores {@code config/last-good.json}; not part of the hash
 * @param canonicalHash lowercase SHA-256 of {@link #canonicalText()}. It does not cover the TLS
 *        key password, the state directory, the signature, or this hash itself
 * @param canonicalText canonical YAML rendered once for {@link #canonicalHash}; null only on a
 *        document the caller built by hand
 * @param signature bytes covering {@code schema}, {@code version} and {@code canonicalHash}, or
 *        null when the document is unsigned. Not part of the canonical text
 */
public record ConfigDocument(ServerConfig server, AcmeSettings acme, Routing routing, List<MiddlewareSpec> middleware,
        AccessSettings access, NodeSettings node, int schemaVersion, long bundleVersion, Path configFile,
        Path stateDir, String canonicalHash, String canonicalText, byte[] signature) {

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
        if (stateDir == null) {
            stateDir = LastGoodStore.defaultStateDir();
        }
        if (canonicalHash == null) {
            canonicalHash = "";
        }
        if (signature != null) {
            signature = signature.clone();
        }
    }

    public static ConfigDocument defaults(ServerConfig server) {
        return of(server, AcmeSettings.disabled(), Routing.disabled(), List.of(), AccessSettings.DEFAULT,
                NodeSettings.STANDALONE, SCHEMA, 0, null);
    }

    /**
     * Builds a document and sets {@link #canonicalHash()} from one rendering of the canonical YAML.
     * The hash is not part of the hashed text.
     */
    public static ConfigDocument of(ServerConfig server, AcmeSettings acme, Routing routing,
            List<MiddlewareSpec> middleware, AccessSettings access, NodeSettings node, int schemaVersion,
            long bundleVersion, Path configFile) {
        var draft = new ConfigDocument(server, acme, routing, middleware, access, node, schemaVersion, bundleVersion,
                configFile, null, "", null, null);
        var text = ConfigCodec.text(draft);
        return new ConfigDocument(server, acme, routing, middleware, access, node, schemaVersion, bundleVersion,
                configFile, draft.stateDir(), ConfigCodec.sha256(text), text, null);
    }

    public ConfigDocument withServer(ServerConfig server) {
        var next = of(server, this.acme, this.routing, this.middleware, this.access, this.node, this.schemaVersion,
                this.bundleVersion, this.configFile).withStateDir(this.stateDir);
        if (next.canonicalHash.equals(this.canonicalHash)) {
            return next.withSignature(this.signature);
        }
        return next;
    }

    public ConfigDocument withNode(NodeSettings node) {
        var next = of(this.server, this.acme, this.routing, this.middleware, this.access, node, this.schemaVersion,
                this.bundleVersion, this.configFile).withStateDir(this.stateDir);
        if (next.canonicalHash.equals(this.canonicalHash)) {
            return next.withSignature(this.signature);
        }
        return next;
    }

    /** Same canonical bytes. The state directory is local and is not part of the hash. */
    public ConfigDocument withStateDir(Path stateDir) {
        var dir = stateDir == null ? LastGoodStore.defaultStateDir() : stateDir;
        if (dir.equals(this.stateDir)) {
            return this;
        }
        return new ConfigDocument(this.server, this.acme, this.routing, this.middleware, this.access, this.node,
                this.schemaVersion, this.bundleVersion, this.configFile, dir, this.canonicalHash, this.canonicalText,
                this.signature);
    }

    /** Same canonical bytes. A pushed bundle keeps the path of the local file. */
    public ConfigDocument withConfigFile(Path configFile) {
        if (configFile == null ? this.configFile == null : configFile.equals(this.configFile)) {
            return this;
        }
        return new ConfigDocument(this.server, this.acme, this.routing, this.middleware, this.access, this.node,
                this.schemaVersion, this.bundleVersion, configFile, this.stateDir, this.canonicalHash,
                this.canonicalText, this.signature);
    }

    /** The signature is not part of the canonical text, so the hash stays as it was. */
    public ConfigDocument withSignature(byte[] signature) {
        if (Arrays.equals(this.signature, signature)) {
            return this;
        }
        return new ConfigDocument(this.server, this.acme, this.routing, this.middleware, this.access, this.node,
                this.schemaVersion, this.bundleVersion, this.configFile, this.stateDir, this.canonicalHash,
                this.canonicalText, signature);
    }
}
