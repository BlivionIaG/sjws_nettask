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

/**
 * Checks the signature on a pushed snapshot before {@code SjwsServer.applyRemote} swaps it in.
 * The signature covers {@code schema}, {@code version} and {@code canonicalHash}
 * ({@code schema + "\n" + version + "\n" + canonicalHash}). A verifier fails closed: a missing
 * or wrong signature, or a canonical hash that is not the SHA-256 of the canonical serialization,
 * is a {@link ConfigException}. There is no apply method that skips this call.
 * <p>
 * {@link #NOOP} is for a standalone process and for tests. A data or control process that was
 * not given a verifier uses {@link #required()} and rejects every push.
 */
@FunctionalInterface
public interface SnapshotVerifier {

    SnapshotVerifier NOOP = document -> {
    };

    void verify(ConfigDocument document);

    static SnapshotVerifier noop() {
        return NOOP;
    }

    /** Rejects every document. The data and control default until a verifier is injected. */
    static SnapshotVerifier required() {
        return document -> {
            throw new ConfigException("a signature verifier is required");
        };
    }

    static SnapshotVerifier forMode(NodeSettings.Mode mode) {
        return mode == NodeSettings.Mode.STANDALONE ? NOOP : required();
    }
}
