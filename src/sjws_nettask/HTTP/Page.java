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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes complete HTTP responses (head + body) to an output stream.
 *
 * @author BlivionIaG <BlivionIaG at chenco.tk>
 */
public final class Page {

    private Page() {
    }

    public static int sendFile(OutputStream out, Path file, boolean headOnly, boolean keepAlive) throws IOException {
        var header = new Header(200, file.getFileName().toString(), Files.size(file))
                .lastModified(Files.getLastModifiedTime(file).toInstant())
                .keepAlive(keepAlive);
        out.write(header.toBytes());
        if (!headOnly) {
            Files.copy(file, out); // streams in chunks, never loads the whole file
        }
        out.flush();
        return 200;
    }

    public static int sendFolder(OutputStream out, Path folder, String urlPath, boolean headOnly, boolean keepAlive)
            throws IOException {
        var base = urlPath.endsWith("/") ? urlPath : urlPath + "/";
        List<String> names = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            entries.forEach(p -> names.add(p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "")));
        }
        names.sort(String::compareTo);

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
