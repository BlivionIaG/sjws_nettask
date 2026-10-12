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

import java.util.Locale;
import sjws_nettask.core.Body;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;
import sjws_nettask.core.StaticFileHandler;

/**
 * CORS response headers, including a preflight short-circuit. An {@code OPTIONS} request that
 * carries {@code Access-Control-Request-Method} is a preflight: 204 when the origin, method and
 * requested headers are allowed, otherwise 403, and the rest of the chain does not run. Any other
 * request proceeds, and the allow-origin headers are added only when the origin is allowed.
 * <p>
 * Params: {@code origins} ({@code *} or a comma-separated list, required), {@code methods}
 * (default {@code GET,HEAD,OPTIONS}), {@code headers} (request headers a preflight may ask for),
 * {@code expose}, {@code credentials} ({@code true} with {@code *} is rejected), {@code max-age}
 * (default 600).
 */
public final class CorsFilter implements RequestFilter {

    private final boolean anyOrigin;
    private final String[] origins;
    private final String methodsHeader;
    private final String[] methods;
    private final String headersHeader;
    private final String[] headers;
    private final String exposeHeader;
    private final boolean credentials;
    private final String maxAge;

    private CorsFilter(boolean anyOrigin, String[] origins, String methodsHeader, String[] methods,
            String headersHeader, String[] headers, String exposeHeader, boolean credentials, String maxAge) {
        this.anyOrigin = anyOrigin;
        this.origins = origins;
        this.methodsHeader = methodsHeader;
        this.methods = methods;
        this.headersHeader = headersHeader;
        this.headers = headers;
        this.exposeHeader = exposeHeader;
        this.credentials = credentials;
        this.maxAge = maxAge;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        var originText = spec.option("origins");
        if (originText == null || originText.isBlank()) {
            throw new IllegalArgumentException("cors origins is required");
        }
        boolean credentials = flag(spec.option("credentials"), "credentials");
        boolean anyOrigin;
        String[] origins;
        var listed = split(originText, "origins");
        if (listed.length == 1 && "*".equals(listed[0])) {
            if (credentials) {
                throw new IllegalArgumentException("cors credentials cannot be combined with origin *");
            }
            anyOrigin = true;
            origins = new String[0];
        } else {
            for (var origin : listed) {
                if ("*".equals(origin) || origin.indexOf(' ') >= 0) {
                    throw new IllegalArgumentException("cors origin is invalid: " + origin);
                }
            }
            anyOrigin = false;
            origins = listed;
        }
        var methodText = spec.option("methods");
        if (methodText == null || methodText.isBlank()) {
            methodText = "GET,HEAD,OPTIONS";
        }
        var methods = split(methodText, "methods");
        for (int i = 0; i < methods.length; i++) {
            methods[i] = methods[i].toUpperCase(Locale.ROOT);
        }
        var headerText = spec.option("headers");
        String[] headers = headerText == null || headerText.isBlank() ? new String[0] : split(headerText, "headers");
        for (int i = 0; i < headers.length; i++) {
            headers[i] = headers[i].toLowerCase(Locale.ROOT);
        }
        var exposeText = spec.option("expose");
        String expose = null;
        if (exposeText != null && !exposeText.isBlank()) {
            expose = String.join(", ", split(exposeText, "expose"));
        }
        long maxAge = 600;
        var max = spec.option("max-age");
        if (max != null) {
            maxAge = parseLong(max, "max-age");
        }
        return new CorsFilter(anyOrigin, origins, String.join(", ", methods), methods,
                headers.length == 0 ? null : String.join(", ", headers), headers, expose, credentials,
                Long.toString(maxAge));
    }

    @Override
    public Response filter(Request request, Chain next) {
        if ("OPTIONS".equals(request.method()) && !request.headers().values("access-control-request-method").isEmpty()) {
            return preflight(request);
        }
        var response = next.proceed(request);
        var origin = origin(request);
        if (origin != null && allowed(origin)) {
            applyActual(response.headers(), origin);
        }
        return response;
    }

