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

import java.util.Map;

/**
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public class CONSTANTS {

    public final static int DEFAULT_PORT = 8080; // Default Port
    public final static String DEFAULT_HTML_PATH = "./HTML";
    public final static int SOCKET_TIMEOUT_MS = 30_000; // idle keep-alive timeout
    public final static int MAX_LINE_LENGTH = 8192; // request line / header line limit
    public final static int MAX_HEADERS = 100;

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
