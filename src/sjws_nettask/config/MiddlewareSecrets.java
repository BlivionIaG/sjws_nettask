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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sjws_nettask.core.MiddlewareSpec;

/**
 * Gateway filter options that carry shared credentials ({@code api-key} {@code keys}, {@code jwt}
 * {@code hmac-keys}). In a control-plane proposal those values must be node-local file references
 * ({@code keys-file}, {@code hmac-keys-file}); the canonical document, Raft log and signed snapshots
 * must not hold the secret bytes. Local (non-CP) config may still use the inline form.
 * <p>
 * Error text never echoes a secret value. {@link #MASK} is the stand-in written by
 * {@link ConfigCodec} and by {@link #maskYaml(String)} for viewer reads.
 */
public final class MiddlewareSecrets {

    /** Stand-in for a redacted secret option value. */
    public static final String MASK = "***";

    /** Inline secret option names (kebab-case, matching the filter params). */
    public static final Set<String> INLINE = Set.of("keys", "hmac-keys");

    /** File-reference option names that replace the matching inline form. */
    public static final Set<String> FILES = Set.of("keys-file", "hmac-keys-file");

    /** Largest accepted secret file (same order as other SecureKeyFile caps). */
    public static final int MAX_FILE_BYTES = 1 << 16;

    private MiddlewareSecrets() {
    }

    /**
     * Why a control-plane proposal must be refused, or {@code null} when every middleware step is
     * free of inline secret options. The message names the filter and the option; it never carries
     * the value.
     */
    public static String refuseInline(List<MiddlewareSpec> middleware) {
        if (middleware == null || middleware.isEmpty()) {
            return null;
        }
        for (int i = 0; i < middleware.size(); i++) {
            var step = middleware.get(i);
            for (var key : INLINE) {
                if (step.options().containsKey(key)) {
                    var file = fileOption(key);
                    return "middleware[" + i + "] (" + step.name() + ") option '" + key
                            + "' is not accepted in a control-plane proposal; use " + file;
                }
            }
        }
        return null;
    }

    /** File option that replaces {@code inline} ({@code keys} → {@code keys-file}). */
    public static String fileOption(String inline) {
        return inline + "-file";
    }

    /**
     * Resolves an inline option or its {@code *-file} counterpart. Exactly one must be set.
     * File bytes are read with {@link SecureKeyFile} (0600/0400, owner, NOFOLLOW). The returned
     * string is the filter's raw material (same format as the inline option).
     *
     * @param label filter option label used in errors (for example {@code api-key keys})
     */
    public static String resolve(MiddlewareSpec spec, String inlineKey, String label) {
        var value = resolveOptional(spec, inlineKey, label);
        if (value == null) {
            throw new IllegalArgumentException(label + " " + inlineKey + " or " + fileOption(inlineKey)
                    + " is required");
        }
        return value;
    }

    /**
     * Like {@link #resolve} but returns {@code null} when neither form is set (for optional
     * material such as {@code jwt} {@code hmac-keys} when only public-key algorithms are used).
     * Both forms together are still refused.
     */
    public static String resolveOptional(MiddlewareSpec spec, String inlineKey, String label) {
        var inline = spec.option(inlineKey);
        var fileKey = fileOption(inlineKey);
        var file = spec.option(fileKey);
        boolean hasInline = inline != null && !inline.isBlank();
        boolean hasFile = file != null && !file.isBlank();
        if (hasInline && hasFile) {
            throw new IllegalArgumentException(label + ": " + inlineKey + " and " + fileKey
                    + " cannot both be set");
        }
        if (hasFile) {
            var path = Path.of(file.trim());
            var bytes = SecureKeyFile.read(path, label + " " + fileKey, MAX_FILE_BYTES);
            return new String(bytes, StandardCharsets.UTF_8).strip();
        }
        if (hasInline) {
            return inline;
        }
        return null;
    }

    /**
     * Copy of {@code options} with inline secret values replaced by {@link #MASK}. File-reference
     * options are left unchanged (they are paths, not secrets).
     */
    public static Map<String, String> maskOptions(Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return options == null ? Map.of() : options;
        }
        var out = new LinkedHashMap<String, String>(options.size());
        for (var entry : options.entrySet()) {
            out.put(entry.getKey(), INLINE.contains(entry.getKey()) ? MASK : entry.getValue());
        }
        return Map.copyOf(out);
    }

    /**
     * Defence in depth for viewer reads of a stored canonical YAML: replace values of known
     * inline secret options. Lines that are not {@code keys:} / {@code hmac-keys:} under an
     * options map are left alone. The secret value is never copied into the result beyond the
     * scan needed to find the colon.
     */
    public static String maskYaml(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return yaml;
        }
        var lines = yaml.split("\n", -1);
        var out = new StringBuilder(yaml.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(maskLine(lines[i]));
        }
        return out.toString();
    }

    private static String maskLine(String line) {
        var trimmed = line.stripLeading();
        for (var key : INLINE) {
            if (trimmed.startsWith(key + ":") || trimmed.startsWith(key + " :")) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    return line;
                }
                return line.substring(0, colon + 1) + " " + ConfigCodec.quote(MASK);
            }
        }
        return line;
    }

    /** True when {@code options} carries an inline secret (local WARN path). */
    public static boolean hasInline(Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return false;
        }
        for (var key : INLINE) {
            if (options.containsKey(key)) {
                return true;
            }
        }
        return false;
    }
}
