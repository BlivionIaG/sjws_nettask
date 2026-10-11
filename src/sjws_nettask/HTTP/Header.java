/*
 * Copyright (C) 2024 BlivionIaG <kev29lt@gmail.com>
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
package sjws_nettask.HTTP;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import sjws_nettask.CONSTANTS;
import sjws_nettask.Tools;

/**
 * HTTP/1.1 response head (status line + header fields).
 *
 * @author BlivionIaG <kev29lt@gmail.com>
 */
public class Header {

    public static final String DEFAULT_ACCEPT_RANGE = "none"; // Range requests are not supported
    public static final String DEFAULT_PROTOCOL = "1.1";
    public static final String DEFAULT_SERVER_NAME = "sjws_nettask web server";
    public static final String DEFAULT_SERVER_VERSION = "0.2";
    public static final String DEFAULT_CONNECTION = "keep-alive";
    public static final String DEFAULT_MIME = "application/octet-stream";

    private static final String CRLF = "\r\n";

    private final int httpCode;
    private final String contentType;
    private final long contentLength;
    private final String currentDate;
    private String lastModified; // null -> header omitted
    private String connection = DEFAULT_CONNECTION;
    private final Map<String, String> extra = new LinkedHashMap<>();

    public Header(int httpCode, String path, long fileSize) {
        this(httpCode, path, getMime(path), fileSize);
    }

    public Header(int httpCode, String path, String mime, long fileSize) {
        this.httpCode = httpCode;
        this.contentType = mime;
        this.contentLength = fileSize;
        this.currentDate = Tools.httpDate(Instant.now());
    }

    public static String getMime(String path) {
        int extensionIndex = path.lastIndexOf('.');

        if (extensionIndex == -1 || extensionIndex == path.length() - 1) {
            return DEFAULT_MIME;
        }

        var extension = path.substring(extensionIndex + 1).toLowerCase(Locale.ROOT);
        return CONSTANTS.MIME_TYPE.getOrDefault(extension, DEFAULT_MIME);
    }

    public static String reasonPhrase(int code) {
        return switch (code) {
            case 100 -> "Continue";
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 417 -> "Expectation Failed";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 505 -> "HTTP Version Not Supported";
            default -> "Unknown";
        };
    }

    public Header lastModified(Instant instant) {
        this.lastModified = Tools.httpDate(instant);
        return this;
    }

    public Header keepAlive(boolean keepAlive) {
        this.connection = keepAlive ? "keep-alive" : "close";
        return this;
    }

    public Header with(String name, String value) {
        this.extra.put(name, value);
        return this;
    }

    public int getHttpCode() {
        return this.httpCode;
    }

    public byte[] toBytes() {
        return toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Textual types are served as UTF-8. */
    static String withCharset(String mime) {
        if (mime.startsWith("text/") || mime.equals("application/javascript") || mime.equals("application/json")
                || mime.equals("image/svg+xml")) {
            return mime + "; charset=utf-8";
        }
        return mime;
    }

    @Override
    public String toString() {
        var type = withCharset(this.contentType);
        var sb = new StringBuilder(256)
                .append("HTTP/").append(DEFAULT_PROTOCOL).append(' ')
                .append(this.httpCode).append(' ').append(reasonPhrase(this.httpCode)).append(CRLF)
                .append("Server: ").append(DEFAULT_SERVER_NAME).append('/').append(DEFAULT_SERVER_VERSION).append(CRLF)
                .append("Date: ").append(this.currentDate).append(CRLF);
        if (this.lastModified != null) {
            sb.append("Last-Modified: ").append(this.lastModified).append(CRLF);
        }
        sb.append("Accept-Ranges: ").append(DEFAULT_ACCEPT_RANGE).append(CRLF)
                .append("Content-Type: ").append(type).append(CRLF)
                .append("Connection: ").append(this.connection).append(CRLF);
        if (this.httpCode != 304) { // a 304 has no body; omit the length to keep naive clients happy
            sb.append("Content-Length: ").append(this.contentLength).append(CRLF);
        }
        this.extra.forEach((k, v) -> sb.append(k).append(": ").append(v).append(CRLF));
        return sb.append(CRLF).toString();
    }
}
