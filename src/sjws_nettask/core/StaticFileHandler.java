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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Protocol-neutral static file handler: decoding, containment, index.html, listings,
 * MIME types, dates, byte ranges and every status decision (200/206/301/304/400/403/404/405/416/500).
 * Transports only parse requests and serialize the returned {@link Response}.
 * <p>
 * Thread-safe and lock-free: it holds only immutable state.
 */
public final class StaticFileHandler implements Handler {

    public static final String ALLOW = "GET, HEAD";
    /** Accept-Ranges of file responses (single byte ranges, see {@link ByteRanges}). */
    public static final String ACCEPT_RANGES = "bytes";
    /** Accept-Ranges of generated pages (errors, listings, redirects): Range is ignored there. */
    public static final String NO_RANGES = "none";

    /** Used by the static helpers, which have no handler (and so no per-version memory). */
    private static final EntityTag.Policy STATIC_POLICY = new EntityTag.Policy(java.time.InstantSource.system());

    private final PathResolver resolver;
    private final boolean allowUnlistableDirs;
    private final EntityTag.Policy etags;
    private final FileCache cache;

    /** @throws UncheckedIOException if the root does not exist or is not a directory */
    public StaticFileHandler(Path root) {
        this(root, false);
    }

    /**
     * @param allowUnlistableDirs serve files below search-only directories by path (weaker; see
     *        {@link SafeOpen}); false answers them 403
     * @throws UncheckedIOException if the root does not exist or is not a directory
     */
    public StaticFileHandler(Path root, boolean allowUnlistableDirs) {
        this(root, allowUnlistableDirs, java.time.InstantSource.system());
    }

    /** @param clock decides which file versions are "young" (weak ETag); tests inject a fake one */
    public StaticFileHandler(Path root, boolean allowUnlistableDirs, java.time.InstantSource clock) {
        this(root, allowUnlistableDirs, clock, FileCache.disabled());
    }

    /** With a small-file cache (see {@link FileCache}). */
    public StaticFileHandler(Path root, boolean allowUnlistableDirs, FileCache cache) {
        this(root, allowUnlistableDirs, java.time.InstantSource.system(), cache);
    }

    public StaticFileHandler(Path root, boolean allowUnlistableDirs, java.time.InstantSource clock,
            FileCache cache) {
        this.resolver = new PathResolver(root);
        this.allowUnlistableDirs = allowUnlistableDirs;
        this.etags = new EntityTag.Policy(clock);
        this.cache = java.util.Objects.requireNonNull(cache, "cache");
    }

    /** The entity-tag policy (tests). */
    EntityTag.Policy etags() {
        return this.etags;
    }

    public FileCache cache() {
        return this.cache;
    }

    /** @return whether files below search-only directories are served (see the constructor) */
    public boolean allowUnlistableDirs() {
        return this.allowUnlistableDirs;
    }

    public Path root() {
        return this.resolver.root();
    }

    public PathResolver resolver() {
        return this.resolver;
    }

