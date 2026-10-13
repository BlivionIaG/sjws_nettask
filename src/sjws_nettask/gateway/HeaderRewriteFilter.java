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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import sjws_nettask.core.MiddlewareSpec;
import sjws_nettask.core.Request;
import sjws_nettask.core.RequestFilter;
import sjws_nettask.core.RequestHeaders;
import sjws_nettask.core.Response;
import sjws_nettask.core.ResponseHeaders;

/**
 * Rewrites request headers before {@code proceed} and response headers after it. Each param is
 * a newline-separated list. A set entry is {@code name: value}. A remove entry is a name.
 * Names are validated when the filter is built, including a rejection of CR and LF in values.
 * Request rules allocate one header map; response-only rules do not.
 * <p>
 * Response rules cannot set or remove {@code content-length}, {@code transfer-encoding},
 * {@code connection}, {@code keep-alive}, {@code date}, {@code server} or {@code alt-svc}.
 * Those fields belong to the protocol writers.
 */
public final class HeaderRewriteFilter implements RequestFilter {

    private final String[] requestRemove;
    private final String[] requestSetName;
    private final String[] requestSetValue;
    private final String[] responseRemove;
    private final String[] responseSetName;
    private final String[] responseSetValue;
    private final boolean touchesHost;

    private HeaderRewriteFilter(String[] requestRemove, String[] requestSetName, String[] requestSetValue,
            String[] responseRemove, String[] responseSetName, String[] responseSetValue, boolean touchesHost) {
        this.requestRemove = requestRemove;
        this.requestSetName = requestSetName;
        this.requestSetValue = requestSetValue;
        this.responseRemove = responseRemove;
        this.responseSetName = responseSetName;
        this.responseSetValue = responseSetValue;
        this.touchesHost = touchesHost;
    }

    public static RequestFilter create(MiddlewareSpec spec) {
        var requestRemove = names(spec.option("request-remove"), "request-remove");
        var requestSet = pairs(spec.option("request-set"), "request-set");
        var responseRemove = names(spec.option("response-remove"), "response-remove");
        var responseSet = pairs(spec.option("response-set"), "response-set");
        for (var name : responseRemove) {
            rejectTransport(name);
        }
        for (var name : responseSet[0]) {
            rejectTransport(name);
        }
        if (requestRemove.length == 0 && requestSet[0].length == 0 && responseRemove.length == 0
                && responseSet[0].length == 0) {
            throw new IllegalArgumentException("header-rewrite needs a request or response rule");
        }
        boolean touchesHost = false;
        for (var name : requestRemove) {
            touchesHost |= "host".equals(name);
        }
        for (var name : requestSet[0]) {
            touchesHost |= "host".equals(name);
        }
        return new HeaderRewriteFilter(requestRemove, requestSet[0], requestSet[1], responseRemove, responseSet[0],
                responseSet[1], touchesHost);
    }

    @Override
    public Response filter(Request request, Chain next) {
        if (this.requestRemove.length > 0 || this.requestSetName.length > 0) {
            request = rewrite(request);
        }
        var response = next.proceed(request);
        var headers = response.headers();
        for (var name : this.responseRemove) {
            headers.remove(name);
        }
        for (int i = 0; i < this.responseSetName.length; i++) {
            headers.set(this.responseSetName[i], this.responseSetValue[i]);
        }
        return response;
    }

    private Request rewrite(Request request) {
        var copy = new LinkedHashMap<String, List<String>>();
        for (var entry : request.headers().fields().entrySet()) {
            if (listed(this.requestRemove, entry.getKey()) || listed(this.requestSetName, entry.getKey())) {
                continue;
            }
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        for (int i = 0; i < this.requestSetName.length; i++) {
            copy.put(this.requestSetName[i], List.of(this.requestSetValue[i]));
        }
        var host = this.touchesHost ? null : request.headers().host();
        return request.withHeaders(RequestHeaders.of(copy, host));
    }

    private static boolean listed(String[] names, String name) {
        for (var candidate : names) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static void rejectTransport(String name) {
        switch (name) {
            case "content-length", "transfer-encoding", "connection", "keep-alive", "date", "server", "alt-svc" ->
                throw new IllegalArgumentException("header-rewrite cannot change " + name);
            default -> {
            }
        }
    }

    private static String[] names(String raw, String param) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        var out = new ArrayList<String>();
        for (var line : raw.split("\\r?\\n", -1)) {
            var name = line.trim();
            if (name.isEmpty()) {
                continue;
            }
            validate(name, "x");
            out.add(name.toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(param + " has no entries");
        }
        return out.toArray(String[]::new);
    }

    private static String[][] pairs(String raw, String param) {
        if (raw == null || raw.isBlank()) {
            return new String[][] { new String[0], new String[0] };
        }
        var names = new ArrayList<String>();
        var values = new ArrayList<String>();
        for (var line : raw.split("\\r?\\n", -1)) {
            var text = line.trim();
            if (text.isEmpty()) {
                continue;
            }
            int colon = text.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException(param + " entry needs name: value");
            }
            var name = text.substring(0, colon).trim();
            var value = text.substring(colon + 1).trim();
            validate(name, value);
            names.add(name.toLowerCase(Locale.ROOT));
            values.add(value);
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException(param + " has no entries");
        }
        return new String[][] { names.toArray(String[]::new), values.toArray(String[]::new) };
    }

    /** Rejects a bad name or a CR/LF/NUL value the same way a response header would. */
    private static void validate(String name, String value) {
        ResponseHeaders.of().set(name, value);
    }
}
