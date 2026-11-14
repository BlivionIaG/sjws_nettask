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
package sjws_nettask.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import sjws_nettask.core.Body;
import sjws_nettask.core.EntityTag;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.SafeOpen;
import sjws_nettask.core.StaticFileHandler;

/**
 * gzip response compression negotiated from {@code Accept-Encoding}. Compiled when the filter is
 * built; off, with no cost, until a document names it. Brotli is not implemented in this land
 * ({@code br} in {@code encodings} is rejected so it is not advertised).
 * <p>
 * Params: {@code min_size} (default 256), {@code max_buffer} (default 1 MiB, cap 16 MiB),
 * {@code types} (optional allow list), {@code except} (exclusions), {@code encodings} (default
 * {@code gzip}). A streamed body is compressed only when its length is known and at most
 * {@code max_buffer}: the filter reads the source itself and returns a new {@link Body.Bytes},
 * so {@code FilterChain} can close the original stream. A {@link Body.FileBody} larger than the
 * cap is compressed as a new unknown-length stream (the file is not a shared stream source).
 */
public final class CompressFilter implements RequestFilter {

    static final int DEFAULT_MIN_SIZE = 256;
    static final int DEFAULT_MAX_BUFFER = 1024 * 1024;
    static final int ABSOLUTE_MAX_BUFFER = 16 * 1024 * 1024;

    private final int minSize;
    private final int maxBuffer;
    private final String[] types;
    private final String[] except;

    private CompressFilter(int minSize, int maxBuffer, String[] types, String[] except) {
        this.minSize = minSize;
        this.maxBuffer = maxBuffer;
        this.types = types;
        this.except = except;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        int minSize = parseInt(spec.option("min_size"), DEFAULT_MIN_SIZE, 0, ABSOLUTE_MAX_BUFFER, "min_size");
        int maxBuffer = parseInt(spec.option("max_buffer"), DEFAULT_MAX_BUFFER, 1, ABSOLUTE_MAX_BUFFER, "max_buffer");
        if (minSize > maxBuffer) {
            throw new IllegalArgumentException("compress min_size cannot exceed max_buffer");
        }
        var encodings = parseList(spec.option("encodings"));
        if (encodings.isEmpty()) {
            encodings = List.of("gzip");
        }
        for (var enc : encodings) {
            if ("br".equals(enc) || "brotli".equals(enc)) {
                throw new IllegalArgumentException("compress encodings: brotli is not implemented yet");
            }
            if (!"gzip".equals(enc) && !"x-gzip".equals(enc)) {
                throw new IllegalArgumentException("compress encodings: unsupported " + enc);
            }
        }
        return new CompressFilter(minSize, maxBuffer, parseMedia(spec.option("types")),
                parseMedia(spec.option("except")));
    }

    @Override
    public Response filter(Request request, Chain next) {
        // Request-side no-transform also forbids an intermediary from changing the
        // representation, including a gzip-specific 304.
        var acceptEncoding = acceptEncoding(request.headers());
        boolean gzip = chooseGzip(acceptEncoding) && !hasCacheDirective(request.headers(), "no-transform");
        // Translate filter-generated gzip validators to their identity form before the
        // inner handler runs: an upstream identity ETag can collide with a previous
        // gzip tag (e.g. "revision" then "revision-gzip"). Evaluate the client's
        // original If-None-Match against the gzip ETag after proceed.
        var response = next.proceed(gzip ? withIdentityValidators(request) : request);
        if (gzip && (compressible(response) || response.status() == 304)
                && compressedValidatorHits(request, response)) {
            return notModifiedGzip(response);
        }
        if (gzip && compressible(response)) {
            return gzip(response, acceptsIdentity(acceptEncoding));
        }
        if (compressible(response)) {
            // Identity is still negotiated: caches must not reuse it for a gzip request.
            return withAcceptEncodingVary(response);
        }
        return response;
    }

    boolean compressible(Response response) {
        if (response == null || !response.mayHaveBody()) {
            return false;
        }
        var headers = response.headers();
        if (headers.contains("content-range") || response.status() == 206) {
            return false;
        }
        // Every Content-Encoding field: a later "gzip" field after "identity" is still encoded.
        for (var entry : headers.entries()) {
            if ("content-encoding".equals(entry.getKey()) && !isIdentity(entry.getValue())) {
                return false;
            }
        }
        if (hasCacheDirective(headers, "no-transform")) {
            return false;
        }
        if (!typeAllowed(mediaType(headers.get("content-type")))) {
            return false;
        }
        return switch (response.body()) {
            case Body.Empty ignored -> false;
            case Body.Bytes bytes -> bytes.length() >= this.minSize && bytes.length() <= this.maxBuffer;
            case Body.FileBody file -> file.length() >= this.minSize;
            // HEAD streams are often metadata-only (known length, empty source). Do not consume them.
            case Body.Stream stream -> !response.headOnly() && stream.length() >= this.minSize
                    && stream.length() <= this.maxBuffer;
        };
    }

