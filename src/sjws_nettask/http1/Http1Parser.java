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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import sjws_nettask.core.RequestRules;

/**
 * Strict HTTP/1.x request-head parser (RFC 9112). Anything ambiguous enough to
 * enable request smuggling is rejected with a {@link ParseError}.
 * <p>
 * The result is the wire-level {@link Head}; the protocol-neutral core Request
 * planned in the architecture docs can be built from it (method, target,
 * conditionals such as {@link Head#ifModifiedSince()}).
 */
public final class Http1Parser {

    /** Methods we recognise; of these only GET and HEAD are implemented (others: 405). */
    public static final Set<String> KNOWN_METHODS = RequestRules.KNOWN_METHODS;

    public static final int MAX_LEADING_EMPTY_LINES = 4;

    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern VERSION = Pattern.compile("HTTP/[0-9]\\.[0-9]");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");

    private Http1Parser() {
    }

    /**
     * A parsed HTTP/1.x request head.
     *
     * @param method request method (case-sensitive token)
     * @param target origin-form target ("/path?query"), also for absolute-form requests
     * @param version "HTTP/1.0" or "HTTP/1.1"
     * @param headers lower-cased field name to values, in arrival order (unmodifiable)
     * @param contentLength declared body length (0 if none)
     * @param keepAlive whether the connection may be reused after the response
     * @param expectContinue the client waits for "100 Continue" before sending the body
     */
    public record Head(String method, String target, String version, Map<String, List<String>> headers,
            long contentLength, boolean keepAlive, boolean expectContinue) {

        public boolean headOnly() {
            return "HEAD".equals(this.method);
        }

        /** @return first value of the (case-insensitive) header, or null */
        public String header(String name) {
            var values = this.headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        public List<String> headers(String name) {
            return this.headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }

        public String ifModifiedSince() {
            return header("if-modified-since");
        }
    }

    /**
     * Reads one request head from a stream, one byte at a time (never past the head's final
     * line feed, so the body stays in {@code in}). Same rules as {@link HeadParser}.
     *
     * @return the request, or null if the stream ended cleanly before a request started
     * @throws ParseError for malformed or unsupported requests (answer with its status and close)
     * @throws EOFException if the stream ended in the middle of a request head
     */
    public static Head read(InputStream in, int maxLineLength, int maxHeaders) throws IOException, ParseError {
        var parser = new HeadParser(maxLineLength, maxHeaders);
        var one = new byte[1];
        while (true) {
            int b = in.read();
            if (b == -1) {
                return parser.endOfInput();
            }
            one[0] = (byte) b;
            parser.feed(one, 0, 1);
            if (parser.isComplete()) {
                return parser.head();
            }
        }
    }

    /**
     * Incremental, byte-level request-head parser for channel transports: feed it whatever the
     * socket returned; each complete line is validated once, as soon as it ends. The rules are
     * those of {@link #read(InputStream, int, int)} (which is built on it):
     * <ul>
     * <li>lines end with CRLF or a bare LF; a CR not followed by LF is a 400 (also at end of input);</li>
     * <li>a line holding more than {@code maxLineLength} bytes (CR/LF not counted) is a 414 for the
     * request line, 431 for a header line; more than {@code maxHeaders} fields is a 431;</li>
     * <li>at most {@link #MAX_LEADING_EMPTY_LINES} empty lines before the request line;</li>
     * <li>request line, field syntax, Host, Transfer-Encoding, Content-Length, Connection and Expect
     * checks are unchanged (smuggling vectors are rejected, never guessed).</li>
     * </ul>
     * Not thread-safe; reuse one per connection with {@link #reset()}.
     */
    public static final class HeadParser {

        private static final int INITIAL_LINE = 256;

        private final int maxLineLength;
        private final int maxHeaders;
        private byte[] line = new byte[INITIAL_LINE];
        private int lineLength;
        private boolean pendingCr;
        private int emptyLines;
        private boolean inHeaders;
        private int headerCount;
        private String method;
        private String target;
        private String version;
        private LinkedHashMap<String, List<String>> headers;
        private Head head;

        public HeadParser(int maxLineLength, int maxHeaders) {
            this.maxLineLength = maxLineLength;
            this.maxHeaders = maxHeaders;
        }

        /** Forgets the previous head (keep-alive); drops a line buffer grown by a long line. */
        public void reset() {
            if (this.line.length > 4 * INITIAL_LINE) {
                this.line = new byte[INITIAL_LINE];
            }
            this.lineLength = 0;
            this.pendingCr = false;
            this.emptyLines = 0;
            this.inHeaders = false;
            this.headerCount = 0;
            this.method = null;
            this.target = null;
            this.version = null;
            this.headers = null;
            this.head = null;
        }

        /** @return true once the empty line ending the head has been consumed */
        public boolean isComplete() {
            return this.head != null;
        }

        /** @return the parsed head (only when {@link #isComplete()}) */
        public Head head() {
            if (this.head == null) {
                throw new IllegalStateException("head not complete");
            }
            return this.head;
        }

        /**
         * Consumes bytes up to and including the head's final line feed.
         *
         * @return bytes consumed: {@code len}, or fewer when the head completed inside the range
         *         (the rest belongs to the body or the next pipelined request)
         * @throws ParseError as soon as a complete line (or an overlong one) breaks a rule
         */
        public int feed(byte[] buf, int off, int len) throws ParseError {
            if (this.head != null) {
                throw new IllegalStateException("head already complete; reset() first");
            }
            int end = off + len;
            int i = off;
            while (i < end) {
                byte b = buf[i++];
                if (this.pendingCr) {
                    if (b != '\n') {
                        throw new ParseError(400, "bare CR");
                    }
                    this.pendingCr = false;
                    if (completeLine()) {
                        return i - off;
                    }
                } else if (b == '\r') {
                    this.pendingCr = true;
                } else if (b == '\n') {
                    if (completeLine()) {
                        return i - off;
                    }
                } else {
                    if (this.lineLength >= this.maxLineLength) {
                        throw new ParseError(this.inHeaders ? 431 : 414, "line too long");
                    }
                    if (this.lineLength == this.line.length) {
                        this.line = Arrays.copyOf(this.line, Math.min(this.maxLineLength, 2 * this.line.length));
                    }
                    this.line[this.lineLength++] = b;
                }
            }
            return len;
        }

        /**
         * The peer closed its side.
         *
         * @return null if no request had started (only empty lines, or nothing, were received)
         * @throws EOFException if the input ended inside a request head
         * @throws ParseError if it ended right after a CR (bare CR)
         */
        public Head endOfInput() throws EOFException, ParseError {
            if (this.head != null) {
                return this.head;
            }
            if (this.pendingCr) {
                throw new ParseError(400, "bare CR");
            }
            if (!this.inHeaders && this.lineLength == 0) {
                return null;
            }
            throw new EOFException(this.lineLength == 0 ? "connection closed inside request head"
                    : "connection closed inside a line");
        }

        /** @return true if the line completed the head */
        private boolean completeLine() throws ParseError {
            var text = new String(this.line, 0, this.lineLength, StandardCharsets.ISO_8859_1);
            this.lineLength = 0;
            if (!this.inHeaders) {
                if (text.isEmpty()) {
                    if (++this.emptyLines > MAX_LEADING_EMPTY_LINES) {
                        throw new ParseError(400, "too many empty lines before request");
                    }
                    return false;
                }
                requestLine(text);
                this.inHeaders = true;
                this.headers = new LinkedHashMap<>();
                return false;
            }
            if (text.isEmpty()) {
                this.head = finish(this.method, this.target, this.version, this.headers);
                return true;
            }
            if (this.headerCount++ >= this.maxHeaders) {
                throw new ParseError(431, "too many header fields");
            }
            headerLine(text, this.headers);
            return false;
        }

        private void requestLine(String requestLine) throws ParseError {
            var parts = requestLine.split(" ", -1);
            if (parts.length != 3 || !TOKEN.matcher(parts[0]).matches() || parts[1].isEmpty()) {
                throw new ParseError(400, "malformed request line");
            }
            var m = parts[0];
            var v = parts[2];
            if (!VERSION.matcher(v).matches()) {
                throw new ParseError(400, "malformed HTTP version");
            }
            if (!v.equals("HTTP/1.1") && !v.equals("HTTP/1.0")) {
                throw new ParseError(505, "unsupported HTTP version");
            }
            if (RequestRules.checkMethod(m) != 0) {
                throw new ParseError(501, "unknown method");
            }
            if (RequestRules.checkTarget(parts[1]) == 400) { // (longer than the line limit: 414 above)
                throw new ParseError(400, "invalid character in request-target");
            }
            this.target = originForm(parts[1]);
            this.method = m;
            this.version = v;
        }
    }

    private static void headerLine(String line, Map<String, List<String>> headers) throws ParseError {
        char first = line.charAt(0);
        if (first == ' ' || first == '\t') {
            throw new ParseError(400, "obsolete line folding");
        }
        int colon = line.indexOf(':');
        if (colon <= 0 || !TOKEN.matcher(line.substring(0, colon)).matches()) {
            throw new ParseError(400, "invalid header field name");
        }
        var value = trimOws(line.substring(colon + 1));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                throw new ParseError(400, "control character in header value");
            }
        }
        headers.computeIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
    }

    private static Head finish(String method, String target, String version,
            LinkedHashMap<String, List<String>> headers) throws ParseError {
        var hosts = headers.getOrDefault("host", List.of());
        if (hosts.size() > 1 || (version.equals("HTTP/1.1") && hosts.isEmpty())) {
            throw new ParseError(400, "missing or duplicate Host");
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new ParseError(501, "Transfer-Encoding not supported"); // also covers TE + CL smuggling
        }
        long contentLength = parseContentLength(headers.getOrDefault("content-length", List.of()));

        boolean close = false;
        boolean keepAliveToken = false;
        for (var value : headers.getOrDefault("connection", List.of())) {
            for (var token : value.split(",")) {
                var t = trimOws(token).toLowerCase(Locale.ROOT);
                close |= t.equals("close");
                keepAliveToken |= t.equals("keep-alive");
            }
        }
        boolean keepAlive = !close && (version.equals("HTTP/1.1") || keepAliveToken);

        boolean expectContinue = false;
        for (var value : headers.getOrDefault("expect", List.of())) {
            if (!value.equalsIgnoreCase("100-continue")) {
                throw new ParseError(417, "unsupported expectation");
            }
            expectContinue = version.equals("HTTP/1.1");
        }

        headers.replaceAll((k, v) -> Collections.unmodifiableList(v));
        return new Head(method, target, version, Collections.unmodifiableMap(headers),
                contentLength, keepAlive, expectContinue);
    }

    /** Converts origin-form or absolute-form (http/https) targets to origin-form. */
    static String originForm(String target) throws ParseError {
        if (target.startsWith("/")) {
            return target;
        }
        var lower = target.toLowerCase(Locale.ROOT);
        int schemeEnd = lower.startsWith("http://") ? 7 : lower.startsWith("https://") ? 8 : -1;
        if (schemeEnd < 0) {
            throw new ParseError(400, "unsupported request-target form");
        }
        int pathStart = schemeEnd;
        while (pathStart < target.length() && target.charAt(pathStart) != '/' && target.charAt(pathStart) != '?') {
            pathStart++;
        }
        if (pathStart == schemeEnd) {
            throw new ParseError(400, "absolute-form target without authority");
        }
        var rest = target.substring(pathStart);
        return rest.isEmpty() ? "/" : rest.startsWith("?") ? "/" + rest : rest;
    }

    private static long parseContentLength(List<String> values) throws ParseError {
        long result = -1;
        for (var value : values) {
            for (var item : value.split(",", -1)) {
                var v = trimOws(item);
                if (!DIGITS.matcher(v).matches()) {
                    throw new ParseError(400, "invalid Content-Length");
                }
                long n = Long.parseLong(v);
                if (result >= 0 && n != result) {
                    throw new ParseError(400, "conflicting Content-Length");
                }
                result = n;
            }
        }
        return Math.max(result, 0);
    }

    private static String trimOws(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(start, end);
    }
}