    /** Never throws: unexpected filesystem errors become 500. */
    @Override
    public Response handle(Request request) {
        boolean head = request.headOnly();
        if (!head && !"GET".equals(request.method())) {
            return methodNotAllowed();
        }
        var target = request.target();
        int q = target.indexOf('?');
        int h = target.indexOf('#');
        int pathEnd = q < 0 ? (h < 0 ? target.length() : h) : (h < 0 ? q : Math.min(q, h));
        var rawPath = target.substring(0, pathEnd);
        var query = q >= 0 && (h < 0 || q < h) ? target.substring(q, h > q ? h : target.length()) : "";

        var decoded = PathResolver.decode(rawPath);
        if (decoded == null) {
            return error(400, head);
        }
        String key = null;
        if (this.cache.enabled()) {
            var logical = this.resolver.logical(decoded); // lexical checks run on hits too
            if (logical == null) {
                return error(404, head);
            }
            key = decoded.endsWith("/") ? logical + "/" : logical.toString();
            var hit = this.cache.lookup(key);
            if (hit != null) {
                if (!hit.weak() || this.etags.weak(hit.meta().validator())) {
                    return hit.response(head, request.headers());
                }
                // The policy forgot this young version (bounded memory) and now tags it strong:
                // rebuild the entry so cached and uncached answers keep the same ETag.
                this.cache.drop(hit);
            }
        }
        var resolved = this.resolver.resolve(decoded);
        if (resolved == null) {
            return error(404, head);
        }
        try {
            var attributes = Files.readAttributes(resolved.real(), BasicFileAttributes.class);
            if (attributes.isDirectory()) {
                if (!rawPath.endsWith("/")) {
                    return redirect(rawPath + "/" + query, head);
                }
                var base = decoded.endsWith("/") ? decoded : decoded + "/";
                var index = this.resolver.resolve(base + "index.html");
                if (index != null && Files.isRegularFile(index.real())) {
                    return file(index, head, request.headers(), key);
                }
                return listing(listVisible(resolved.real()), decoded, head);
            }
            if (!attributes.isRegularFile()) {
                return error(404, head);
            }
            return file(resolved, head, request.headers(), key);
        } catch (AccessDeniedException e) {
            return error(403, head);
        } catch (IOException | UncheckedIOException e) {
            return error(500, head);
        }
    }

    /** @param key cache key of the request, or null when the cache is off */
    private Response file(PathResolver.Resolved file, boolean head, RequestHeaders conditionals, String key)
            throws IOException {
        if (!Files.isReadable(file.real())) {
            return error(403, head);
        }
        var name = file.logical().getFileName().toString();
        var attributes = Files.readAttributes(file.real(), BasicFileAttributes.class);
        var meta = FileMeta.of(Validator.of(attributes), name, this.etags);
        // Load into the cache only for a GET that sends the body: HEAD and 304 never open the file
        // (as without a cache), so they keep their exact semantics.
        if (key != null && !head && attributes.isRegularFile() && this.cache.admits(attributes.size())
                && !meta.notModified(conditionals)) {
            var body = new Body.FileBody(file.real(), 0, attributes.size(), meta.validator(), this.resolver.realRoot(),
                    this.allowUnlistableDirs);
            FileCache.Entry entry;
            try {
                entry = this.cache.load(key, file.logical(), body, meta);
            } catch (IOException e) { // what the writer would have answered when opening it
                return error(SafeOpen.status(e), false);
            }
            if (entry != null) {
                return entry.response(false, conditionals);
            }
            // changed while it was read: serve the version that is there now, uncached
            attributes = Files.readAttributes(file.real(), BasicFileAttributes.class);
            meta = FileMeta.of(Validator.of(attributes), name, this.etags);
        }
        return fileResponse(meta, this.resolver.realRoot(), file.real(), head, conditionals, this.allowUnlistableDirs);
    }

    /**
     * 200 with a {@link Body.FileBody} for the whole file, or 304 if not modified since
     * {@code ifModifiedSince}.
     *
     * @param name logical file name (selects the media type)
     */
    public static Response fileResponse(String name, Path realPath, BasicFileAttributes attributes, boolean head,
            String ifModifiedSince) {
        return fileResponse(name, realPath.getRoot() == null ? realPath : realPath.getRoot(), realPath, attributes,
                head, ifModifiedSince);
    }

    /**
     * Same, for a file resolved inside {@code realRoot} (the writer opens it relative to that root).
     */
    public static Response fileResponse(String name, Path realRoot, Path realPath, BasicFileAttributes attributes,
            boolean head, String ifModifiedSince) {
        return fileResponse(name, realRoot, realPath, attributes, head, ifModifiedSince, false);
    }

