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

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Durations in the config file: a bare integer is seconds, otherwise {@code ms}, {@code s},
 * {@code m} (minutes), {@code h} or {@code d}. {@code 1m} is one minute here and one mebibyte in
 * {@link Sizes}.
 */
public final class Durations {

    private static final Pattern TOKEN = Pattern.compile("([0-9]{1,18})(ms|s|m|h|d)");

    private Durations() {
    }

    public static Duration parse(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new ConfigException(what + ": missing duration");
        }
        var text = value.trim();
        if (text.matches("[0-9]{1,18}")) {
            return requirePositive(Duration.ofSeconds(Long.parseLong(text)), what);
        }
        var matcher = TOKEN.matcher(text);
        if (!matcher.matches()) {
            throw new ConfigException(what + ": expected a duration such as 30, 5s, 500ms, 1m, 1h or 1d");
        }
        long amount = Long.parseLong(matcher.group(1));
        var duration = switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new ConfigException(what + ": expected a duration such as 30, 5s, 500ms, 1m, 1h or 1d");
        };
        return requirePositive(duration, what);
    }

    private static Duration requirePositive(Duration duration, String what) {
        if (duration.isZero() || duration.isNegative() || duration.toMillis() < 1) {
            throw new ConfigException(what + ": duration must be at least 1ms");
        }
        return duration;
    }
}
