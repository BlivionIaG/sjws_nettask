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
package sjws_nettask.HTTP;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Writes complete HTTP responses (head + body) to an output stream.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public final class Page {

    private static final int TRANSFER_BUFFER = 64 * 1024;

    private Page() {
    }

    /**
     * Sends exactly the number of bytes the file had when it was opened. If the
     * file shrinks during the transfer an IOException is thrown (the caller must
     * close the connection); growth is ignored.
     */
    public static int sendFile(OutputStream out, Path file, boolean headOnly, boolean keepAlive) throws IOException {
        return sendFile(out, file, headOnly, keepAlive, null);
    }

    public static int sendFile(OutputStream out, Path file, boolean headOnly, boolean keepAlive,
            String ifModifiedSince) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            var modified = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);
            var since = parseHttpDate(ifModifiedSince);
            if (since != null && !modified.isAfter(since)) {
                out.write(new Header(304, file.getFileName().toString(), size)
                        .lastModified(modified).keepAlive(keepAlive).toBytes());
                out.flush();
                return 304;
            }

            var header = new Header(200, file.getFileName().toString(), size)
                    .lastModified(modified)
                    .keepAlive(keepAlive);
            out.write(header.toBytes());
            if (!headOnly) {
                var buffer = ByteBuffer.allocate((int) Math.min(TRANSFER_BUFFER, Math.max(size, 1)));
                long position = 0;
                while (position < size) {
                    buffer.clear().limit((int) Math.min(buffer.capacity(), size - position));
                    int n = channel.read(buffer, position);
                    if (n < 0) {
                        throw new IOException("file shrank during transfer: " + file);
                    }
                    out.write(buffer.array(), 0, n);
                    position += n;
                }
            }
            out.flush();
            return 200;
        }
    }

    static Instant parseHttpDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null; // invalid dates are ignored (RFC 9110 13.1.3)
        }
    }

    public static int sendRedirect(OutputStream out, String location, boolean headOnly, boolean keepAlive)
            throws IOException {
        var body = "<!DOCTYPE html><html><body><a href=\"" + escape(location) + "\">Moved</a></body></html>";
        return sendBody(out, 301, "text/html", body, headOnly, keepAlive, "Location", location);
    }

    /** @param names entries to list; directories end with "/" */
    public static int sendFolder(OutputStream out, List<String> names, String urlPath, boolean headOnly,
            boolean keepAlive) throws IOException {
        var base = urlPath.endsWith("/") ? urlPath : urlPath + "/";
        var title = escape(base);
        var html = new StringBuilder(256 + names.size() * 64)
                .append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>").append(title)
                .append("</title></head><body><h1>").append(title).append("</h1>");
        for (var name : names) {
            html.append("<a href=\"").append(escape(encodePath(base) + encodeSegment(name)))
                    .append("\"> ").append(escape(name)).append(" </a><br>");
        }
        html.append("</body></html>");

        return sendBody(out, 200, "text/html", html.toString(), headOnly, keepAlive);
    }

    public static int sendError(OutputStream out, int code, boolean headOnly, boolean keepAlive) throws IOException {
        var title = code + " " + Header.reasonPhrase(code);
        var body = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>" + title
                + "</title></head><body><h1>" + title + "</h1></body></html>";
        return sendBody(out, code, "text/html", body, headOnly, keepAlive);
    }

    public static int sendBody(OutputStream out, int code, String mime, String body, boolean headOnly,
            boolean keepAlive, String... extraHeaders) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var header = new Header(code, "", mime, bytes.length).keepAlive(keepAlive);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            header.with(extraHeaders[i], extraHeaders[i + 1]);
        }
        out.write(header.toBytes());
        if (!headOnly) {
            out.write(bytes);
        }
        out.flush();
        return code;
    }

    static String escape(String s) {
        var sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String encodeSegment(String segment) {
        var trailingSlash = segment.endsWith("/");
        var name = trailingSlash ? segment.substring(0, segment.length() - 1) : segment;
        var encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return trailingSlash ? encoded + "/" : encoded;
    }

    private static String encodePath(String path) {
        var parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20");
        }
        return String.join("/", parts);
    }
}
