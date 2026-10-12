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
import java.util.regex.Pattern;

/**
 * Sizes in the config file: a bare integer is bytes, otherwise {@code k}, {@code m} or {@code g}
 * (1024-based). {@code 1m} is one mebibyte here and one minute in {@link Durations}.
 */
public final class Sizes {

    private static final Pattern TOKEN = Pattern.compile("([0-9]{1,18})([kmgKMG]?)");

    private Sizes() {
    }

    public static long parse(String value, long max, String what) {
        if (value == null || value.isBlank()) {
            throw new ConfigException(what + ": missing size");
        }
        var matcher = TOKEN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new ConfigException(what + ": expected a size such as 0, 65536, 64k, 16m or 1g");
        }
        long amount = Long.parseLong(matcher.group(1));
        int shift = switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 10;
            case "m" -> 20;
            case "g" -> 30;
            default -> 0;
        };
        if (shift > 0 && amount > (Long.MAX_VALUE >> shift)) {
            throw new ConfigException(what + ": size overflows");
        }
        long bytes = amount << shift;
        if (bytes < 0 || bytes > max) {
            throw new ConfigException(what + ": size must be 0.." + max);
        }
        return bytes;
    }
}
