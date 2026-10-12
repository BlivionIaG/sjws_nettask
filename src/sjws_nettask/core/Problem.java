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

import java.nio.charset.StandardCharsets;

/**
 * RFC 9457 problem details ({@code application/problem+json}) for gateway-style error responses.
 * Only the five standard members are written: {@code type}, {@code title}, {@code status},
 * {@code detail}, {@code instance}.
 */
public final class Problem {

    public static final String CONTENT_TYPE = "application/problem+json";

    private Problem() {
    }

    /** @return true for the statuses a route can render as problem+json */
    public static boolean supports(int status) {
        return detail(status) != null;
    }

    /** Fixed, non-revealing explanation per status, or null when the status is not rendered as a problem. */
    public static String detail(int status) {
        return switch (status) {
            case 400 -> "The request could not be understood.";
            case 401 -> "Authentication is required to access this resource.";
            case 403 -> "Access to this resource is forbidden.";
            case 404 -> "No resource exists at this location.";
            case 405 -> "The method is not allowed for this resource.";
            case 408 -> "The request was not received in time.";
            case 413 -> "The request content is too large.";
            case 429 -> "Too many requests; retry later.";
            case 500 -> "The server failed to complete the request.";
            case 501 -> "The server does not support this request.";
            case 502 -> "The upstream server returned an invalid response or could not be reached.";
            case 503 -> "The service is temporarily unavailable.";
            case 504 -> "The upstream server did not answer in time.";
            default -> null;
        };
    }

    /**
     * Builds the response. Header fields of {@code like} other than the representation ones
     * (e.g. Allow, Content-Range, Retry-After, WWW-Authenticate) are kept.
     *
     * @param typeBase null for {@code "about:blank"}, otherwise a URI the status code is appended to
     *        (e.g. "https://errors.example.com/" gives "https://errors.example.com/503")
     * @param instance the request path (query excluded), or null to omit the member
     */
    public static Response of(int status, String typeBase, String instance, ResponseHeaders like, boolean head) {
        var json = new StringBuilder(256).append("{\"type\":");
        string(json, typeBase == null ? "about:blank" : typeBase + status);
        json.append(",\"title\":");
        string(json, StaticFileHandler.reasonPhrase(status));
        json.append(",\"status\":").append(status);
        var detail = detail(status);
        if (detail != null) {
            json.append(",\"detail\":");
            string(json, detail);
        }
        if (instance != null) {
            json.append(",\"instance\":");
            string(json, instance);
        }
        json.append('}');
        var headers = ResponseHeaders.of()
                .add("accept-ranges", StaticFileHandler.NO_RANGES)
                .add("content-type", CONTENT_TYPE)
                .add("x-content-type-options", StaticFileHandler.NOSNIFF)
                .add("cache-control", "no-store");
        if (like != null) {
            for (var e : like.entries()) {
                switch (e.getKey()) {
                    case "accept-ranges", "content-type", "x-content-type-options", "content-security-policy",
                            "cache-control" -> { }
                    default -> headers.add(e.getKey(), e.getValue());
                }
            }
        }
        return new Response(status, headers, Body.Bytes.of(json.toString().getBytes(StandardCharsets.UTF_8)), head);
    }

    /** JSON string literal (RFC 8259): quotes, backslash and control characters escaped. */
    static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
