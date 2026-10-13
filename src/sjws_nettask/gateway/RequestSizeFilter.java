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

import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.StaticFileHandler;

/**
 * Rejects a request whose target or declared body is too large, before the rest of the chain.
 * The body is not read. Params: {@code max-bytes} (Content-Length), {@code max-target} (raw
 * target length, query included), {@code reject-chunked} ({@code true} answers 411 when
 * {@code Transfer-Encoding} contains {@code chunked}). At least one limit is required.
 */
public final class RequestSizeFilter implements RequestFilter {

    private final long maxBytes;
    private final int maxTarget;
    private final boolean rejectChunked;

    private RequestSizeFilter(long maxBytes, int maxTarget, boolean rejectChunked) {
        this.maxBytes = maxBytes;
        this.maxTarget = maxTarget;
        this.rejectChunked = rejectChunked;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        long maxBytes = -1;
        var bytes = spec.option("max-bytes");
        if (bytes != null) {
            maxBytes = parseLong(bytes, "max-bytes");
        }
        int maxTarget = -1;
        var target = spec.option("max-target");
        if (target != null) {
            long n = parseLong(target, "max-target");
            if (n > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("max-target is out of range");
            }
            maxTarget = (int) n;
        }
        if (maxBytes < 0 && maxTarget < 0) {
            throw new IllegalArgumentException("request-size needs max-bytes or max-target");
        }
        boolean rejectChunked = "true".equals(spec.option("reject-chunked"));
        return new RequestSizeFilter(maxBytes, maxTarget, rejectChunked);
    }

    @Override
    public Response filter(Request request, Chain next) {
        if (this.maxTarget >= 0 && request.target().length() > this.maxTarget) {
            return StaticFileHandler.error(414, request.headOnly());
        }
        if (this.rejectChunked && chunked(request)) {
            return StaticFileHandler.error(411, request.headOnly());
        }
        if (this.maxBytes >= 0) {
            var lengths = request.headers().values("content-length");
            if (lengths.size() > 1) {
                return StaticFileHandler.error(400, request.headOnly());
            }
            if (lengths.size() == 1) {
                long length;
                try {
                    length = parseLong(lengths.get(0), "content-length");
                } catch (IllegalArgumentException e) {
                    return StaticFileHandler.error(400, request.headOnly());
                }
                if (length > this.maxBytes) {
                    return StaticFileHandler.error(413, request.headOnly());
                }
            }
        }
        return next.proceed(request);
    }

    private static boolean chunked(Request request) {
        for (var value : request.headers().values("transfer-encoding")) {
            int i = 0;
            int n = value.length();
            while (i < n) {
                int comma = value.indexOf(',', i);
                int end = comma < 0 ? n : comma;
                if (token(value, i, end, "chunked")) {
                    return true;
                }
                i = end + 1;
            }
        }
        return false;
    }

    /** Coding name, optional transfer-extension, compared case-insensitively. */
    private static boolean token(String value, int start, int end, String word) {
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        int semi = value.indexOf(';', start);
        if (semi >= 0 && semi < end) {
            end = semi;
            while (end > start && value.charAt(end - 1) == ' ') {
                end--;
            }
        }
        return end - start == word.length() && value.regionMatches(true, start, word, 0, word.length());
    }

    private static long parseLong(String raw, String name) {
        var text = raw.trim();
        if (text.isEmpty() || text.charAt(0) == '-' || text.charAt(0) == '+') {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
