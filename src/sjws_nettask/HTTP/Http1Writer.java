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
package sjws_nettask.HTTP;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import sjws_nettask.core.Body;
import sjws_nettask.core.HttpDate;
import sjws_nettask.core.Response;
import sjws_nettask.core.SafeOpen;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * Serializes a core {@link Response} as HTTP/1.1.
 * <p>
 * Head layout (fixed, byte-compatible with the pre-core server):
 * status line, Server, Date, representation fields ({@link #REPRESENTATION}, in response order),
 * Connection, Content-Length (omitted for 1xx/204/304), remaining response fields in order,
 * then the transport's extra fields (e.g. Alt-Svc).
 */
public final class Http1Writer {


    /** Fields written before Connection/Content-Length. */
    static final Set<String> REPRESENTATION = Set.of(
            "last-modified", "etag", "accept-ranges", "content-type", "content-encoding", "vary");

    /** Names whose canonical case is not simple title case. */
    private static final Map<String, String> CANONICAL = Map.of(
            "etag", "ETag",
            "www-authenticate", "WWW-Authenticate",
            "content-md5", "Content-MD5",
            "x-xss-protection", "X-XSS-Protection",
            "dnt", "DNT",
            "te", "TE");

    private static final int TRANSFER_BUFFER = 64 * 1024;
    private static final String CRLF = "\r\n";

    private Http1Writer() {
    }

    /** "content-type" -> "Content-Type", "etag" -> "ETag". */
    public static String canonicalName(String lowerCaseName) {
        var special = CANONICAL.get(lowerCaseName);
        if (special != null) {
            return special;
        }
        var sb = new StringBuilder(lowerCaseName.length());
        boolean upper = true;
        for (int i = 0; i < lowerCaseName.length(); i++) {
            char c = lowerCaseName.charAt(i);
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = c == '-';
        }
        return sb.toString();
    }

    /** Status line and header fields, terminated by the empty line. */
    public static String head(int status, ResponseHeaders headers, boolean withLength, long length,
            boolean keepAlive, ResponseHeaders extra, Instant now) {
        var sb = new StringBuilder(256)
                .append("HTTP/1.1 ").append(status).append(' ').append(StaticFileHandler.reasonPhrase(status)).append(CRLF)
                .append("Server: ").append(sjws_nettask.core.ServerInfo.NAME).append(CRLF)
                .append("Date: ").append(HttpDate.format(now)).append(CRLF);
        var entries = headers.entries();
        for (var e : entries) {
            if (REPRESENTATION.contains(e.getKey())) {
                field(sb, e.getKey(), e.getValue());
            }
        }
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append(CRLF);
        if (withLength) {
            sb.append("Content-Length: ").append(length).append(CRLF);
        }
        for (var e : entries) {
            if (!REPRESENTATION.contains(e.getKey())) {
                field(sb, e.getKey(), e.getValue());
            }
        }
        for (var e : extra.entries()) {
            field(sb, e.getKey(), e.getValue());
        }
        return sb.append(CRLF).toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append(canonicalName(name.toLowerCase(Locale.ROOT))).append(": ").append(value).append(CRLF);
    }

    public static byte[] serializeHead(Response response, boolean keepAlive, ResponseHeaders extra) {
        return head(response.status(), response.headers(), response.mayHaveBody(), response.contentLength(),
                keepAlive, extra, Instant.now()).getBytes(StandardCharsets.ISO_8859_1);
    }

    public static void write(OutputStream out, Response response, boolean keepAlive) throws IOException {
        write(out, response, keepAlive, ResponseHeaders.of());
    }

    /**
     * Writes head and body and flushes.
     *
     * @throws IOException if the response could not be completed. If the file of a
     *         {@link Body.FileBody} was swapped, this happens before anything is written; if it
     *         shrinks during the transfer, after a partial body. Either way the caller must close
     *         the connection.
     */
    public static void write(OutputStream out, Response response, boolean keepAlive, ResponseHeaders extra)
            throws IOException {
        boolean sendBody = response.sendsBody();
        if (sendBody && response.body() instanceof Body.FileBody file) {
            try (var channel = SafeOpen.open(file)) {
                out.write(serializeHead(response, keepAlive, extra));
                copy(channel, file, out);
            }
        } else {
            out.write(serializeHead(response, keepAlive, extra));
            if (sendBody && response.body() instanceof Body.Bytes bytes) {
                var data = bytes.data().duplicate();
                var chunk = new byte[(int) Math.min(TRANSFER_BUFFER, Math.max(1, data.remaining()))];
                while (data.hasRemaining()) {
                    int n = Math.min(chunk.length, data.remaining());
                    data.get(chunk, 0, n);
                    out.write(chunk, 0, n);
                }
            }
        }
        out.flush();
    }

    private static void copy(FileChannel channel, Body.FileBody file, OutputStream out) throws IOException {
        long size = file.length();
        var buffer = ByteBuffer.allocate((int) Math.min(TRANSFER_BUFFER, Math.max(size, 1)));
        long done = 0;
        while (done < size) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), size - done));
            int n = channel.read(buffer, file.offset() + done);
            if (n < 0) {
                throw new IOException("file shrank during transfer: " + file.realPath());
            }
            out.write(buffer.array(), 0, n);
            done += n;
        }
    }
}
