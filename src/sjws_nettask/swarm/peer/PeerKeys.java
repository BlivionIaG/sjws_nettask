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
package sjws_nettask.swarm.peer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Function;
import sjws_nettask.cluster.ClusterKey;
import sjws_nettask.config.SecureKeyFile;

/**
 * The swarm peer frame key (#236 §9.2): {@code SJWS_SWARM_PEER_KEY} plus {@code SJWS_SWARM_PEER_KEY_PREVIOUS} during a
 * rotation, from the environment or from owner-only key files ({@code sjws.swarm.peer.keyFile} /
 * {@code sjws.swarm.peer.keyFilePrevious}, read with {@link SecureKeyFile}). There is deliberately no property that
 * carries the key itself, so it never appears on a command line. Parsed with {@link ClusterKey#parse}; refused when
 * either value equals the HA cluster key or the CP Raft key (current or previous), compared with
 * {@link MessageDigest#isEqual}. Error texts name variables, never values.
 */
public final class PeerKeys {

    public static final String ENV = "SJWS_SWARM_PEER_KEY";
    public static final String ENV_PREVIOUS = "SJWS_SWARM_PEER_KEY_PREVIOUS";
    public static final String FILE_PROPERTY = "sjws.swarm.peer.keyFile";
    public static final String FILE_PREVIOUS_PROPERTY = "sjws.swarm.peer.keyFilePrevious";
    /** Keys of other domains this one must differ from. */
    static final String[] OTHER_KEYS = {ClusterKey.ENV_CURRENT, ClusterKey.ENV_PREVIOUS, "SJWS_CP_RAFT_KEY",
        "SJWS_CP_RAFT_KEY_PREVIOUS"};
    private static final int MAX_KEY_FILE_BYTES = 4096;

    private PeerKeys() {
    }

    /** True when any swarm peer key source is set (so a key with no peer map can be refused). */
    static boolean anySet(Function<String, String> props, Map<String, String> env) {
        return present(env.get(ENV)) || present(env.get(ENV_PREVIOUS)) || present(props.apply(FILE_PROPERTY))
                || present(props.apply(FILE_PREVIOUS_PROPERTY));
    }

    /** Loads and checks the key; {@link IllegalArgumentException} names what is wrong. */
    public static ClusterKey load(Function<String, String> props, Map<String, String> env) {
        var current = one(props, env, ENV, FILE_PROPERTY, true);
        var previous = one(props, env, ENV_PREVIOUS, FILE_PREVIOUS_PROPERTY, false);
        for (var other : OTHER_KEYS) {
            var raw = env.get(other);
            if (!present(raw)) {
                continue;
            }
            byte[] theirs;
            try {
                theirs = ClusterKey.parse(raw);
            } catch (IllegalArgumentException unusable) {
                continue; // not a usable key, so it cannot equal ours
            }
            if (MessageDigest.isEqual(theirs, current) || (previous != null && MessageDigest.isEqual(theirs, previous))) {
                throw new IllegalArgumentException(ENV + " must differ from " + other
                        + " (the swarm peer links have their own key); refusing to start");
            }
        }
        return ClusterKey.of(current, previous);
    }

    private static byte[] one(Function<String, String> props, Map<String, String> env, String envName, String fileProperty,
            boolean required) {
        var raw = env.get(envName);
        var file = props.apply(fileProperty);
        if (present(raw) && present(file)) {
            throw new IllegalArgumentException("set either " + envName + " or " + fileProperty + ", not both");
        }
        if (present(file)) {
            var bytes = SecureKeyFile.read(Path.of(file.trim()), fileProperty, MAX_KEY_FILE_BYTES);
            try {
                return ClusterKey.parse(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(fileProperty + " refused: " + e.getMessage());
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        }
        if (raw != null && raw.isBlank()) {
            throw new IllegalArgumentException(envName + " is empty");
        }
        if (raw == null) {
            if (required) {
                throw new IllegalArgumentException(PeerConfig.PROPERTY + " lists more than this node but no peer key is"
                        + " set: set " + ENV + " or " + FILE_PROPERTY + "; refusing to start");
            }
            return null;
        }
        try {
            return ClusterKey.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(envName + " refused: " + e.getMessage());
        }
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }
}