    private Response preflight(Request request) {
        var origin = origin(request);
        var method = first(request, "access-control-request-method");
        if (origin == null || method == null || !allowed(origin) || !methodAllowed(method)
                || !headersAllowed(request)) {
            return StaticFileHandler.error(403, false);
        }
        var headers = ResponseHeaders.of();
        headers.set("access-control-allow-origin", this.anyOrigin ? "*" : origin);
        headers.set("access-control-allow-methods", this.methodsHeader);
        if (this.headersHeader != null) {
            headers.set("access-control-allow-headers", this.headersHeader);
        }
        headers.set("access-control-max-age", this.maxAge);
        if (this.credentials) {
            headers.set("access-control-allow-credentials", "true");
        }
        if (!this.anyOrigin) {
            headers.set("vary", "Origin");
        }
        return new Response(204, headers, Body.Empty.INSTANCE, false);
    }

    private void applyActual(ResponseHeaders headers, String origin) {
        headers.set("access-control-allow-origin", this.anyOrigin ? "*" : origin);
        if (this.credentials) {
            headers.set("access-control-allow-credentials", "true");
        }
        if (this.exposeHeader != null) {
            headers.set("access-control-expose-headers", this.exposeHeader);
        }
        if (!this.anyOrigin) {
            var vary = headers.get("vary");
            if (vary == null || vary.isEmpty()) {
                headers.set("vary", "Origin");
            } else if (!hasToken(vary, "Origin")) {
                headers.set("vary", vary + ", Origin");
            }
        }
    }

    private boolean allowed(String origin) {
        if (this.anyOrigin) {
            return true;
        }
        for (var candidate : this.origins) {
            if (candidate.equals(origin)) {
                return true;
            }
        }
        return false;
    }

    private boolean methodAllowed(String method) {
        for (var candidate : this.methods) {
            if (candidate.equals(method)) {
                return true;
            }
        }
        return false;
    }

    private boolean headersAllowed(Request request) {
        var values = request.headers().values("access-control-request-headers");
        if (values.isEmpty()) {
            return true;
        }
        for (var value : values) {
            int i = 0;
            int n = value.length();
            while (i < n) {
                int comma = value.indexOf(',', i);
                int end = comma < 0 ? n : comma;
                int start = i;
                while (start < end && value.charAt(start) == ' ') {
                    start++;
                }
                while (end > start && value.charAt(end - 1) == ' ') {
                    end--;
                }
                if (start < end && !headerAllowed(value.substring(start, end))) {
                    return false;
                }
                if (comma < 0) {
                    break;
                }
                i = comma + 1;
            }
        }
        return true;
    }

    private boolean headerAllowed(String name) {
        for (var candidate : this.headers) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasToken(String vary, String token) {
        int i = 0;
        int n = vary.length();
        while (i < n) {
            int comma = vary.indexOf(',', i);
            int end = comma < 0 ? n : comma;
            int start = i;
            while (start < end && vary.charAt(start) == ' ') {
                start++;
            }
            while (end > start && vary.charAt(end - 1) == ' ') {
                end--;
            }
            if (end - start == token.length() && vary.regionMatches(true, start, token, 0, token.length())) {
                return true;
            }
            if (comma < 0) {
                return false;
            }
            i = comma + 1;
        }
        return false;
    }

    private static String origin(Request request) {
        var values = request.headers().values("origin");
        if (values.isEmpty() || values.get(0).isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static String first(Request request, String name) {
        var values = request.headers().values(name);
        return values.isEmpty() ? null : values.get(0);
    }

    private static boolean flag(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if ("true".equals(raw.trim())) {
            return true;
        }
        if ("false".equals(raw.trim())) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static String[] split(String raw, String name) {
        var parts = raw.split(",", -1);
        var out = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            var trimmed = parts[i].trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException(name + " has an empty entry");
            }
            out[i] = trimmed;
        }
        return out;
    }

    private static long parseLong(String raw, String name) {
        var text = raw.trim();
        if (text.isEmpty() || text.charAt(0) == '+' || text.charAt(0) == '-') {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}
