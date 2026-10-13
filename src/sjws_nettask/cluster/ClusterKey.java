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
package sjws_nettask.cluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Shared secret for HA UDP heartbeats. The bytes come from the config file
 * ({@code ha.cluster_key}) or from {@link #ENV_CURRENT}. They are never taken from a command-line
 * argument. {@link #toString()} redacts them; do not log {@link #current()} or {@link #previous()}.
 */
public final class ClusterKey {

    public static final String ENV_CURRENT = "WEB_SERVER_HA_CLUSTER_KEY";
    public static final String ENV_PREVIOUS = "WEB_SERVER_HA_CLUSTER_KEY_PREVIOUS";
    /** HMAC-SHA256 key floor. Shorter secrets are refused. */
    public static final int MIN_BYTES = 16;

    private final byte[] current;
    private final byte[] previous;

    private ClusterKey(byte[] current, byte[] previous) {
        this.current = current;
        this.previous = previous;
    }

    /**
     * @param current required key, at least {@link #MIN_BYTES} bytes
     * @param previous key still accepted during rotation, or null
     */
    public static ClusterKey of(byte[] current, byte[] previous) {
        if (current == null) {
            throw new NullPointerException("cluster key");
        }
        if (current.length < MIN_BYTES) {
            throw new IllegalArgumentException("cluster key must be at least " + MIN_BYTES + " bytes");
        }
        byte[] prev = null;
        if (previous != null) {
            if (previous.length < MIN_BYTES) {
                throw new IllegalArgumentException("previous cluster key must be at least " + MIN_BYTES + " bytes");
            }
            prev = previous.clone();
        }
        return new ClusterKey(current.clone(), prev);
    }

    /**
     * Reads the key from an environment map. A missing or blank current key is refused.
     * The map values are not copied into the exception text. Whitespace is trimmed.
     * {@code base64:} and {@code hex:} prefixes are decoded; anything else is UTF-8.
     */
    public static ClusterKey fromEnvironment(Map<String, String> env) {
        Objects.requireNonNull(env, "env");
        if (!env.containsKey(ENV_CURRENT)) {
            throw new IllegalArgumentException(
                    "HA is enabled but no cluster key is set; set ha.cluster_key or " + ENV_CURRENT);
        }
        var current = env.get(ENV_CURRENT);
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException(ENV_CURRENT + " is empty");
        }
        byte[] prev = null;
        if (env.containsKey(ENV_PREVIOUS)) {
            var previous = env.get(ENV_PREVIOUS);
            if (previous == null || previous.isBlank()) {
                throw new IllegalArgumentException(ENV_PREVIOUS + " is empty");
            }
            prev = parse(previous);
        }
        return of(parse(current), prev);
    }

    /**
     * Decodes one secret. Leading and trailing whitespace is removed, including a trailing
     * newline from a file. {@code base64:} and {@code hex:} select those encodings; otherwise
     * the trimmed text is UTF-8. The result is at least {@link #MIN_BYTES} bytes. The text is
     * not copied into the exception.
     */
    public static byte[] parse(String raw) {
        Objects.requireNonNull(raw, "cluster key");
        var text = raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("cluster key is empty");
        }
        byte[] bytes;
        if (text.startsWith("base64:")) {
            try {
                bytes = Base64.getDecoder().decode(text.substring("base64:".length()).trim());
            } catch (IllegalArgumentException failed) {
                throw new IllegalArgumentException("cluster key base64 is invalid");
            }
        } else if (text.startsWith("hex:")) {
            bytes = decodeHex(text.substring("hex:".length()).trim());
        } else {
            bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < MIN_BYTES) {
            throw new IllegalArgumentException("cluster key must be at least " + MIN_BYTES + " bytes");
        }
        return bytes;
    }

    private static byte[] decodeHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("cluster key hex is invalid");
        }
        var out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("cluster key hex is invalid");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** @return a copy of the current key. Do not log it. */
    public byte[] current() {
        return this.current.clone();
    }

    /** @return a copy of the previous key, or null when rotation has no previous key. Do not log it. */
    public byte[] previous() {
        return this.previous == null ? null : this.previous.clone();
    }

    public boolean hasPrevious() {
        return this.previous != null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClusterKey key && same(this.current, key.current) && same(this.previous, key.previous);
    }

    private static boolean same(byte[] left, byte[] right) {
        if (left == null || right == null) {
            return left == right;
        }
        return MessageDigest.isEqual(left, right);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(this.current) + Arrays.hashCode(this.previous);
    }

    @Override
    public String toString() {
        return "ClusterKey{current=****, previous=" + (this.previous == null ? "none" : "****") + "}";
    }
}
