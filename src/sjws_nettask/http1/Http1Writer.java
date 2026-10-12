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
package sjws_nettask.http1;

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
import sjws_nettask.core.DateClock;
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

    /** Names whose canonical case is not simple title case, plus the common ones (saves building them). */
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("etag", "ETag"),
            Map.entry("www-authenticate", "WWW-Authenticate"),
            Map.entry("content-md5", "Content-MD5"),
            Map.entry("x-xss-protection", "X-XSS-Protection"),
            Map.entry("dnt", "DNT"),
            Map.entry("te", "TE"),
            Map.entry("last-modified", "Last-Modified"),
            Map.entry("accept-ranges", "Accept-Ranges"),
            Map.entry("content-type", "Content-Type"),
            Map.entry("location", "Location"),
            Map.entry("allow", "Allow"),
            Map.entry("alt-svc", "Alt-Svc"),
            Map.entry("cache-control", "Cache-Control"),
            Map.entry("vary", "Vary"));

    /**
     * Largest in-memory body sent together with the head in one {@code write}: covers every body the
     * file cache holds ({@link sjws_nettask.core.FileCache#MAX_FILE_SIZE}) plus listings and error pages.
     */
    static final int SINGLE_WRITE_MAX = 256 * 1024;

    private static final int TRANSFER_BUFFER = 64 * 1024;

    /** Files up to this size are read whole and leave with their head in one write. */
    static final int SMALL_FILE_MAX = TRANSFER_BUFFER;

    /**
     * An output that can send file ranges itself (sendfile). {@link #write} hands it file bodies
     * larger than {@link #SMALL_FILE_MAX} after writing the head.
     */
    public interface FileSink {
        /**
         * @throws IOException if the file ends before {@code position + count} (it shrank) or the
         *         transfer fails; the response is then incomplete and the connection must close
         */
        void transferFrom(FileChannel source, long position, long count) throws IOException;
    }
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
        return head(status, headers, withLength, length, keepAlive, extra, HttpDate.format(now));
    }

    /** Same, with the Date value already formatted (see {@link DateClock}). */
    public static String head(int status, ResponseHeaders headers, boolean withLength, long length,
            boolean keepAlive, ResponseHeaders extra, String date) {
        var sb = new StringBuilder(256)
                .append("HTTP/1.1 ").append(status).append(' ').append(StaticFileHandler.reasonPhrase(status)).append(CRLF)
                .append("Server: ").append(sjws_nettask.core.ServerInfo.NAME).append(CRLF)
                .append("Date: ").append(date).append(CRLF);
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
                keepAlive, extra, DateClock.SYSTEM.now()).getBytes(StandardCharsets.ISO_8859_1);
    }

    public static void write(OutputStream out, Response response, boolean keepAlive) throws IOException {
        write(out, response, keepAlive, ResponseHeaders.of());
    }

    /**
     * Writes head and body and flushes.
     *
     * If the file of a {@link Body.FileBody} can't be opened as resolved (deleted, swapped, not a
     * regular file, permission denied), nothing of {@code response} has been written yet, so the
     * matching error response (404/403/500, see {@link SafeOpen#status}) is written instead.
     *
     * @throws IOException if the response could not be completed (the file shrank during the
     *         transfer, after a partial body, or the client went away); the caller must close the
     *         connection.
     */
    public static void write(OutputStream out, Response response, boolean keepAlive, ResponseHeaders extra)
            throws IOException {
        boolean sendBody = response.sendsBody();
        if (sendBody && response.body() instanceof Body.FileBody file) {
            java.nio.channels.FileChannel opened;
            try {
                opened = SafeOpen.open(file);
            } catch (IOException e) { // the status isn't committed yet: answer with the matching error
                write(out, sjws_nettask.core.StaticFileHandler.error(SafeOpen.status(e), false), keepAlive, extra);
                return;
            }
            try (var channel = opened) {
                var head = serializeHead(response, keepAlive, extra);
                if (file.length() <= SMALL_FILE_MAX) {
                    // head + body in one write; a file that shrank is detected before anything is sent
                    var all = new byte[head.length + (int) file.length()];
                    System.arraycopy(head, 0, all, 0, head.length);
                    readFully(channel, file, ByteBuffer.wrap(all, head.length, (int) file.length()));
                    out.write(all);
                } else if (out instanceof FileSink sink) {
                    out.write(head);
                    out.flush();
                    sink.transferFrom(channel, file.offset(), file.length());
                } else {
                    out.write(head);
                    copy(channel, file, out);
                }
            }
        } else if (sendBody && response.body() instanceof Body.Bytes bytes
                && bytes.length() <= SINGLE_WRITE_MAX) {
            // head + body in one write (one syscall even when it exceeds the stream's buffer)
            var head = serializeHead(response, keepAlive, extra);
            var all = new byte[head.length + (int) bytes.length()];
            System.arraycopy(head, 0, all, 0, head.length);
            bytes.data().duplicate().get(all, head.length, (int) bytes.length());
            out.write(all);
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

    private static void readFully(FileChannel channel, Body.FileBody file, ByteBuffer into) throws IOException {
        long position = file.offset();
        while (into.hasRemaining()) {
            int n = channel.read(into, position);
            if (n < 0) {
                throw new IOException("file shrank during transfer: " + file.realPath());
            }
            position += n;
        }
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
