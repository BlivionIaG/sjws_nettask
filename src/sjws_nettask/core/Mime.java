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
package sjws_nettask.core;

import java.util.Locale;
import java.util.Map;

/** File extension to media type, plus the charset rule for textual types. */
public final class Mime {

    public static final String DEFAULT = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("css", "text/css"),
            Map.entry("js", "application/javascript"),
            Map.entry("mjs", "application/javascript"),
            Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("xml", "application/xml"),
            Map.entry("map", "application/json"),
            Map.entry("webmanifest", "application/manifest+json"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("wav", "audio/wav"),
            Map.entry("gz", "application/gzip"),
            Map.entry("tar", "application/x-tar"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("wasm", "application/wasm"),
            Map.entry("zip", "application/zip"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("ogg", "application/ogg"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"));

    private Mime() {
    }

    /** @return media type for a file name or path, without parameters */
    public static String of(String path) {
        int dot = path.lastIndexOf('.');
        if (dot == -1 || dot == path.length() - 1) {
            return DEFAULT;
        }
        return TYPES.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT), DEFAULT);
    }

    /** @return the Content-Type value: textual types are served as UTF-8 */
    public static String contentType(String mime) {
        if (mime.startsWith("text/") || mime.equals("application/javascript") || mime.equals("application/json")
                || mime.equals("image/svg+xml") || mime.equals("application/xml")
                || mime.equals("application/manifest+json")) {
            return mime + "; charset=utf-8";
        }
        return mime;
    }

    public static Map<String, String> types() {
        return TYPES;
    }
}
