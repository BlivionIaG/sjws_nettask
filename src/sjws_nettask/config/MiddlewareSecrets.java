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
 * {@link ConfigCodec#viewerText} and by {@link #maskYaml(String)} for viewer reads.
 * Canonical identity encoding ({@link ConfigCodec#text}) keeps real values.
 */
public final class MiddlewareSecrets {

    /** Stand-in for a redacted secret option value. */
    public static final String MASK = "***";

    /** Inline secret option names (kebab-case, matching the filter params). */
    public static final Set<String> INLINE = Set.of("keys", "hmac-keys");

    /** File-reference option names that replace the matching inline form. */
    public static final Set<String> FILES = Set.of("keys-file", "hmac-keys-file");

    /** Filter names whose options may carry {@link #INLINE} secrets. */
    public static final Set<String> SECRET_FILTERS = Set.of("api-key", "jwt");

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
     * Copy of filter {@code options} with inline secret values replaced by {@link #MASK}.
     * Callers must pass options from a {@link #SECRET_FILTERS} entry only. File-reference
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
     * Defence in depth for viewer reads of a stored canonical YAML: replace inline secret
     * values only on {@link #SECRET_FILTERS} middleware entries ({@code api-key} / {@code jwt})
     * under their {@code options} map. A messaging topic (or any other node) named
     * {@code keys} / {@code hmac-keys} is left unchanged so the YAML stays valid. The secret
     * value is never copied into the result beyond the scan needed to find the colon.
     */
    public static String maskYaml(String yaml) {
        if (yaml == null || yaml.isEmpty()) {
            return yaml;
        }
        var lines = yaml.split("\n", -1);
        var out = new StringBuilder(yaml.length());
        boolean inMiddleware = false;
        int middlewareIndent = -1;
        boolean secretFilter = false;
        boolean inOptions = false;
        int optionsIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            var line = lines[i];
            int indent = leadingSpaces(line);
            var trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.append(line);
                continue;
            }
            if (inMiddleware && indent <= middlewareIndent && !trimmed.startsWith("-")) {
                inMiddleware = false;
                secretFilter = false;
                inOptions = false;
            }
            if (trimmed.equals("middleware:") || trimmed.startsWith("middleware:")) {
                inMiddleware = true;
                middlewareIndent = indent;
                secretFilter = false;
                inOptions = false;
                out.append(line);
                continue;
            }
            if (!inMiddleware) {
                out.append(line);
                continue;
            }
            if (trimmed.startsWith("- ")) {
                secretFilter = false;
                inOptions = false;
                optionsIndent = -1;
                var rest = trimmed.substring(2).strip();
                secretFilter = nameDeclaresSecretFilter(rest);
                out.append(line);
                continue;
            }
            if (inOptions && indent <= optionsIndent) {
                inOptions = false;
            }
            if (isKey(trimmed, "name")) {
                secretFilter = secretFilterName(scalarAfterColon(trimmed));
                out.append(line);
                continue;
            }
            if (secretFilter && isKey(trimmed, "options")) {
                inOptions = true;
                optionsIndent = indent;
                out.append(line);
                continue;
            }
            if (inOptions && secretFilter) {
                out.append(maskSecretOptionLine(line, trimmed));
                continue;
            }
            out.append(line);
        }
        return out.toString();
    }

    private static String maskSecretOptionLine(String line, String trimmed) {
        for (var key : INLINE) {
            if (isKey(trimmed, key)) {
                int colon = line.indexOf(':');
                if (colon < 0) {
                    return line;
                }
                return line.substring(0, colon + 1) + " " + ConfigCodec.quote(MASK);
            }
        }
        return line;
    }

    private static boolean isKey(String trimmed, String key) {
        return trimmed.startsWith(key + ":") || trimmed.startsWith(key + " :");
    }

    private static boolean nameDeclaresSecretFilter(String rest) {
        return isKey(rest, "name") && secretFilterName(scalarAfterColon(rest));
    }

    private static boolean secretFilterName(String raw) {
        if (raw == null) {
            return false;
        }
        var name = raw.strip();
        if ((name.startsWith("\"") && name.endsWith("\"")) || (name.startsWith("'") && name.endsWith("'"))) {
            name = name.substring(1, name.length() - 1);
        }
        return SECRET_FILTERS.contains(name);
    }

    private static String scalarAfterColon(String trimmed) {
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return trimmed.substring(colon + 1).strip();
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
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

    /**
     * Operator doc pointer embedded in the CP startup WARN for leftover inline filter secrets.
     * Matches the rotation note under middleware in {@code docs/user/config-file.md}.
     */
    public static final String ROTATION_DOC = "docs/user/config-file.md (middleware credential file refs)";

    private static final java.util.concurrent.atomic.AtomicBoolean STARTUP_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Filter/option locations that still carry inline secrets. Names the filter index, name,
     * optional route, and option key only — never a value, length, or hash. Empty when clean.
     */
    public static List<String> inlineLocations(List<MiddlewareSpec> middleware) {
        if (middleware == null || middleware.isEmpty()) {
            return List.of();
        }
        var out = new java.util.ArrayList<String>();
        for (int i = 0; i < middleware.size(); i++) {
            var step = middleware.get(i);
            for (var key : INLINE) {
                if (step.options().containsKey(key)) {
                    var route = step.route() == null || step.route().isBlank() ? "" : " route=" + step.route();
                    out.add("middleware[" + i + "] (" + step.name() + route + ") option '" + key + "'");
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * One WARN body covering applied, activated and last-good middleware that still hold inline
     * {@code keys}/{@code hmac-keys}, or {@code null} when all three are clean. Never echoes a
     * secret value, its length, or a hash. Points at {@link #ROTATION_DOC}.
     */
    public static String startupWarnMessage(List<MiddlewareSpec> applied, List<MiddlewareSpec> activated,
            List<MiddlewareSpec> lastGood) {
        var parts = new java.util.ArrayList<String>();
        appendSource(parts, "applied", applied);
        appendSource(parts, "activated", activated);
        appendSource(parts, "last-good", lastGood);
        if (parts.isEmpty()) {
            return null;
        }
        return "control-plane config still holds inline gateway filter secrets in "
                + String.join("; ", parts)
                + "; move them to keys-file / hmac-keys-file, rotate the affected keys, then let a"
                + " snapshot and compaction drop the old log entries — see " + ROTATION_DOC;
    }

    private static void appendSource(java.util.List<String> parts, String source, List<MiddlewareSpec> middleware) {
        var locs = inlineLocations(middleware);
        if (locs.isEmpty()) {
            return;
        }
        parts.add(source + ": " + String.join(", ", locs));
    }

    /**
     * Logs at most one WARNING per process for leftover inline secrets in applied / activated /
     * last-good CP config. No-op when every source is clean. Safe on the CP-off path: callers must
     * not invoke this unless CP config is being inspected.
     */
    public static void logStartupWarn(System.Logger log, List<MiddlewareSpec> applied,
            List<MiddlewareSpec> activated, List<MiddlewareSpec> lastGood) {
        var msg = startupWarnMessage(applied, activated, lastGood);
        if (msg == null || log == null) {
            return;
        }
        if (STARTUP_WARNED.compareAndSet(false, true)) {
            log.log(System.Logger.Level.WARNING, msg);
        }
    }

    /** Clears the once-per-process gate so tests can assert the WARN again. */
    public static void resetStartupWarnGateForTest() {
        STARTUP_WARNED.set(false);
    }
}
