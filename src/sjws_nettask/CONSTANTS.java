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

import java.util.Collections;
import java.util.Map;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class CONSTANTS {

    public final static int DEFAULT_PORT = 8080; // Default Port
    public final static char[] ALPHANUMERIC_SET = ("1234567890"
            + "AZERTYUIOPQSDFGHJKLMWXCVBN"
            + "azertyuiopqsdfghjklmwxcvbn").toCharArray();
    public final static int ID_SIZE = 16; // Default ID Size

    public final static String DEFAULT_HTML_PATH = "./HTML";
    public final static int HTML_BUFFER_SIZE = 65536;

    public static final String[] DAYS = {
        "Sun",
        "Mon",
        "Tue",
        "Wed",
        "Thu",
        "Fri",
        "Sat"
    };

    public static final String[] MONTHS = {
        "Jan",
        "Feb",
        "Mar",
        "Apr",
        "May",
        "Jun",
        "Jul",
        "Aug",
        "Sep",
        "Oct",
        "Nov",
        "Dec"
    };

    public static final Map<String, String> MIME_TYPE = Map.ofEntries(
        Map.entry("html", "text/html"),
        Map.entry("css", "text/css"),
        Map.entry("js", "application/javascript"),
        Map.entry("txt", "text/plain"),
        Map.entry("zip", "application/zip"),
        Map.entry("png", "image/png"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("ogg", "application/ogg"),
        Map.entry("mp4", "video/mp4"),
        Map.entry("webm", "video/webm"),
        Map.entry("pdf", "application/pdf")
    );
}
