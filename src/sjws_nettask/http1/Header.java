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
package sjws_nettask.http1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import sjws_nettask.core.HttpDate;
import sjws_nettask.core.Mime;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * HTTP/1.1 response head. Legacy API kept for existing callers: it builds core
 * {@link ResponseHeaders} and delegates serialization to {@link Http1Writer}.
 *
 * @author BlivionIaG <kev29lt@gmail.com>
 */
public class Header {

    public static final String DEFAULT_ACCEPT_RANGE = StaticFileHandler.ACCEPT_RANGES;
    public static final String DEFAULT_PROTOCOL = "1.1";
    public static final String DEFAULT_SERVER_NAME = "sjws_nettask web server";
    public static final String DEFAULT_SERVER_VERSION = "0.2";
    public static final String DEFAULT_CONNECTION = "keep-alive";
    public static final String DEFAULT_MIME = Mime.DEFAULT;

    private final int httpCode;
    private final String contentType;
    private final long contentLength;
    private final Instant date = Instant.now();
    private Instant lastModified;
    private boolean keepAlive = true;
    private final ResponseHeaders extra = ResponseHeaders.of();

    public Header(int httpCode, String path, long fileSize) {
        this(httpCode, path, getMime(path), fileSize);
    }

    public Header(int httpCode, String path, String mime, long fileSize) {
        this.httpCode = httpCode;
        this.contentType = mime;
        this.contentLength = fileSize;
    }

    public static String getMime(String path) {
        return Mime.of(path);
    }

    public static String reasonPhrase(int code) {
        return StaticFileHandler.reasonPhrase(code);
    }

    static String withCharset(String mime) {
        return Mime.contentType(mime);
    }

    public Header lastModified(Instant instant) {
        this.lastModified = instant;
        return this;
    }

    public Header keepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
        return this;
    }

    public Header with(String name, String value) {
        this.extra.add(name, value);
        return this;
    }

    public int getHttpCode() {
        return this.httpCode;
    }

    public byte[] toBytes() {
        return toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Override
    public String toString() {
        var headers = ResponseHeaders.of();
        if (this.lastModified != null) {
            headers.add("last-modified", HttpDate.format(this.lastModified));
        }
        headers.add("accept-ranges", DEFAULT_ACCEPT_RANGE).add("content-type", withCharset(this.contentType));
        for (var e : this.extra.entries()) {
            headers.add(e.getKey(), e.getValue());
        }
        boolean withLength = this.httpCode >= 200 && this.httpCode != 204 && this.httpCode != 304;
        return Http1Writer.head(this.httpCode, headers, withLength, this.contentLength, this.keepAlive,
                ResponseHeaders.of(), this.date);
    }
}