    private Response gzip(Response response, boolean identityOk) {
        try {
            return switch (response.body()) {
                case Body.Bytes bytes -> buffered(response, read(bytes.data().duplicate()), false, identityOk);
                case Body.FileBody file when file.length() <= this.maxBuffer -> buffered(response, read(file), false, identityOk);
                case Body.FileBody file -> streamFile(response, file);
                case Body.Stream stream -> buffered(response, read(stream), true, identityOk);
                default -> response;
            };
        } catch (IOException e) {
            if (response.body() instanceof Body.FileBody) {
                return StaticFileHandler.error(SafeOpen.status(e), response.headOnly());
            }
            return StaticFileHandler.error(500, response.headOnly());
        }
    }

    private static Response buffered(Response original, byte[] plain, boolean consumed, boolean identityOk)
            throws IOException {
        var compressed = gzipBytes(plain);
        if (compressed.length >= plain.length && identityOk) {
            // A consumed stream cannot be re-read; return the bytes we already own.
            var identity = consumed
                    ? copy(original, original.headers(), Body.Bytes.of(plain))
                    : original;
            return withAcceptEncodingVary(identity);
        }
        return copy(original, encodedHeaders(original.headers()), Body.Bytes.of(compressed));
    }

    private static Response streamFile(Response original, Body.FileBody file) throws IOException {
        var channel = SafeOpen.open(file);
        return copy(original, encodedHeaders(original.headers()),
                new Body.Stream(-1, new GzipFileSource(channel, file)));
    }

    private static Response copy(Response original, ResponseHeaders headers, Body body) {
        return new Response(original.status(), headers, body, original.headOnly(),
                original.localAdmission(), original.generated());
    }

