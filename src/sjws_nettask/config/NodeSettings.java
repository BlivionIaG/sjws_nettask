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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where this process sits. {@link Mode#STANDALONE} is the default and is not consulted on the
 * request path. {@link Mode#CONTROL} is a control-plane node. {@link Mode#DATA} serves traffic
 * and keeps the last good config when the control plane is unreachable.
 *
 * @param labels selector map pushed with the config; empty when unset
 */
public record NodeSettings(Mode mode, Map<String, String> labels) {

    public static final int MAX_LABELS = 64;
    public static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_.:-]{1,63}");
    public static final NodeSettings STANDALONE = new NodeSettings(Mode.STANDALONE, Map.of());

    public NodeSettings {
        Objects.requireNonNull(mode, "mode");
        labels = labels == null || labels.isEmpty() ? Map.of() : Map.copyOf(labels);
        if (labels.size() > MAX_LABELS) {
            throw new ConfigException("node.labels: at most " + MAX_LABELS + " entries");
        }
        for (var entry : labels.entrySet()) {
            if (!LABEL.matcher(entry.getKey()).matches() || entry.getValue() == null
                    || !LABEL.matcher(entry.getValue()).matches()) {
                throw new ConfigException("node.labels: invalid entry");
            }
        }
    }

    public enum Mode {
        STANDALONE, CONTROL, DATA;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new ConfigException("node.mode: missing value");
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "standalone" -> STANDALONE;
                case "control" -> CONTROL;
                case "data" -> DATA;
                default -> throw new ConfigException("node.mode: expected standalone, control or data");
            };
        }

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