    private static Response fileResponse(String name, Path realRoot, Path realPath, BasicFileAttributes attributes,
            boolean head, String ifModifiedSince, boolean allowUnlistable) {
        var meta = FileMeta.of(Validator.of(attributes), name, STATIC_POLICY);
        return fileResponse(meta, realRoot, realPath, head, RequestHeaders.ifModifiedSince(ifModifiedSince),
                allowUnlistable);
    }

    private static Response fileResponse(FileMeta meta, Path realRoot, Path realPath, boolean head,
            RequestHeaders conditionals, boolean allowUnlistable) {
        if (meta.notModified(conditionals)) {
            return new Response(304, meta.headers304(), Body.Empty.INSTANCE, head);
        }
        var v = meta.validator();
        if (!head) { // only GET has range semantics (RFC 9110 §14.2)
            switch (ByteRanges.decide(conditionals, meta)) {
                case ByteRanges.Partial p -> {
                    return new Response(206, meta.headers200().add("content-range", p.contentRange()),
                            new Body.FileBody(realPath, p.offset(), p.length(), v, realRoot, allowUnlistable), false);
                }
                case ByteRanges.Unsatisfiable u -> {
                    return rangeNotSatisfiable(u);
                }
                case ByteRanges.Full f -> {
                    // whole file below
                }
            }
        }
        return new Response(200, meta.headers200(),
                new Body.FileBody(realPath, 0, v.size(), v, realRoot, allowUnlistable), head);
    }

    /** Convenience for callers holding a plain path (no containment check). */
    public static Response fileResponse(Path file, boolean head, String ifModifiedSince) throws IOException {
        var real = file.toRealPath();
        return fileResponse(file.getFileName().toString(), real,
                Files.readAttributes(real, BasicFileAttributes.class), head, ifModifiedSince);
    }

    private List<String> listVisible(Path folder) throws IOException {
        List<String> names = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            for (var p : (Iterable<Path>) entries::iterator) {
                if (this.resolver.isVisible(p)) {
                    var name = p.getFileName().toString();
                    names.add(Files.isDirectory(p) ? name + "/" : name);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        names.sort(String::compareTo);
        return names;
    }

    // --- response builders (shared with the legacy http1/Page API) -------------------------

    public static Response html(int status, String html, boolean head) {
        var headers = ResponseHeaders.of()
                .add("accept-ranges", NO_RANGES)
                .add("content-type", Mime.contentType("text/html"));
        return new Response(status, headers, Body.Bytes.of(html.getBytes(StandardCharsets.UTF_8)), head);
    }

    public static Response error(int status, boolean head) {
        var title = status + " " + reasonPhrase(status);
        return html(status, "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"/><title>" + title
                + "</title></head><body><h1>" + title + "</h1></body></html>", head);
    }

    /** 416 for a GET whose single byte range lies outside the file. */
    public static Response rangeNotSatisfiable(ByteRanges.Unsatisfiable u) {
        var r = error(416, false);
        r.headers().add("content-range", u.contentRange());
        return r;
    }

    public static Response methodNotAllowed() {
        var r = html(405, "<!DOCTYPE html><html><body><h1>405 Method Not Allowed</h1></body></html>", false);
        r.headers().add("allow", ALLOW);
        return r;
    }

    public static Response redirect(String location, boolean head) {
        var r = html(301, "<!DOCTYPE html><html><body><a href=\"" + escape(location) + "\">Moved</a></body></html>",
                head);
        r.headers().add("location", location);
        return r;
    }

    /** @param names entries to list; directories end with "/" */
    public static Response listing(List<String> names, String urlPath, boolean head) {
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
        return html(200, html.toString(), head);
    }

    /** Standard reason phrases (used by error pages and by the HTTP/1 status line). */
    public static String reasonPhrase(int code) {
        return switch (code) {
            case 100 -> "Continue";
            case 200 -> "OK";
            case 206 -> "Partial Content";
            case 301 -> "Moved Permanently";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 505 -> "HTTP Version Not Supported";
            default -> "Unknown";
        };
    }

    public static String escape(String s) {
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
