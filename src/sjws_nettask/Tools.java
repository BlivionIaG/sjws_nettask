/*
 * Copyright (C) 2019 BlivionIaG <BlivionIaG at chenco.tk>
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
package sjws_nettask;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public final class Tools {

    /** IMF-fixdate (RFC 9110 section 5.6.7), e.g. "Sun, 06 Nov 1994 08:49:37 GMT". */
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private Tools() {
    }

    /**
     *
     * @param value String to check
     * @return result of the verification
     */
    public static boolean isNumeric(String value) {
        return value != null && value.matches("[-+]?\\d*\\.?\\d+");
    }

    /**
     * @param instant point in time
     * @return the instant formatted as an HTTP date (always GMT)
     */
    public static String httpDate(Instant instant) {
        return HTTP_DATE.format(instant);
    }

    /**
     * Generate readable current date
     *
     * @return HTTP "Date" header line (without line terminator)
     */
    public static String formatedCurrentDate() {
        return "Date: " + httpDate(Instant.now());
    }
}