    static byte[] gzipBytes(byte[] plain) throws IOException {
        var out = new ByteArrayOutputStream(Math.max(64, plain.length / 2));
        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(plain);
        }
        return out.toByteArray();
    }

    static ResponseHeaders encodedHeaders(ResponseHeaders original) {
        var headers = original.copy();
        headers.set("content-encoding", "gzip");
        headers.remove("content-length");
        headers.remove("content-md5");
        // Digests over the content or the selected representation (RFC 9530, and the legacy
        // RFC 3230 instance digest) no longer match once the content coding changes.
        headers.remove("content-digest");
        headers.remove("repr-digest");
        headers.remove("digest");
        headers.remove("accept-ranges");
        var etag = headers.get("etag");
        if (etag != null) {
            var tagged = withCoding(etag, "gzip");
            if (tagged == null) {
                headers.remove("etag");
            } else {
                headers.set("etag", tagged);
            }
        }
        var vary = mergeVary(headers);
        headers.set("vary", vary == null ? "Accept-Encoding" : vary);
        return headers;
    }

    static String withCoding(String etag, String coding) {
        var t = etag.trim();
        boolean weak = t.length() >= 2 && (t.startsWith("W/") || t.startsWith("w/"));
        var rest = weak ? t.substring(2).trim() : t;
        if (rest.length() < 2 || rest.charAt(0) != '"' || rest.charAt(rest.length() - 1) != '"') {
            return null;
        }
        var value = rest.substring(1, rest.length() - 1);
        // Eligibility already skips responses this filter has gzipped. An upstream tag
        // that happens to end in -gzip is still the identity validator and must not be
        // reused as the strong tag of the compressed representation.
        return (weak ? "W/" : "") + "\"" + value + "-" + coding + "\"";
    }

    /**
     * Rewrites {@code If-None-Match} and {@code If-Match} so filter-generated {@code -gzip}
     * validators are presented to the inner handler in identity form. Leaves {@code *},
     * malformed fields, and {@code If-Range} unchanged: a translated If-Range would make
     * the inner handler apply identity byte offsets to a client that already has gzip
     * bytes, and this filter does not recompress 206 responses.
     */
    static Request withIdentityValidators(Request request) {
        var headers = request.headers();
        var fields = headers.fields();
        if (fields.isEmpty()) {
            var inm = identityValidators(headers.ifNoneMatch());
            if (same(inm, headers.ifNoneMatch())) {
                return request;
            }
            return request.withHeaders(new RequestHeaders(inm, headers.ifModifiedSince(),
                    headers.ifRange(), headers.range(), headers.acceptEncoding(), headers.host()));
        }
        var copy = new LinkedHashMap<String, List<String>>(fields);
        boolean changed = translateField(copy, "if-none-match")
                | translateField(copy, "if-match");
        if (!changed) {
            return request;
        }
        return request.withHeaders(RequestHeaders.of(Map.copyOf(copy), headers.host()));
    }

    private static boolean translateField(Map<String, List<String>> fields, String name) {
        var values = fields.get(name);
        if (values == null || values.isEmpty()) {
            return false;
        }
        var next = new ArrayList<String>(values.size());
        boolean changed = false;
        for (var raw : values) {
            var translated = identityValidators(raw);
            changed |= !same(translated, raw);
            next.add(translated);
        }
        if (changed) {
            fields.put(name, List.copyOf(next));
        }
        return changed;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Inverse of {@link #withCoding}: strip a trailing {@code -gzip} from each listed tag. */
    static String identityValidators(String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return ifNoneMatch;
        }
        var suffix = "-gzip";
        var out = new StringBuilder(ifNoneMatch.length());
        int i = 0;
        int n = ifNoneMatch.length();
        boolean wrote = false;
        while (i < n) {
            char c = ifNoneMatch.charAt(i);
            if (c == ' ' || c == '\t' || c == ',') {
                i++;
                continue;
            }
            if (c == '*') {
                return ifNoneMatch;
            }
            var weak = "";
            if ((c == 'W' || c == 'w') && i + 1 < n && ifNoneMatch.charAt(i + 1) == '/') {
                weak = ifNoneMatch.substring(i, i + 2);
                i += 2;
            }
            if (i >= n || ifNoneMatch.charAt(i) != '"') {
                return ifNoneMatch;
            }
            int end = ifNoneMatch.indexOf('"', i + 1);
            if (end < 0) {
                return ifNoneMatch;
            }
            var opaque = ifNoneMatch.substring(i + 1, end);
            if (opaque.endsWith(suffix) && opaque.length() > suffix.length()) {
                opaque = opaque.substring(0, opaque.length() - suffix.length());
            }
            if (wrote) {
                out.append(", ");
            }
            out.append(weak).append('"').append(opaque).append('"');
            wrote = true;
            i = end + 1;
        }
        return wrote ? out.toString() : ifNoneMatch;
    }

    /**
     * True when If-None-Match names the gzip representation this filter would emit. Only a
     * successful (2xx or 304) GET or HEAD may become a 304 (RFC 9110 13.1.2, 13.2.2); an upstream
     * error or the result of an unsafe method is passed through unchanged.
     */
    static boolean compressedValidatorHits(Request request, Response response) {
        var method = request.method();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        int status = response.status();
        if (status != 304 && (status < 200 || status > 299)) {
            return false;
        }
        var inm = request.headers().ifNoneMatch();
        if (inm == null || inm.isBlank()) {
            return false;
        }
        var etag = response.headers().get("etag");
        if (etag == null) {
            return false;
        }
        var tagged = withCoding(etag, "gzip");
        return tagged != null && EntityTag.noneMatchHits(inm, tagged);
    }

    static Response notModifiedGzip(Response original) {
        return new Response(304, encodedHeaders(original.headers()), Body.Empty.INSTANCE,
                original.headOnly(), original.localAdmission(), original.generated());
    }

    static Response withAcceptEncodingVary(Response response) {
        var headers = response.headers().copy();
        var vary = mergeVary(headers);
        headers.set("vary", vary == null ? "Accept-Encoding" : vary);
        return copy(response, headers, response.body());
    }

    /**
     * Every Accept-Encoding field of the request as one list (RFC 9110 5.3: repeated fields are
     * equivalent to one comma-joined field), so a later {@code gzip;q=0} is not ignored. Null when
     * the request has none.
     */
    static String acceptEncoding(RequestHeaders headers) {
        var values = headers.values("accept-encoding");
        if (values.isEmpty()) {
            return headers.acceptEncoding();
        }
        return values.size() == 1 ? values.getFirst() : String.join(",", values);
    }

    /** True if any request Cache-Control field carries {@code directive} (RFC 9111). */
    static boolean hasCacheDirective(RequestHeaders headers, String directive) {
        for (var raw : headers.values("cache-control")) {
            if (cacheControlHas(raw, directive)) {
                return true;
            }
        }
        return false;
    }

    /** True if any Cache-Control field carries {@code directive} (RFC 9111, case-insensitive). */
    static boolean hasCacheDirective(ResponseHeaders headers, String directive) {
        for (var entry : headers.entries()) {
            if ("cache-control".equals(entry.getKey()) && cacheControlHas(entry.getValue(), directive)) {
                return true;
            }
        }
        return false;
    }

    static boolean cacheControlHas(String raw, String directive) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (var part : raw.split(",")) {
            var token = part.trim();
            int eq = token.indexOf('=');
            if (eq >= 0) {
                token = token.substring(0, eq).trim();
            }
            if (directive.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Chooses gzip when the request offers it (or {@code *} with q &gt; 0). No {@code Accept-Encoding}
     * means no compression (safer for clients that cannot decode it).
     */
    static boolean chooseGzip(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return false;
        }
        Double gzipQ = null;
        Double starQ = null;
        for (var part : acceptEncoding.split(",")) {
            var item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int semi = item.indexOf(';');
            var token = (semi < 0 ? item : item.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
            double q = 1;
            if (semi >= 0) {
                q = quality(item.substring(semi + 1));
            }
            if (q < 0) {
                q = 0;
            } else if (q > 1) {
                q = 1;
            }
            switch (token) {
                case "gzip", "x-gzip" -> gzipQ = q;
                case "*" -> starQ = q;
                default -> {
                }
            }
        }
        double effective = gzipQ != null ? gzipQ : (starQ != null ? starQ : 0);
        return effective > 0;
    }

    /**
     * Identity is acceptable unless the request refuses it with {@code identity;q=0} or
     * {@code *;q=0} and no more specific identity entry (RFC 9110 12.5.3).
     */
    static boolean acceptsIdentity(String acceptEncoding) {
        if (acceptEncoding == null || acceptEncoding.isBlank()) {
            return true;
        }
        Double identityQ = null;
        Double starQ = null;
        for (var part : acceptEncoding.split(",")) {
            var item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int semi = item.indexOf(';');
            var token = (semi < 0 ? item : item.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
            double q = 1;
            if (semi >= 0) {
                q = quality(item.substring(semi + 1));
            }
            if (q < 0) {
                q = 0;
            } else if (q > 1) {
                q = 1;
            }
            switch (token) {
                case "identity" -> identityQ = q;
                case "*" -> starQ = q;
                default -> {
                }
            }
        }
        if (identityQ != null) {
            return identityQ > 0;
        }
        if (starQ != null) {
            return starQ > 0;
        }
        return true;
    }

    private static double quality(String params) {
        for (var p : params.split(";")) {
            var s = p.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (!"q".equalsIgnoreCase(s.substring(0, eq).trim())) {
                continue;
            }
            try {
                return Double.parseDouble(s.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 1;
    }

    private boolean typeAllowed(String media) {
        if (media == null) {
            return false;
        }
        if (matches(this.except, media)) {
            return false;
        }
        if (this.types.length > 0) {
            return matches(this.types, media);
        }
        return defaultCompressible(media);
    }

    static boolean defaultCompressible(String media) {
        if (media.startsWith("text/")) {
            return !media.equals("text/event-stream");
        }
        return media.equals("application/json")
                || media.equals("application/javascript")
                || media.equals("application/ecmascript")
                || media.equals("application/xml")
                || media.equals("application/xhtml+xml")
                || media.equals("application/rss+xml")
                || media.equals("application/atom+xml")
                || media.equals("application/ld+json")
                || media.equals("application/manifest+json")
                || media.equals("image/svg+xml")
                || media.endsWith("+json")
                || media.endsWith("+xml");
    }

    private static boolean matches(String[] list, String media) {
        for (var item : list) {
            if (item.endsWith("/*")) {
                if (media.startsWith(item.substring(0, item.length() - 1))) {
                    return true;
                }
            } else if (item.equals(media)) {
                return true;
            }
        }
        return false;
    }

    static String mediaType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semi = contentType.indexOf(';');
        var type = (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? null : type;
    }

    private static boolean isIdentity(String encoding) {
        for (var part : encoding.split(",")) {
            var token = part.trim().toLowerCase(Locale.ROOT);
            int semi = token.indexOf(';');
            if (semi >= 0) {
                token = token.substring(0, semi).trim();
            }
            if (!token.isEmpty() && !"identity".equals(token)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Combined Vary tokens from every field, or {@code *} if any value is {@code *}, or
     * {@code null} when none were present. Always includes {@code Accept-Encoding} unless the
     * result is {@code *}.
     */
    static String mergeVary(ResponseHeaders headers) {
        var tokens = new java.util.ArrayList<String>();
        boolean star = false;
        boolean hasAcceptEncoding = false;
        for (var entry : headers.entries()) {
            if (!"vary".equals(entry.getKey())) {
                continue;
            }
            var raw = entry.getValue();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            for (var part : raw.split(",")) {
                var token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                if ("*".equals(token)) {
                    star = true;
                    continue;
                }
                if ("accept-encoding".equalsIgnoreCase(token)) {
                    hasAcceptEncoding = true;
                }
                if (!containsToken(String.join(",", tokens), token)) {
                    tokens.add(token);
                }
            }
        }
        if (star) {
            return "*";
        }
        if (tokens.isEmpty() && !hasAcceptEncoding) {
            return null;
        }
        if (!hasAcceptEncoding) {
            tokens.add("Accept-Encoding");
        }
        return String.join(", ", tokens);
    }

    private static boolean containsToken(String list, String token) {
        for (var part : list.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    private static byte[] read(ByteBuffer data) {
        var bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    private static byte[] read(Body.FileBody file) throws IOException {
        if (file.length() > Integer.MAX_VALUE) {
            throw new IOException("file too large to buffer");
        }
        var bytes = new byte[(int) file.length()];
        try (var channel = SafeOpen.open(file)) {
            var buf = ByteBuffer.wrap(bytes);
            long pos = file.offset();
            while (buf.hasRemaining()) {
                int n = channel.read(buf, pos);
                if (n < 0) {
                    throw new IOException("file ended early");
                }
                pos += n;
            }
        }
        return bytes;
    }

    /**
     * Read the source to the end and leave it open: {@code FilterChain.settle} closes it. The
     * declared length is enforced as the transport writers would: a source that ends early or
     * yields more bytes than declared is an error, never a shorter or longer 200 (and never more
     * than {@code max_buffer}, which eligibility checked against the declared length).
     */
    static byte[] read(Body.Stream stream) throws IOException {
        long declared = stream.length();
        var out = new ByteArrayOutputStream(declared > 0 ? (int) declared : 4096);
        var src = stream.source();
        long total = 0;
        for (var data = src.next(); data != null; data = src.next()) {
            if (declared >= 0 && data.remaining() > declared - total) {
                throw new IOException("stream body longer than its declared length " + declared);
            }
            total += data.remaining();
            if (data.hasArray()) {
                out.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
            } else {
                var copy = new byte[data.remaining()];
                data.get(copy);
                out.write(copy);
            }
        }
        if (declared >= 0 && total < declared) {
            throw new IOException("stream body ended after " + total + " of " + declared + " bytes");
        }
        return out.toByteArray();
    }

    private static int parseInt(String raw, int fallback, int min, int max, String name) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException("compress " + name + " must be " + min + ".." + max);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("compress " + name + ": not a number", e);
        }
    }

    private static List<String> parseList(String raw) {
        var out = new ArrayList<String>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (var part : raw.split(",")) {
            var t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String[] parseMedia(String raw) {
        var list = parseList(raw);
        return list.toArray(String[]::new);
    }

    /** Pull-style gzip of an already-opened file. {@link #close} closes the channel. */
    static final class GzipFileSource implements Body.Stream.Source {

        private static final int CHUNK = 16 * 1024;

        private final FileChannel channel;
        private final long end;
        private final ByteArrayOutputStream box = new ByteArrayOutputStream(CHUNK);
        private final GZIPOutputStream gzip;
        private long pos;
        private boolean finished;
        private boolean closed;

        GzipFileSource(FileChannel channel, Body.FileBody file) throws IOException {
            this.channel = channel;
            this.pos = file.offset();
            this.end = file.offset() + file.length();
            this.gzip = new GZIPOutputStream(this.box, CHUNK);
        }

        @Override
        public ByteBuffer next() throws IOException {
            if (this.closed) {
                return null;
            }
            var in = new byte[CHUNK];
            while (this.box.size() == 0 && !this.finished) {
                if (this.pos >= this.end) {
                    this.gzip.finish();
                    this.finished = true;
                    break;
                }
                int want = (int) Math.min(in.length, this.end - this.pos);
                var buf = ByteBuffer.wrap(in, 0, want);
                int n = this.channel.read(buf, this.pos);
                if (n < 0) {
                    throw new IOException("file ended early");
                }
                this.pos += n;
                this.gzip.write(in, 0, n);
            }
            if (this.box.size() == 0) {
                return null;
            }
            var bytes = this.box.toByteArray();
            this.box.reset();
            return ByteBuffer.wrap(bytes);
        }

        @Override
        public void close() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try {
                this.gzip.close();
            } catch (IOException ignored) {
                // channel still closes below
            }
            try {
                this.channel.close();
            } catch (IOException ignored) {
            }
        }
    }
}
